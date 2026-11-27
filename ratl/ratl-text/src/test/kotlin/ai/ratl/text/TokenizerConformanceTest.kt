package ai.ratl.text

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the Kotlin tokenizer against the committed T0 golden vector.
 *
 * The vector was generated from the spike reference modules imported unmodified,
 * so passing here means the Kotlin encoder agrees with the code path that
 * measured G2 = 0.8570 - not that it agrees with a re-description of it.
 *
 * Tolerance is exact: these are integers and strings.
 */
class TokenizerConformanceTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "conformance/manifest.json").exists() }

    private fun readVector(name: String): String =
        File(repoRoot, "conformance/vectors/$name").readText()

    private val vocabulary: Vocabulary =
        Vocabulary.fromJson(File(repoRoot, "models/out/vocab.json").readText())

    @Test
    fun `vocabulary is the frozen 35 token inventory`() {
        assertEquals(35, vocabulary.size)
        assertEquals("<blank>", vocabulary.tokenAt(Vocabulary.BLANK_INDEX))
        assertEquals(1, vocabulary.indexOf(Vocabulary.SEPARATOR))
    }

    @Test
    fun `tokenizer reproduces the T0 golden vector exactly`() {
        val json = readVector("t0_tokenize.json")
        val cases = parseCases(json)
        assertTrue(cases.isNotEmpty(), "no cases parsed from t0_tokenize.json")

        val tokenizer = Tokenizer(vocabulary)
        for ((i, case) in cases.withIndex()) {
            val got = tokenizer.encode(case.words)
            assertEquals(
                case.ids, got.ids.toList(),
                "case $i ids mismatch for words=${case.words}",
            )
            val gotSpans = got.wordSpans.map { s ->
                s?.let { listOf(it.first, it.last + 1) }
            }
            assertEquals(
                case.spans, gotSpans,
                "case $i span mismatch for words=${case.words}",
            )
            assertEquals(case.oov, got.outOfVocabulary, "case $i oov mismatch")
        }
    }

    // ---- minimal reader for the vector's shape; no JSON dependency in this module
    private data class Case(
        val words: List<String>,
        val ids: List<Int>,
        val spans: List<List<Int>?>,
        val oov: List<String>,
    )

    private fun parseCases(json: String): List<Case> {
        val out = ArrayList<Case>()
        var i = json.indexOf("\"cases\"")
        require(i >= 0) { "vector has no cases array" }
        while (true) {
            val w = json.indexOf("\"words\"", i)
            if (w < 0) break
            val words = readStringArray(json, json.indexOf('[', w))
            val idsAt = json.indexOf("\"ids\"", w)
            val ids = readIntArray(json, json.indexOf('[', idsAt))
            val spansAt = json.indexOf("\"spans\"", idsAt)
            val spans = readSpanArray(json, json.indexOf('[', spansAt))
            val oovAt = json.indexOf("\"oov\"", spansAt)
            val oov = readStringArray(json, json.indexOf('[', oovAt))
            out.add(Case(words, ids, spans, oov))
            i = json.indexOf(']', oovAt)
            if (i < 0) break
        }
        return out
    }

    private fun matchingBracket(s: String, open: Int): Int {
        var depth = 0
        var i = open
        var inStr = false
        while (i < s.length) {
            val c = s[i]
            if (inStr) {
                if (c == '\\') i++ else if (c == '"') inStr = false
            } else when (c) {
                '"' -> inStr = true
                '[' -> depth++
                ']' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        error("unbalanced bracket from $open")
    }

    private fun readStringArray(s: String, open: Int): List<String> {
        val body = s.substring(open, matchingBracket(s, open) + 1)
        val out = ArrayList<String>()
        var i = 1
        while (i < body.length) {
            if (body[i] == '"') {
                val sb = StringBuilder()
                i++
                while (body[i] != '"') {
                    if (body[i] == '\\') {
                        i++
                        if (body[i] == 'u') {
                            sb.append(body.substring(i + 1, i + 5).toInt(16).toChar()); i += 4
                        } else sb.append(body[i])
                    } else sb.append(body[i])
                    i++
                }
                out.add(sb.toString())
            }
            i++
        }
        return out
    }

    private fun readIntArray(s: String, open: Int): List<Int> =
        s.substring(open + 1, matchingBracket(s, open))
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { it.toInt() }

    private fun readSpanArray(s: String, open: Int): List<List<Int>?> {
        val body = s.substring(open + 1, matchingBracket(s, open))
        val out = ArrayList<List<Int>?>()
        var i = 0
        while (i < body.length) {
            when {
                body[i] == '[' -> {
                    val close = body.indexOf(']', i)
                    out.add(
                        body.substring(i + 1, close).split(',')
                            .map { it.trim().toInt() },
                    )
                    i = close + 1
                }
                body.startsWith("null", i) -> { out.add(null); i += 4 }
                else -> i++
            }
        }
        return out
    }
}
