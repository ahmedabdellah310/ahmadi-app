package io.github.sammynashed.emran.data.audio

import android.content.Context

/**
 * The voices Emran offers for Quran audio. [folder] is the reciter's folder on EveryAyah, where the
 * ayat are downloaded from (one MP3 per ayah, so the reader always knows which ayah is playing).
 * The short preview (Al-Fatiha 1:2–4) ships inside the app so a voice can be heard before any
 * download. [bytesPerWord] was measured on Surat al-Mulk and only feeds size estimates.
 *
 * A few voices exist only as whole-surah recordings (mp3quran.net, [surahUrl]): one file per surah,
 * no ayah timings, so they play the surah from its start and nothing is marked on the page.
 * [missing] lists the surahs such a reciter never recorded; [extra] gives the file of a surah that
 * lives somewhere other than [surahUrl].
 */
data class Reciter(
    val id: String,
    val ar: String,
    val en: String,
    val folder: String,
    val bytesPerWord: Int,
    val surahUrl: String? = null,
    val missing: Set<Int> = emptySet(),
    val extra: Map<Int, String> = emptyMap(),
) {
    val previewAsset get() = "reciters/$id.ogg"

    /** One file per surah, so no ayah-by-ayah marking, repeat or skipping. */
    val wholeSurah get() = surahUrl != null

    fun has(surah: Int) = surah in 1..114 && surah !in missing

    val surahCount get() = 114 - missing.size

    /** Where a whole-surah reciter's [surah] is downloaded from. */
    fun surahFileUrl(surah: Int) = extra[surah] ?: "$surahUrl%03d.mp3".format(surah)
}

object Reciters {
    val BUILTIN = listOf(
        Reciter("alafasy", "مشاري العفاسي", "Mishary Alafasy", "Alafasy_128kbps", 21391),
        Reciter("muaiqly", "ماهر المعيقلي", "Maher Al-Muaiqly", "MaherAlMuaiqly128kbps", 13353),
        Reciter("dosari", "ياسر الدوسري", "Yasser Al-Dosari", "Yasser_Ad-Dussary_128kbps", 18180),
        Reciter("juhany", "عبد الله عواد الجهني", "Abdullah Awad Al-Juhany", "Abdullaah_3awwaad_Al-Juhaynee_128kbps", 14424),
        Reciter("shatri", "أبو بكر الشاطري", "Abu Bakr Al-Shatri", "Abu_Bakr_Ash-Shaatree_128kbps", 18354),
        Reciter("qatami", "ناصر القطامي", "Nasser Al-Qatami", "Nasser_Alqatami_128kbps", 14742),
        Reciter("ghamdi", "سعد الغامدي", "Saad Al-Ghamdi", "Ghamadi_40kbps", 5687),
        Reciter("husary", "محمود خليل الحصري", "Mahmoud Khalil Al-Husary", "Husary_128kbps", 32113),
        Reciter("abdulbasit", "عبد الباسط عبد الصمد", "Abdul Basit Abdus Samad", "Abdul_Basit_Murattal_64kbps", 11626),
        Reciter("minshawi", "محمد صدّيق المنشاوي", "Muhammad Siddiq Al-Minshawi", "Minshawy_Murattal_128kbps", 19796),
        // Asked for by a friend (2026-11). Not on EveryAyah. mp3quran.net has 109 surahs; the other five
        // come from way2quran.com (At-Talaq checked: all 12 ayat, ayah lengths match the usual pattern).
        // bytesPerWord: Al-Fatiha, al-Mujadila, al-Mulk and an-Naba measured together (files are 96–128 kbps).
        Reciter(
            "islam_sobhi", "إسلام صبحي", "Islam Sobhi", folder = "", bytesPerWord = 21030,
            surahUrl = "https://cdn.mp3quran.net/audio/islam-sobhi/r1/",
            extra = listOf(37, 39, 40, 45, 65).associateWith { "https://media.way2quran.com/islam-sobhi/hafs-an-asim/%03d.mp3".format(it) },
        ),
    )

    /** mp3quran.net sets (one per recitation): loaded once from the phone's saved copy, else the list built into the app. */
    @Volatile private var remote: List<Reciter> = emptyList()
    @Volatile private var remoteLoaded = false
    @Volatile private var rawRemote: org.json.JSONArray? = null

    /** Built-in voices first, then every mp3quran set. Call [ensureLoaded] once with a context before relying on it. */
    val ALL: List<Reciter> get() = BUILTIN + remote

    val remoteSets: List<Reciter> get() = remote
    val remoteReciterCount: Int get() = remote.map { it.id.substringBefore('_') }.distinct().size

    private fun cacheFile(context: Context) = java.io.File(context.applicationContext.filesDir, "mp3quran_reciters.json")

    @Synchronized
    fun ensureLoaded(context: Context) {
        if (remoteLoaded) return
        val app = context.applicationContext
        val f = cacheFile(app)
        val raw = try {
            if (f.exists()) f.readText() else app.assets.open("mq_reciters.json").bufferedReader().use { it.readText() }
        } catch (e: Exception) { app.assets.open("mq_reciters.json").bufferedReader().use { it.readText() } }
        remote = parse(raw)
        remoteLoaded = true
    }

    private fun parse(raw: String): List<Reciter> {
        val arr = try { org.json.JSONArray(raw) } catch (e: Exception) { return emptyList() }
        val out = ArrayList<Reciter>()
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            val ms = r.getJSONArray("m")
            for (j in 0 until ms.length()) {
                val m = ms.getJSONObject(j)
                val server = m.getString("s").let { if (it.endsWith("/")) it else "$it/" }
                if (!server.startsWith("https://")) continue
                val l = m.get("l")
                val have: Set<Int> = if (l is String) (1..114).toSet() else {
                    val a = l as org.json.JSONArray; (0 until a.length()).map { a.getInt(it) }.toSet()
                }
                val rw = m.getString("n")
                out.add(
                    Reciter(
                        id = "mq${r.getInt("i")}_${m.getInt("i")}",
                        ar = r.getString("a") + " · " + rw,
                        en = r.getString("e") + " · " + rw,
                        folder = "", bytesPerWord = 9000, surahUrl = server,
                        missing = (1..114).filter { it !in have }.toSet(),
                    ),
                )
            }
        }
        return out
    }

    /** Fetches the current list from mp3quran.net (Arabic names), keeps it on the phone and uses it. Returns the reciter count, or null when offline. */
    suspend fun refreshRemote(context: Context): Int? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val app = context.applicationContext
            val c = java.net.URL("https://www.mp3quran.net/api/v3/reciters?language=ar").openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 15000; c.readTimeout = 20000; c.instanceFollowRedirects = true
            if (c.responseCode != 200) return@withContext null
            val src = org.json.JSONObject(c.inputStream.bufferedReader().use { it.readText() }).getJSONArray("reciters")
            val enByIdx = HashMap<Int, String>()
            remote.forEach { enByIdx[it.id.substringAfter("mq").substringBefore('_').toInt()] = it.en.substringBefore(" · ") }
            val out = org.json.JSONArray()
            for (i in 0 until src.length()) {
                val r = src.getJSONObject(i)
                val ms = org.json.JSONArray()
                val arr = r.getJSONArray("moshaf")
                for (j in 0 until arr.length()) {
                    val m = arr.getJSONObject(j)
                    val list = m.optString("surah_list").split(",").mapNotNull { it.trim().toIntOrNull() }
                    ms.put(org.json.JSONObject().put("i", m.getInt("id")).put("n", m.getString("name").replace("  ", " ")).put("s", m.getString("server"))
                        .put("l", if (list.size == 114) "*" else org.json.JSONArray(list)))
                }
                out.put(org.json.JSONObject().put("i", r.getInt("id")).put("a", r.getString("name")).put("e", enByIdx[r.getInt("id")] ?: r.getString("name")).put("m", ms))
            }
            val text = out.toString()
            val parsed = parse(text)
            if (parsed.isEmpty()) return@withContext null
            cacheFile(app).writeText(text)
            remote = parsed
            out.length()
        } catch (e: Exception) { null }
    }

    private const val PREFS = "emran_settings"
    private const val KEY = "quran_reciter"

    fun chosen(context: Context): Reciter {
        ensureLoaded(context)
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        return ALL.firstOrNull { it.id == id } ?: ALL.first()
    }

    fun choose(context: Context, reciter: Reciter) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, reciter.id).apply()
    }
}
