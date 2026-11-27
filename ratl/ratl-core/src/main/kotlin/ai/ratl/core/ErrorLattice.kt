package ai.ratl.core

import kotlin.math.abs
import kotlin.math.exp

/**
 * Decision parameters for the error-tolerant lattice.
 *
 * [DISABLED] leaves every error arc unreachable, which reduces the lattice
 * exactly to the frozen forced aligner. That is not a convention - it is an
 * invariant the test suite asserts, and it is why G2 = 0.8570 is preserved by
 * construction rather than by re-measurement.
 *
 * [V1] is the configuration selected on the seen-reciter tier and gated once on
 * held-out data: G3 = 0.9333, G4 = 0.0085 at 89.3% coverage.
 *
 * `lamSkip` IS the omission threshold: Viterbi skips word i exactly when
 * `LLR_i < -lamSkip`, so the arc penalty and the decision threshold are one
 * number rather than two that could disagree.
 */
data class LatticeConfig(
    val lamSkip: Double = Double.NEGATIVE_INFINITY,
    val lamIns: Double = Double.NEGATIVE_INFINITY,
    val lamRep: Double = Double.NEGATIVE_INFINITY,
    val lamTrunc: Double = Double.NEGATIVE_INFINITY,
    val garbPen: Double = 0.0,
    val tauSubZ: Double = Double.POSITIVE_INFINITY,
    val tauSubZAbstain: Double = Double.POSITIVE_INFINITY,
    val tauOmitAbstain: Double = Double.NEGATIVE_INFINITY,
    val tauUncertain: Double = Double.NEGATIVE_INFINITY,
    val minInsFrames: Int = 5,
    val confScale: Double = 10.0,
) {
    val allArcsDisabled: Boolean
        get() = lamSkip.isInfinite() && lamSkip < 0 &&
            lamIns.isInfinite() && lamIns < 0 &&
            lamRep.isInfinite() && lamRep < 0 &&
            lamTrunc.isInfinite() && lamTrunc < 0

    companion object {
        /** Every error arc off: bit-identical to the frozen forced aligner. */
        val DISABLED = LatticeConfig()

        /**
         * The gated V1 configuration.
         *
         * `tauSubZ` is +inf deliberately: V1 does not assert SUBSTITUTION
         * (SPEC 4.1). Candidates fall through to the abstain band and surface as
         * UNCERTAIN.
         */
        val V1 = LatticeConfig(
            lamSkip = -20.0,
            lamIns = -20.0,
            lamRep = -25.0,
            lamTrunc = -40.0,
            garbPen = 2.0,
            tauSubZ = Double.POSITIVE_INFINITY,
            tauSubZAbstain = 6.0,
            tauOmitAbstain = -8.0,
            tauUncertain = Double.NEGATIVE_INFINITY,
        )
    }
}

/** Raw per-utterance signals, recorded before any threshold is applied. */
data class LatticeSignals(
    val frames: Int,
    val wordCount: Int,
    val cutWord: Int,
    val garbage: Map<Int, List<IntArray>>,
    val present: Map<Int, Boolean>,
    val repetitions: Map<Int, Int>,
    val rawGap: Map<Int, Double>,
    val subZ: Map<Int, Double>,
    val bounds: Map<Int, DoubleArray>,
    val labelSpans: List<LabelSpan?>,
)

/**
 * The frozen CTC lattice extended with four error arcs.
 *
 * Port of the validated Python reference (`eval/lattice.py`).
 *
 *  - **skip**: bypass word i and one adjacent separator -> OMISSION
 *  - **garbage**: a self-looping "any token" state at each word gap -> INSERTION
 *  - **repeat**: re-enter word i after leaving it -> REPETITION
 *  - **truncation**: terminate mid-word -> INCOMPLETE
 *
 * Edge order is `[stay, s-1, s-2]` first for every CTC state, and terminal
 * candidates are enumerated `[last blank, last label, trailing garbage]`, so
 * ties break exactly as the frozen aligner's argmax does. Frame indices are read
 * off this path, so tie-breaking is correctness, not style.
 */
object ErrorLattice {

    private const val NEG = -1e30

    private class Tables(
        val ext: IntArray,
        val nCtc: Int,
        val states: Int,
        val src: Array<IntArray>,
        val cost: Array<DoubleArray>,
        val gapState: Map<Int, Int>,
        val repEdges: Map<Int, IntArray>,
        val nGaps: Int,
    )

    /** Blank states a skip of word [wi] jumps between, or null. */
    private fun skipArc(wordSpans: List<IntRange?>, wi: Int, nLabels: Int): IntArray? {
        val sp = wordSpans[wi] ?: return null
        val lo = sp.first
        val hi = sp.last + 1
        // Consume one adjacent separator too, so the remaining target has the
        // separator structure the tokenizer would produce for the shorter word
        // list - which is what the validated LLR rescoring compares against.
        return when {
            wi > 0 -> intArrayOf(2 * (lo - 1), 2 * hi)
            hi < nLabels -> intArrayOf(2 * lo, 2 * (hi + 1))
            else -> intArrayOf(2 * lo, 2 * hi)
        }
    }

    private fun buildTables(ids: IntArray, wordSpans: List<IntRange?>, cfg: LatticeConfig): Tables {
        val nLabels = ids.size
        val nCtc = 2 * nLabels + 1
        val ext = IntArray(nCtc)
        for (l in 0 until nLabels) ext[2 * l + 1] = ids[l]
        val canSkip = BooleanArray(nCtc)
        for (s in 2 until nCtc) canSkip[s] = ext[s] != 0 && ext[s] != ext[s - 2]

        val nWords = wordSpans.size
        val nGaps = nWords + 1
        val states = nCtc + nGaps

        val inc = Array(states) { ArrayList<Pair<Int, Double>>(6) }
        for (s in 0 until nCtc) {
            inc[s].add(s to 0.0)
            if (s >= 1) inc[s].add((s - 1) to 0.0)
            if (s >= 2 && canSkip[s]) inc[s].add((s - 2) to 0.0)
        }

        val gapState = HashMap<Int, Int>()
        for (g in 0 until nGaps) {
            val gs = nCtc + g
            gapState[g] = gs
            inc[gs].add(gs to 0.0)
        }
        for (g in 0 until nGaps) {
            val span = if (g < nWords) wordSpans[g] else null
            val blank = if (span != null) {
                if (g > 0) 2 * (span.first - 1) else 2 * span.first
            } else nCtc - 1
            val gs = gapState.getValue(g)
            inc[gs].add(blank to cfg.lamIns)
            inc[blank].add(gs to 0.0)
        }

        val repEdges = HashMap<Int, IntArray>()
        for (wi in 0 until nWords) {
            val arc = skipArc(wordSpans, wi, nLabels) ?: continue
            val (a, b) = arc[0] to arc[1]
            if (a in 0 until nCtc && b in 0 until nCtc) {
                inc[b].add(a to cfg.lamSkip)
                val sp = wordSpans[wi]!!
                inc[2 * sp.first].add((2 * (sp.last + 1)) to cfg.lamRep)
                repEdges[wi] = intArrayOf(2 * (sp.last + 1), 2 * sp.first)
            }
        }

        val fanIn = inc.maxOf { it.size }
        val src = Array(states) { IntArray(fanIn) }
        val cost = Array(states) { DoubleArray(fanIn) { NEG } }
        for (s in 0 until states) {
            for ((k, e) in inc[s].withIndex()) {
                src[s][k] = e.first
                cost[s][k] = if (e.second.isFinite()) e.second else NEG
            }
        }
        return Tables(ext, nCtc, states, src, cost, gapState, repEdges, nGaps)
    }

    private class Decoded(val path: IntArray, val total: Double, val tables: Tables)

    private fun viterbi(
        logp: Array<DoubleArray>,
        ids: IntArray,
        wordSpans: List<IntRange?>,
        cfg: LatticeConfig,
    ): Decoded {
        val tb = buildTables(ids, wordSpans, cfg)
        val T = logp.size
        val S = tb.states
        val nCtc = tb.nCtc

        // Emission is computed one frame at a time rather than materialised as
        // a T x S matrix. The full matrix was the single largest allocation in
        // an assessment and it is pure derived data - every entry is either a
        // gather from logp or the per-frame maximum. Integration measurement on
        // the benchmark device showed peak RSS growing sharply with audio
        // duration; this is the main contributor.
        val emitRow = DoubleArray(S)
        fun fillEmit(t: Int) {
            val row = logp[t]
            var best = Double.NEGATIVE_INFINITY
            for (v in row) if (v > best) best = v
            val garbage = best - cfg.garbPen
            for (s in 0 until nCtc) emitRow[s] = row[tb.ext[s]]
            for (s in nCtc until S) emitRow[s] = garbage
        }

        var alpha = DoubleArray(S) { NEG }
        fillEmit(0)
        alpha[0] = emitRow[0]
        if (nCtc > 1) alpha[1] = emitRow[1]
        if (cfg.lamIns.isFinite()) {
            for ((_, gs) in tb.gapState) alpha[gs] = cfg.lamIns + emitRow[gs]
        }

        // Backpointers as one flat ShortArray rather than T separate IntArrays:
        // halves the bytes and removes T object headers. S is bounded by
        // 2 * labels + words + 2, far below Short.MAX_VALUE for any passage.
        require(S <= Short.MAX_VALUE) { "lattice too large for the flat backpointer table: $S" }
        val back = ShortArray(T * S)
        var next = DoubleArray(S)
        for (t in 1 until T) {
            fillEmit(t)
            val base = t * S
            for (s in 0 until S) {
                var bestVal = Double.NEGATIVE_INFINITY
                var bestSrc = s
                val sr = tb.src[s]
                val cs = tb.cost[s]
                for (k in sr.indices) {
                    val c = cs[k]
                    if (c <= NEG) continue
                    val v = alpha[sr[k]] + c
                    if (v > bestVal) { bestVal = v; bestSrc = sr[k] }
                }
                back[base + s] = bestSrc.toShort()
                next[s] = bestVal + emitRow[s]
            }
            // swap the two score buffers instead of allocating a new one each frame
            val swap = alpha
            alpha = next
            next = swap
        }

        // Terminal candidates in a fixed order so ties resolve identically to
        // the frozen aligner. Truncation targets are appended after, so with
        // lamTrunc = -inf the terminal set is exactly the frozen one.
        val ends = ArrayList<Int>()
        val vals = ArrayList<Double>()
        for (e in listOf(nCtc - 1, nCtc - 2, tb.gapState.getValue(tb.nGaps - 1))) {
            if (e in 0 until S) { ends.add(e); vals.add(alpha[e]) }
        }
        if (cfg.lamTrunc.isFinite()) {
            for (e in 0 until nCtc - 2) { ends.add(e); vals.add(alpha[e] + cfg.lamTrunc) }
        }
        var argMax = 0
        for (i in vals.indices) if (vals[i] > vals[argMax]) argMax = i
        var s = ends[argMax]
        val total = vals[argMax]

        val path = IntArray(T)
        for (t in T - 1 downTo 0) {
            path[t] = s
            s = back[t * S + s].toInt()
        }
        return Decoded(path, total, tb)
    }

    /** Contiguous `true` runs of [mask] as half-open `[start, end)` pairs. */
    private fun runs(mask: BooleanArray): List<IntArray> {
        val out = ArrayList<IntArray>()
        var i = 0
        while (i < mask.size) {
            if (mask[i]) {
                var j = i
                while (j + 1 < mask.size && mask[j + 1]) j++
                out.add(intArrayOf(i, j + 1))
                i = j + 1
            } else i++
        }
        return out
    }

    private fun labelSpansOf(path: IntArray, nLabels: Int): List<LabelSpan?> =
        (0 until nLabels).map { l ->
            val state = 2 * l + 1
            var first = -1
            var last = -1
            for (t in path.indices) if (path[t] == state) { if (first < 0) first = t; last = t }
            if (first < 0) null else LabelSpan(first, last)
        }

    /** Run the lattice and record every raw signal, before any threshold. */
    fun computeSignals(
        logp: Array<DoubleArray>,
        encoded: ai.ratl.text.EncodedTarget,
        cfg: LatticeConfig,
    ): LatticeSignals? {
        val ids = encoded.ids
        if (ids.isEmpty()) return null
        val wordSpans = encoded.wordSpans
        val T = logp.size
        val dec = viterbi(logp, ids, wordSpans, cfg)
        val tb = dec.tables
        val path = dec.path
        val labelSpans = labelSpansOf(path, ids.size)

        val repetitions = HashMap<Int, Int>()
        for ((wi, edge) in tb.repEdges) {
            var n = 0
            for (t in 1 until path.size) if (path[t - 1] == edge[0] && path[t] == edge[1]) n++
            repetitions[wi] = n
        }

        val gapOf = tb.gapState.entries.associate { (g, s) -> s to g }
        val isGarbage = BooleanArray(T) { gapOf.containsKey(path[it]) }
        val garbage = HashMap<Int, MutableList<IntArray>>()
        for (r in runs(isGarbage)) {
            if (r[1] - r[0] >= cfg.minInsFrames) {
                garbage.getOrPut(gapOf.getValue(path[r[0]])) { ArrayList() }.add(r)
            }
        }

        val terminal = path[T - 1]
        var cutWord = -1
        if (terminal < tb.nCtc - 2 && cfg.lamTrunc.isFinite()) {
            val tl = if (terminal % 2 == 1) (terminal - 1) / 2 else terminal / 2
            for ((wj, spj) in wordSpans.withIndex()) {
                if (spj != null && tl >= spj.first && tl <= spj.last) { cutWord = wj; break }
            }
        }

        val rawGap = HashMap<Int, Double>()
        val bounds = HashMap<Int, DoubleArray>()
        val present = HashMap<Int, Boolean>()
        for ((wi, sp) in wordSpans.withIndex()) {
            if (sp == null) continue
            val mask = BooleanArray(T)
            for (l in sp.first..sp.last) {
                val st = 2 * l + 1
                for (t in 0 until T) if (path[t] == st) mask[t] = true
            }
            val rr = runs(mask)
            present[wi] = rr.isNotEmpty()
            if (rr.isEmpty()) continue
            val a = rr.first()[0]
            val b = rr.last()[1]
            var sumMax = 0.0
            var sumLab = 0.0
            for (t in a until b) {
                var best = Double.NEGATIVE_INFINITY
                for (v in logp[t]) if (v > best) best = v
                sumMax += best
                val st = path[t]
                sumLab += logp[t][if (st < tb.nCtc) tb.ext[st] else 0]
            }
            val n = (b - a).toDouble()
            rawGap[wi] = sumMax / n - sumLab / n
            BoundaryRule.m2eEdgeSpike(wordSpans, labelSpans, wi, ids.size, T)?.let {
                bounds[wi] = doubleArrayOf(
                    Frames.frameToMs(it.startFrame), Frames.frameToMs(it.endFrame),
                )
            }
        }

        // Reciter-normalised substitution score: leave-one-out robust z-score
        // against the other words of the SAME clip. Unit-free, so the per-voice
        // scale cancels - the raw gap does not transfer across reciters.
        val subZ = HashMap<Int, Double>()
        if (rawGap.size >= 3) {
            for ((wi, v) in rawGap) {
                val others = rawGap.filterKeys { it != wi }.values.toDoubleArray()
                val med = median(others)
                var mad = median(DoubleArray(others.size) { abs(others[it] - med) }) * 1.4826
                if (mad < 1e-6) {
                    val mean = others.average()
                    var varSum = 0.0
                    for (o in others) varSum += (o - mean) * (o - mean)
                    val sd = kotlin.math.sqrt(varSum / others.size)
                    mad = if (sd > 0.0) sd else 1e-6
                }
                subZ[wi] = (v - med) / mad
            }
        }

        return LatticeSignals(
            frames = T, wordCount = wordSpans.size, cutWord = cutWord,
            garbage = garbage, present = present, repetitions = repetitions,
            rawGap = rawGap, subZ = subZ, bounds = bounds, labelSpans = labelSpans,
        )
    }

    private fun median(v: DoubleArray): Double {
        if (v.isEmpty()) return 0.0
        val s = v.sortedArray()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2.0
    }

    /**
     * Map recorded signals to one [WordStatus]. **The** decision rule.
     *
     * Order is significant and deterministic:
     *  1. absent -> OMISSION, or UNCERTAIN when the evidence for absence is marginal
     *  2. repeat arc taken -> REPETITION
     *  3. after the cut -> OMISSION (the recitation ended before this word)
     *  4. at the cut -> INCOMPLETE
     *  5. acoustic outlier -> SUBSTITUTION, or UNCERTAIN in the abstain band
     *  6. low confidence -> UNCERTAIN
     *  7. otherwise -> CORRECT
     *
     * Under [LatticeConfig.V1] step 5 never reaches SUBSTITUTION, because
     * `tauSubZ` is +inf (SPEC 4.1).
     */
    fun classify(sig: LatticeSignals, wi: Int, cfg: LatticeConfig, llr: Double? = null): WordStatus {
        if (sig.present[wi] != true) {
            if (llr != null && llr > cfg.tauOmitAbstain) return WordStatus.UNCERTAIN
            val said = sig.garbage[wi] ?: sig.garbage[wi + 1]
            return if (said != null) WordStatus.SUBSTITUTION else WordStatus.OMISSION
        }
        if ((sig.repetitions[wi] ?: 0) >= 1 && cfg.lamRep.isFinite()) return WordStatus.REPETITION
        if (sig.cutWord >= 0 && wi > sig.cutWord) return WordStatus.OMISSION
        if (sig.cutWord >= 0 && wi == sig.cutWord) return WordStatus.INCOMPLETE
        val z = sig.subZ[wi]
        if (z != null && z > cfg.tauSubZ) return WordStatus.SUBSTITUTION
        if (z != null && z > cfg.tauSubZAbstain) return WordStatus.UNCERTAIN
        if (llr != null && llr < cfg.tauUncertain) return WordStatus.UNCERTAIN
        return WordStatus.CORRECT
    }

    /**
     * Per-token leave-one-word-out log-likelihood ratio for every word.
     *
     * Positive means the audio is better explained WITH the word than without.
     * Measured separability on held-out reciters: AUC 0.9992.
     *
     * Costs M+1 Viterbi passes over the disabled lattice.
     */
    fun llrPerWord(
        logp: Array<DoubleArray>,
        words: List<String>,
        tokenizer: ai.ratl.text.Tokenizer,
    ): Map<Int, Double> {
        val full = tokenizer.encode(words)
        if (full.ids.isEmpty()) return emptyMap()
        val base = LatticeConfig.DISABLED
        val sFull = viterbi(logp, full.ids, full.wordSpans, base).total
        val out = HashMap<Int, Double>()
        for (wi in full.wordSpans.indices) {
            val sp = full.wordSpans[wi] ?: continue
            val kept = words.filterIndexed { j, _ -> j != wi }
            val enc = tokenizer.encode(kept)
            if (enc.ids.isEmpty()) continue
            val sWo = viterbi(logp, enc.ids, enc.wordSpans, base).total
            val nTok = maxOf(1, sp.last + 1 - sp.first)
            out[wi] = (sFull - sWo) / nTok
        }
        return out
    }

    /** Logistic map from per-token LLR to a 0..1 confidence. */
    fun confidenceOf(llr: Double?, cfg: LatticeConfig): Float =
        if (llr == null) 0.0f else (1.0 / (1.0 + exp(-llr / cfg.confScale))).toFloat()

    /** Forced-alignment label spans under the disabled lattice - the frozen path. */
    fun frozenLabelSpans(logp: Array<DoubleArray>, ids: IntArray, wordSpans: List<IntRange?>):
        List<LabelSpan?> = labelSpansOf(viterbi(logp, ids, wordSpans, LatticeConfig.DISABLED).path, ids.size)
}
