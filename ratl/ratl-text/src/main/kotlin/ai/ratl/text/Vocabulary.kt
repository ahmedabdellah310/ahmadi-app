package ai.ratl.text

/**
 * The model's token inventory.
 *
 * 35 tokens, frozen with the acoustic model (SPEC 2.1): `<blank>` at index 0,
 * an explicit `|` word separator at index 1, then the romanised character set.
 * The separator is not decoration - it is a trained token, and the boundary rule
 * that produced G2 = 0.8570 reads word edges from where it fires.
 *
 * Loaded from the `vocab.json` shipped beside the model rather than hardcoded,
 * so the vocabulary can never silently drift from the weights.
 */
class Vocabulary private constructor(val tokens: List<String>) {

    private val index: Map<String, Int> = tokens.withIndex().associate { (i, t) -> t to i }

    val size: Int get() = tokens.size

    /** Index of a single-character token, or null if out of vocabulary. */
    fun indexOf(token: String): Int? = index[token]

    fun tokenAt(i: Int): String = tokens[i]

    companion object {
        const val BLANK_INDEX = 0
        const val SEPARATOR = "|"

        /**
         * Parse a `vocab.json` payload: a JSON array of single-token strings.
         *
         * Deliberately hand-parsed. The vocabulary is a flat string array and
         * pulling a JSON dependency into the most portable module in the tree
         * to read it would be a poor trade.
         */
        fun fromJson(json: String): Vocabulary {
            val trimmed = json.trim()
            require(trimmed.startsWith("[") && trimmed.endsWith("]")) {
                "vocab.json must be a JSON array of token strings"
            }
            val out = ArrayList<String>()
            val body = trimmed.substring(1, trimmed.length - 1)
            var i = 0
            while (i < body.length) {
                when {
                    body[i].isWhitespace() || body[i] == ',' -> i++
                    body[i] == '"' -> {
                        val sb = StringBuilder()
                        i++
                        while (i < body.length && body[i] != '"') {
                            if (body[i] == '\\' && i + 1 < body.length) {
                                i++
                                when (val e = body[i]) {
                                    'n' -> sb.append('\n')
                                    't' -> sb.append('\t')
                                    'r' -> sb.append('\r')
                                    'b' -> sb.append('\b')
                                    'f' -> sb.append('')
                                    'u' -> {
                                        sb.append(body.substring(i + 1, i + 5).toInt(16).toChar())
                                        i += 4
                                    }
                                    else -> sb.append(e)   // \" \\ \/
                                }
                            } else {
                                sb.append(body[i])
                            }
                            i++
                        }
                        i++
                        out.add(sb.toString())
                    }
                    else -> error("unexpected character ${body[i]} at $i in vocab.json")
                }
            }
            require(out.isNotEmpty()) { "vocab.json is empty" }
            require(out.size == 35) {
                "expected the frozen 35-token vocabulary, got ${out.size}"
            }
            require(out[BLANK_INDEX] == "<blank>") {
                "blank must be index $BLANK_INDEX, found '${out[BLANK_INDEX]}'"
            }
            require(out.contains(SEPARATOR)) { "vocabulary is missing the '|' separator" }
            return Vocabulary(out)
        }
    }
}
