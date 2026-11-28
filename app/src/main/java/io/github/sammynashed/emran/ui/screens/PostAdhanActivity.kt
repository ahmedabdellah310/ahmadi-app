package io.github.sammynashed.emran.ui.screens

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import io.github.sammynashed.emran.R
import io.github.sammynashed.emran.data.LocationPresets
import io.github.sammynashed.emran.data.PrayerNames
import io.github.sammynashed.emran.service.AdhanPolicy
import io.github.sammynashed.emran.service.PrayerAlarmReceiver
import io.github.sammynashed.emran.ui.localization.LocalAppLanguage
import io.github.sammynashed.emran.ui.theme.MyApplicationTheme

/**
 * The screen after the adhan: it fills the phone with the adhan's text and the dua after it.
 * It is launched by [PrayerAlarmReceiver.showPostAdhanScreen] through a full-screen-intent
 * notification when an adhan ends on its own, so it appears over the lock screen and over
 * other apps. A muted adhan never opens it (stop() fires no completion), and neither do
 * vibrate/silent (no audio, no completion).
 */
class PostAdhanActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        // The notification did its job once this screen is up.
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(PrayerAlarmReceiver.POST_ADHAN_NOTIFICATION_ID)
        }
        val prayer = intent.getStringExtra(EXTRA_PRAYER) ?: "Maghrib"
        val lang = getSharedPreferences("emran_settings", Context.MODE_PRIVATE)
            .getString("current_language", "en") ?: "en"
        setContent {
            CompositionLocalProvider(LocalAppLanguage provides lang) {
                MyApplicationTheme { PostAdhanScreen(prayer = prayer, onClose = { finish() }) }
            }
        }
    }

    companion object {
        const val EXTRA_PRAYER = "post_adhan_prayer"
    }
}

private const val DUA_AFTER_ADHAN =
    "اللَّهُمَّ رَبَّ هَذِهِ الدَّعْوَةِ التَّامَّةِ، وَالصَّلَاةِ الْقَائِمَةِ، آتِ مُحَمَّدًا الْوَسِيلَةَ وَالْفَضِيلَةَ، وَابْعَثْهُ مَقَامًا مَحْمُودًا الَّذِي وَعَدْتَهُ."

/** The adhan's words; Fajr adds "الصلاة خير من النوم" after "حي على الفلاح", as it is called. */
private fun adhanText(prayer: String): String {
    val tathweeb = if (prayer == "Fajr") "الصلاة خير من النوم، الصلاة خير من النوم، " else ""
    return "الله أكبر، الله أكبر، الله أكبر، الله أكبر، أشهد أن لا إله إلا الله، أشهد أن لا إله إلا الله، " +
        "أشهد أن محمدًا رسول الله، أشهد أن محمدًا رسول الله، حيَّ على الصلاة، حيَّ على الصلاة، " +
        "حيَّ على الفلاح، حيَّ على الفلاح، " + tathweeb + "الله أكبر، الله أكبر، لا إله إلا الله."
}

@Composable
private fun PostAdhanScreen(prayer: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("emran_settings", Context.MODE_PRIVATE) }
    val ar = (prefs.getString("current_language", "en") ?: "en") == "ar"
    val locName = remember {
        val full = prefs.getString("location_name", "Damietta, Egypt") ?: "Damietta, Egypt"
        LocationPresets.getLocalizedName(full, if (ar) "ar" else "en").split(",").first().trim()
    }
    val prayerName = PrayerNames.name(prayer, ar)
    // The row reads "don't show": checked means the screen stays hidden from now on.
    var hide by remember { mutableStateOf(!AdhanPolicy.showPostAdhanScreen(context)) }
    val icon = remember {
        runCatching {
            val pm = context.packageManager
            val e = pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName), 0
            ).firstOrNull()
            (e?.loadIcon(pm) ?: pm.getApplicationIcon(context.packageName)).toBitmap(256, 256)
        }.getOrNull()
    }
    val amiri = remember {
        FontFamily(Font(R.font.amiri_regular, FontWeight.Normal), Font(R.font.amiri_bold, FontWeight.Bold))
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF0B0C0E))) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            icon?.let { Image(it.asImageBitmap(), null, Modifier.size(104.dp).clip(RoundedCornerShape(24.dp))) }
            Spacer(Modifier.height(20.dp))
            Text(
                if (ar) "حان الآن وقت صلاة $prayerName" else "It is now time for $prayerName prayer",
                color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
            Text(locName, color = Color.White.copy(alpha = 0.6f), fontSize = 15.sp)
            Spacer(Modifier.height(28.dp))
            PostAdhanCard(title = if (ar) "نص الأذان" else "The adhan") {
                Text(
                    adhanText(prayer), color = Color.White, fontSize = 19.sp, lineHeight = 34.sp,
                    textAlign = TextAlign.Center, fontFamily = amiri
                )
            }
            Spacer(Modifier.height(16.dp))
            PostAdhanCard(title = if (ar) "الدعاء بعد الأذان" else "The dua after the adhan") {
                Text(
                    DUA_AFTER_ADHAN, color = Color.White, fontSize = 19.sp, lineHeight = 34.sp,
                    textAlign = TextAlign.Center, fontFamily = amiri
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    if (ar) "قال ﷺ: «مَن قال حين يسمع النداء هذا الدعاء حلَّت له شفاعتي يوم القيامة»."
                    else "The Prophet ﷺ said: “Whoever says this dua when he hears the call, my intercession will be granted to him on the Day of Resurrection.”",
                    color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp, lineHeight = 22.sp,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(28.dp))
            Box(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(50))
                    .background(Color.White)
                    .clickable { onClose() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (ar) "إغلاق" else "Close",
                    color = Color(0xFF0B1B33), fontSize = 17.sp, fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(18.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (ar) "لا تظهر هذه الشاشة بعد الأذان" else "Don't show this screen after the adhan",
                    color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp
                )
                Switch(
                    checked = hide,
                    onCheckedChange = { hide = it; AdhanPolicy.setShowPostAdhanScreen(context, !it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFFE9CB86),
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = Color.White.copy(alpha = 0.25f)
                    )
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(
                if (ar) "بتظهر بعد ما الأذان يخلص، فوق أي تطبيق — وحتى لو الهاتف مقفل."
                else "It appears when the adhan ends, over any app — even with the phone locked.",
                color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp, textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun PostAdhanCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(24.dp))
            .padding(horizontal = 20.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = Color(0xFFE9CB86), fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        content()
    }
}
