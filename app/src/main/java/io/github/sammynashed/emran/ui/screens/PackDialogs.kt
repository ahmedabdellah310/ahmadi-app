package io.github.sammynashed.emran.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sammynashed.emran.data.packs.PackStatus
import io.github.sammynashed.emran.data.packs.Packs
import io.github.sammynashed.emran.ui.design.GlassButton
import io.github.sammynashed.emran.ui.design.GlassDialog
import io.github.sammynashed.emran.ui.design.LocalSky
import io.github.sammynashed.emran.ui.localization.Text

/** "48 MB" / "٤٨ ميغابايت", the way the app says sizes everywhere. */
internal fun packSize(bytes: Long, ar: Boolean): String {
    val mb = bytes / 1_000_000.0
    val n = if (mb >= 10) "%.0f".format(mb) else "%.1f".format(mb).let { if (it.endsWith(".0")) it.dropLast(2) else it }
    return if (ar) "${n.map { if (it in '0'..'9') ('٠' + (it - '0')) else if (it == '.') '٫' else it }.joinToString("")} ميغابايت" else "$n MB"
}

/**
 * The one dialog every optional download goes through. It asks once ("download X, N MB?"), then turns into the
 * download itself (bar, MB so far); when the pack is there it calls [onReady] and the feature carries on. The user
 * cannot get past it without the pack: [onDismiss] only backs out of the feature.
 */
@Composable
fun PackDownloadDialog(packId: String, ar: Boolean, why: String, onReady: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val def = remember(packId) { Packs.def(packId)!! }
    val status by Packs.status.collectAsState()
    val changes by Packs.changes.collectAsState()
    val installed = remember(changes, status[packId]) { Packs.isInstalled(context, packId) }
    val total = Packs.totalBytes(context, packId)

    LaunchedEffect(installed) { if (installed) onReady() }
    if (installed) return

    val sky = LocalSky.current
    when (val s = status[packId] ?: PackStatus.Idle) {
        is PackStatus.Downloading -> GlassDialog(
            onDismiss = { Packs.cancel(packId); onDismiss() },
            title = if (ar) "جارٍ تنزيل ${def.ar}" else "Downloading ${def.en}",
            dismissLabel = if (ar) "إلغاء" else "Cancel",
        ) {
            val f = if (s.total > 0) (s.done.toFloat() / s.total).coerceIn(0f, 1f) else 0f
            LinearProgressIndicator(
                progress = { f }, modifier = Modifier.fillMaxWidth().height(8.dp),
                color = sky.ink, trackColor = sky.ink.copy(alpha = 0.15f),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "${packSize(s.done, ar)} / ${packSize(s.total, ar)}", color = sky.inkSoft, fontSize = 14.sp,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
            Text(
                if (ar) "يعمل بعدها دون إنترنت. يمكنك ترك التطبيق، سيكمل التنزيل." else "It works offline afterwards. You can leave the app; the download continues.",
                color = sky.inkSoft, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
        is PackStatus.Failed -> GlassDialog(
            onDismiss = onDismiss,
            title = if (ar) "تعذّر التنزيل" else "Couldn't download",
            confirmLabel = if (ar) "حاول مجددًا" else "Try again",
            onConfirm = { Packs.start(context, packId) },
            dismissLabel = if (ar) "ليس الآن" else "Not now",
        ) {
            // The old text always blamed the internet, even when the real cause was a file
            // missing inside the app itself (that is what broke the Hifz install). Say why.
            val reason = s.reason
            val whyText = if (ar) when {
                reason.contains("missing in app assets") || reason.contains("packs/") ->
                    "ملف من الحزمة غير موجود داخل التطبيق نفسه. حدِّث التطبيق لآخر نسخة من موقع التحديثات ثم حاول مجددًا."
                reason.contains("did not match") ->
                    "الملف الذي وصل لم يطابق المتوقَّع. حاول مجددًا."
                reason.contains("size ") ->
                    "الملف الذي وصل غير مكتمل. حاول مجددًا."
                else -> "تأكد من الاتصال بالإنترنت وحاول مرة أخرى. ما نزل منه محفوظ ولن يُعاد."
            } else when {
                reason.contains("missing in app assets") || reason.contains("packs/") ->
                    "A file of this pack is missing inside the app itself. Update the app to the latest version from the updates site, then try again."
                reason.contains("did not match") ->
                    "The file that arrived did not match what was expected. Try again."
                reason.contains("size ") ->
                    "The file that arrived is incomplete. Try again."
                else -> "Check your connection and try again. What already arrived is kept."
            }
            Text(
                whyText,
                color = sky.inkSoft, fontSize = 14.sp, lineHeight = 20.sp,
            )
            Text(
                reason,
                color = sky.inkSoft.copy(alpha = 0.6f), fontSize = 12.sp, lineHeight = 16.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        PackStatus.Idle -> GlassDialog(
            onDismiss = onDismiss,
            title = def.name(ar),
            confirmLabel = if (ar) "تنزيل (${packSize(total, ar)})" else "Download (${packSize(total, ar)})",
            onConfirm = { Packs.start(context, packId) },
            dismissLabel = if (ar) "ليس الآن" else "Not now",
        ) {
            Text(why, color = sky.ink, fontSize = 15.sp, lineHeight = 22.sp)
            Text(
                if (ar) "تنزيل لمرة واحدة، ثم يعمل دون إنترنت. يبقى على هاتفك ولا يحذفه التحديث، ويمكنك حذفه من الإعدادات." else
                    "A one-time download, then it works offline. It stays on your phone and updates never remove it; you can delete it in Settings.",
                color = sky.inkSoft, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
