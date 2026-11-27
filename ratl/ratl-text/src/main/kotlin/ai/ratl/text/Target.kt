package ai.ratl.text

/**
 * One word of the target passage. SPEC 5.1.
 *
 * @param index    0-based position within the target
 * @param qulKey   "surah:ayah:word", QUL-compatible
 * @param uthmani  Uthmani script, as supplied by the caller
 * @param imlaei   Imlaei script, as supplied by the caller
 * @param tokens   model-vocabulary tokens for this word
 */
data class TargetWord(
    val index: Int,
    val qulKey: String,
    val uthmani: String,
    val imlaei: String,
    val tokens: List<String>,
)

/** A target passage. SPEC 5.1. */
data class Target(
    val words: List<TargetWord>,
    val surah: Int,
    val ayahStart: Int,
    val ayahEnd: Int,
)

/**
 * The target encoded for the CTC lattice.
 *
 * [ids] is the flat label sequence with `|` separators inserted BETWEEN words.
 * [wordSpans] maps each target word to its half-open `[lo, hi)` range in [ids],
 * or null for a word that contributed no tokens.
 *
 * Separators live between words only, never at the edges. That asymmetry is
 * load-bearing: it is exactly why the boundary rule needs an edge fallback, and
 * getting it wrong destroyed 21% of boundaries once already.
 */
data class EncodedTarget(
    val ids: IntArray,
    val wordSpans: List<IntRange?>,
    val outOfVocabulary: List<String>,
) {
    val wordCount: Int get() = wordSpans.size

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncodedTarget) return false
        return ids.contentEquals(other.ids) &&
            wordSpans == other.wordSpans &&
            outOfVocabulary == other.outOfVocabulary
    }

    override fun hashCode(): Int =
        31 * (31 * ids.contentHashCode() + wordSpans.hashCode()) + outOfVocabulary.hashCode()
}
