package ai.ratl.core

import java.io.File

/**
 * Minimal reader for the committed conformance vectors.
 *
 * Hand-written so the portable modules stay dependency-free. It parses only the
 * shapes the vectors actually use: nested arrays of numbers, nulls, and strings.
 */
object Vectors {

    val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "conformance/manifest.json").exists() }

    fun read(name: String): String =
        File(repoRoot, "conformance/vectors/$name").readText()

    fun modelFile(name: String): File = File(repoRoot, "models/out/$name")

    /** Index of the bracket matching the one at [open], string-aware. */
    fun matching(s: String, open: Int): Int {
        val closeOf = mapOf('[' to ']', '{' to '}')
        val want = closeOf[s[open]] ?: error("not a bracket at $open")
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
        error("unbalanced from $open")
    }

    /** Top-level element slices of the array starting at [open]. */
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
                ',' -> if (depth == 0) {
                    out.add(s.substring(start, i).trim()); start = i + 1
                }
            }
            i++
        }
        s.substring(start, end).trim().takeIf { it.isNotEmpty() }?.let { out.add(it) }
        return out
    }

    fun doubles(slice: String): DoubleArray {
        val open = slice.indexOf('[')
        return elements(slice, open).map { it.trim().toDouble() }.toDoubleArray()
    }

    fun ints(slice: String): IntArray {
        val open = slice.indexOf('[')
        return elements(slice, open).map { it.trim().toInt() }.toIntArray()
    }

    /** `[[a,b], null, ...]` -> list of IntArray?; used for spans. */
    fun pairsOrNull(slice: String): List<IntArray?> {
        val open = slice.indexOf('[')
        return elements(slice, open).map { e ->
            val t = e.trim()
            if (t.startsWith("null")) null
            else ints(t).also { require(it.size == 2) { "expected a pair, got $t" } }
        }
    }

    /** Value of key [key] within the object/section starting at [from]. */
    fun field(s: String, key: String, from: Int = 0): String {
        val k = s.indexOf("\"$key\"", from)
        require(k >= 0) { "key $key not found" }
        var i = s.indexOf(':', k) + 1
        while (s[i].isWhitespace()) i++
        return when (s[i]) {
            '[', '{' -> s.substring(i, matching(s, i) + 1)
            '"' -> {
                val end = generateSequence(i + 1) { j ->
                    if (s[j] == '\\') j + 2 else j + 1
                }.first { s[it] == '"' }
                s.substring(i + 1, end)
            }
            else -> {
                var j = i
                while (j < s.length && s[j] !in ",}\n") j++
                s.substring(i, j).trim()
            }
        }
    }

    fun indexOfKey(s: String, key: String, from: Int = 0): Int = s.indexOf("\"$key\"", from)
}
