package io.github.sammynashed.emran.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Storage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sammynashed.emran.data.audio.AudioStore
import io.github.sammynashed.emran.data.audio.Reciters
import io.github.sammynashed.emran.data.packs.PackStatus
import io.github.sammynashed.emran.data.packs.Packs
import io.github.sammynashed.emran.ui.design.GlassDialog
import io.github.sammynashed.emran.ui.design.GlassDivider
import io.github.sammynashed.emran.ui.design.GlassGroup
import io.github.sammynashed.emran.ui.design.GlassSectionLabel
import io.github.sammynashed.emran.ui.design.LocalSky
import io.github.sammynashed.emran.ui.design.ScreenHeader
import io.github.sammynashed.emran.ui.design.SettingsRow
import io.github.sammynashed.emran.ui.design.Space
import io.github.sammynashed.emran.ui.localization.Text
import java.io.File

/** One thing that can be on the phone and removed: an optional pack or a reciter's downloaded recitations. */
private class StoredItem(val key: String, val title: String, val sub: String?, val bytes: Long, val at: Long, val remove: () -> Unit, val pack: String? = null)

/**
 * Settings > Storage. The size of the app itself, everything optional that was downloaded (newest first, each with a
 * Delete), and what is still available to download. Nothing here is ever removed except on the user's tap.
 */
@Composable
fun StorageScreen(lang: String, onBack: () -> Unit) {
    val ar = lang == "ar"
    val sky = LocalSky.current
    val context = LocalContext.current
    val status by Packs.status.collectAsState()
    val packChanges by Packs.changes.collectAsState()
    val audioChanges by AudioStore.changes.collectAsState()
    var confirm by remember { mutableStateOf<StoredItem?>(null) }

    val apkBytes = remember {
        val ai = context.applicationInfo
        (listOf(ai.sourceDir) + (ai.splitSourceDirs?.toList().orEmpty())).sumOf { File(it).length() }
    }

    // Rebuilt whenever a pack or a recitation set changes, or a download moves to a new state.
    val installed = remember(packChanges, audioChanges, status.values.count { it is PackStatus.Downloading }) {
        val packs = Packs.DEFS.filter { Packs.isInstalled(context, it.id) }.map { d ->
            StoredItem(d.id, d.name(ar), d.desc(ar), Packs.sizeOnDisk(context, d.id), Packs.installedAt(context, d.id), { Packs.delete(context, d.id) }, d.id)
        }
        val recitations = Reciters.ALL.mapNotNull { r ->
            val bytes = AudioStore.bytesUsed(context, r.id)
            if (bytes <= 0) null else StoredItem(
                "audio-${r.id}", if (ar) "تلاوات ${r.ar}" else "${r.en}'s recitations",
                if (ar) "سور محمّلة للاستماع دون إنترنت" else "Surahs downloaded to listen offline",
                bytes, File(context.filesDir, "audio/${r.id}").lastModified(), { AudioStore.remove(context, r.id) },
            )
        }
        (packs + recitations).sortedByDescending { it.at }
    }
    val available = remember(packChanges, status.values.count { it is PackStatus.Downloading }) {
        Packs.DEFS.filter { !Packs.isInstalled(context, it.id) }
    }
    val dataBytes = installed.sumOf { it.bytes }

    CompositionLocalProvider(LocalLayoutDirection provides if (ar) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(
                title = if (ar) "التخزين والتنزيلات" else "Storage & downloads",
                subtitle = if (ar) "حجم التطبيق وما نزّلته، ويمكنك حذف أي شيء منه"
                else "The app's size and what you downloaded; delete anything you don't need",
                onBack = onBack,
                modifier = Modifier.padding(horizontal = Space.screen),
            )
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = Space.screen).padding(bottom = 120.dp),
            ) {
                GlassGroup {
                    SettingsRow(Icons.Default.Storage, if (ar) "حجم التطبيق" else "App size", null, value = packSize(apkBytes, ar), chevron = false, minHeight = 50.dp)
                    GlassDivider()
                    SettingsRow(Icons.Default.Download, if (ar) "ما نزّلته" else "Downloaded data", null, value = packSize(dataBytes, ar), chevron = false, minHeight = 50.dp)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (ar) "التطبيق نفسه خفيف. ما تنزّله يُحفظ هنا ولا يحذفه أي تحديث؛ لا يُحذف إلا إذا حذفتَه أنت."
                    else "The app itself stays light. What you download is kept here and no update removes it; only you can delete it.",
                    color = sky.inkSoft, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                )

                GlassSectionLabel(if (ar) "نزّلتَه" else "DOWNLOADED")
                if (installed.isEmpty()) {
                    Text(if (ar) "لا شيء بعد." else "Nothing yet.", color = sky.inkSoft, fontSize = 14.sp, modifier = Modifier.padding(6.dp))
                } else GlassGroup {
                    installed.forEachIndexed { i, it ->
                        if (i > 0) GlassDivider()
                        SettingsRow(
                            icon = Icons.Default.Delete, minHeight = 56.dp,
                            title = it.title, value = packSize(it.bytes, ar),
                            onClick = { confirm = it }, chevron = false,
                        )
                    }
                }

                if (available.isNotEmpty()) {
                    GlassSectionLabel(if (ar) "متاح للتنزيل" else "AVAILABLE TO DOWNLOAD")
                    GlassGroup {
                        available.forEachIndexed { i, d ->
                            if (i > 0) GlassDivider()
                            val s = status[d.id] ?: PackStatus.Idle
                            SettingsRow(
                                icon = Icons.Default.Download, minHeight = 56.dp, title = d.name(ar), chevron = false,
                                value = when (s) {
                                    is PackStatus.Downloading -> "${packSize(s.done, ar)} / ${packSize(s.total, ar)}"
                                    is PackStatus.Failed -> if (ar) "تعذّر، اضغط للمحاولة" else "Failed, tap to retry"
                                    PackStatus.Idle -> packSize(Packs.totalBytes(context, d.id), ar)
                                },
                                onClick = { if (s is PackStatus.Downloading) Packs.cancel(d.id) else Packs.start(context, d.id) },
                            )
                        }
                    }
                    Text(
                        if (ar) "اضغط لتنزيل أي عنصر. اضغط عليه أثناء التنزيل لإيقافه."
                        else "Tap an item to download it. Tap it while it downloads to stop.",
                        color = sky.inkSoft, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp).fillMaxWidth(),
                    )
                }
            }
        }
    }

    confirm?.let { item ->
        GlassDialog(
            onDismiss = { confirm = null },
            title = if (ar) "حذف ${item.title}؟" else "Delete ${item.title}?",
            confirmLabel = if (ar) "حذف (${packSize(item.bytes, ar)})" else "Delete (${packSize(item.bytes, ar)})",
            onConfirm = { item.remove(); confirm = null },
            dismissLabel = if (ar) "إلغاء" else "Cancel",
        ) {
            Text(
                if (ar) "ستُحرَّر المساحة، ويمكنك تنزيله مرة أخرى متى شئت." else "This frees the space. You can download it again any time.",
                color = sky.inkSoft, fontSize = 14.sp, lineHeight = 20.sp,
            )
        }
    }
}
