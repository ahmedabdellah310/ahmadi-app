package io.github.sammynashed.emran.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sammynashed.emran.data.audio.Reciters

/**
 * What a tap on the muṣḥaf page does.
 *
 * WHOLE: the tap selects the whole āyah — its tafsir opens, and (when
 * [readsAyah]) the āyah is read aloud. WORD: the tap selects one word only —
 * its meaning opens, and the āyah is still read whole when [readsAyah] is on,
 * because the app has no per-word audio timings: every recitation is one MP3
 * per āyah, so a single word cannot be played on its own.
 */
object MushafTapPrefs {
    enum class TapMode { WHOLE, WORD }

    private const val PREFS = "mushaf_tap_prefs"
    private const val KEY_MODE = "tap_mode"
    private const val KEY_READ = "tap_reads_ayah"

    fun mode(context: Context): TapMode {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODE, null)
        return if (raw == "word") TapMode.WORD else TapMode.WHOLE
    }

    fun saveMode(context: Context, mode: TapMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MODE, if (mode == TapMode.WORD) "word" else "whole").apply()
    }

    /** Whether a tap reads the āyah aloud with the chosen reciter. Default on. */
    fun readsAyah(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_READ, true)

    fun saveReadsAyah(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_READ, value).apply()
    }
}

/**
 * The "عند الضغط على الآية" section of the reader's Display panel: whole
 * āyah vs word by word, the reciter row (which opens the existing reciter
 * picker), and the "tap reads the āyah" switch. Tafsir follows the same
 * choice: whole-āyah mode opens the āyah's tafsir, word mode opens the
 * tapped word — whose meaning the app does not bundle, so that sheet says so
 * plainly instead of inventing one.
 */
@Composable
fun MushafTapSettingsSection(
    theme: QuranThemeMode,
    lang: String,
    onOpenReciters: () -> Unit,
    tapMode: MushafTapPrefs.TapMode,
    onTapModeChange: (MushafTapPrefs.TapMode) -> Unit,
    tapReadsAyah: Boolean,
    onTapReadsChange: (Boolean) -> Unit,
) {
    val ar = lang == "ar"
    val context = LocalContext.current
    val ink = theme.textColor
    val card = ink.copy(alpha = 0.05f)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = if (ar) "عند الضغط على الآية" else "When tapping an ayah",
            color = theme.ornament,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(card)
                .border(1.dp, ink.copy(alpha = 0.10f), RoundedCornerShape(16.dp)),
        ) {
            MushafTapModeRow(
                title = if (ar) "الآية كاملة" else "Whole ayah",
                subtitle = if (ar) "الضغط يحدّد الآية كلها ويقرأها كاملة بصوت القارئ"
                else "A tap selects the whole ayah and reads it with the reciter",
                selected = tapMode == MushafTapPrefs.TapMode.WHOLE,
                ink = ink,
                onClick = { onTapModeChange(MushafTapPrefs.TapMode.WHOLE) },
            )
            Box(
                modifier = Modifier.fillMaxWidth().height(1.dp)
                    .background(ink.copy(alpha = 0.08f)),
            )
            MushafTapModeRow(
                title = if (ar) "كلمة بكلمة" else "Word by word",
                subtitle = if (ar) "الضغط يحدّد كلمة واحدة فقط — وتُقرأ الآية كاملة بصوت القارئ، إذ لا يوجد تسجيل لكل كلمة على حدة"
                else "A tap selects one word only — the ayah is still read whole, as there is no recording of single words",
                selected = tapMode == MushafTapPrefs.TapMode.WORD,
                ink = ink,
                onClick = { onTapModeChange(MushafTapPrefs.TapMode.WORD) },
            )
        }

        // The reciter, as chosen on the Reciters page; tapping opens that picker.
        val reciter = Reciters.chosen(context)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(card)
                .border(1.dp, ink.copy(alpha = 0.10f), RoundedCornerShape(16.dp))
                .clickable { onOpenReciters() }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (ar) reciter.ar else reciter.en,
                    color = ink,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (ar) "التلاوة بصوته — الآية كاملة أو كلمة واحدة حسب الاختيار"
                    else "Recitation in this voice — whole ayah or one word, as chosen",
                    color = ink.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
            Text(text = "‹", color = ink.copy(alpha = 0.5f), fontSize = 22.sp)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(card)
                .border(1.dp, ink.copy(alpha = 0.10f), RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (ar) "الضغط يقرأ الآية كاملة" else "Tap reads the whole ayah",
                    color = ink,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (ar) "تشتغل تلاوة الآية كاملة عند الضغط عليها"
                    else "Plays the whole ayah when it is tapped",
                    color = ink.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(
                checked = tapReadsAyah,
                onCheckedChange = onTapReadsChange,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = theme.ornament,
                    checkedThumbColor = theme.backgroundColor,
                    uncheckedTrackColor = ink.copy(alpha = 0.18f),
                    uncheckedThumbColor = ink.copy(alpha = 0.7f),
                ),
            )
        }
    }
}

@Composable
private fun MushafTapModeRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    ink: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(text = subtitle, color = ink.copy(alpha = 0.6f), fontSize = 12.sp, lineHeight = 17.sp)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .size(26.dp)
                .background(
                    if (selected) Color(0xFFE9CB86) else Color.Transparent,
                    CircleShape,
                )
                .border(
                    width = 2.dp,
                    color = if (selected) Color(0xFFE9CB86) else ink.copy(alpha = 0.3f),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Text(text = "✓", color = Color(0xFF0B1B33), fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}
