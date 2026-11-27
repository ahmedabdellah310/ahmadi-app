package io.github.sammynashed.emran.ui.screens

import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sammynashed.emran.service.MissionReminders
import io.github.sammynashed.emran.ui.design.GlassButton
import io.github.sammynashed.emran.ui.design.GlassPanel
import io.github.sammynashed.emran.ui.design.GlassSectionLabel
import io.github.sammynashed.emran.ui.design.LocalSky
import io.github.sammynashed.emran.ui.design.Radius
import io.github.sammynashed.emran.ui.localization.Text
import java.time.LocalDate
import java.util.Locale

private fun hhmm(min: Int): String = String.format(Locale.ENGLISH, "%02d:%02d", min / 60, min % 60)

// ---------------------------------------------------------------------------------------
// Missions
// ---------------------------------------------------------------------------------------

@Composable
fun MissionsScreen(lang: String, onBack: () -> Unit) {
    val ar = lang == "ar"
    val sky = LocalSky.current
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf("") }
    val list = remember(tick) { MissionReminders.load(ctx) }
    val today = LocalDate.now().toString()
    val suggestions = if (ar) listOf("ورد القرآن اليومي", "أذكار الصباح", "أذكار المساء", "صلاة الضحى", "الوتر قبل النوم", "صدقة ولو بالقليل", "صلة رحم", "الاستغفار مئة مرة")
    else listOf("Daily Quran portion", "Morning adhkar", "Evening adhkar", "Duha prayer", "Witr before sleep", "A small charity", "Call a relative", "Istighfar 100 times")

    fun pickTime(initial: Int, onPick: (Int) -> Unit) {
        TimePickerDialog(ctx, { _, h, m -> onPick(h * 60 + m) }, (if (initial >= 0) initial else 8 * 60) / 60, (if (initial >= 0) initial else 0) % 60, true).show()
    }

    SectionPage(ar, if (ar) "مهامي" else "My missions", if (ar) "مهام يومية بتذكير وعدّاد أيام متتالية" else "Daily missions with reminders and a streak", onBack) {
        val doneCount = list.count { it.doneDate == today }
        if (list.isNotEmpty()) {
            Text(
                if (ar) "أنجزت $doneCount من ${list.size} اليوم" else "$doneCount of ${list.size} done today",
                color = sky.inkSoft, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        list.forEach { m ->
            val done = m.doneDate == today
            GlassPanel(Modifier.fillMaxWidth(), cornerRadius = Radius.row) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(
                            Modifier.size(34.dp).background(if (done) sky.ink else Color.Transparent, CircleShape)
                                .border(2.dp, sky.ink, CircleShape)
                                .clickable { MissionReminders.toggleToday(ctx, m.id); tick++ },
                            contentAlignment = Alignment.Center,
                        ) { if (done) Text("✓", color = Color(0xFF101820), fontSize = 18.sp, fontWeight = FontWeight.Bold, bypassNumerals = true) }
                        Column(Modifier.weight(1f)) {
                            Text(m.title, color = sky.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            val st = MissionReminders.liveStreak(m)
                            Text(
                                (if (st > 0) (if (ar) "$st أيام متتالية" else "$st day streak") else (if (ar) "ابدأ سلسلتك اليوم" else "Start a streak today")) +
                                    (if (m.minute >= 0) (if (ar) " · تذكير ${hhmm(m.minute)}" else " · reminder ${hhmm(m.minute)}") else ""),
                                color = sky.inkSoft, fontSize = 12.sp, bypassNumerals = true,
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassButton(if (m.minute >= 0) (if (ar) "غيّر وقت التذكير" else "Change reminder") else (if (ar) "أضف تذكيراً" else "Add reminder"),
                            { pickTime(m.minute) { MissionReminders.setMinute(ctx, m.id, it); tick++ } }, false, Modifier.weight(1f), hPad = 8.dp)
                        if (m.minute >= 0) GlassButton(if (ar) "بلا تذكير" else "No reminder", { MissionReminders.setMinute(ctx, m.id, -1); tick++ }, false, Modifier.weight(1f), hPad = 8.dp)
                        GlassButton(if (ar) "حذف" else "Delete", { MissionReminders.remove(ctx, m.id); tick++ }, false, Modifier.weight(1f), hPad = 8.dp)
                    }
                }
            }
        }
        if (list.isEmpty()) Text(if (ar) "لا مهام بعد. أضف أول مهمة." else "No missions yet. Add the first one.", color = sky.inkSoft, fontSize = 15.sp, modifier = Modifier.padding(8.dp))
        GlassButton(if (adding) (if (ar) "إغلاق" else "Close") else (if (ar) "إضافة مهمة" else "Add a mission"), { adding = !adding }, true, Modifier.fillMaxWidth())
        if (adding) {
            GlassSectionLabel(if (ar) "اقتراحات" else "Suggestions")
            suggestions.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { s -> GlassButton(s, { MissionReminders.add(ctx, s, -1); tick++; adding = false }, false, Modifier.weight(1f), hPad = 6.dp) }
                }
            }
            GlassSectionLabel(if (ar) "أو اكتب مهمتك" else "Or write your own")
            TextField(
                value = custom, onValueChange = { custom = it.take(60) },
                label = { Text(if (ar) "عنوان المهمة" else "Mission title", color = sky.inkSoft, fontSize = 13.sp) },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = sky.ink.copy(alpha = 0.20f), unfocusedContainerColor = sky.ink.copy(alpha = 0.16f),
                    focusedTextColor = sky.ink, unfocusedTextColor = sky.ink, cursorColor = sky.ink,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                ),
                shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
            )
            GlassButton(if (ar) "أضف" else "Add", { if (custom.isNotBlank()) { MissionReminders.add(ctx, custom, -1); custom = ""; tick++; adding = false } }, true, Modifier.fillMaxWidth())
        }
        Text(
            if (ar) "التذكير يعمل والتطبيق مغلق بمنبّه دقيق، لكن «إيقاف إجباري» أو توفير البطارية الصارم قد يمنعه. اضبط الإعدادات من قائمة الموثوقية."
            else "Reminders fire with the app closed through an exact alarm, but Force stop or strict battery saving can block them.",
            color = sky.inkSoft, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}


// ---------------------------------------------------------------------------------------
// Build your paradise
// ---------------------------------------------------------------------------------------

private class Deed(val key: String, val ar: String, val en: String, val what: Int, val hadithAr: String, val srcAr: String)

private val DEEDS = listOf(
    Deed("house", "اثنتا عشرة ركعة سنّة راتبة اليوم", "Twelve rak'ahs of regular sunnah today", 0,
        "«مَا مِنْ عَبْدٍ مُسْلِمٍ يُصَلِّي لِلَّهِ كُلَّ يَوْمٍ ثِنْتَيْ عَشْرَةَ رَكْعَةً تَطَوُّعًا غَيْرَ فَرِيضَةٍ إِلَّا بَنَى اللَّهُ لَهُ بَيْتًا فِي الْجَنَّةِ»", "رواه مسلم (728)"),
    Deed("palm", "سبحان الله العظيم وبحمده", "Subhan Allah al-Azim wa bihamdih", 1,
        "«مَنْ قَالَ سُبْحَانَ اللَّهِ الْعَظِيمِ وَبِحَمْدِهِ غُرِسَتْ لَهُ نَخْلَةٌ فِي الْجَنَّةِ»", "رواه الترمذي (3464)، وصححه الألباني"),
    Deed("mosque", "إسهام في بناء مسجد أو إعماره", "A contribution to building a mosque", 2,
        "«مَنْ بَنَى مَسْجِدًا لِلَّهِ بَنَى اللَّهُ لَهُ مِثْلَهُ فِي الْجَنَّةِ»", "متفق عليه (البخاري 450، مسلم 533)"),
)

@Composable
fun ParadiseScreen(lang: String, onBack: () -> Unit) {
    val ar = lang == "ar"
    val sky = LocalSky.current
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("paradise", Context.MODE_PRIVATE) }
    var tick by remember { mutableIntStateOf(0) }
    val today = LocalDate.now().toString()
    fun total(k: String) = prefs.getInt("total_$k", 0)
    fun todayN(k: String) = if (prefs.getString("day_$k", "") == today) prefs.getInt("today_$k", 0) else 0
    fun bump(k: String, d: Int) {
        val t = todayN(k)
        if (t + d < 0 || total(k) + d < 0) return
        prefs.edit().putString("day_$k", today).putInt("today_$k", t + d).putInt("total_$k", total(k) + d).apply(); tick++
    }
    SectionPage(ar, if (ar) "ابنِ جنّتك" else "Build your paradise", if (ar) "عدّاد لأعمال ورد فيها وعد ببناء أو غرس في الجنة" else "A counter for deeds with a promised house, palm or building", onBack) {
        tick.let {
            GlassPanel(Modifier.fillMaxWidth(), cornerRadius = Radius.row) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
                        val w = size.width; val h = size.height
                        val ground = h * 0.86f
                        drawRect(sky.ink.copy(alpha = 0.25f), Offset(0f, ground), Size(w, h - ground))
                        val houses = minOf(total("house"), 6); val palms = minOf(total("palm"), 10); val mosques = minOf(total("mosque"), 3)
                        var x = 12f
                        for (i in 0 until houses) {
                            val bw = 44f; val bh = 34f
                            drawRect(sky.ink.copy(alpha = 0.85f), Offset(x, ground - bh), Size(bw, bh))
                            val roof = Path().apply { moveTo(x - 5, ground - bh); lineTo(x + bw / 2, ground - bh - 24); lineTo(x + bw + 5, ground - bh); close() }
                            drawPath(roof, Color(0xFFD9A441))
                            drawRect(Color(0xFF101820), Offset(x + bw / 2 - 5, ground - 18), Size(10f, 18f))
                            x += bw + 14
                        }
                        for (i in 0 until mosques) {
                            val bw = 60f; val bh = 40f
                            drawRect(sky.ink.copy(alpha = 0.9f), Offset(x, ground - bh), Size(bw, bh))
                            drawCircle(Color(0xFF4CAF8A), 22f, Offset(x + bw / 2, ground - bh))
                            drawRect(sky.ink, Offset(x + bw - 8, ground - bh - 40), Size(8f, 40f))
                            x += bw + 14
                        }
                        for (i in 0 until palms) {
                            if (x > w - 20) break
                            drawRect(Color(0xFF8D6E4A), Offset(x + 6, ground - 56), Size(7f, 56f))
                            for (a in listOf(-1f, 0f, 1f)) drawLine(Color(0xFF4CAF50), Offset(x + 9, ground - 56), Offset(x + 9 + a * 24, ground - 70 + kotlin.math.abs(a) * 10), 6f)
                            x += 30
                        }
                    }
                    Text(
                        if (ar) "بيوت: ${total("house")} · نخيل: ${total("palm")} · مساجد: ${total("mosque")}" else "Houses: ${total("house")} · Palms: ${total("palm")} · Mosques: ${total("mosque")}",
                        color = sky.ink, fontSize = 15.sp, bypassNumerals = true,
                    )
                    Text(
                        if (ar) "الرسم رمزي لتحفيزك. الثواب والقبول بيد الله وحده، ولا يعلم أحد ما كُتب له."
                        else "The picture is symbolic, to motivate you. Reward and acceptance are with Allah alone.",
                        color = sky.inkSoft, fontSize = 12.sp,
                    )
                }
            }
            DEEDS.forEach { d ->
                GlassPanel(Modifier.fillMaxWidth(), cornerRadius = Radius.row) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (ar) d.ar else d.en, color = sky.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text(d.hadithAr, color = sky.inkSoft, fontSize = 14.sp)
                        Text(d.srcAr, color = sky.inkSoft, fontSize = 11.sp)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            GlassButton("−", { bump(d.key, -1) }, false, Modifier.weight(1f))
                            Text(
                                if (ar) "اليوم ${todayN(d.key)} · الكل ${total(d.key)}" else "Today ${todayN(d.key)} · Total ${total(d.key)}",
                                color = sky.ink, fontSize = 14.sp, textAlign = TextAlign.Center, bypassNumerals = true, modifier = Modifier.weight(2f),
                            )
                            GlassButton("+", { bump(d.key, 1) }, true, Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------
// Ruqyah, from the bundled Quran text and authentic adhkar
// ---------------------------------------------------------------------------------------

private class RuqyahBlock(val title: String, val sourceAr: String, val verses: List<Pair<Int, IntRange>>?, val text: String?)

@Composable
fun RuqyahScreen(lang: String, onBack: () -> Unit) {
    val ar = lang == "ar"
    val sky = LocalSky.current
    val ctx = LocalContext.current
    val blocks = remember {
        listOf(
            RuqyahBlock("سورة الفاتحة", "", listOf(1 to 1..7), null),
            RuqyahBlock("آية الكرسي", "البقرة ٢٥٥", listOf(2 to 255..255), null),
            RuqyahBlock("خواتيم سورة البقرة", "البقرة ٢٨٥-٢٨٦", listOf(2 to 285..286), null),
            RuqyahBlock("سورة الإخلاص", "", listOf(112 to 1..4), null),
            RuqyahBlock("سورة الفلق", "", listOf(113 to 1..5), null),
            RuqyahBlock("سورة الناس", "", listOf(114 to 1..6), null),
            RuqyahBlock("الاستعاذة", "رواه مسلم (2708)", null, "أَعُوذُ بِكَلِمَاتِ اللَّهِ التَّامَّاتِ مِنْ شَرِّ مَا خَلَقَ"),
            RuqyahBlock("ذكر الحفظ صباحاً ومساءً", "رواه أبو داود (5088) والترمذي (3388)، وصححه الألباني", null,
                "بِسْمِ اللَّهِ الَّذِي لَا يَضُرُّ مَعَ اسْمِهِ شَيْءٌ فِي الْأَرْضِ وَلَا فِي السَّمَاءِ وَهُوَ السَّمِيعُ الْعَلِيمُ (ثلاث مرات)"),
        )
    }
    val quran by produceState<List<QSurah>?>(null) { value = QuranText.load(ctx) }
    SectionPage(ar, if (ar) "الرقية الشرعية" else "Ruqyah (Qur'an and authentic adhkar)", if (ar) "من القرآن والأذكار الثابتة، بدون صوت وبدون إنترنت" else null, onBack) {
        Text(
            "الرقية الشرعية قراءة من القرآن والأذكار الثابتة. هذه النصوص للقراءة والتحصين، وليست بديلاً عن الطبيب عند المرض.",
            color = sky.inkSoft, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp),
        )
        blocks.forEach { b ->
            GlassPanel(Modifier.fillMaxWidth(), cornerRadius = Radius.row) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(b.title, color = sky.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    val body = b.text ?: b.verses?.flatMap { (s, r) ->
                        quran?.firstOrNull { it.id == s }?.ayat?.filter { it.n in r }?.map { it.text + " ﴿${it.n}﴾" } ?: emptyList()
                    }?.joinToString(" ") ?: ""
                    Text(body, color = sky.ink, fontSize = 20.sp, bypassNumerals = true)
                    if (b.sourceAr.isNotEmpty()) Text(b.sourceAr, color = sky.inkSoft, fontSize = 11.sp)
                }
            }
        }
    }
}
