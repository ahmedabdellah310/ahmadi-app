package io.github.sammynashed.emran.data.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Downloads a forced update in a foreground service, so the download keeps going even if the user
 * leaves the app, switches to another app, or the screen turns off. The download itself is
 * ForcedUpdate.download: it never fails and resumes from the saved partial file whenever the
 * connection returns. The update screen observes [progress], [waiting] and [doneFile] to show the
 * same state when the user comes back. When the download finishes, a notification offers to open
 * the app and install; the service itself never pops the installer over another app.
 * If the system ever kills the service, START_REDELIVER_INTENT restarts it with the same release
 * and the download resumes from the partial file.
 */
class UpdateDownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var manager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID, "تنزيل التحديث", NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "تنزيل تحديث التطبيق في الخلفية" }
        manager.createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val info = intent?.let { readInfo(it) } ?: return START_NOT_STICKY
        if (runningCode == info.versionCode) return START_STICKY
        runningCode = info.versionCode
        _progress.value = 0
        _waiting.value = false
        _doneFile.value = null
        startForeground(NOTIF_ID, buildNotification(0, false))
        scope.launch {
            when (val r = ForcedUpdate.download(
                this@UpdateDownloadService, info,
                onProgress = { p -> _progress.value = p; updateNotification(p, _waiting.value) },
                onWaiting = { w -> _waiting.value = w; updateNotification(_progress.value, w) },
            )) {
                is ForcedUpdate.Download.Done -> {
                    _progress.value = 100
                    _waiting.value = false
                    _doneFile.value = r.file
                    runningCode = -1L
                    manager.notify(NOTIF_ID, buildDoneNotification())
                    stopForeground(false)
                    stopSelf()
                }
                is ForcedUpdate.Download.Failed -> {
                    // download() never fails; this is only for exhaustiveness.
                    runningCode = -1L
                    stopSelf()
                }
            }
        }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun readInfo(intent: Intent): ForcedUpdate.Info? {
        val apkUrl = intent.getStringExtra(EXTRA_APK_URL).orEmpty()
        if (apkUrl.isBlank()) return null
        return ForcedUpdate.Info(
            intent.getLongExtra(EXTRA_VERSION_CODE, 0),
            intent.getStringExtra(EXTRA_VERSION_NAME).orEmpty(),
            intent.getStringExtra(EXTRA_NOTES).orEmpty(),
            apkUrl,
            intent.getLongExtra(EXTRA_SIZE_BYTES, 0),
            intent.getStringExtra(EXTRA_SHA256).orEmpty(),
            intent.getBooleanExtra(EXTRA_FORCED, false),
        )
    }

    private fun openAppIntent(): PendingIntent? =
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

    private fun buildNotification(progress: Int, waiting: Boolean): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("الأحمدي")
            .setContentText(if (waiting) "بانتظار الاتصال…" else "جارٍ تنزيل التحديث… $progress%")
            .setProgress(100, progress.coerceIn(0, 100), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .build()

    private fun updateNotification(progress: Int, waiting: Boolean) {
        manager.notify(NOTIF_ID, buildNotification(progress, waiting))
    }

    private fun buildDoneNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("الأحمدي")
            .setContentText("اكتمل تنزيل التحديث — اضغط للتثبيت")
            .setOngoing(false)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .build()

    companion object {
        const val CHANNEL_ID = "update_download"
        private const val NOTIF_ID = 1001
        const val ACTION_DOWNLOAD = "io.github.sammynashed.emran.action.DOWNLOAD_UPDATE"
        private const val EXTRA_VERSION_CODE = "versionCode"
        private const val EXTRA_VERSION_NAME = "versionName"
        private const val EXTRA_NOTES = "notes"
        private const val EXTRA_APK_URL = "apkUrl"
        private const val EXTRA_SIZE_BYTES = "sizeBytes"
        private const val EXTRA_SHA256 = "sha256"
        private const val EXTRA_FORCED = "forced"

        /** versionCode currently downloading, or -1 when idle. */
        @Volatile
        var runningCode: Long = -1L

        private val _progress = MutableStateFlow(-1)
        /** 0..100 while downloading, 100 when done, -1 when idle. */
        val progress: StateFlow<Int> = _progress.asStateFlow()
        private val _waiting = MutableStateFlow(false)
        /** True while the connection is down and the download is waiting (never cancelled). */
        val waiting: StateFlow<Boolean> = _waiting.asStateFlow()
        private val _doneFile = MutableStateFlow<File?>(null)
        /** The finished, verified APK once the download completes, or null. */
        val doneFile: StateFlow<File?> = _doneFile.asStateFlow()

        /** Starts the background download for [info]; a no-op when it is already running. */
        fun start(context: Context, info: ForcedUpdate.Info) {
            if (runningCode == info.versionCode) return
            val intent = Intent(context, UpdateDownloadService::class.java).apply {
                action = ACTION_DOWNLOAD
                putExtra(EXTRA_VERSION_CODE, info.versionCode)
                putExtra(EXTRA_VERSION_NAME, info.versionName)
                putExtra(EXTRA_NOTES, info.notes)
                putExtra(EXTRA_APK_URL, info.apkUrl)
                putExtra(EXTRA_SIZE_BYTES, info.sizeBytes)
                putExtra(EXTRA_SHA256, info.sha256)
                putExtra(EXTRA_FORCED, info.forced)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
