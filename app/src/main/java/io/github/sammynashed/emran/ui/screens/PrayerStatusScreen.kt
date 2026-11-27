package io.github.sammynashed.emran.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.sammynashed.emran.data.PrayerNames
import io.github.sammynashed.emran.data.TimeFormat
import io.github.sammynashed.emran.service.PrayerStatus
import io.github.sammynashed.emran.ui.design.GlassDivider
import io.github.sammynashed.emran.ui.design.GlassGroup
import io.github.sammynashed.emran.ui.design.GlassSectionLabel
import io.github.sammynashed.emran.ui.design.GlassSegmented
import io.github.sammynashed.emran.ui.design.LocalSky
import io.github.sammynashed.emran.ui.design.ScreenHeader
import io.github.sammynashed.emran.ui.design.SettingsRow
import io.github.sammynashed.emran.ui.design.Space
import io.github.sammynashed.emran.ui.localization.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * Settings > Prayer times in notifications. A switch, what it shows (the next prayer, or the whole day when expanded),
 * and its look, each look drawn as a small picture of the real notification so the choice is easy to make.
 */
@Composable
fun PrayerStatusScreen(lang: String, onBack: () -> Unit) {
    val ar = lang == "ar"
    val sky = LocalSky.current
    val context = LocalContext.current
    var on by remember { mutableStateOf(PrayerStatus.isOn(context)) }
    var content by remember { mutableStateOf(PrayerStatus.content(context)) }
    val snap by produceState<PrayerStatus.Snapshot?>(null) { value = withContext(Dispatchers.IO) { PrayerStatus.snapshot(context) } }

    // Android 13+ asks before any notification is shown.
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { on = true; PrayerStatus.setOn(context, true) }
    }
    fun turnOn() {
        val need = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (need) ask.launch(Manifest.permission.POST_NOTIFICATIONS) else { on = true; PrayerStatus.setOn(context, true) }
    }
    val blocked = !NotificationManagerCompat.from(context).areNotificationsEnabled()

    CompositionLocalProvider(LocalLayoutDirection provides if (ar) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(
                title = if (ar) "المواقيت في الإشعارات" else "Prayer times in notifications",
                subtitle = if (ar) "إشعار هادئ يبقى في شريط الإشعارات، كأنه ودجت حيّ"
                else "A quiet notification that stays in your notification panel, like a live widget",
                onBack = onBack,
                modifier = Modifier.padding(horizontal = Space.screen),
            )
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = Space.screen).padding(bottom = 120.dp),
            ) {
                StatusMock(content, ar, snap, Modifier.fillMaxWidth())
                Spacer(Modifier.height(Space.md))

                GlassGroup {
                    SettingsRow(
                        icon = Icons.Default.Notifications, minHeight = 50.dp,
                        title = if (ar) "إظهار في الإشعارات" else "Show in notifications",
                        value = if (on) (if (ar) "مفعّل" else "On") else (if (ar) "متوقف" else "Off"),
                        onClick = { if (on) { on = false; PrayerStatus.setOn(context, false) } else turnOn() },
                    )
                    GlassDivider()
                    SettingsRow(
                        icon = Icons.Default.Schedule, minHeight = 50.dp, title = if (ar) "يعرض الصلاة" else "Shows",
                        onClick = null,
                        trailing = {
                            GlassSegmented(
                                options = listOf("next" to (if (ar) "القادمة" else "Next"), "all" to (if (ar) "الكل" else "All"))
                                    .let { if (ar) it.reversed() else it },
                                selectedKey = if (content == PrayerStatus.Content.ALL) "all" else "next",
                                onSelect = { content = if (it == "all") PrayerStatus.Content.ALL else PrayerStatus.Content.NEXT; PrayerStatus.setContent(context, content) },
                                modifier = Modifier.width(170.dp),
                            )
                        },
                    )
                }
                if (blocked && on) Text(
                    if (ar) "الإشعارات متوقفة للأَحْمَدِيّ في إعدادات هاتفك، فلن يظهر حتى تسمح بها."
                    else "Notifications are turned off for الأَحْمَدِيّ in your phone's settings, so it will not show until you allow them.",
                    color = sky.inkSoft, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(6.dp),
                )
                Text(
                    if (content == PrayerStatus.Content.ALL)
                        (if (ar) "اسحب الإشعار لأسفل لترى مواقيت اليوم كاملة." else "Pull the notification down to see the whole day.")
                    else (if (ar) "يعرض الصلاة القادمة مع عدّ تنازلي حيّ." else "Shows the next prayer with a live countdown."),
                    color = sky.inkSoft, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/** A small picture of the notification, with the real next prayer and times when they are known. */
@Composable
private fun StatusMock(content: PrayerStatus.Content, ar: Boolean, snap: PrayerStatus.Snapshot?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val use24 = TimeFormat.is24(context)
    val names = PrayerStatus.NAMES
    val next = snap?.next ?: 2
    fun clock(i: Int): String = snap?.let {
        TimeFormat.full(Instant.ofEpochMilli(it.times[i]).atZone(it.zone).toLocalDateTime(), ar, use24)
    } ?: listOf("5:12", "6:41", "12:31", "3:44", "6:12", "7:38")[i]
    val left = snap?.let { ((it.times[it.next] - System.currentTimeMillis()) / 1000).coerceAtLeast(0) } ?: 8049L
    val timer = "%d:%02d:%02d".format(left / 3600, left / 60 % 60, left % 60)
    val ink = Color.White
    val soft = Color(0xFFB8B8C4)
    val accent = Color(0xFFFF9A3D)
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(Color(0xFF2A2C33))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(Color(0xFF7C7CF0)))
            Spacer(Modifier.width(7.dp))
            Text(if (ar) "الأَحْمَدِيّ" else "الأَحْمَدِيّ", color = soft, fontSize = 11.sp)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${PrayerNames.name(names[next], ar)} · ${clock(next)}", color = ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
            Text(timer, color = ink, fontSize = 18.sp, fontWeight = FontWeight.Bold, bypassNumerals = true)
        }
        if (content == PrayerStatus.Content.ALL) {
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth()) {
                names.indices.forEach { i ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(PrayerNames.name(names[i], ar), color = if (i == next) accent else soft, fontSize = 9.sp, maxLines = 1)
                        Text(
                            clock(i).replace(Regex("\\s+(AM|PM|ص|م)$"), ""),
                            color = if (i == next) accent else ink.copy(alpha = 0.85f),
                            fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, bypassNumerals = true,
                        )
                    }
                }
            }
        } else {
            Text(if (ar) "الصلاة القادمة" else "Next prayer", color = soft, fontSize = 12.sp, maxLines = 1)
        }
    }
}
