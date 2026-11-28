package io.github.sammynashed.emran.data

import android.content.Context
import org.json.JSONObject
import java.util.zip.GZIPInputStream

/**
 * What one word of the Qur'ān means, in Arabic, from the books of
 * gharīb al-Qur'ān (words that need explaining — common words have no entry,
 * as in the printed books).
 *
 * Source: Quranpedia (الموسوعة القرآنية, quranpedia.net), whose meanings dump
 * is free to use inside apps with no attribution required. Four books, in
 * this order: السراج في بيان غريب القرآن، كلمات القرآن، تذكرة الأريب في تفسير
 * الغريب، التبيان في تفسير غريب القرآن. The dump (4,799 āyāt, 20,244 entries)
 * is bundled as `word_meanings.json.gz` and parsed once, off the main thread.
 * A word with no entry in any book gets no invented meaning — the sheet says
 * so plainly and shows the āyah's tafsīr instead.
 *
 * Matching is by the word's skeleton: diacritics, pause marks and tatweel
 * stripped, hamza forms unified, so the muṣḥaf's Uthmanic spelling and the
 * books' spelling meet. An entry whose text is a phrase (e.g. "بِسْمِ اللهِ")
 * matches any word of that phrase; the phrase itself is shown, so it is
 * clear what the meaning explains.
 */
object WordMeanings {

    data class Meaning(
        /** The glossed word or phrase, as written in the book. */
        val text: String,
        val meaning: String,
        /** The book it comes from, e.g. "السراج في بيان غريب القرآن". */
        val book: String,
    )

    @Volatile private var meanings: Map<String, List<Meaning>>? = null

    /** Loads the bundled dump once; call off the main thread. */
    private fun load(context: Context): Map<String, List<Meaning>> {
        meanings?.let { return it }
        val root = GZIPInputStream(context.assets.open("word_meanings.json.gz")).bufferedReader().use {
            JSONObject(it.readText())
        }
        val out = HashMap<String, List<Meaning>>(4800)
        val keys = root.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val arr = root.getJSONArray(k)
            out[k] = List(arr.length()) { i ->
                val e = arr.getJSONArray(i)
                Meaning(e.getString(0), e.getString(1), e.getString(2))
            }
        }
        return out.also { meanings = it }
    }

    /**
     * The skeleton of Arabic text: diacritics, pause marks, tatweel and the
     * hamza letter stripped; alef forms unified, alef maksura and hamza-on-yeh
     * read as yeh, hamza-on-waw as waw. Spaces are kept (a phrase's words are
     * matched one by one).
     */
    fun normalizeArabic(s: String): String {
        val out = StringBuilder(s.length)
        for (c in s) {
            val o = c.code
            if ((o in 0x0610..0x061A) || (o in 0x064B..0x065F) || o == 0x0670 ||
                (o in 0x06D6..0x06DC) || (o in 0x06DF..0x06E8) || (o in 0x06EA..0x06ED) ||
                o == 0x0640 || (o in 0x08D3..0x08E2) || (o in 0x200C..0x200F) ||
                o == 0x06DD || o == 0x06DE || o == 0x06E9 || o == 0x0621
            ) continue
            when (c) {
                'ٱ', 'أ', 'إ', 'آ' -> out.append('ا')
                'ى', 'ئ' -> out.append('ي')
                'ؤ' -> out.append('و')
                else -> out.append(c)
            }
        }
        return out.toString().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
    }

    /**
     * The meaning of [wordText] in [surah]:[ayah], or null when no book
     * explains it. An entry whose whole text is the word wins over one that
     * merely contains it; within each, the books are tried in their order.
     * Never throws: a failed load is simply no meaning.
     */
    fun meaningFor(context: Context, surah: Int, ayah: Int, wordText: String): Meaning? {
        val w = normalizeArabic(wordText)
        if (w.isEmpty()) return null
        val entries = runCatching { load(context.applicationContext)["$surah:$ayah"] }.getOrNull()
            ?: return null
        for (e in entries) {
            if (normalizeArabic(e.text) == w) return e
        }
        for (e in entries) {
            if (normalizeArabic(e.text).split(' ').any { it == w }) return e
        }
        return null
    }
}
