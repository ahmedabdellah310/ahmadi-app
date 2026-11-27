package io.github.sammynashed.emran.data.khatmah

import android.content.Context
import android.util.Base64
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Keeps a group khatmah current without anyone pasting codes again.
 *
 * The invite carries a random key. Every phone keeps ITS copy of the group on a small relay
 * (emran-sync on Netlify), encrypted with that key, and reads everyone else's copy back; copies merge
 * newest-wins per juz, exactly as pasted codes always did. The relay only stores opaque blobs under an
 * id derived from the key: it never sees a name, a juz or the group.
 */
object KhatmahSync {
    private const val BASE = ""
    private val rng = SecureRandom()

    fun newKey(): String = ByteArray(24).also { rng.nextBytes(it) }.let { b64(it) }

    /** The key in an invite ("<code>#<key>"), or null for a plain code. */
    fun keyOf(invite: String): String? = invite.trim().substringAfter('#', "").trim().takeIf { it.length in 20..64 && it.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' } }

    /** What changed when a group synced: the group before and after, for news. */
    data class Change(val before: GroupKhatmah, val after: GroupKhatmah, val me: String?, val ended: Boolean = false)

    /** Pulls friends' copies, merges, and pushes mine if it changed. Null when the group has no key or the net failed. */
    suspend fun sync(context: Context, groupId: String): Change? = withContext(Dispatchers.IO) {
        val store = KhatmahStore(context)
        val saved = store.load(groupId) ?: return@withContext null
        val key = store.syncKey(groupId) ?: return@withContext null
        runCatching {
            val room = roomOf(key)
            val got = JSONObject(http("GET", "$BASE?g=$room", null))
            if (got.optBoolean("ended")) {
                // The creator ended it for everyone: forget it here too.
                store.delete(groupId)
                return@runCatching Change(saved.group, saved.group, saved.myMemberId, ended = true)
            }
            var merged = saved.group
            val left = mutableListOf<String>()
            val m = got.optJSONObject("m")
            m?.keys()?.forEach { mid ->
                val d = m.optJSONObject(mid)?.optString("d").orEmpty()
                val plain = runCatching { decrypt(key, d) }.getOrNull() ?: return@forEach
                if (plain.startsWith("LEFT|")) { left += mid; return@forEach }
                val theirs = GroupKhatmahCode.decode(plain).getOrNull() ?: return@forEach
                if (theirs.id == merged.id) merged = merged.merge(theirs)
            }
            // Someone who left gives up their reading juz for everyone; the juz they finished stay, with their name.
            for (mid in left) {
                val now = System.currentTimeMillis()
                merged = merged.copy(marks = merged.marks.mapValues { (_, mk) ->
                    if (mk.by == mid && mk.reading) mk.copy(reading = false, at = now) else mk
                })
            }
            if (merged != saved.group) store.replace(merged)
            val me = saved.myMemberId
            val mine = GroupKhatmahCode.encode(merged)
            if (me != null && mine != store.lastUpload(groupId)) {
                val body = JSONObject().put("d", encrypt(key, mine)).put("w", store.writeSecret(groupId)).toString()
                http("PUT", "$BASE?g=$room&m=$me", body)
                store.setLastUpload(groupId, mine)
            }
            Change(saved.group, merged, me)
        }.getOrNull()
    }

    /** Leaves the group: my copy becomes a "left" marker (no juz in it) and my reading juz are released. False if offline. */
    suspend fun leave(context: Context, groupId: String): Boolean = withContext(Dispatchers.IO) {
        val store = KhatmahStore(context)
        val me = store.load(groupId)?.myMemberId ?: return@withContext true
        val key = store.syncKey(groupId)
        if (key != null) runCatching {
            val body = JSONObject().put("d", encrypt(key, "LEFT|$me")).put("w", store.writeSecret(groupId)).toString()
            http("PUT", "$BASE?g=${roomOf(key)}&m=$me", body)
        }.getOrElse { return@withContext false }
        store.delete(groupId)
        true
    }

    /** Creator only: ends the group for everyone. False if offline or I am not the creator on the server. */
    suspend fun end(context: Context, groupId: String): Boolean = withContext(Dispatchers.IO) {
        val store = KhatmahStore(context)
        val me = store.load(groupId)?.myMemberId ?: return@withContext false
        val key = store.syncKey(groupId) ?: return@withContext false
        runCatching {
            val body = JSONObject().put("m", me).put("w", store.writeSecret(groupId)).toString()
            http("POST", "$BASE?g=${roomOf(key)}&op=end", body)
            store.delete(groupId)
            true
        }.getOrDefault(false)
    }

    /** Background sync every ~15 min while any group exists (what lets news arrive with Emran closed). */
    fun schedule(context: Context) {
        val req = PeriodicWorkRequestBuilder<Worker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("khatmah_sync", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    class Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val store = KhatmahStore(applicationContext)
            val groups = store.groups()
            if (groups.isEmpty()) { WorkManager.getInstance(applicationContext).cancelUniqueWork("khatmah_sync"); return Result.success() }
            val ar = applicationContext.getSharedPreferences("emran_settings", Context.MODE_PRIVATE).getString("current_language", "en") == "ar"
            for (g in groups) {
                val c = sync(applicationContext, g.group.id) ?: continue
                if (c.ended) io.github.sammynashed.emran.ui.screens.khatmahEnded(applicationContext, g.group.name, ar)
                else io.github.sammynashed.emran.ui.screens.khatmahNews(applicationContext, c, ar)
            }
            return Result.success()
        }
    }

    private fun roomOf(key: String): String =
        b64(MessageDigest.getInstance("SHA-256").digest("emran-khatmah-room:$key".toByteArray())).take(32)

    private fun aes(key: String): SecretKeySpec =
        SecretKeySpec(MessageDigest.getInstance("SHA-256").digest("emran-khatmah-key:$key".toByteArray()), "AES")

    private fun encrypt(key: String, text: String): String {
        val iv = ByteArray(12).also { rng.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, aes(key), GCMParameterSpec(128, iv)) }
        return b64(iv + c.doFinal(text.toByteArray(Charsets.UTF_8)))
    }

    private fun decrypt(key: String, data: String): String {
        val raw = Base64.decode(data, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, aes(key), GCMParameterSpec(128, raw, 0, 12)) }
        return String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun http(method: String, url: String, body: String?): String {
        io.github.sammynashed.emran.data.NoRemote.block()
        @Suppress("UNREACHABLE_CODE")
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15000; c.readTimeout = 20000
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            check(code in 200..299) { "sync $code" }
            return text
        } finally { c.disconnect() }
    }
}
