package ai.ratl.text

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Kotlin Normalizer must reproduce the validated Python reference exactly.
 *
 * The vector was generated from the implementation measured against 4,287
 * cached reference ayat, so agreement here means the Kotlin port carries the
 * measured behaviour (G2 = 0.8504 through this pipeline) rather than merely
 * compiling.
 *
 * Cases include every orthographic feature SPEC 5.5 names and, deliberately,
 * the ones that broke during development - the U+06E1 sukun encoding above all,
 * which silently disabled every sukun rule when it was mistaken for decoration.
 */
class NormalizerConformanceTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "conformance/manifest.json").exists() }

    private val json: String by lazy {
        File(repoRoot, "conformance/vectors/t0_normalizer.json").readText()
    }

    private fun strField(obj: String, key: String): String {
        val k = obj.indexOf("\"$key\"")
        require(k >= 0) { "key $key missing" }
        var i = obj.indexOf(':', k) + 1
        while (obj[i].isWhitespace()) i++
        require(obj[i] == '"') { "$key is not a string" }
        val sb = StringBuilder()
        var j = i + 1
        while (obj[j] != '"') {
            if (obj[j] == '\\') {
                j++
                when (obj[j]) {
                    'u' -> { sb.append(obj.substring(j + 1, j + 5).toInt(16).toChar()); j += 4 }
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    else -> sb.append(obj[j])
                }
            } else sb.append(obj[j])
            j++
        }
        return sb.toString()
    }

    private fun boolField(obj: String, key: String): Boolean {
        val k = obj.indexOf("\"$key\"")
        return obj.substring(k).substringAfter(':').trimStart().startsWith("true")
    }

    /** Top-level `{...}` objects inside the array named [key]. */
    private fun objects(key: String): List<String> {
        val start = json.indexOf('[', json.indexOf("\"$key\""))
        val out = ArrayList<String>()
        var i = start + 1
        var depth = 0
        var begin = -1
        var inStr = false
        while (i < json.length) {
            val c = json[i]
            if (inStr) {
                if (c == '\\') i++ else if (c == '"') inStr = false
            } else when (c) {
                '"' -> inStr = true
                '{' -> { if (depth == 0) begin = i; depth++ }
                '}' -> { depth--; if (depth == 0) out.add(json.substring(begin, i + 1)) }
                ']' -> if (depth == 0) return out
            }
            i++
        }
        return out
    }

    @Test
    fun `romanize reproduces the reference on every orthographic case`() {
        val cases = objects("words")
        assertTrue(cases.size >= 10, "expected the orthography cases, got ${cases.size}")
        for (c in cases) {
            val arabic = strField(c, "arabic")
            val note = strField(c, "note")
            val expected = strField(c, "expected")
            val initial = boolField(c, "utterance_initial")
            val got = Normalizer.romanize(arabic, initial).lowercase()
            assertEquals(expected, got, "[$note] $arabic")
        }
    }

    @Test
    fun `romanizeVerse reproduces the reference on real ayat`() {
        val ayat = objects("ayat")
        assertTrue(ayat.isNotEmpty())
        for (a in ayat) {
            val arabic = strField(a, "arabic")
            val expected = json.let {
                val seg = a.substring(a.indexOf("\"expected_words\""))
                val arr = seg.substring(seg.indexOf('['), seg.indexOf(']') + 1)
                Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(arr).map { m ->
                    m.groupValues[1].replace(Regex("\\\\u([0-9a-fA-F]{4})")) { r ->
                        r.groupValues[1].toInt(16).toChar().toString()
                    }
                }.toList()
            }
            val got = Normalizer.romanizeVerse(arabic).map { it.lowercase() }
            assertEquals(expected, got, "ayah: $arabic")
        }
    }

    @Test
    fun `every produced character is in the model vocabulary`() {
        // A character outside the 35-token vocabulary is silently dropped by the
        // tokenizer, so it would degrade alignment without any visible error.
        val vocabulary = Vocabulary.fromJson(
            File(repoRoot, "models/out/vocab.json").readText(),
        )
        val tokenizer = Tokenizer(vocabulary)
        for (a in objects("ayat")) {
            val words = Normalizer.romanizeVerse(strField(a, "arabic"))
            val encoded = tokenizer.encode(words)
            assertEquals(
                emptyList(), encoded.outOfVocabulary,
                "normalizer produced characters the tokenizer cannot encode",
            )
            assertTrue(encoded.ids.isNotEmpty(), "no tokens produced")
        }
    }

    @Test
    fun `hamzat wasl depends on position in the utterance`() {
        // al-hamdu at an ayah start, l-lahi after a vowel: the single rule most
        // likely to be "simplified" away by someone who has not read SPEC 5.5.
        val word = "ٱلۡحَمۡدُ"
        assertTrue(Normalizer.romanize(word, utteranceInitial = true).startsWith("al"))
        assertTrue(!Normalizer.romanize(word, utteranceInitial = false).startsWith("al"))
    }

    @Test
    fun `both sukun encodings are honoured`() {
        // U+0652 and U+06E1 must behave identically. Treating U+06E1 as
        // decoration silently disabled every sukun rule once already.
        val withU0652 = "بِسْمِ"
        val withU06E1 = "بِسۡمِ"
        assertEquals(
            Normalizer.romanize(withU0652, false),
            Normalizer.romanize(withU06E1, false),
        )
    }
}
