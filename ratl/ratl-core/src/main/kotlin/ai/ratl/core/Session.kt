package ai.ratl.core

import ai.ratl.text.Tokenizer

/**
 * A streaming assessment session. SPEC 5.3.
 *
 * ## Results are provisional until [finish]
 *
 * [pushAudio] returns a [PartialAssessment] that **may revise words it has
 * already reported**. More audio changes the alignment of what came before -
 * that is inherent to forced alignment, not a defect. A UI must not render a
 * partial verdict as final. SPEC 5.3 requires this to be documented explicitly
 * and it is the easiest way to misuse RATL.
 *
 * ## Why re-decoding, not incremental decoding
 *
 * Each call re-decodes the audio buffered so far. Viterbi backtracking is
 * global: a frame near the end can change the best path near the beginning, so
 * an incremental decoder would produce different results from the batch path.
 * Batch and streaming must agree - that is the M2.2 exit condition - and
 * re-decoding guarantees it by construction. The cost is bounded because RTF is
 * ~0.02 with roughly 5x headroom against G5.
 *
 * Words beyond the alignment cursor report [WordStatus.PENDING], which is the
 * only place PENDING is ever emitted (SPEC 4).
 */
class Session internal constructor(
    private val engine: Engine,
    private val model: AcousticModel,
    private val tokenizer: Tokenizer,
    private var target: List<String>,
    private var qulKeys: List<String>,
    private val config: SessionConfig,
    private val lattice: LatticeConfig,
    private val modelId: String,
) {

    private var buffer = FloatArray(0)
    private var cursor = 0
    private var lastPartial: PartialAssessment? = null
    private var decodedAtSamples = 0

    /** Words assessed so far; the rest are [WordStatus.PENDING]. SPEC 5.3. */
    fun cursor(): Int = cursor

    /** Buffered audio duration in milliseconds. */
    fun bufferedMs(): Double = buffer.size * 1000.0 / Frames.SAMPLE_RATE

    /**
     * Append audio. Chunk sizes are arbitrary; buffering is internal.
     *
     * @return a provisional assessment, or the previous one when
     *   [SessionConfig.emitPartials] is false or there is not yet a full frame.
     */
    fun pushAudio(frames: FloatArray): PartialAssessment {
        if (frames.isNotEmpty()) {
            val grown = buffer.copyOf(buffer.size + frames.size)
            frames.copyInto(grown, buffer.size)
            buffer = grown
        }
        if (!config.emitPartials) {
            return lastPartial ?: emptyPartial()
        }
        // One encoder frame is 20 ms; below that there is nothing new to say.
        if (buffer.size < Frames.SAMPLE_RATE / Frames.FPS.toInt()) return emptyPartial()

        // Re-decoding on EVERY push is quadratic: each call decodes the whole
        // buffer, so 30 pushes of a 15 s recitation means 30 decodes of growing
        // length. Measured in the Emran integration, that churn drove peak RSS
        // to 572.9 MB against a 400 MB ceiling.
        //
        // Decoding is therefore rate-limited by how much NEW audio has arrived.
        // This changes only how often a partial is recomputed, never what it
        // says: finish() always decodes the full buffer, so batch and streaming
        // still agree exactly.
        val newSamples = buffer.size - decodedAtSamples
        if (lastPartial != null && newSamples < config.partialIntervalSamples) {
            return lastPartial!!
        }
        decodedAtSamples = buffer.size

        val assessment = decode()
        cursor = assessment.words.count { it.status != WordStatus.PENDING }
        val partial = PartialAssessment(
            words = assessment.words,
            events = assessment.events,
            cursor = cursor,
            audioDurationMs = assessment.audioDurationMs,
        )
        lastPartial = partial
        return partial
    }

    /**
     * Close the session and return the final assessment.
     *
     * This is the first result that is not provisional.
     */
    fun finish(): Assessment {
        val assessment = decode(final = true)
        cursor = assessment.words.size
        return assessment
    }

    /** Reset for a new target, discarding buffered audio. SPEC 5.3. */
    fun reset(newTarget: List<String>? = null, newQulKeys: List<String>? = null) {
        buffer = FloatArray(0)
        cursor = 0
        lastPartial = null
        decodedAtSamples = 0
        if (newTarget != null) {
            target = newTarget
            qulKeys = newQulKeys ?: newTarget.indices.map { (it + 1).toString() }
        }
    }

    private fun emptyPartial() = PartialAssessment(
        words = target.indices.map {
            WordResult(it, qulKeys.getOrElse(it) { (it + 1).toString() },
                WordStatus.PENDING, 0.0f, null, null)
        },
        events = emptyList(), cursor = 0, audioDurationMs = bufferedMs(),
    )

    private fun decode(final: Boolean = false): Assessment {
        val encoded = tokenizer.encode(target)
        if (encoded.ids.isEmpty() || buffer.isEmpty()) {
            val p = emptyPartial()
            return Assessment(p.words, emptyList(), bufferedMs(), modelId)
        }
        val logits = Windowing.runWindows(buffer, model)
        val logp = ForcedAligner.logSoftmax(logits)
        val assessment = engine.assessFromLogp(logp, encoded, target, qulKeys, config)
        if (final) return assessment

        // Mid-stream, a word the audio has not reached yet must NOT be reported
        // as OMISSION. Accusing a reciter of omitting a word they have simply
        // not said yet is the single most damaging thing a streaming assessor
        // can do, and SPEC 4 exists to prevent exactly that.
        //
        // The rule is a TRAILING RUN, not the truncation cut: the aligner is
        // free to satisfy a short prefix by taking skip arcs over every
        // remaining word, in which case no cut fires at all. An unaligned word
        // with aligned words after it was genuinely skipped; an unaligned word
        // in the trailing run has not been reached. That distinction is
        // deterministic and needs no threshold.
        val words = assessment.words.toMutableList()
        var i = words.lastIndex
        while (i >= 0 && words[i].status == WordStatus.OMISSION) {
            words[i] = words[i].copy(
                status = WordStatus.PENDING, confidence = 0.0f,
                tStartMs = null, tEndMs = null,
            )
            i--
        }
        // A word the audio stopped inside is INCOMPLETE, and anything past it
        // is likewise unreached.
        val cut = ErrorLattice.computeSignals(logp, encoded, lattice)?.cutWord ?: -1
        if (cut >= 0) {
            for (j in cut + 1 until words.size) {
                if (words[j].status != WordStatus.PENDING) {
                    words[j] = words[j].copy(
                        status = WordStatus.PENDING, confidence = 0.0f,
                        tStartMs = null, tEndMs = null,
                    )
                }
            }
        }
        return assessment.copy(words = words)
    }
}
