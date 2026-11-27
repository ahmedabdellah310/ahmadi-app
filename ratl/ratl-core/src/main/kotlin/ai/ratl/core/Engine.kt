package ai.ratl.core

import ai.ratl.text.EncodedTarget
import ai.ratl.text.Target
import ai.ratl.text.Tokenizer
import ai.ratl.text.Vocabulary

/**
 * The RATL engine. SPEC 5.2.
 *
 * Loaded once and shared across sessions: the model is the expensive part and
 * it is stateless, so sessions are cheap.
 *
 * Deterministic - the same audio and target produce the same assessment.
 */
class Engine(
    private val model: AcousticModel,
    private val vocabulary: Vocabulary,
    private val modelId: String,
    private val lattice: LatticeConfig = LatticeConfig.V1,
) : AutoCloseable {

    private val tokenizer = Tokenizer(vocabulary)

    fun modelInfo(): ModelInfo = ModelInfo(modelId, vocabulary.size)

    /** Batch assessment of a complete recitation. SPEC 5.2. */
    fun assess(
        audio: FloatArray,
        target: Target,
        config: SessionConfig = SessionConfig(),
    ): Assessment = assessWords(audio, target.words.map { it.tokens.joinToString("") },
        qulKeys = target.words.map { it.qulKey }, config = config)

    /**
     * Assess against romanised word forms directly.
     *
     * SPEC 5.5 makes the Quran source the caller's; this is the path an
     * integrator uses when they already hold romanised forms.
     */
    fun assessWords(
        audio: FloatArray,
        words: List<String>,
        qulKeys: List<String> = words.indices.map { (it + 1).toString() },
        config: SessionConfig = SessionConfig(),
    ): Assessment {
        val encoded = tokenizer.encode(words)
        if (encoded.ids.isEmpty()) {
            return Assessment(emptyList(), emptyList(), 0.0, modelId)
        }
        val logits = Windowing.runWindows(audio, model)
        val logp = ForcedAligner.logSoftmax(logits)
        return assessFromLogp(logp, encoded, words, qulKeys, config)
    }

    internal fun assessFromLogp(
        logp: Array<DoubleArray>,
        encoded: EncodedTarget,
        words: List<String>,
        qulKeys: List<String>,
        config: SessionConfig,
    ): Assessment {
        val sig = ErrorLattice.computeSignals(logp, encoded, lattice)
            ?: return Assessment(emptyList(), emptyList(), 0.0, modelId)
        val llr = ErrorLattice.llrPerWord(logp, words, tokenizer)

        val results = ArrayList<WordResult>(sig.wordCount)
        for (wi in 0 until sig.wordCount) {
            val status = ErrorLattice.classify(sig, wi, lattice, llr[wi])
            val b = sig.bounds[wi]
            results.add(
                WordResult(
                    index = wi,
                    qulKey = qulKeys.getOrElse(wi) { (wi + 1).toString() },
                    status = status,
                    confidence = ErrorLattice.confidenceOf(llr[wi], lattice),
                    tStartMs = b?.get(0),
                    tEndMs = b?.get(1),
                ),
            )
        }

        // INSERTION has no target index to attach to, so it is a session event
        // rather than a word status (SPEC 4). Gaps already explained by an
        // OMISSION or SUBSTITUTION verdict are not reported twice.
        val events = ArrayList<SessionEvent>()
        for ((gap, spans) in sig.garbage) {
            val owner = results.getOrNull(gap)
            if (owner != null &&
                (owner.status == WordStatus.OMISSION || owner.status == WordStatus.SUBSTITUTION)
            ) continue
            for (s in spans) {
                events.add(
                    SessionEvent.Insertion(
                        afterWord = gap - 1,
                        startMs = Frames.frameToMs(s[0].toDouble()),
                        endMs = Frames.frameToMs(s[1].toDouble()),
                    ),
                )
            }
        }

        return Assessment(
            words = results,
            events = events,
            audioDurationMs = sig.frames * Frames.MS_PER_FRAME,
            modelId = modelId,
        )
    }

    /** Open a streaming session. SPEC 5.2 / 5.3. */
    fun createSession(
        target: List<String>,
        config: SessionConfig = SessionConfig(),
        qulKeys: List<String> = target.indices.map { (it + 1).toString() },
    ): Session = Session(this, model, tokenizer, target, qulKeys, config, lattice, modelId)

    internal fun tokenizer(): Tokenizer = tokenizer

    /**
     * Closes the [AcousticModel] this engine was given.
     *
     * The engine owns the model for its lifetime - that is the SPEC 5.2 shape,
     * where the engine is loaded once and shared across sessions. Model
     * implementations must make close idempotent so a caller who also manages
     * the model directly is not punished for it.
     */
    override fun close() = model.close()

    companion object {
        /**
         * Load an engine from a model directory. SPEC 5.2.
         *
         * Expects the exported graph and its `vocab.json` sidecar side by side,
         * so the vocabulary can never drift from the weights.
         */
        fun load(
            modelDir: java.io.File,
            modelFactory: (java.io.File, Int) -> Pair<AcousticModel, String>,
            lattice: LatticeConfig = LatticeConfig.V1,
        ): Engine {
            val vocabFile = java.io.File(modelDir, "vocab.json")
            require(vocabFile.isFile) { "vocab.json not found in $modelDir" }
            val vocabulary = Vocabulary.fromJson(vocabFile.readText())
            val graph = modelDir.listFiles { f -> f.name.endsWith(".onnx") }
                ?.minByOrNull { it.name }
                ?: error("no .onnx graph found in $modelDir")
            val (model, id) = modelFactory(graph, vocabulary.size)
            return Engine(model, vocabulary, id, lattice)
        }
    }
}
