package ai.ratl.text

/**
 * Encodes target words into the CTC label sequence.
 *
 * Port of the frozen reference `build_tokens_v2` (spike/13_eval_exp1.py), which
 * is the encoder used for every measured result including G2 = 0.8570. The
 * contract it must reproduce exactly:
 *
 *  - a word's romanised form is lowercased and trimmed;
 *  - an empty word yields a null span and emits NO separator, so an empty word
 *    does not leave a stray separator behind;
 *  - a separator is emitted before every word except the first NON-EMPTY one,
 *    i.e. only when ids is already non-empty;
 *  - out-of-vocabulary characters are dropped from ids but collected and
 *    reported rather than silently swallowed.
 *
 * Conformance vector `t0_tokenize.json` pins all of this.
 */
class Tokenizer(private val vocabulary: Vocabulary) {

    private val separatorId: Int = requireNotNull(vocabulary.indexOf(Vocabulary.SEPARATOR)) {
        "vocabulary has no '${Vocabulary.SEPARATOR}' separator"
    }

    /**
     * Encode romanised word forms into the flat label sequence.
     *
     * @param words romanised (transliterated) forms, one per target word
     */
    fun encode(words: List<String>): EncodedTarget {
        val ids = ArrayList<Int>()
        val spans = ArrayList<IntRange?>(words.size)
        val oov = ArrayList<String>()

        for (raw in words) {
            val word = raw.trim().lowercase()
            if (word.isEmpty()) {
                spans.add(null)
                continue
            }
            if (ids.isNotEmpty()) ids.add(separatorId)
            val start = ids.size
            for (ch in word) {
                val id = vocabulary.indexOf(ch.toString())
                if (id != null) ids.add(id) else oov.add(ch.toString())
            }
            spans.add(start until ids.size)
        }
        return EncodedTarget(ids.toIntArray(), spans, oov)
    }

    /** Encode a [Target] built by the caller's own Quran source (SPEC 5.5). */
    fun encode(target: Target): EncodedTarget =
        encode(target.words.map { it.tokens.joinToString("") })
}
