package io.github.sammynashed.emran.data.audio

import android.content.Context
import android.media.MediaPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * One word, on its own, in the fixed voice.
 *
 * The reciters' recordings are one MP3 per āyah, so a single word cannot be
 * played from them. The word-by-word recordings at verses.quran.com are one
 * MP3 per word (Quran.com's wbw set, a single fixed voice — not the chosen
 * reciter's). The file for a word is found through the bundled
 * `word_audio.json`: for every āyah, the wbw file number of each of its words
 * in the order the muṣḥaf draws them (the numbers are NOT the word's position:
 * pause marks count as tokens in the wbw numbering, e.g. 2:2 is [1,2,3,4,6,8,9],
 * and a few words share one file, e.g. 2:181's "بَعْدَ مَا"). The map was
 * verified against the Quran.com API for all 6,236 āyāt before it was bundled;
 * an āyah missing from it simply falls back to the whole āyah, as before.
 *
 * Files are downloaded on demand into cacheDir/word-audio/ and reused. The
 * player is a plain MediaPlayer, not the recitation queue: a one-second word
 * must not replace the queue, the Now-playing state, or the Listen screen.
 * All player access is confined to the main thread.
 */
object WordAudio {

    enum class Result { PLAYED_WORD, FALLBACK_AYAH }

    private const val BASE_URL = "https://verses.quran.com/"
    private const val DIR = "word-audio"

    @Volatile private var audioMap: Map<String, IntArray>? = null

    /** Loads the bundled map once; call off the main thread. */
    @Synchronized
    private fun loadMap(context: Context): Map<String, IntArray> {
        audioMap?.let { return it }
        val root = JSONObject(context.assets.open("word_audio.json").bufferedReader().use { it.readText() })
        val out = HashMap<String, IntArray>(6300)
        val keys = root.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val arr = root.getJSONArray(k)
            out[k] = IntArray(arr.length()) { arr.getInt(it) }
        }
        return out.also { audioMap = it }
    }

    /** The wbw file number for [word] (0-based, as drawn) of [surah]:[ayah], or null. */
    fun audioNumber(context: Context, surah: Int, ayah: Int, word: Int): Int? {
        val nums = loadMap(context.applicationContext)["$surah:$ayah"] ?: return null
        return nums.getOrNull(word)
    }

    private fun fileFor(context: Context, surah: Int, ayah: Int, num: Int): File {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        return File(dir, "wbw_%03d_%03d_%03d.mp3".format(surah, ayah, num))
    }

    /** Downloads one word file; a unique temp name keeps two taps from clashing. */
    private fun download(surah: Int, ayah: Int, num: Int, out: File): Boolean {
        val tmp = File(out.parentFile, out.name + "." + System.nanoTime() + ".tmp")
        val ok = runCatching {
            val c = URL(BASE_URL + "wbw/%03d_%03d_%03d.mp3".format(surah, ayah, num))
                .openConnection() as HttpURLConnection
            c.connectTimeout = 10000
            c.readTimeout = 15000
            c.instanceFollowRedirects = true
            c.connect()
            if (c.responseCode != HttpURLConnection.HTTP_OK) return@runCatching false
            tmp.outputStream().use { sink ->
                c.inputStream.use { input -> input.copyTo(sink) }
            }
            c.disconnect()
            tmp.length() > 0 && tmp.renameTo(out)
        }.getOrDefault(false)
        runCatching { tmp.delete() }
        return ok && out.exists() && out.length() > 0
    }

    private var player: MediaPlayer? = null

    /** Stops a word that is sounding. Call on the main thread. */
    fun stop() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
    }

    /**
     * Plays just [word] of [surah]:[ayah] in the fixed voice: the cached file
     * when it is there, otherwise a download. When the āyah has no mapping for
     * the word, or the file cannot be fetched (offline), the caller falls back
     * to the whole āyah — [Result.FALLBACK_AYAH]. Never throws: any failure
     * is a fallback, so a tap can never crash the reader.
     */
    suspend fun playWord(context: Context, surah: Int, ayah: Int, word: Int): Result {
        return runCatching {
            val appContext = context.applicationContext
            val num = withContext(Dispatchers.IO) { audioNumber(appContext, surah, ayah, word) }
                ?: return Result.FALLBACK_AYAH
            val file = fileFor(appContext, surah, ayah, num)
            val ready = if (file.exists() && file.length() > 0) true
                else withContext(Dispatchers.IO) { download(surah, ayah, num, file) }
            if (!ready) return Result.FALLBACK_AYAH
            withContext(Dispatchers.Main) {
                QuranAudio.stop()
                stop()
                val ok = runCatching {
                    val mp = MediaPlayer()
                    mp.setDataSource(file.path)
                    mp.setOnCompletionListener { if (player === mp) stop() }
                    mp.prepare()
                    mp.start()
                    player = mp
                }.isSuccess
                if (!ok) runCatching { file.delete() }
                if (ok) Result.PLAYED_WORD else Result.FALLBACK_AYAH
            }
        }.getOrDefault(Result.FALLBACK_AYAH)
    }
}
