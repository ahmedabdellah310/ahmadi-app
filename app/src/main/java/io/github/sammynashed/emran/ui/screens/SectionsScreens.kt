package io.github.sammynashed.emran.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Celebration
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Park
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Radio
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.LocalLibrary
import androidx.compose.material.icons.outlined.Quiz
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sammynashed.emran.ui.design.GlassButton
import io.github.sammynashed.emran.ui.design.GlassGroup
import io.github.sammynashed.emran.ui.design.GlassPanel
import io.github.sammynashed.emran.ui.design.GlassSectionLabel
import io.github.sammynashed.emran.ui.design.GlassDivider
import io.github.sammynashed.emran.ui.design.LocalSky
import io.github.sammynashed.emran.ui.design.Radius
import io.github.sammynashed.emran.ui.design.ScreenHeader
import io.github.sammynashed.emran.ui.design.SettingsRow
import io.github.sammynashed.emran.ui.design.Space
import io.github.sammynashed.emran.ui.localization.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.time.LocalDate
import java.time.chrono.HijrahDate
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle
import java.time.temporal.ChronoField
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.random.Random

/** Right-to-left in Arabic, with the page's normal side margins; every section screen starts the same way. */
@Composable
internal fun SectionPage(ar: Boolean, title: String, subtitle: String?, onBack: () -> Unit, scroll: Boolean = true, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides if (ar) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        Column(Modifier.fillMaxSize().padding(horizontal = Space.screen).padding(bottom = 112.dp)) {
            ScreenHeader(title = title, subtitle = subtitle, onBack = onBack)
            Column(
                Modifier.fillMaxWidth().weight(1f).then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier),
                verticalArrangement = Arrangement.spacedBy(Space.md),
            ) { content() }
        }
    }
}

// ---------------------------------------------------------------------------------------
// The list of sections
// ---------------------------------------------------------------------------------------

/**
 * Sections kept in the code but not shown in the list (Ahmed's choice, 27 Nov). Remove a name from this
 * set to bring that section back; nothing else needs to change.
 */
private val HIDDEN_SECTIONS = setOf("zakat", "calendar", "library", "missions", "paradise", "ruqyah", "videos", "mushaf")

@Composable
fun SectionsScreen(
    lang: String,
    onBack: () -> Unit,
    onOpenHifzTest: () -> Unit,
    onOpenZakat: () -> Unit,
    onOpenEvents: () -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenMissions: () -> Unit,
    onOpenParadise: () -> Unit,
    onOpenRuqyah: () -> Unit,
    onOpenRadio: () -> Unit,
    onOpenVideos: () -> Unit,
    onOpenMushaf: () -> Unit = {},
) {
    val ar = lang == "ar"
    SectionPage(ar, if (ar) "الأقسام" else "Sections", null, onBack) {
        GlassGroup {
            var first = true
            if ("hifz" !in HIDDEN_SECTIONS) {
                if (!first) GlassDivider(); first = false
                SettingsRow(Icons.Outlined.Quiz, if (ar) "اختبر حفظك" else "Test your memorisation", onOpenHifzTest)
            }
            if ("zakat" !in HIDDEN_SECTIONS) {
                if (!first) GlassDivider(); first = false
                SettingsRow(Icons.Outlined.Calculate, if (ar) "احسب زكاتك" else "Zakat calculator", onOpenZakat)
            }
            if ("events" !in HIDDEN_SECTIONS) {
                if (!first) GlassDivider(); first = false
                SettingsRow(Icons.Outlined.Celebration, if (ar) "المناسبات الإسلامية" else "Islamic occasions", onOpenEvents)
            }
            if ("calendar" !in HIDDEN_SECTIONS) {
                if (!first) GlassDivider(); first = false
                SettingsRow(Icons.Outlined.CalendarMonth, if (ar) "التقويم الهجري" else "Hijri calendar", onOpenCalendar)
            }
            if ("library" !in HIDDEN_SECTIONS) {
                if (!first) GlassDivider(); first = false
                SettingsRow(Icons.Outlined.LocalLibrary, if (ar) "المكتبة" else "Library", onOpenLibrary)
            }
            if ("missions" !in HIDDEN_SECTIONS) {
                if (!first) GlassDivider(); first = false
                SettingsRow(Icons.Outlined.Checklist, if (ar) "مهامي" else "My missions", onOpenMissions)
            }
            if ("paradise" !in HIDDEN_SECTIONS) {
                if (!first) GlassDivider(); first = false
                SettingsRow(Icons.Outlined.Park, if (ar) "ابنِ جنّتك" else "Build your paradise", onOpenParadise)
            }
            if ("ruqyah" !in HIDDEN_SECTIONS) {
                if (!first) GlassDivider(); first = false
                SettingsRow(Icons.Outlined.Shield, if (ar) "الرقية الشرعية" else "Ruqyah", onOpenRuqyah)
            }
            if ("radio" !in HIDDEN_SECTIONS) {
                if (!first) GlassDivider(); first = false
                SettingsRow(Icons.Outlined.Radio, if (ar) "الإذاعات" else "Radio", onOpenRadio)
            }
            if ("videos" !in HIDDEN_SECTIONS) {
                if (!first) GlassDivider(); first = false
                SettingsRow(Icons.Outlined.PlayCircle, if (ar) "مرئيات" else "Videos", onOpenVideos)
            }
            if ("mushaf" !in HIDDEN_SECTIONS) {
                if (!first) GlassDivider(); first = false
                SettingsRow(Icons.Outlined.AutoStories, if (ar) "المصاحف والروايات" else "Mushaf editions", onOpenMushaf)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------
// Zakat
// ---------------------------------------------------------------------------------------

@Composable
private fun NumField(label: String, value: String, onChange: (String) -> Unit) {
    val sky = LocalSky.current
    TextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() || it == '.' }.take(14)) },
        label = { Text(label, color = sky.inkSoft, fontSize = 13.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = sky.ink.copy(alpha = 0.20f),
            unfocusedContainerColor = sky.ink.copy(alpha = 0.16f),
            focusedTextColor = sky.ink,
            unfocusedTextColor = sky.ink,
            cursorColor = sky.ink,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    )
}

private class LivePrices(val goldGram: Double, val silverGram: Double, val currency: String, val fetchedAt: Long)

/** Spot gold and silver in US dollars (gold-api.com) and the dollar's rate to [currency] (Frankfurter, central-bank reference rates). */
private suspend fun fetchLivePrices(currency: String): LivePrices? = withContext(Dispatchers.IO) {
    fun get(u: String): String? = try {
        val c = java.net.URL(u).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 10000
        c.requestMethod = "GET"
        if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText() } else null
    } catch (e: Exception) { null }
    try {
        val xau = org.json.JSONObject(get("https://api.gold-api.com/price/XAU") ?: return@withContext null).getDouble("price")
        val xag = org.json.JSONObject(get("https://api.gold-api.com/price/XAG") ?: return@withContext null).getDouble("price")
        val rate = if (currency == "USD") 1.0
        else org.json.JSONObject(get("https://api.frankfurter.dev/v2/rate/USD/$currency") ?: return@withContext null).getDouble("rate")
        val oz = 31.1034768
        LivePrices(xau * rate / oz, xag * rate / oz, currency, System.currentTimeMillis())
    } catch (e: Exception) { null }
}

private fun fmtMoney(v: Double): String = String.format(Locale.ENGLISH, "%,.2f", v)

@Composable
fun ZakatScreen(lang: String, onBack: () -> Unit) {
    val ar = lang == "ar"
    val sky = LocalSky.current
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("zakat_calc", Context.MODE_PRIVATE) }
    var cash by remember { mutableStateOf(prefs.getString("cash", "") ?: "") }
    var trade by remember { mutableStateOf(prefs.getString("trade", "") ?: "") }
    var goldG by remember { mutableStateOf(prefs.getString("goldG", "") ?: "") }
    var silverG by remember { mutableStateOf(prefs.getString("silverG", "") ?: "") }
    var debts by remember { mutableStateOf(prefs.getString("debts", "") ?: "") }
    var goldPrice by remember { mutableStateOf(prefs.getString("goldPrice", "") ?: "") }
    var silverPrice by remember { mutableStateOf(prefs.getString("silverPrice", "") ?: "") }
    var useSilver by remember { mutableStateOf(prefs.getBoolean("useSilver", false)) }
    var currency by remember { mutableStateOf(prefs.getString("currency", "EGP") ?: "EGP") }
    var fetchedAt by remember { mutableStateOf(prefs.getLong("fetchedAt", 0L)) }
    var fetchedCur by remember { mutableStateOf(prefs.getString("fetchedCur", "") ?: "") }
    var status by remember { mutableStateOf("") } // "" | loading | failed
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    fun save() {
        prefs.edit().putString("cash", cash).putString("trade", trade).putString("goldG", goldG).putString("silverG", silverG)
            .putString("debts", debts).putString("goldPrice", goldPrice).putString("silverPrice", silverPrice)
            .putBoolean("useSilver", useSilver).putString("currency", currency).putLong("fetchedAt", fetchedAt).putString("fetchedCur", fetchedCur).apply()
    }
    fun refresh() {
        status = "loading"
        scope.launch {
            val r = fetchLivePrices(currency)
            if (r == null) status = "failed" else {
                goldPrice = String.format(Locale.ENGLISH, "%.2f", r.goldGram)
                silverPrice = String.format(Locale.ENGLISH, "%.2f", r.silverGram)
                fetchedAt = r.fetchedAt; fetchedCur = r.currency; status = ""; save()
            }
        }
    }
    fun d(s: String) = s.toDoubleOrNull() ?: 0.0
    val gp = d(goldPrice); val sp = d(silverPrice)
    val wealth = d(cash) + d(trade) + d(goldG) * gp + d(silverG) * sp
    val net = (wealth - d(debts)).coerceAtLeast(0.0)
    val nisabGold = 85.0 * gp
    val nisabSilver = 595.0 * sp
    val nisab = if (useSilver) nisabSilver else nisabGold
    val priceMissing = nisab <= 0.0
    val due = !priceMissing && net >= nisab
    val zakat = if (due) net * 0.025 else 0.0

    SectionPage(ar, if (ar) "احسب زكاتك" else "Zakat calculator", if (ar) "كل شيء على جهازك، بدون إنترنت" else "Everything stays on your phone", onBack) {
        GlassSectionLabel(if (ar) "العملة" else "Currency")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("EGP", "SAR", "AED", "KWD", "USD").forEach { c ->
                GlassButton(c, { currency = c; save() }, currency == c, Modifier.weight(1f), hPad = 2.dp)
            }
        }
        GlassButton(
            when (status) { "loading" -> if (ar) "جارٍ التحديث…" else "Updating…"; else -> if (ar) "حدّث الأسعار من الإنترنت" else "Update prices from the internet" },
            { if (status != "loading") refresh() }, true, Modifier.fillMaxWidth(),
        )
        val stamp = if (fetchedAt > 0) java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ENGLISH).format(java.util.Date(fetchedAt)) else null
        Text(
            when {
                status == "failed" -> if (ar) "تعذّر التحديث. اكتب الأسعار بنفسك." else "Could not update. Type the prices yourself."
                stamp != null -> (if (ar) "آخر تحديث: $stamp ($fetchedCur) · سعر عالمي، وقد يختلف عن سوقك المحلي، فعدّله إن شئت."
                    else "Last update: $stamp ($fetchedCur). International spot price; your local market may differ, so edit it if needed.")
                else -> if (ar) "اضغط للتحديث، أو اكتب الأسعار بنفسك." else "Tap to update, or type the prices yourself."
            },
            color = sky.inkSoft, fontSize = 12.sp, bypassNumerals = true, modifier = Modifier.padding(horizontal = 8.dp),
        )
        GlassSectionLabel(if (ar) "أسعار الجرام (بعملتك)" else "Prices per gram (your currency)")
        NumField(if (ar) "سعر جرام الذهب" else "Gold price per gram", goldPrice) { goldPrice = it; save() }
        NumField(if (ar) "سعر جرام الفضة" else "Silver price per gram", silverPrice) { silverPrice = it; save() }
        GlassSectionLabel(if (ar) "أموالك" else "Your wealth")
        NumField(if (ar) "نقد وحسابات بنكية" else "Cash and bank balances", cash) { cash = it; save() }
        NumField(if (ar) "عروض التجارة (قيمة البضاعة)" else "Trade goods (value)", trade) { trade = it; save() }
        NumField(if (ar) "ذهب (جرام)" else "Gold (grams)", goldG) { goldG = it; save() }
        NumField(if (ar) "فضة (جرام)" else "Silver (grams)", silverG) { silverG = it; save() }
        NumField(if (ar) "ديون حالّة عليك" else "Debts you owe now", debts) { debts = it; save() }
        GlassSectionLabel(if (ar) "النصاب" else "Nisab")
        GlassGroup {
            SettingsRow(Icons.Outlined.Calculate, if (ar) "حسب الذهب (٨٥ جم)" else "By gold (85 g)", { useSilver = false; save() },
                value = if (!useSilver) (if (ar) "✓ مختار" else "✓ chosen") else null, chevron = false)
            GlassDivider()
            SettingsRow(Icons.Outlined.Calculate, if (ar) "حسب الفضة (٥٩٥ جم)" else "By silver (595 g)", { useSilver = true; save() },
                value = if (useSilver) (if (ar) "✓ مختار" else "✓ chosen") else null, chevron = false)
        }
        GlassPanel(Modifier.fillMaxWidth(), cornerRadius = Radius.row) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (ar) "صافي المال: ${fmtMoney(net)}" else "Net wealth: ${fmtMoney(net)}", color = sky.ink, fontSize = 15.sp)
                Text(
                    if (priceMissing) (if (ar) "اكتب سعر الجرام ليظهر النصاب" else "Enter the gram price to see the nisab")
                    else (if (ar) "النصاب: ${fmtMoney(nisab)}" else "Nisab: ${fmtMoney(nisab)}"),
                    color = sky.inkSoft, fontSize = 14.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    when {
                        priceMissing -> "—"
                        due -> (if (ar) "زكاتك: ${fmtMoney(zakat)}" else "Your zakat: ${fmtMoney(zakat)}")
                        else -> if (ar) "لا زكاة عليك الآن، المال دون النصاب" else "No zakat due: below the nisab"
                    },
                    color = sky.ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, bypassNumerals = true,
                )
            }
        }
        Text(
            if (ar) "الزكاة ٢٫٥٪ من المال الذي بلغ النصاب ومرّ عليه سنة هجرية كاملة. هذه حاسبة عامة وليست فتوى، فاسأل أهل العلم في حالتك الخاصة."
            else "Zakat is 2.5% of wealth that reached the nisab and was held for a full lunar year. This is a general calculator, not a fatwa: ask a scholar about your own case.",
            color = sky.inkSoft, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

// ---------------------------------------------------------------------------------------
// Islamic occasions
// ---------------------------------------------------------------------------------------

private class Occ(val ar: String, val en: String, val month: Int, val day: Int)

private val OCC = listOf(
    Occ("رأس السنة الهجرية", "Islamic New Year", 1, 1),
    Occ("تاسوعاء", "Tasu'a (9 Muharram)", 1, 9),
    Occ("عاشوراء", "Ashura", 1, 10),
    Occ("بداية رمضان", "Start of Ramadan", 9, 1),
    Occ("بداية العشر الأواخر", "Start of the last ten nights", 9, 21),
    Occ("عيد الفطر", "Eid al-Fitr", 10, 1),
    Occ("أول العشر من ذي الحجة", "First of Dhul Hijjah", 12, 1),
    Occ("يوم عرفة", "Day of Arafah", 12, 9),
    Occ("عيد الأضحى", "Eid al-Adha", 12, 10),
    Occ("أول أيام التشريق", "First day of Tashreeq", 12, 11),
)

@Composable
fun IslamicEventsScreen(lang: String, onBack: () -> Unit) {
    val ar = lang == "ar"
    val sky = LocalSky.current
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("zakat_calc", Context.MODE_PRIVATE) }
    var adjust by remember { mutableIntStateOf(prefs.getInt("hijri_adjust", 0)) }
    val today = remember { LocalDate.now() }
    val locale = remember(ar) { if (ar) Locale("ar") else Locale.ENGLISH }
    val fmt = remember(ar) { DateTimeFormatter.ofPattern("EEEE d MMMM y", locale).withDecimalStyle(DecimalStyle.STANDARD) }
    val items = remember(adjust) {
        val y = HijrahDate.from(today).get(ChronoField.YEAR)
        OCC.flatMap { o -> listOf(y, y + 1).map { yy -> o to LocalDate.from(HijrahDate.of(yy, o.month, o.day)).plusDays(adjust.toLong()) } }
            .filter { !it.second.isBefore(today) }.sortedBy { it.second }.take(12)
    }
    SectionPage(ar, if (ar) "المناسبات الإسلامية" else "Islamic occasions", if (ar) "تقويم أم القرى مع تعديل يوم" else "Umm al-Qura, with a day adjustment", onBack) {
        GlassPanel(Modifier.fillMaxWidth(), cornerRadius = Radius.row) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (ar) "تعديل التاريخ" else "Date adjustment", color = sky.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text(
                        if (ar) "لو الرؤية في بلدك تتأخر يوماً عن أم القرى" else "If your country's moon sighting runs a day later",
                        color = sky.inkSoft, fontSize = 12.sp,
                    )
                }
                GlassButton("−", { adjust = (adjust - 1).coerceAtLeast(-2); prefs.edit().putInt("hijri_adjust", adjust).apply() }, false)
                Text(
                    (if (adjust > 0) "+" else "") + adjust.toString(), color = sky.ink, fontSize = 18.sp, bypassNumerals = true,
                    textAlign = TextAlign.Center, modifier = Modifier.width(40.dp),
                )
                GlassButton("+", { adjust = (adjust + 1).coerceAtMost(2); prefs.edit().putInt("hijri_adjust", adjust).apply() }, false)
            }
        }
        GlassGroup {
            items.forEachIndexed { i, (o, date) ->
                if (i > 0) GlassDivider()
                val left = ChronoUnit.DAYS.between(today, date)
                Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (ar) o.ar else o.en, color = sky.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        Text(
                            when {
                                left == 0L -> if (ar) "اليوم" else "Today"
                                ar -> "بعد $left يوم"
                                else -> "in $left days"
                            },
                            color = sky.inkSoft, fontSize = 13.sp,
                        )
                    }
                    Text(fmt.format(date), color = sky.inkSoft, fontSize = 13.sp)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------
// Quran text, read once from the bundled quran.json
// ---------------------------------------------------------------------------------------

internal class QAyah(val surah: Int, val n: Int, val text: String)
internal class QSurah(val id: Int, val name: String, val ayat: List<QAyah>)

internal object QuranText {
    @Volatile private var cache: List<QSurah>? = null

    suspend fun load(context: Context): List<QSurah> {
        cache?.let { return it }
        return withContext(Dispatchers.IO) {
            val raw = context.applicationContext.assets.open("quran.json").bufferedReader().use { it.readText() }
            val arr = JSONArray(raw)
            val out = ArrayList<QSurah>(arr.length())
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                val id = s.getInt("id")
                val vs = s.getJSONArray("verses")
                val ayat = ArrayList<QAyah>(vs.length())
                for (j in 0 until vs.length()) {
                    val v = vs.getJSONObject(j)
                    ayat.add(QAyah(id, v.getInt("id"), v.getString("text")))
                }
                out.add(QSurah(id, s.getString("name"), ayat))
            }
            cache = out
            out
        }
    }

    /** Letters only: no marks, one shape for each alef, ya and ha, so a search finds the word however it was typed. */
    fun plain(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            val code = c.code
            if (code in 0x064B..0x065F || code == 0x0670 || code in 0x06D6..0x06ED || code == 0x0640) continue
            sb.append(
                when (c) {
                    'أ', 'إ', 'آ', 'ٱ' -> 'ا'
                    'ى' -> 'ي'
                    'ة' -> 'ه'
                    else -> c
                },
            )
        }
        return sb.toString()
    }
}

// ---------------------------------------------------------------------------------------
// Test your memorisation
// ---------------------------------------------------------------------------------------

private class Question(val prompt: String, val shown: String, val options: List<String>, val correct: Int)

private fun words(s: String) = s.trim().split(' ').filter { it.isNotEmpty() }

/** Ten questions from [surahs]: finish the verse, which verse comes next, or which surah is it from. */
private fun buildQuiz(surahs: List<QSurah>, ar: Boolean, count: Int = 10): List<Question> {
    val all = surahs.flatMap { it.ayat }
    val long = all.filter { words(it.text).size >= 6 }
    if (all.size < 4 || long.size < 4) return emptyList()
    val rnd = Random(System.nanoTime())
    val qs = ArrayList<Question>()
    var guard = 0
    while (qs.size < count && guard++ < 200) {
        val kind = rnd.nextInt(3)
        when {
            kind == 0 -> {
                val a = long.random(rnd)
                val w = words(a.text)
                val cut = (w.size / 2).coerceIn(2, w.size - 2)
                val head = w.take(cut).joinToString(" ")
                val tail = w.drop(cut).joinToString(" ")
                val wrong = long.filter { it !== a }.shuffled(rnd).take(3).map { x ->
                    val xw = words(x.text); xw.drop((xw.size / 2).coerceIn(2, xw.size - 2)).joinToString(" ")
                }
                if (wrong.size < 3 || wrong.any { it == tail }) continue
                val opts = (wrong + tail).shuffled(rnd)
                qs.add(Question(if (ar) "أكمل الآية" else "Finish the verse", head + " …", opts, opts.indexOf(tail)))
            }
            kind == 1 -> {
                val s = surahs.random(rnd)
                if (s.ayat.size < 2) continue
                val i = rnd.nextInt(s.ayat.size - 1)
                val a = s.ayat[i]; val next = s.ayat[i + 1]
                val wrong = all.filter { it.surah != next.surah || it.n != next.n }.shuffled(rnd).take(3).map { it.text }
                if (wrong.size < 3 || wrong.any { it == next.text }) continue
                val opts = (wrong + next.text).shuffled(rnd)
                qs.add(Question(if (ar) "ما الآية التي تأتي بعد:" else "Which verse comes next?", a.text, opts, opts.indexOf(next.text)))
            }
            else -> {
                if (surahs.size < 4) continue
                val a = long.random(rnd)
                val right = surahs.first { it.id == a.surah }
                val wrong = surahs.filter { it.id != a.surah }.shuffled(rnd).take(3)
                val opts = (wrong + right).shuffled(rnd).map { it.name }
                qs.add(Question(if (ar) "في أي سورة هذه الآية؟" else "Which surah is this verse from?", a.text, opts, opts.indexOf(right.name)))
            }
        }
    }
    return qs
}

@Composable
fun HifzTestScreen(lang: String, onBack: () -> Unit) {
    val ar = lang == "ar"
    val sky = LocalSky.current
    val context = LocalContext.current
    val quran by produceState<List<QSurah>?>(null) { value = QuranText.load(context) }

    var stage by remember { mutableStateOf("setup") } // setup | pick | quiz | result
    var scope by remember { mutableStateOf("all") } // all | amma | one
    var oneSurah by remember { mutableIntStateOf(1) }
    var quiz by remember { mutableStateOf<List<Question>>(emptyList()) }
    var index by remember { mutableIntStateOf(0) }
    var chosen by remember { mutableIntStateOf(-1) }
    var score by remember { mutableIntStateOf(0) }
    val wrongOnes = remember { mutableStateOf<List<Question>>(emptyList()) }

    fun start() {
        val q = quran ?: return
        val pool = when (scope) {
            "amma" -> q.filter { it.id >= 78 }
            "one" -> q.filter { it.id == oneSurah }
            else -> q
        }
        quiz = buildQuiz(pool, ar)
        index = 0; chosen = -1; score = 0; wrongOnes.value = emptyList()
        stage = if (quiz.isEmpty()) "empty" else "quiz"
    }

    SectionPage(ar, if (ar) "اختبر حفظك" else "Test your memorisation", null, { if (stage == "setup" || stage == "empty") onBack() else stage = "setup" }, scroll = stage != "pick") {
        val surahs = quran
        when {
            surahs == null -> Text(if (ar) "جارٍ التحميل…" else "Loading…", color = sky.inkSoft)
            stage == "pick" -> LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(surahs) { s ->
                    GlassPanel(Modifier.fillMaxWidth().clickable { oneSurah = s.id; scope = "one"; stage = "setup" }, cornerRadius = Radius.row) {
                        Text("${s.id}. ${s.name}", color = sky.ink, fontSize = 17.sp, modifier = Modifier.fillMaxWidth().padding(16.dp), bypassNumerals = true)
                    }
                }
            }
            stage == "setup" || stage == "empty" -> {
                if (stage == "empty") Text(if (ar) "السورة قصيرة جداً لهذا الاختبار، اختر نطاقاً أوسع." else "That surah is too short for this test; pick a wider range.", color = sky.inkSoft, fontSize = 14.sp)
                GlassSectionLabel(if (ar) "من أين الأسئلة؟" else "Where do the questions come from?")
                GlassGroup {
                    SettingsRow(Icons.Outlined.Quiz, if (ar) "القرآن كله" else "The whole Quran", { scope = "all" }, value = if (scope == "all") "✓" else null, chevron = false)
                    GlassDivider()
                    SettingsRow(Icons.Outlined.Quiz, if (ar) "جزء عمّ" else "Juz Amma", { scope = "amma" }, value = if (scope == "amma") "✓" else null, chevron = false)
                    GlassDivider()
                    SettingsRow(
                        Icons.Outlined.Quiz, if (ar) "سورة محددة" else "One surah", { stage = "pick" },
                        value = if (scope == "one") surahs.firstOrNull { it.id == oneSurah }?.name else null,
                    )
                }
                Text(
                    if (ar) "عشرة أسئلة: أكمل الآية، الآية التالية، أو في أي سورة. من نص المصحف الموجود في التطبيق."
                    else "Ten questions: finish the verse, the next verse, or which surah. From the Quran text inside the app.",
                    color = sky.inkSoft, fontSize = 13.sp,
                )
                GlassButton(if (ar) "ابدأ" else "Start", { start() }, true, Modifier.fillMaxWidth())
            }
            stage == "quiz" -> {
                val q = quiz[index]
                Text(
                    (if (ar) "السؤال " else "Question ") + "${index + 1}/${quiz.size}",
                    color = sky.inkSoft, fontSize = 13.sp, bypassNumerals = false,
                )
                GlassPanel(Modifier.fillMaxWidth(), cornerRadius = Radius.row) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(q.prompt, color = sky.inkSoft, fontSize = 14.sp)
                        Text(q.shown, color = sky.ink, fontSize = 22.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium)
                    }
                }
                q.options.forEachIndexed { i, opt ->
                    val isRight = i == q.correct
                    val shade = when {
                        chosen < 0 -> 0.06f
                        isRight -> 0.28f
                        i == chosen -> 0.02f
                        else -> 0.06f
                    }
                    GlassPanel(
                        Modifier.fillMaxWidth().clickable(enabled = chosen < 0) {
                            chosen = i
                            if (isRight) score++ else wrongOnes.value = wrongOnes.value + q
                        },
                        cornerRadius = Radius.row,
                    ) {
                        Row(Modifier.fillMaxWidth().background(sky.ink.copy(alpha = shade)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(opt, color = sky.ink, fontSize = 17.sp, lineHeight = 28.sp, modifier = Modifier.weight(1f))
                            if (chosen >= 0 && isRight) Text("✓", color = sky.ink, fontSize = 20.sp, bypassNumerals = true)
                            if (chosen >= 0 && i == chosen && !isRight) Text("✗", color = sky.inkSoft, fontSize = 20.sp, bypassNumerals = true)
                        }
                    }
                }
                if (chosen >= 0) {
                    GlassButton(
                        if (index + 1 < quiz.size) (if (ar) "التالي" else "Next") else (if (ar) "النتيجة" else "Result"),
                        { if (index + 1 < quiz.size) { index++; chosen = -1 } else stage = "result" },
                        true, Modifier.fillMaxWidth(),
                    )
                }
            }
            else -> {
                GlassPanel(Modifier.fillMaxWidth(), cornerRadius = Radius.row) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("$score / ${quiz.size}", color = sky.ink, fontSize = 44.sp, fontWeight = FontWeight.ExtraLight, bypassNumerals = true)
                        Text(
                            if (score == quiz.size) (if (ar) "ما شاء الله، كله صحيح" else "Perfect")
                            else if (score * 2 >= quiz.size) (if (ar) "أحسنت، راجع الأخطاء" else "Well done, review the misses")
                            else (if (ar) "واصل المراجعة" else "Keep reviewing"),
                            color = sky.inkSoft, fontSize = 15.sp,
                        )
                    }
                }
                if (wrongOnes.value.isNotEmpty()) {
                    GlassSectionLabel(if (ar) "ما أخطأت فيه" else "What you missed")
                    wrongOnes.value.forEach { q ->
                        GlassPanel(Modifier.fillMaxWidth(), cornerRadius = Radius.row) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(q.shown, color = sky.ink, fontSize = 16.sp, lineHeight = 26.sp)
                                Text((if (ar) "الصواب: " else "Answer: ") + q.options[q.correct], color = sky.inkSoft, fontSize = 15.sp, lineHeight = 24.sp)
                            }
                        }
                    }
                }
                GlassButton(if (ar) "اختبار جديد" else "New test", { start() }, true, Modifier.fillMaxWidth())
                GlassButton(if (ar) "تغيير النطاق" else "Change range", { stage = "setup" }, false, Modifier.fillMaxWidth())
            }
        }
    }
}

// ---------------------------------------------------------------------------------------
// Library
// ---------------------------------------------------------------------------------------

@Composable
fun LibraryScreen(
    lang: String,
    onBack: () -> Unit,
    onOpenHadith: () -> Unit,
    onOpenAdhkar: () -> Unit,
    onOpenAyahPicker: () -> Unit,
    onOpenAyah: (Int, Int) -> Unit,
) {
    val ar = lang == "ar"
    val sky = LocalSky.current
    val context = LocalContext.current
    val quran by produceState<List<QSurah>?>(null) { value = QuranText.load(context) }
    val index by produceState<List<Pair<QAyah, String>>?>(null, quran) {
        val q = quran
        value = if (q == null) null else withContext(Dispatchers.Default) { q.flatMap { s -> s.ayat.map { it to QuranText.plain(it.text) } } }
    }
    var query by remember { mutableStateOf("") }
    val hits = remember(query, index) {
        val q = QuranText.plain(query.trim())
        val idx = index
        if (q.length < 3 || idx == null) emptyList() else idx.filter { it.second.contains(q) }.take(40).map { it.first }
    }
    val names = remember(quran) { quran?.associate { it.id to it.name } ?: emptyMap() }

    SectionPage(ar, if (ar) "المكتبة" else "Library", if (ar) "كل ما في التطبيق في مكان واحد" else "Everything in the app in one place", onBack) {
        TextField(
            value = query, onValueChange = { query = it },
            placeholder = { Text(if (ar) "ابحث في آيات القرآن…" else "Search the Quran…", color = sky.inkSoft, fontSize = 14.sp) },
            leadingIcon = { androidx.compose.material3.Icon(Icons.Outlined.Search, null, tint = sky.inkSoft) },
            singleLine = true,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = sky.ink.copy(alpha = 0.20f), unfocusedContainerColor = sky.ink.copy(alpha = 0.16f),
                focusedTextColor = sky.ink, unfocusedTextColor = sky.ink, cursorColor = sky.ink,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            ),
            shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
        )
        if (query.trim().length >= 3) {
            if (hits.isEmpty()) Text(if (ar) "لا نتائج" else "No results", color = sky.inkSoft, fontSize = 14.sp)
            hits.forEach { a ->
                GlassPanel(Modifier.fillMaxWidth().clickable { onOpenAyah(a.surah, a.n) }, cornerRadius = Radius.row) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(a.text, color = sky.ink, fontSize = 18.sp, lineHeight = 30.sp)
                        Text("${names[a.surah] ?: ""} · ${a.n}", color = sky.inkSoft, fontSize = 13.sp, bypassNumerals = true)
                    }
                }
            }
        } else {
            GlassGroup {
                SettingsRow(Icons.Outlined.AutoStories, if (ar) "الأحاديث" else "Hadith", onOpenHadith)
                GlassDivider()
                SettingsRow(Icons.Outlined.MenuBook, if (ar) "الأذكار" else "Adhkar", onOpenAdhkar)
                GlassDivider()
                SettingsRow(Icons.Outlined.Explore, if (ar) "آية وتفسيرها" else "A verse and its tafsir", onOpenAyahPicker)
            }
        }
    }
}
