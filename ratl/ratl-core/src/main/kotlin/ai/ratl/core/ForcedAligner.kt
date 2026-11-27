package ai.ratl.core

import kotlin.math.exp
import kotlin.math.ln

/** Inclusive frame range a label occupied, or null if it never surfaced. */
data class LabelSpan(val startFrame: Int, val endFrame: Int)

/**
 * Viterbi forced alignment over the blank-augmented CTC lattice.
 *
 * Port of the frozen `ctc_forced_align` (spike/05_g2_alignment.py), which is the
 * aligner behind G2 = 0.8570 and is imported unmodified by every measured
 * result.
 *
 * Two details are load-bearing and must not be "tidied":
 *
 *  - The skip transition into state s is allowed only when ext[s] is a real
 *    label AND differs from ext[s-2]. That is what forces a blank between two
 *    identical consecutive labels.
 *  - Ties break toward the LOWEST-numbered predecessor, in the order
 *    [stay, s-1, s-2], because the reference takes an argmax over a stack in
 *    that order and argmax returns the first maximum. Frame indices are integers
 *    read off this path, so a different tie-break is a different answer, not a
 *    rounding difference.
 *
 * All arithmetic is Double, matching the reference's float64.
 */
object ForcedAligner {

    private const val NEG = -1e30

    /** Log-softmax over the last axis, in Double. Port of `log_softmax`. */
    fun logSoftmax(logits: Array<FloatArray>): Array<DoubleArray> =
        Array(logits.size) { t ->
            val row = logits[t]
            var max = Double.NEGATIVE_INFINITY
            for (v in row) if (v.toDouble() > max) max = v.toDouble()
            var sum = 0.0
            val shifted = DoubleArray(row.size) { i -> row[i].toDouble() - max }
            for (v in shifted) sum += exp(v)
            val logSum = ln(sum)
            DoubleArray(row.size) { i -> shifted[i] - logSum }
        }

    /**
     * Align [labels] to [logp] and return each label's inclusive frame span.
     *
     * @param logp `[frames][vocab]` log posteriors
     * @param labels the flat CTC label sequence (with `|` separators)
     */
    fun align(logp: Array<DoubleArray>, labels: IntArray, blank: Int = 0): List<LabelSpan?> {
        val T = logp.size
        val L = labels.size
        require(T > 0 && L > 0) { "empty logits or labels" }
        val S = 2 * L + 1

        val ext = IntArray(S) { blank }
        for (l in 0 until L) ext[2 * l + 1] = labels[l]

        val canSkip = BooleanArray(S)
        for (s in 2 until S) canSkip[s] = ext[s] != blank && ext[s] != ext[s - 2]

        var alpha = DoubleArray(S) { NEG }
        alpha[0] = logp[0][ext[0]]
        if (S > 1) alpha[1] = logp[0][ext[1]]

        // 0 = stay, 1 = from s-1, 2 = from s-2
        val back = Array(T) { ByteArray(S) }

        for (t in 1 until T) {
            val next = DoubleArray(S)
            val row = logp[t]
            for (s in 0 until S) {
                var best = alpha[s]
                var arg: Byte = 0
                if (s >= 1) {
                    val cand = alpha[s - 1]
                    if (cand > best) { best = cand; arg = 1 }
                }
                if (s >= 2 && canSkip[s]) {
                    val cand = alpha[s - 2]
                    if (cand > best) { best = cand; arg = 2 }
                }
                back[t][s] = arg
                next[s] = best + row[ext[s]]
            }
            alpha = next
        }

        var s = if (alpha[S - 1] >= alpha[S - 2]) S - 1 else S - 2
        val path = IntArray(T)
        for (t in T - 1 downTo 0) {
            path[t] = s
            s -= back[t][s].toInt()
        }

        return (0 until L).map { l ->
            val state = 2 * l + 1
            var first = -1
            var last = -1
            for (t in 0 until T) {
                if (path[t] == state) {
                    if (first < 0) first = t
                    last = t
                }
            }
            if (first < 0) null else LabelSpan(first, last)
        }
    }
}
