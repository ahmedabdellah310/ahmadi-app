package ai.ratl.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One romanised word out for every Arabic word in.
 *
 * The blind-recitation screen maps result *i* onto the Arabic word at position
 * *i*. If the Normalizer ever dropped or merged a word, that mapping would
 * silently shift and the wrong words would be revealed - which, in a
 * memorisation aid, is worse than revealing nothing.
 *
 * Verified across the whole Qur'an with the reference implementation: 6,236
 * ayat, 77,429 words, zero dropped. These cases pin the property in the build.
 */
class NormalizerAlignmentTest {

    private val ayat = listOf(
        // al-Fatihah 1:1-2, hamzat wasl in both positions
        "بِسۡمِ ٱللَّهِ ٱلرَّحۡمَٰنِ ٱلرَّحِيمِ",
        "ٱلۡحَمۡدُ لِلَّهِ رَبِّ ٱلۡعَٰلَمِينَ",
        // al-Ikhlas, the shortest complete surah
        "قُلۡ هُوَ ٱللَّهُ أَحَدٌ",
        "ٱللَّهُ ٱلصَّمَدُ",
        "لَمۡ يَلِدۡ وَلَمۡ يُولَدۡ",
        "وَلَمۡ يَكُن لَّهُۥ كُفُوًا أَحَدُۢ",
        // an-Nas, with a waqf mark and a long vowel run
        "قُلۡ أَعُوذُ بِرَبِّ ٱلنَّاسِ",
        // muqatta'at: recited as letter names, never dropped
        "الٓمٓ",
    )

    @Test
    fun `every arabic word yields exactly one romanised word`() {
        for (verse in ayat) {
            val arabicWords = verse.split(Regex("\\s+")).filter { it.isNotBlank() }
            val romanised = Normalizer.romanizeVerse(verse)
            assertEquals(
                arabicWords.size, romanised.size,
                "word count changed for: $verse\n  arabic=$arabicWords\n  romanised=$romanised",
            )
            romanised.forEachIndexed { i, w ->
                assertTrue(w.isNotBlank(), "word $i romanised to blank in: $verse")
            }
        }
    }

    @Test
    fun `romanised words encode to non-empty token spans`() {
        // A word that produced no tokens would leave a hole in the CTC target
        // and the reveal indices would drift past it.
        val vocabulary = Vocabulary.fromJson(
            java.io.File(
                generateSequence(java.io.File(".").absoluteFile) { it.parentFile }
                    .first { java.io.File(it, "conformance/manifest.json").exists() },
                "models/out/vocab.json",
            ).readText(),
        )
        val tokenizer = Tokenizer(vocabulary)
        for (verse in ayat) {
            val words = Normalizer.romanizeVerse(verse)
            val encoded = tokenizer.encode(words)
            assertEquals(
                words.size, encoded.wordSpans.size,
                "span count must match word count for: $verse",
            )
            encoded.wordSpans.forEachIndexed { i, span ->
                assertTrue(span != null, "word $i has no token span in: $verse")
            }
        }
    }
}
