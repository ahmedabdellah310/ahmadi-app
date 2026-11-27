package ai.ratl.eval

/**
 * Minimal JSON reader for the evaluation manifests.
 *
 * Hand-written to keep the harness dependency-free; it handles only the shapes
 * the manifests use - objects, arrays, numbers and strings.
 */
object Json {

    fun indexOfKey(s: String, key: String, from: Int = 0): Int = s.indexOf("\"$key\"", from)

    fun matching(s: String, open: Int): Int {
        val want = if (s[open] == '[') ']' else '}'
        var depth = 0
        var i = open
        var inStr = false
        while (i < s.length) {
            val c = s[i]
            if (inStr) {
                if (c == '\\') i++ else if (c == '"') inStr = false
            } else when (c) {
                '"' -> inStr = true
                s[open] -> depth++
                want -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        error("unbalanced bracket at $open")
    }

    /** Top-level element slices of the array beginning at [open]. */
    fun elements(s: String, open: Int): List<String> {
        val end = matching(s, open)
        val out = ArrayList<String>()
        var i = open + 1
        var depth = 0
        var start = i
        var inStr = false
        while (i < end) {
            val c = s[i]
            if (inStr) {
                if (c == '\\') i++ else if (c == '"') inStr = false
            } else when (c) {
                '"' -> inStr = true
                '[', '{' -> { if (depth == 0) start = i; depth++ }
                ']', '}' -> depth--
                ',' -> if (depth == 0) { out.add(s.substring(start, i).trim()); start = i + 1 }
            }
            i++
        }
        s.substring(start, end).trim().takeIf { it.isNotEmpty() }?.let { out.add(it) }
        return out
    }

    fun field(s: String, key: String, from: Int = 0): String {
        val k = indexOfKey(s, key, from)
        require(k >= 0) { "key '$key' not found" }
        var i = s.indexOf(':', k) + 1
        while (s[i].isWhitespace()) i++
        return when (s[i]) {
            '[', '{' -> s.substring(i, matching(s, i) + 1)
            '"' -> readString(s, i).first
            else -> {
                var j = i
                while (j < s.length && s[j] != ',' && s[j] != '}' && s[j] != '\n') j++
                s.substring(i, j).trim()
            }
        }
    }

    fun ints(slice: String): IntArray {
        val open = slice.indexOf('[')
        return elements(slice, open).map { it.trim().toInt() }.toIntArray()
    }

    fun stringArray(slice: String): List<String> {
        val open = slice.indexOf('[')
        val end = matching(slice, open)
        val out = ArrayList<String>()
        var i = open + 1
        while (i < end) {
            if (slice[i] == '"') {
                val (value, next) = readString(slice, i)
                out.add(value)
                i = next
            } else i++
        }
        return out
    }

    /** Reads the string starting at the quote [i]; returns value and next index. */
    private fun readString(s: String, i: Int): Pair<String, Int> {
        val sb = StringBuilder()
        var j = i + 1
        while (s[j] != '"') {
            if (s[j] == '\\') {
                j++
                when (val e = s[j]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    'b' -> sb.append('\b')
                    'u' -> { sb.append(s.substring(j + 1, j + 5).toInt(16).toChar()); j += 4 }
                    else -> sb.append(e)
                }
            } else sb.append(s[j])
            j++
        }
        return sb.toString() to j + 1
    }
}
