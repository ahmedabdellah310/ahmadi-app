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

    /** Downloads the APK into the app's cache, reports 0..100, and checks its SHA-256 before it may be installed. */
    suspend fun download(context: Context, info: Info, onProgress: (Int) -> Unit): Download = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "update-${info.versionCode}.apk")
        try {
            val c = URL(info.apkUrl).openConnection() as HttpURLConnection
            c.connectTimeout = 10000; c.readTimeout = 20000
            c.instanceFollowRedirects = true
            if (c.responseCode != 200) return@withContext Download.Failed("http ${c.responseCode}")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: info.sizeBytes
            val md = MessageDigest.getInstance("SHA-256")
            var done = 0L; var last = -1
            c.inputStream.use { ins ->
                out.outputStream().use { os ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = ins.read(buf); if (n < 0) break
                        os.write(buf, 0, n); md.update(buf, 0, n); done += n
                        val p = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
                        if (p != last) { last = p; onProgress(p) }
                    }
                }
            }
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            if (info.sha256.isNotBlank() && hex != info.sha256) { out.delete(); return@withContext Download.Failed("sha256") }
            Download.Done(out)
        } catch (e: Exception) {
            out.delete(); Download.Failed(e.message ?: "error")
        }
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
