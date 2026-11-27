package io.github.sammynashed.emran.ui.screens

import android.media.MediaPlayer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sammynashed.emran.data.audio.AudioDownloadWorker
import io.github.sammynashed.emran.data.audio.AudioStore
import io.github.sammynashed.emran.data.audio.QuranAudio
import io.github.sammynashed.emran.data.audio.Reciter
import io.github.sammynashed.emran.data.audio.Reciters
import io.github.sammynashed.emran.ui.design.GlassButton
import io.github.sammynashed.emran.ui.design.GlassDialog
import io.github.sammynashed.emran.ui.design.GlassIconButton
import io.github.sammynashed.emran.ui.design.GlassPanel
import io.github.sammynashed.emran.ui.design.LocalSky
import io.github.sammynashed.emran.ui.design.Radius
import io.github.sammynashed.emran.ui.design.ScreenHeader
import io.github.sammynashed.emran.ui.design.Space
import io.github.sammynashed.emran.ui.localization.Text
import kotlinx.coroutines.launch

private val RIWAYA_FILTERS = listOf("الكل", "حفص", "ورش", "قالون", "الدوري", "شعبة", "السوسي", "البزي", "خلف", "ابن ذكوان", "قنبل", "يعقوب", "ابن جماز", "هشام")

/** Plain letters, so a search ignores marks and the different shapes of alef and ya. */
private fun plain(s: String) = s.filter { it.code !in 0x064B..0x065F && it.code != 0x0640 }
    .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ى', 'ي').replace('ة', 'ه')

/**
 * Every recitation set on mp3quran.net (their API: the count shown is the real count of the list in use).
 * A set is a reciter in one riwayah or style. Tapping a set makes it your reciter; its surahs download in the
 * app, with progress, resume and delete, and are played from the phone afterwards.
 */
@Composable
fun RecitersCatalogScreen(lang: String, onBack: () -> Unit) {
    val ar = lang == "ar"
    val sky = LocalSky.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Reciters.ensureLoaded(context)
    var version by mutableIntStateOf(0)
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("الكل") }
    var chosen by remember { mutableStateOf(Reciters.chosen(context).id) }
    var playing by remember { mutableStateOf<String?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var refreshState by remember { mutableStateOf("") } // "" | loading | failed | ok
    var confirmWhole by remember { mutableStateOf<Reciter?>(null) }
    var confirmDelete by remember { mutableStateOf<Reciter?>(null) }
    val changes by AudioStore.changes.collectAsState()

    fun stop() { runCatching { player?.stop() }; runCatching { player?.release() }; player = null; playing = null }
    fun preview(r: Reciter) {
        if (playing == r.id) { stop(); return }
        stop()
        val s = listOf(112, 1, 113, 114).firstOrNull { r.has(it) } ?: (1..114).first { r.has(it) }
        runCatching {
            val mp = MediaPlayer()
            mp.setDataSource(r.surahFileUrl(s))
            mp.setOnPreparedListener { it.start() }
            mp.setOnCompletionListener { if (player === mp) stop() }
            mp.setOnErrorListener { _, _, _ -> if (player === mp) stop(); true }
            mp.prepareAsync()
            player = mp; playing = r.id
        }
    }
    DisposableEffect(Unit) { onDispose { runCatching { player?.release() } } }

    val all = remember(version) { Reciters.remoteSets }
    val shown = remember(version, query, filter) {
        val q = plain(query.trim())
        all.filter { r ->
            (filter == "الكل" || plain(r.ar.substringAfter(" · ")).contains(plain(filter))) && (q.isEmpty() || plain(r.ar).contains(q) || r.en.contains(query.trim(), ignoreCase = true))
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides if (ar) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        Column(Modifier.fillMaxSize().padding(horizontal = Space.screen)) {
            ScreenHeader(
                title = if (ar) "كل القرّاء" else "All reciters",
                subtitle = if (ar) "${Reciters.remoteReciterCount} قارئًا · ${all.size} تلاوة" else "${Reciters.remoteReciterCount} reciters · ${all.size} recitations",
                onBack = onBack,
            )
            TextField(
                value = query, onValueChange = { query = it.take(40) },
                label = { Text(if (ar) "ابحث باسم القارئ" else "Search by reciter", color = sky.inkSoft, fontSize = 13.sp) },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = sky.ink.copy(alpha = 0.20f), unfocusedContainerColor = sky.ink.copy(alpha = 0.16f),
                    focusedTextColor = sky.ink, unfocusedTextColor = sky.ink, cursorColor = sky.ink,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                ),
                shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RIWAYA_FILTERS.forEach { f -> GlassButton(f, { filter = f }, filter == f, hPad = 14.dp) }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${shown.size} " + (if (ar) "تلاوة" else "recitations") + when (refreshState) {
                        "loading" -> if (ar) " · جارٍ تحديث القائمة…" else " · updating list…"
                        "failed" -> if (ar) " · تعذّر التحديث (لا إنترنت؟)" else " · could not update"
                        "ok" -> if (ar) " · تم تحديث القائمة" else " · list updated"
                        else -> ""
                    },
                    color = sky.inkSoft, fontSize = 12.sp, modifier = Modifier.weight(1f), bypassNumerals = true,
                )
                GlassButton(if (ar) "حدّث القائمة" else "Update list", {
                    if (refreshState != "loading") { refreshState = "loading"; scope.launch { refreshState = if (Reciters.refreshRemote(context) != null) "ok" else "failed"; version++ } }
                }, false, hPad = 12.dp)
            }
            LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 124.dp), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                items(shown, key = { it.id }) { r ->
                    val isChosen = chosen == r.id
                    val here = remember(changes, r.id) { AudioStore.installedSurahs(context, r.id).size }
                    val progress by remember(r.id) { AudioDownloadWorker.progress(context, r) }.collectAsState(initial = null)
                    GlassPanel(
                        Modifier.fillMaxWidth().clickable { Reciters.choose(context, r); chosen = r.id; AudioStore.notifyChanged() },
                        cornerRadius = Radius.row, lens = false,
                    ) {
                        Column {
                            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                GlassIconButton(
                                    icon = if (playing == r.id) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = if (ar) "استماع" else "Preview", onClick = { preview(r) },
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(r.ar.substringBefore(" · "), color = sky.ink, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                                    Text(
                                        r.ar.substringAfter(" · ") + if (r.surahCount < 114) (if (ar) " · ${r.surahCount} سورة" else " · ${r.surahCount} surahs") else "",
                                        color = sky.inkSoft, fontSize = 12.5.sp, bypassNumerals = true,
                                    )
                                }
                                if (isChosen) Icon(Icons.Filled.Check, contentDescription = null, tint = sky.ink)
                            }
                            if (isChosen || here > 0 || progress != null) {
                                ReciterStorage(r, ar, here, progress, onWhole = { confirmWhole = r }, onDelete = { confirmDelete = r })
                            }
                        }
                    }
                }
            }
        }
    }

    confirmWhole?.let { r ->
        GlassDialog(
            onDismiss = { confirmWhole = null },
            title = if (ar) "تنزيل ${r.surahCount} سورة" else "Download ${r.surahCount} surahs",
            confirmLabel = if (ar) "نزّل" else "Download",
            onConfirm = { AudioDownloadWorker.enqueue(context, r, (1..114).filter { r.has(it) }); confirmWhole = null },
            dismissLabel = if (ar) "إلغاء" else "Cancel",
        ) {
            Text(
                if (ar) "الحجم التقريبي ${sizeLabel(AudioStore.wholeQuranBytes(r) * r.surahCount / 114, true)} (تقدير، يختلف بجودة الملفات). يُفضَّل واي فاي. يكمل وحده والتطبيق مغلق ويستأنف لو انقطع النت."
                else "Roughly ${sizeLabel(AudioStore.wholeQuranBytes(r) * r.surahCount / 114, false)} (an estimate; it varies with file quality). Wi-Fi is best. It continues with the app closed and resumes if the network drops.",
                color = sky.ink, fontSize = 15.sp, lineHeight = 22.sp,
            )
        }
    }
    confirmDelete?.let { r ->
        GlassDialog(
            onDismiss = { confirmDelete = null },
            title = if (ar) "حذف التلاوة من الهاتف؟" else "Delete from this phone?",
            confirmLabel = if (ar) "احذف" else "Delete",
            onConfirm = {
                AudioDownloadWorker.cancel(context, r)
                if (QuranAudio.state.value?.reciter == r.id) QuranAudio.stop()
                AudioStore.remove(context, r.id); confirmDelete = null
            },
            dismissLabel = if (ar) "إلغاء" else "Cancel",
        ) {
            Text(
                (if (ar) "يُحذف ما نزّلته من هذه التلاوة (" else "Removes what you downloaded of this recitation (") + sizeLabel(AudioStore.bytesUsed(context, r.id), ar) + ")",
                color = sky.ink, fontSize = 15.sp, lineHeight = 22.sp,
            )
        }
    }
}
