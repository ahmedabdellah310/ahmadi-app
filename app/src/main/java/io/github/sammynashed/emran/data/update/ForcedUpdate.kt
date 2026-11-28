package io.github.sammynashed.emran.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The app's own update site. On every app open the app asks GET <BASE_URL>/api/version; a newer versionCode with
 * forced = true blocks the app behind the update page (ui/screens/ForcedUpdateScreen.kt). Anything else (no site set,
 * offline, a bad answer, forced = false, nothing newer) lets the app open normally.
 */
object ForcedUpdate {
    /** Set this ONCE when the update site is deployed, e.g. "https://updates.example.com". Empty = the check is off. */
    const val BASE_URL = "https://ahmadi-updates-gamma.vercel.app"

    data class Info(
        val versionCode: Long, val versionName: String, val notes: String,
        val apkUrl: String, val sizeBytes: Long, val sha256: String, val forced: Boolean,
    )

    fun installedCode(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode

    /** The release to force, or null when the app should just open. Never throws. */
    suspend fun check(context: Context): Info? = withContext(Dispatchers.IO) {
        if (BASE_URL.isBlank()) return@withContext null
        runCatching {
            val c = URL(BASE_URL.trimEnd('/') + "/api/version").openConnection() as HttpURLConnection
            c.connectTimeout = 6000; c.readTimeout = 6000
            c.setRequestProperty("Accept", "application/json")
            if (c.responseCode != 200) return@runCatching null
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val info = Info(
                j.optLong("versionCode", 0), j.optString("versionName"), j.optString("notes"),
                j.optString("apkUrl"), j.optLong("sizeBytes", 0), j.optString("sha256").lowercase(), j.optBoolean("forced", false),
            )
            if (info.versionCode > installedCode(context) && info.forced && info.apkUrl.isNotBlank()) info else null
        }.getOrNull()
    }

    sealed class Download {
        data class Done(val file: File) : Download()
        data class Failed(val reason: String) : Download()
    }

    /**
     * Downloads the APK into the app's cache, reports 0..100, and checks its SHA-256 before it may be installed.
     * It NEVER fails: a dropped connection, a stall, or a server error only makes it wait (onWaiting(true))
     * and retry forever with backoff, resuming from the saved partial file (HTTP Range) whenever the
     * connection returns — even if the app was closed or the attempt was cancelled, the next run continues
     * from where it stopped, never from zero. A finished file with a bad checksum is deleted and downloaded
     * again silently. Same behaviour as the website's resumable upload: wait for the connection, never fail.
     */
    suspend fun download(
        context: Context, info: Info,
        onProgress: (Int) -> Unit, onWaiting: (Boolean) -> Unit = {},
    ): Download = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        val out = File(dir, "update-${info.versionCode}.apk")
        val total = if (info.sizeBytes > 0) info.sizeBytes else -1L
        var done = if (out.exists()) out.length() else 0L
        if (total > 0 && done >= total) {
            if (isComplete(out, info)) return@withContext Download.Done(out)
            out.delete(); done = 0L
        }
        if (done > 0 && total > 0) onProgress((done * 100 / total).toInt().coerceIn(0, 100))
        var attempt = 0
        var result: Download? = null
        while (result == null) {
            try {
                val c = URL(info.apkUrl).openConnection() as HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 60000
                c.instanceFollowRedirects = true
                if (done > 0) c.setRequestProperty("Range", "bytes=$done-")
                val code = c.responseCode
                if (code == 416) { // our partial file is longer than the server file: start clean
                    out.delete(); done = 0L; onProgress(0); continue
                }
                if (code != 200 && code != 206) throw java.io.IOException("http $code")
                if (code == 200 && done > 0) { // server ignored Range: restart cleanly
                    out.delete(); done = 0L; onProgress(0)
                }
                onWaiting(false)
                val totalNow = if (code == 206) {
                    val remaining = c.contentLengthLong.takeIf { it > 0 } ?: -1L
                    if (remaining > 0) remaining + done else total
                } else {
                    c.contentLengthLong.takeIf { it > 0 } ?: total
                }
                c.inputStream.use { ins ->
                    java.io.FileOutputStream(out, true).use { os ->
                        val buf = ByteArray(64 * 1024)
                        var last = -1
                        while (true) {
                            val n = ins.read(buf); if (n < 0) break
                            os.write(buf, 0, n); done += n
                            val p = if (totalNow > 0) (done * 100 / totalNow).toInt().coerceIn(0, 100) else 0
                            if (p != last) { last = p; onProgress(p) }
                        }
                    }
                }
            } catch (e: Exception) {
                // Connection dropped or stalled: wait, never cancel. The partial file stays,
                // and the next attempt resumes from `done`.
                onWaiting(true)
                kotlinx.coroutines.delay((2000L * (attempt + 1)).coerceAtMost(30000L))
                attempt++
                continue
            }
            // Stream ended: if the file is short, resume from `done`; if its checksum is bad,
            // delete it and download again silently. This function never reports a failure.
            if (total > 0 && out.length() != total) continue
            if (!isComplete(out, info)) { out.delete(); done = 0L; onProgress(0); continue }
            onWaiting(false)
            result = Download.Done(out)
        }
        result!!
    }

    /** A finished, verified APK already on disk for [info] (downloaded earlier, even in another run), or null. */
    fun findReadyApk(context: Context, info: Info): File? {
        val out = File(File(context.cacheDir, "update"), "update-${info.versionCode}.apk")
        return if (out.exists() && isComplete(out, info)) out else null
    }

    /** True when the file has the expected size (when known) and SHA-256 (when known). Never throws. */
    private fun isComplete(out: File, info: Info): Boolean {
        if (info.sizeBytes > 0 && out.length() != info.sizeBytes) return false
        if (info.sha256.isBlank()) return true
        return runCatching {
            val md = MessageDigest.getInstance("SHA-256")
            out.inputStream().use { ins ->
                val buf = ByteArray(64 * 1024)
                while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            md.digest().joinToString("") { "%02x".format(it) } == info.sha256
        }.getOrDefault(false)
    }

    /** Opens the system installer for [apk]. Returns false when the phone first needs "install unknown apps" switched on for this app. */
    fun install(context: Context, apk: File): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }
}
