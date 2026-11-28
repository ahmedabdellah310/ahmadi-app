package io.github.sammynashed.emran.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sammynashed.emran.data.update.ForcedUpdate
import io.github.sammynashed.emran.data.update.UpdateDownloadService
import io.github.sammynashed.emran.ui.design.LocalSky
import io.github.sammynashed.emran.ui.design.SkyHost
import io.github.sammynashed.emran.ui.viewmodel.PrayerViewModel

/** Debug: show the page with sample data without a server: adb ... --ez FORCE_UPDATE_DEMO true */
object UpdateDemo { var on by mutableStateOf(false) }

/** Sits over the whole app. Checks the update site on every open and every return to the app; blocks the app only for a newer, forced release. */
@Composable
fun ForcedUpdateGate(viewModel: PrayerViewModel, ar: Boolean) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var info by remember { mutableStateOf<ForcedUpdate.Info?>(null) }
    // Re-check on every resume, not just on a fresh launch: coming back to the app
    // from the background must also catch a newly published forced update.
    // A failed re-check never clears a known forced update (only a found one sets it).
    var resumeTick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(resumeTick) { ForcedUpdate.check(context)?.let { info = it } }
    val shown = if (UpdateDemo.on) ForcedUpdate.Info(
        99, "2.4.0", "• خط كايرو وألوان أوضح\n• أيقونة التطبيق في فيديوهات القرآن\n• تحسينات في الأذان", "", 71_529_855, "", true,
    ) else info
    if (shown != null) {
        SkyHost(viewModel = viewModel) { ForcedUpdateScreen(shown, ar, demo = UpdateDemo.on) }
    }
}

@Composable
private fun ForcedUpdateScreen(info: ForcedUpdate.Info, ar: Boolean, demo: Boolean) {
    val context = LocalContext.current
    val sky = LocalSky.current
    var percent by remember { mutableIntStateOf(-1) }   // -1 = not started
    var waiting by remember { mutableStateOf(false) }   // connection dropped: waiting, never cancelled
    var message by remember { mutableStateOf<String?>(null) }
    var file by remember { mutableStateOf<java.io.File?>(null) }
    // The download runs in a foreground service, so it keeps going even if the user leaves the app.
    // These flows carry its live state back into this screen when the user returns.
    val svcProgress by UpdateDownloadService.progress.collectAsState()
    val svcWaiting by UpdateDownloadService.waiting.collectAsState()
    val svcDone by UpdateDownloadService.doneFile.collectAsState()
    LaunchedEffect(svcProgress) { if (svcProgress >= 0) percent = svcProgress }
    LaunchedEffect(svcWaiting) { waiting = svcWaiting }
    LaunchedEffect(svcDone) { svcDone?.let { file = it; percent = 100; waiting = false } }
    // A finished APK already on disk (downloaded earlier, even in another run): offer install at once.
    LaunchedEffect(info) {
        ForcedUpdate.findReadyApk(context, info)?.let { file = it; percent = 100 }
    }
    BackHandler(enabled = true) { }
    val icon = remember {
        runCatching {
            val pm = context.packageManager
            val e = pm.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER).setPackage(context.packageName), 0).firstOrNull()
            (e?.loadIcon(pm) ?: pm.getApplicationIcon(context.packageName)).toBitmap(256, 256)
        }.getOrNull()
    }
    val mb = "%.0f".format(info.sizeBytes / 1_048_576.0)
    val busy = percent in 0..99

    Box(Modifier.fillMaxSize().pointerInput(Unit) { }) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            icon?.let { Image(it.asImageBitmap(), null, Modifier.size(104.dp).clip(RoundedCornerShape(24.dp))) }
            Spacer(Modifier.height(20.dp))
            Text(if (ar) "تحديث جديد متاح" else "A new update is available", color = sky.ink, fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(
                (if (ar) "الإصدار " else "Version ") + info.versionName + (if (info.sizeBytes > 0) " · $mb " + (if (ar) "ميغابايت" else "MB") else ""),
                color = sky.inkSoft, fontSize = 15.sp,
            )
            Spacer(Modifier.height(22.dp))
            if (percent >= 0) {
                LinearProgressIndicator(
                    progress = { percent / 100f }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)),
                    color = Color(0xFFE9CB86), trackColor = Color.White.copy(alpha = 0.18f),
                )
                Spacer(Modifier.height(6.dp))
                Text(if (waiting) (if (ar) "بانتظار الاتصال…" else "Waiting for connection…") else if (percent < 100) "$percent%" else (if (ar) "جارٍ التحقق…" else "Verifying…"), color = sky.inkSoft, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
            }
            message?.let { Text(it, color = sky.ink, fontSize = 14.sp, textAlign = TextAlign.Center); Spacer(Modifier.height(6.dp)) }
            Box(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(50))
                    .background(if (busy) Color.White.copy(alpha = 0.5f) else Color.White)
                    .clickable(enabled = !busy) {
                        message = null
                        waiting = false
                        val ready = file
                        if (ready != null) { if (!ForcedUpdate.install(context, ready)) message = if (ar) "اسمح للتطبيق بتثبيت التحديثات ثم اضغط «تحديث الآن» مرة أخرى." else "Allow installs for this app, then tap Update now again." ; return@clickable }
                        if (demo) { message = if (ar) "هذه معاينة: لا يوجد خادم بعد." else "Preview only: no server yet."; return@clickable }
                        percent = 0
                        // The download runs in a foreground service: it keeps going even if the
                        // user leaves the app, and this screen picks up its progress on return.
                        UpdateDownloadService.start(context, info)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (waiting) (if (ar) "بانتظار الاتصال…" else "Waiting for connection…") else if (busy) (if (ar) "جارٍ التنزيل…" else "Downloading…") else if (file != null) (if (ar) "تثبيت الآن" else "Install now") else (if (ar) "تحديث الآن" else "Update now"),
                    color = Color(0xFF0B1B33), fontSize = 17.sp, fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(50))
                    .clickable {
                        if (demo) return@clickable
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(info.apkUrl))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(if (ar) "تنزيل من المتصفّح" else "Download in browser", color = Color(0xFFE9CB86), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(50))
                    .clickable {
                        if (demo) return@clickable
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(ForcedUpdate.BASE_URL))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(if (ar) "موقع التحديثات" else "Updates site", color = Color(0xFFE9CB86), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(14.dp))
            Text(if (ar) "لازم تحدّث التطبيق عشان تكمل" else "You need to update the app to continue", color = sky.inkSoft, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
    }
}
