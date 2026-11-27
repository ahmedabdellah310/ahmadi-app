package ai.ratl.text

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the alignment contract of [Normalizer.romanizeVerse].
 *
 * `romanizeVerse` ends in `.filter { it.isNotEmpty() }`. That is right for the
 * engine, which only wants pronounceable targets - but it means a word that
 * romanises to nothing disappears from the returned list while remaining in the
 * verse. Index *i* of the result is then no longer word *i* of the verse.
 *
 * This matters to any consumer that points a per-word result back at the text it
 * came from - a read-along display, a highlighter, a word-level score. For a
 * Qur'an application the failure mode is marking the wrong word, so the contract
 * is worth stating in a test rather than leaving for someone to discover.
 *
 * The contract is: **callers must not assume positional alignment.** Romanise
 * word by word and keep your own index map. These tests exist to show why, and
 * to catch the day the filter starts firing.
 */
class RomanizeVerseDropTest {

    /**
     * Arabic words from the T0 normalizer conformance vector.
     *
     * Read straight out of the JSON with a regex rather than a parser: this
     * module has no dependencies at all (see its `build.gradle.kts`), and one
     * field of a fixed-shape file does not justify adding one.
     */
    private fun vectorWords(): List<String> {
        val f = File("../conformance/vectors/t0_normalizer.json")
        assertTrue(f.isFile, "conformance vector missing at ${f.absolutePath}")
        return Regex(""""arabic"\s*:\s*"((?:[^"\\]|\\.)*)"""")
            .findAll(f.readText())
            .map { it.groupValues[1] }
            .filter { it.isNotBlank() }
            .toList()
    }

    @Test
    fun `romanizeVerse is exactly the per-word romanisation with empties dropped`() {
        val words = vectorWords()
        assertTrue(words.size > 10, "expected words in the vector, found ${words.size}")

        // Build verses out of the vector words so the property is exercised on
        // real orthography rather than invented strings.
        val verses = words.chunked(7).map { it.joinToString(" ") }
        for (verse in verses) {
            val perWord = verse.split(Regex("\\s+")).filter { it.isNotBlank() }
                .mapIndexed { i, w -> Normalizer.romanize(w, utteranceInitial = i == 0) }
            assertEquals(
                perWord.filter { it.isNotEmpty() },
                Normalizer.romanizeVerse(verse),
                "romanizeVerse must equal the per-word romanisation with empties removed",
            )
        }
    }

    @Test
    fun `a word that romanises to nothing shifts every later index`() {
        // The hazard, made concrete. If some word ever romanises to empty, the
        // returned list is shorter than the verse and positions no longer line
        // up - so a consumer indexing by position lands on the wrong word.
        val words = vectorWords()
        val verse = words.take(5).joinToString(" ")
        val perWord = verse.split(Regex("\\s+"))
            .mapIndexed { i, w -> Normalizer.romanize(w, utteranceInitial = i == 0) }
        val dropped = perWord.count { it.isEmpty() }
        val returned = Normalizer.romanizeVerse(verse).size

        assertEquals(
            perWord.size - dropped,
            returned,
            "the shortfall must be exactly the dropped words",
        )
    }

    /**
     * Sweeps a full Qur'an text when one is available, reporting how often the
     * filter actually fires.
     *
     * The corpus is not in this repository, so this is opt-in: set
     * `RATL_QURAN_JSON` in the environment, or the `ratl.quranJson` system
     * property, to point at one. It skips otherwise rather than failing,
     * because a contributor without the file has done nothing wrong.
     *
     * Measured against Emran's bundled Uthmani text (6236 verses): **no verse
     * loses a word.** Positional alignment therefore happens to hold today -
     * which is a property of that data, not of this code, and exactly why the
     * tests above pin the contract instead.
     */
    @Test
    fun `optionally measure the drop rate over a full corpus`() {
        val path = System.getProperty("ratl.quranJson")
            ?: System.getenv("RATL_QURAN_JSON")
        if (path == null) {
            println("no corpus configured; skipping the sweep")
            return
        }
        val f = File(path)
        assertTrue(f.isFile, "corpus not found at ${f.absolutePath}")

        val verses = Regex(""""text"\s*:\s*"((?:[^"\\]|\\.)*)"""")
            .findAll(f.readText())
            .map { it.groupValues[1] }
            .filter { it.isNotBlank() }
            .toList()

        var versesAffected = 0
        var wordsDropped = 0
        for (verse in verses) {
            val perWord = verse.split(Regex("\\s+")).filter { it.isNotBlank() }
                .mapIndexed { i, w -> Normalizer.romanize(w, utteranceInitial = i == 0) }
            val dropped = perWord.count { it.isEmpty() }
            if (dropped > 0) {
                versesAffected++
                wordsDropped += dropped
            }
        }
        println("verses scanned       : ${verses.size}")
        println("verses losing a word : $versesAffected")
        println("words dropped total  : $wordsDropped")
    }
}
