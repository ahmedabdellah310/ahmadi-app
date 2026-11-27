package ai.ratl.core

/** A word's frame extent, in fractional frames. */
data class WordBounds(val startFrame: Double, val endFrame: Double)

/**
 * `M2E_edge_spike` - the frozen boundary rule (SPEC 7.6).
 *
 * A word owns everything between the trained `|` separators on either side of
 * it. Separators exist only BETWEEN words, so at an ayah edge there is none, and
 * the rule falls back to the CTC spike there.
 *
 * That fallback is the whole point of the rule. Plain M2 fell back to frame 0
 * and frame T instead, so the first and last word of every ayah absorbed all the
 * leading and trailing silence:
 *
 * | | n | accuracy | median error |
 * |---|---|---|---|
 * | interior words | 1099 | 0.8499 | +40 / +20 ms |
 * | first word | 150 | 0.5600 | -400 ms start |
 * | last word | 150 | 0.5167 | +550 ms end |
 *
 * 21% of boundaries were being destroyed by that branch. Do not "simplify" the
 * edge case away.
 *
 * Port of `word_bounds(method="M2E_edge_spike", ...)` (spike/18_decode_extent.py).
 */
object BoundaryRule {

    /**
     * Frame-domain extent of word [wordIndex].
     *
     * @param wordSpans  per-word `[lo, hi)` ranges into the label sequence
     * @param labelSpans per-label frame spans from [ForcedAligner.align]
     * @param nLabels    total labels, i.e. separators included
     * @param totalFrames frames in the utterance
     * @return bounds, or null if the word contributed no tokens or never aligned
     */
    fun m2eEdgeSpike(
        wordSpans: List<IntRange?>,
        labelSpans: List<LabelSpan?>,
        wordIndex: Int,
        nLabels: Int,
        totalFrames: Int,
    ): WordBounds? {
        val span = wordSpans.getOrNull(wordIndex) ?: return null
        val lo = span.first
        val hi = span.last + 1

        val aLo = labelSpans.getOrNull(lo) ?: return null
        val aHi = labelSpans.getOrNull(hi - 1) ?: return null

        val rawStart = aLo.startFrame.toDouble()
        val rawEnd = (aHi.endFrame + 1).toDouble()

        // nearest token on each side: the '|' separator, where one exists
        var prevEnd = 0.0
        if (lo > 0) {
            labelSpans.getOrNull(lo - 1)?.let { prevEnd = (it.endFrame + 1).toDouble() }
        }
        var nextStart = totalFrames.toDouble()
        if (hi < nLabels) {
            labelSpans.getOrNull(hi)?.let { nextStart = it.startFrame.toDouble() }
        }

        val start = if (lo == 0) rawStart else prevEnd
        val end = if (hi >= nLabels) rawEnd else nextStart
        return WordBounds(start, end)
    }
}
