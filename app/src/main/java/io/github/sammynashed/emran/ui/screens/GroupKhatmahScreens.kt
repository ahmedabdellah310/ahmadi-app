package io.github.sammynashed.emran.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.LocalFlorist
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.ChecklistRtl
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.scaleOut
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.LocalFlorist
import io.github.sammynashed.emran.ui.design.DockTab
import io.github.sammynashed.emran.ui.design.GlassDock
import io.github.sammynashed.emran.ui.design.LocalSkyLayer
import io.github.sammynashed.emran.ui.design.GlassTint
import io.github.sammynashed.emran.ui.design.LocalGlassTint
import io.github.sammynashed.emran.ui.design.LocalSkySize
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sammynashed.emran.R
import io.github.sammynashed.emran.data.khatmah.GroupKhatmah
import io.github.sammynashed.emran.data.khatmah.GroupKhatmahCode
import io.github.sammynashed.emran.data.khatmah.JuzState
import io.github.sammynashed.emran.data.khatmah.KhatmahStore
import io.github.sammynashed.emran.data.khatmah.Member
import io.github.sammynashed.emran.ui.design.LocalSky
import io.github.sammynashed.emran.ui.design.PaperBars
import io.github.sammynashed.emran.ui.localization.Text
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

/*
 * Group khatmah, a watercolor garden (after the ChatGPT reference). Every juz is a plant growing in the painted
 * meadow: a seed, a young plant while someone reads it, a flower once finished. Every screen sits on a painting
 * and fits one screen; inside a group: Garden / Friends / Stats on a floating dock. Light, dark, or auto
 * (follows Emran's night sky).
 */

/* ================================================================ look */

private enum class GardenMode { AUTO, LIGHT, DARK }

/** The garden's light/dark choice, kept on the phone. */
private object GardenLook {
    var mode by mutableStateOf(GardenMode.AUTO)
    private var loaded = false
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val v = context.getSharedPreferences("khatmah_ui", Context.MODE_PRIVATE).getString("mode", null)
        mode = GardenMode.entries.firstOrNull { it.name == v } ?: GardenMode.AUTO
    }
    fun set(context: Context, m: GardenMode) {
        mode = m
        context.getSharedPreferences("khatmah_ui", Context.MODE_PRIVATE).edit().putString("mode", m.name).apply()
    }
}

/** True while the garden draws its night look. Read through [Garden], so every color follows it. */
private var gardenDark by mutableStateOf(false)

/** Warm neutral ramp + one olive accent, by day and by night. Text roles: [ink], [inkSoft], [inkMuted]. */
private object Garden {
    private val d get() = gardenDark
    val paper get() = if (d) Color(0xFF161A13) else Color(0xFFF4EEE2)
    val card get() = if (d) Color(0xFF22271D) else Color(0xFFFBF8F1)
    /** Cards resting on a painting: the card, slightly see-through. */
    val glass get() = card.copy(alpha = if (d) 0.86f else 0.9f)
    val ink get() = if (d) Color(0xFFEDE6D3) else Color(0xFF2E3526)
    val inkSoft get() = if (d) Color(0xFFBDB6A2) else Color(0xFF66665A)
    val inkMuted get() = if (d) Color(0xFF8E8977) else Color(0xFF8F8B7B)
    val hairline get() = ink.copy(alpha = if (d) 0.12f else 0.08f)
    val accent get() = if (d) Color(0xFF93B272) else Color(0xFF5B7744)
    val onAccent get() = if (d) Color(0xFF141A0E) else Color.White
    val accentInk get() = if (d) Color(0xFFADCB8D) else Color(0xFF4A6337)
    val accentSoft get() = if (d) Color(0xFF2F3A25) else Color(0xFFE3E8D6)
    val warn get() = if (d) Color(0xFFD9886F) else Color(0xFF9C5440)
    /** Laid over the paintings at night: deep blue dusk, so the watercolors read as moonlit. */
    val night get() = if (d) Color(0xFF0B1220).copy(alpha = 0.55f) else Color.Transparent
    val onSoil = Color(0xFFF6EEDC)
    val gold = Color(0xFFC9B07E)
    val clay = Color(0xFFC98E5A)
}

private val Ease = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

@OptIn(ExperimentalTextApi::class)
private val Bodoni = FontFamily(
    Font(R.font.bodoni_moda, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.bodoni_moda, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
)
private val Messiri = FontFamily(Font(R.font.el_messiri_regular, FontWeight.Normal), Font(R.font.el_messiri_medium, FontWeight.Medium))

/** Display face for titles and the big figures: Bodoni Moda for Latin, El Messiri for Arabic (soft, modern, calm). */
private fun display(ar: Boolean) = if (ar) Messiri else Bodoni

private val PLANTS = listOf(R.drawable.khatmah_plant_0, R.drawable.khatmah_plant_1, R.drawable.khatmah_plant_2, R.drawable.khatmah_plant_3)

/** The 12 mascots, index = [Member.avatar]. Must stay in step with [Member.AVATAR_COUNT]. */
private val AVATARS = listOf(
    R.drawable.khatmah_avatar_0, R.drawable.khatmah_avatar_1, R.drawable.khatmah_avatar_2, R.drawable.khatmah_avatar_3,
    R.drawable.khatmah_avatar_4, R.drawable.khatmah_avatar_5, R.drawable.khatmah_avatar_6, R.drawable.khatmah_avatar_7,
    R.drawable.khatmah_avatar_8, R.drawable.khatmah_avatar_9, R.drawable.khatmah_avatar_10, R.drawable.khatmah_avatar_11,
)

/** Growth of a whole group for its list thumbnail: seed at 0, then sprout, young plant, flower. */
private fun stageFor(done: Int): Int = when {
    done == 0 -> 0
    done * 3 < GroupKhatmah.JUZ_COUNT -> 1
    done * 3 < GroupKhatmah.JUZ_COUNT * 2 -> 2
    else -> 3
}

private fun membersLabel(n: Int, ar: Boolean) = if (!ar) (if (n == 1) "1 member" else "$n members") else when {
    n == 1 -> "عضو واحد"
    n == 2 -> "عضوان"
    n in 3..10 -> "$n أعضاء"
    else -> "$n عضواً"
}

private fun juzLabel(n: Int, ar: Boolean) = if (!ar) "$n juz" else when {
    n == 1 -> "جزء واحد"
    n == 2 -> "جزءان"
    n in 3..10 -> "$n أجزاء"
    else -> "$n جزءاً"
}

/** "أُتمّ ٣ من ٣٠" / "3 of 30 juz finished", or "none finished yet" when there are none. */
private fun finishedLine(n: Int, ar: Boolean): String = when {
    n == 0 -> if (ar) "لم يُتمّ أي جزء بعد" else "No juz finished yet"
    ar -> "أُتمّ ${juzLabel(n, true)} من ٣٠"
    else -> "$n of 30 juz finished"
}

/** "Today, 9:12 AM" / "14 Nov, 9:12 AM". */
private fun whenLabel(at: Long, ar: Boolean): String {
    val z = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
    val loc = Locale(if (ar) "ar" else "en")
    val time = DateTimeFormatter.ofPattern("h:mm a", loc).format(z)
    val day = if (z.toLocalDate() == LocalDate.now()) (if (ar) "اليوم" else "Today") else DateTimeFormatter.ofPattern("d MMM", loc).format(z)
    return if (ar) "$day، $time" else "$day, $time"
}

private fun copyCode(context: Context, code: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("khatmah", code))
}

private fun pasteText(context: Context): String {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
}

/**
 * The garden's paintings, decoded once in the background while the list is open, so opening a garden shows it at
 * once instead of a moment of empty paper while a full-screen painting decodes.
 */
private object GardenArt {
    val cache = java.util.concurrent.ConcurrentHashMap<Int, androidx.compose.ui.graphics.ImageBitmap>()
    private val warm = listOf(
        R.drawable.khatmah_garden_bg, R.drawable.khatmah_welcome_bg, R.drawable.khatmah_seed, R.drawable.khatmah_divider,
        R.drawable.khatmah_plant_0, R.drawable.khatmah_plant_1, R.drawable.khatmah_plant_2, R.drawable.khatmah_plant_3,
    )
    suspend fun preload(context: Context) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        for (res in warm) if (!cache.containsKey(res)) runCatching {
            android.graphics.BitmapFactory.decodeResource(context.resources, res)?.let { cache[res] = it.asImageBitmap() }
        }
    }
}

/** A garden painting: the warm copy if it is ready, else loaded the usual way. */
@Composable
private fun art(res: Int): androidx.compose.ui.graphics.painter.Painter =
    GardenArt.cache[res]?.let { remember(it) { androidx.compose.ui.graphics.painter.BitmapPainter(it) } } ?: painterResource(res)

/* ================================================================ list: welcome + friends */

/** Groups on this phone. First visit: the archway welcome; then create / join / your groups, on one screen. */
@Composable
fun GroupKhatmahListScreen(
    lang: String, onBack: () -> Unit, onOpen: (String) -> Unit,
    /** My own khatmah, per juz 0..1 (from the muṣḥaf reading); empty hides the card. */
    mine: List<Float> = emptyList(),
    onOpenMine: () -> Unit = {},
) {
    val ar = lang == "ar"
    val context = LocalContext.current
    val store = remember { KhatmahStore(context) }
    var refresh by remember { mutableStateOf(0) }
    val groups = remember(refresh) { store.groups() }
    var started by rememberSaveable { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<String?>(null) } // "create" or a checked join code
    var joinError by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { GardenArt.preload(context) }

    PaperPage(onBack) { leave, handOff ->
        if (groups.isEmpty() && !started) {
            Welcome(ar = ar, onBack = leave, onStart = { started = true })
        } else {
            Painting(R.drawable.khatmah_welcome_bg, veil = 0.62f)
            OneScreen(Modifier.statusBarsPadding().navigationBarsPadding()) {
                TopBar(ar, leave)
                Column(Modifier.padding(horizontal = 24.dp)) {
                    Text(if (ar) "الختمة" else "Khatmah", color = Garden.ink, fontSize = 32.sp, fontFamily = display(ar))
                    Spacer(Modifier.height(16.dp))

                    // My own khatmah: no group needed. Its garden grows from my reading in the muṣḥaf.
                    if (mine.size == GroupKhatmah.JUZ_COUNT) {
                        val doneMine = mine.count { it >= 0.999f }
                        val readingMine = mine.count { it > 0f && it < 0.999f }
                        DepthCard(onClick = { handOff { onOpenMine() } }) {
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(Garden.accentSoft), contentAlignment = Alignment.Center) {
                                    Image(art(PLANTS[if (doneMine > 0) stageFor(doneMine) else if (readingMine > 0) 1 else 0]), null, Modifier.size(48.dp))
                                }
                                Spacer(Modifier.width(16.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(if (ar) "ختمتي" else "My khatmah", color = Garden.ink, fontSize = 22.sp, fontFamily = display(ar))
                                    Text(finishedLine(doneMine, ar), color = Garden.inkSoft, fontSize = 14.sp)
                                }
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Garden.inkMuted, modifier = Modifier.size(24.dp))
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }

                    // Create: the mascots of a garden-to-be, and the one action.
                    GlassCard {
                        Column(Modifier.fillMaxWidth().padding(20.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                MascotCluster(small = true)
                                Spacer(Modifier.width(16.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(if (ar) "أنشئ مجموعة" else "Create a group", color = Garden.ink, fontSize = 22.sp, fontFamily = display(ar))
                                    Text(if (ar) "ابدأ حديقة جديدة مع أصدقائك" else "Start a new garden with your friends", color = Garden.inkSoft, fontSize = 14.sp)
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                            GardenButton(if (ar) "إنشاء مجموعة" else "Create group", onClick = { sheet = "create" }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    Spacer(Modifier.height(12.dp))

                    // Join: paste the code right here.
                    GlassCard {
                        Column(Modifier.fillMaxWidth().padding(20.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(48.dp).clip(CircleShape).background(Garden.accentSoft), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Outlined.ContentPaste, null, tint = Garden.accentInk, modifier = Modifier.size(22.dp))
                                }
                                Spacer(Modifier.width(16.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(if (ar) "انضم إلى مجموعة" else "Join a group", color = Garden.ink, fontSize = 22.sp, fontFamily = display(ar))
                                    Text(if (ar) "الصق الكود الذي أرسله لك صديقك" else "Paste the code a friend sent you", color = Garden.inkSoft, fontSize = 14.sp)
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CodeField(code, { code = it; joinError = null }, if (ar) "الكود" else "Code", ar, Modifier.weight(1f)) {
                                    code = pasteText(context); joinError = null
                                }
                                Spacer(Modifier.width(8.dp))
                                GardenButton(
                                    if (ar) "انضمام" else "Join",
                                    onClick = {
                                        val incoming = GroupKhatmahCode.decode(code).getOrNull()
                                        val existing = incoming?.let { store.load(it.id) }
                                        when {
                                            incoming == null -> joinError = if (ar) "الكود غير صالح أو ناقص" else "That code is damaged or cut off"
                                            // Already in this group: a friend's newer code just brings their progress in.
                                            existing?.myMemberId != null -> store.importCode(code, null, null).onSuccess { after ->
                                                val lines = newsBetween(existing.group, after.group, existing.myMemberId, ar)
                                                KhatmahNotify.post(context, after.group.name, lines, ar)
                                                pendingNews = when {
                                                    lines.isEmpty() -> if (ar) "حديقتكم محدّثة" else "Your garden is up to date"
                                                    lines.size == 1 -> lines.first()
                                                    else -> lines.first() + (if (ar) " وغيره" else " and more")
                                                }
                                                code = ""; refresh++
                                                handOff { onOpen(after.group.id) }
                                            }
                                            else -> sheet = code.trim()
                                        }
                                    },
                                    enabled = code.isNotBlank(), modifier = Modifier.width(104.dp),
                                )
                            }
                            joinError?.let {
                                Spacer(Modifier.height(8.dp))
                                Text(it, color = Garden.warn, fontSize = 14.sp)
                            }
                        }
                    }

                    if (groups.isNotEmpty()) {
                        Spacer(Modifier.height(24.dp))
                        SectionLabel(if (ar) "مجموعاتك" else "Your groups")
                        Spacer(Modifier.height(10.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            groups.forEach { saved ->
                                val g = saved.group
                                GroupRow(g.name, g.doneCount, g.members.size, ar) { handOff { onOpen(g.id) } }
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }

        sheet?.let { s ->
            val join = s != "create"
            MemberSheet(
                ar = ar, join = join,
                onDismiss = { sheet = null },
                onSubmit = { groupName, myName, avatar ->
                    if (!join) {
                        val created = store.createGroup(groupName, myName, avatar, System.currentTimeMillis())
                        sheet = null; refresh++; handOff { onOpen(created.group.id) }; null
                    } else store.importCode(s, myName, avatar).fold(
                        onSuccess = { joined -> sheet = null; code = ""; refresh++; handOff { onOpen(joined.group.id) }; null },
                        onFailure = { if (ar) "الكود غير صالح أو ناقص" else "That code is damaged or cut off" },
                    )
                },
            )
        }
    }
}

/** First visit: the archway, the name, and Get started. */
@Composable
private fun Welcome(ar: Boolean, onBack: () -> Unit, onStart: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Image(art(R.drawable.khatmah_welcome_bg), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.Center)
        Box(Modifier.fillMaxSize().background(Garden.night))
        // A breath of paper behind the words at the top, so they read on the painted sky.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Garden.paper.copy(alpha = 0.6f), 0.4f to Garden.paper.copy(alpha = 0f))))
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(80.dp))
            Image(art(PLANTS[1]), null, Modifier.size(72.dp))
            Text(if (ar) "ختمة معاً" else "Khatmah together", color = Garden.ink, fontSize = 44.sp, lineHeight = 54.sp, fontFamily = display(ar), textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text(
                if (ar) "نزرع معاً،\nونختم معاً" else "Grow together.\nComplete together.",
                color = Garden.ink.copy(alpha = 0.8f), fontSize = 18.sp, lineHeight = 26.sp, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.weight(1f))
            val source = remember { MutableInteractionSource() }
            val pressed by source.collectIsPressedAsState()
            Row(
                Modifier.scale(if (pressed) 0.98f else 1f).height(60.dp).clip(RoundedCornerShape(30.dp)).background(Garden.glass)
                    .clickable(interactionSource = source, indication = null, onClick = onStart).padding(horizontal = 36.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (ar) "ابدأ" else "Get started", color = Garden.ink, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(10.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = Garden.ink, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.height(20.dp))
            Image(art(R.drawable.khatmah_divider), null, Modifier.width(120.dp), contentScale = ContentScale.Fit)
            Spacer(Modifier.height(28.dp))
        }
        TopBar(ar, onBack, Modifier.statusBarsPadding())
    }
}

@Composable
private fun GroupRow(name: String, done: Int, members: Int, ar: Boolean, onClick: () -> Unit) {
    DepthCard(onClick = onClick) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(Garden.accentSoft), contentAlignment = Alignment.Center) {
                Image(art(PLANTS[stageFor(done)]), null, Modifier.size(44.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(name, color = Garden.ink, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (ar) "${membersLabel(members, true)} · أُتمّ $done من ٣٠" else "${membersLabel(members, false)} · $done of 30 finished", color = Garden.inkSoft, fontSize = 14.sp)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Garden.inkMuted, modifier = Modifier.size(24.dp))
        }
    }
}

/* ================================================================ one group: tabs */

/** One group: Garden / Friends / Stats on a floating dock, the juz page and the completion moment. */
@Composable
fun GroupKhatmahDetailScreen(lang: String, groupId: String, onBack: () -> Unit, onOpenJuz: (Int) -> Unit = {}) {
    val ar = lang == "ar"
    val context = LocalContext.current
    val store = remember { KhatmahStore(context) }
    var saved by remember(groupId) { mutableStateOf(store.load(groupId)) }
    var tab by rememberSaveable { mutableStateOf(0) }
    var copied by remember { mutableStateOf(false) }
    var openJuz by rememberSaveable { mutableStateOf<Int?>(null) }
    var celebrate by remember { mutableStateOf<Int?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmEnd by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var choosing by remember { mutableStateOf(false) }
    var banner by remember { mutableStateOf(pendingNews.also { pendingNews = null }) }

    val current = saved
    if (current == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val group = current.group
    val names = group.displayNames()
    val me = current.myMemberId
    fun share() { store.shareCode(groupId)?.let { copyCode(context, it); copied = true } }
    // Live sync while the group is open: now, every 20 s, and right after I change a juz.
    var syncNow by remember { mutableStateOf(0) }
    LaunchedEffect(groupId) {
        io.github.sammynashed.emran.data.khatmah.KhatmahSync.schedule(context)
        while (true) {
            io.github.sammynashed.emran.data.khatmah.KhatmahSync.sync(context, groupId)?.let { c ->
                if (c.ended) { khatmahEnded(context, c.before.name, ar); saved = null; return@let }
                if (c.after != c.before) {
                    saved = store.load(groupId)
                    khatmahNews(context, c, ar, notify = false)?.let { banner = it }
                }
            }
            kotlinx.coroutines.withTimeoutOrNull(20_000) { androidx.compose.runtime.snapshotFlow { syncNow }.first { it > 0 } }
            syncNow = 0
        }
    }
    fun set(juz: Int, state: JuzState) {
        store.setState(groupId, juz, state, System.currentTimeMillis())
        saved = store.load(groupId); copied = false
        syncNow++
    }

    PaperPage(onBack, toPaper = true) { leave, _ ->
        // Back from Friends or Stats returns to the Garden first (registered after the page's own exit, so it wins).
        BackHandler(enabled = tab != 0 && openJuz == null && celebrate == null) { tab = 0 }
        // The tabs are recorded into a layer that Emran's liquid-glass dock bends through its lens, exactly as it
        // bends the sky on Home, Qur'an and Settings (the glass just draws whatever layer it is given).
        val gardenLayer = rememberGraphicsLayer()
        var rootSize by remember { mutableStateOf(Size.Zero) }
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        Box(Modifier.fillMaxSize().onSizeChanged { rootSize = it.toSize() }) {
            Box(Modifier.fillMaxSize().drawWithContent {
                gardenLayer.record { this@drawWithContent.drawContent() }
                drawLayer(gardenLayer)
            }) {
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    // Slide the way the dock moved: the next tab comes in from its side, the old one eases away.
                    val forward = (targetState > initialState) != rtl
                    (slideInHorizontally(tween(380, easing = Ease)) { w -> if (forward) w / 5 else -w / 5 } + fadeIn(tween(300, easing = Ease)))
                        .togetherWith(slideOutHorizontally(tween(320, easing = Ease)) { w -> if (forward) -w / 8 else w / 8 } + fadeOut(tween(200)))
                },
                label = "tab",
            ) { t ->
                when (t) {
                    0 -> GardenTab(group, me, ar, copied, leave, onShare = { share() }, onPick = { openJuz = it }, onChoose = { choosing = true })
                    1 -> FriendsTab(group, names, me, ar, copied, leave, onShare = { share() }, onLeave = { confirmLeave = true }, isOwner = store.isOwner(groupId), onEnd = { confirmEnd = true })
                    else -> StatsTab(group, me, ar, leave)
                }
            }
            }
            val sky = LocalSky.current
            CompositionLocalProvider(
                LocalSkyLayer provides gardenLayer,
                LocalSkySize provides rootSize,
                LocalSky provides sky.copy(darkInk = if (gardenDark) 0f else 1f),
                // The garden's own glass: an olive-cream film, olive ink and a warm shadow instead of the sky's navy.
                LocalGlassTint provides if (gardenDark) GlassTint(
                    frost = Color(0xFF1F2A18).copy(alpha = 0.62f), ink = Color(0xFFE4EDCF), inkSoft = Color(0xFFB4BE9C),
                    shadow = Color(0xFF070A05).copy(alpha = 0.4f),
                ) else GlassTint(
                    frost = Color(0xFFEEF0DA).copy(alpha = 0.6f), ink = Color(0xFF3E5530), inkSoft = Color(0xFF6E7660),
                    shadow = Color(0xFF2F3A1F).copy(alpha = 0.26f),
                ),
            ) {
                GlassDock(
                    tabs = listOf(
                        DockTab("0", if (ar) "الحديقة" else "Garden", Icons.Outlined.LocalFlorist, Icons.Filled.LocalFlorist),
                        DockTab("1", if (ar) "الأصدقاء" else "Friends", Icons.Outlined.Group, Icons.Filled.Group),
                        DockTab("2", if (ar) "الإحصائيات" else "Stats", Icons.Outlined.BarChart, Icons.Filled.BarChart),
                    ),
                    selectedKey = tab.toString(),
                    onSelect = { tab = it.toInt() },
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
                )
            }
        }

        AnimatedVisibility(
            visible = openJuz != null,
            enter = fadeIn(tween(220, easing = Ease)) + slideInVertically(tween(320, easing = Ease)) { it / 12 },
            exit = fadeOut(tween(160)) + slideOutVertically(tween(200)) { it / 16 },
        ) {
            val juz = openJuz ?: 1
            JuzPage(
                juz = juz, group = group, names = names, me = me, ar = ar,
                onBack = { openJuz = null },
                onRead = { onOpenJuz(juz) },
                onSet = { state ->
                    set(juz, state)
                    if (state == JuzState.DONE) { openJuz = null; celebrate = juz }
                },
            )
        }

        AnimatedVisibility(visible = celebrate != null, enter = fadeIn(tween(260, easing = Ease)), exit = fadeOut(tween(180, easing = Ease))) {
            Celebration(
                juz = celebrate ?: 1, complete = group.isComplete, ar = ar, copied = copied,
                onShare = { share() }, onClose = { celebrate = null },
            )
        }

        if (choosing && me != null) {
            JuzPickerSheet(
                group = group, names = names, me = me, ar = ar,
                onDismiss = { choosing = false },
                onConfirm = { take, release ->
                    val now = System.currentTimeMillis()
                    take.forEach { store.setState(groupId, it, JuzState.READING, now) }
                    release.forEach { store.setState(groupId, it, JuzState.NONE, now) }
                    saved = store.load(groupId)
                    choosing = false
                    if (store.syncKey(groupId) != null) {
                        syncNow++  // the lock reaches friends by itself
                        banner = if (ar) "حُجزت أجزاؤك" else "Your juz are kept for you"
                    } else {
                        // An old group without sync: the lock only reaches friends with the new code.
                        share()
                        banner = if (ar) "حُجزت أجزاؤك. تم نسخ الكود، أرسله لأصدقائك" else "Your juz are kept for you. Code copied, send it to your friends"
                    }
                },
            )
        }

        banner?.let { text -> NewsBanner(text) { banner = null } }

        if (confirmLeave) {
            ConfirmSheet(
                ar = ar,
                title = if (ar) "مغادرة «${group.name}»؟" else "Leave “${group.name}”?",
                body = if (ar) "ينسحب منها، ويُفكّ حجز أجزائك التي تقرؤها. الأجزاء التي أتممتها تبقى باسمك عند أصدقائك." else "You leave. The juz you are reading are released for others; the juz you finished stay in the garden with your name.",
                confirm = if (ar) "مغادرة" else "Leave",
                cancel = if (ar) "إلغاء" else "Cancel",
                onConfirm = {
                    confirmLeave = false
                    scope.launch {
                        if (io.github.sammynashed.emran.data.khatmah.KhatmahSync.leave(context, groupId)) leave()
                        else banner = if (ar) "لا يوجد اتصال. حاول مرة أخرى" else "No connection. Try again"
                    }
                },
                onDismiss = { confirmLeave = false },
            )
        }
        if (confirmEnd) {
            ConfirmSheet(
                ar = ar,
                title = if (ar) "إنهاء «${group.name}» للجميع؟" else "End “${group.name}” for everyone?",
                body = if (ar) "تُحذف المجموعة وتقدّمها من عند كل الأصدقاء، ولا يمكن التراجع." else "The group and everyone's progress are deleted, for everyone. This cannot be undone.",
                confirm = if (ar) "إنهاء للجميع" else "End for everyone",
                cancel = if (ar) "إلغاء" else "Cancel",
                onConfirm = {
                    confirmEnd = false
                    scope.launch {
                        if (io.github.sammynashed.emran.data.khatmah.KhatmahSync.end(context, groupId)) leave()
                        else banner = if (ar) "تعذّر الإنهاء. تأكّد من الاتصال" else "Couldn't end it. Check the connection"
                    }
                },
                onDismiss = { confirmEnd = false },
            )
        }
    }
}

/** Room the floating dock takes at the bottom (its height, margin and the gesture bar). */
@Composable
private fun dockRoom(): Dp = 104.dp + with(LocalDensity.current) { WindowInsets.navigationBars.getBottom(this).toDp() }

/** A steady pseudo-random 0..1 per juz and salt, so the meadow looks planted by hand yet never moves around. */
private fun scatter(juz: Int, salt: Int): Float = (((juz * 73856093) xor (salt * 19349663)) and 0xFFFF) / 65535f

/**
 * The garden itself, one screen: the painted meadow fills the phone and the 30 juz grow in its grass, planted a
 * little unevenly, smaller toward the horizon, swaying in a light wind. A small chip on the sky tells the progress;
 * a small pill above the dock leads to my next juz.
 */
@Composable
private fun GardenTab(
    group: GroupKhatmah, me: String?, ar: Boolean, copied: Boolean, onBack: () -> Unit, onShare: () -> Unit, onPick: (Int) -> Unit,
    /** My own khatmah (no friends, no dock): the garden grows from my reading in the muṣḥaf. */
    personal: Boolean = false,
    /** Opens the list for choosing which juz I'll read (groups only). */
    onChoose: (() -> Unit)? = null,
) {
    val room = if (personal) 24.dp + with(LocalDensity.current) { WindowInsets.navigationBars.getBottom(this).toDp() } else dockRoom()
    val wind = rememberInfiniteTransition(label = "wind")
    val gust = wind.animateFloat(0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(7000, easing = LinearEasing), RepeatMode.Restart), label = "gust")
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth
        val h = maxHeight
        Image(art(R.drawable.khatmah_garden_bg), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = BiasAlignment(0f, 0.2f))
        Box(Modifier.fillMaxSize().background(Garden.night))

        val mineReading = me?.let { group.readingBy(it) }
        val nextFree = (1..GroupKhatmah.JUZ_COUNT).firstOrNull { group.state(it) == JuzState.NONE }
        val target = mineReading ?: nextFree

        // The meadow: five loose rows of six on the lawn only (below the gazebo's steps), far (small) to near.
        // Offsets follow the layout direction by themselves, so juz 1 starts on the right in Arabic.
        val farY = h * 0.665f
        val nearY = h - room - 30.dp
        for (r in 0 until 5) {
            val t = r / 4f
            val rowBase = farY + (nearY - farY) * t
            val rowGap = (nearY - farY) / 4f
            val scale = 0.62f + 0.4f * t
            val rowW = w * (0.66f + 0.32f * t)
            val left = (w - rowW) / 2
            for (i in 0 until 6) {
                val juz = r * 6 + i + 1
                val cx = left + rowW * ((i + 0.5f) / 6f) + rowW / 6f * (scatter(juz, 1) - 0.5f) * 0.4f
                val baseline = rowBase + rowGap * (scatter(juz, 2) - 0.5f) * 0.3f
                val k = scale * (0.92f + 0.16f * scatter(juz, 3))
                val mark = group.marks[juz]
                val who = mark?.takeIf { it.state != JuzState.NONE }?.let { group.members[it.by] }
                MeadowPlant(
                    juz = juz, state = group.state(juz), avatar = if (personal) null else who?.avatar, scale = k, wind = gust, phase = juz * 0.9f,
                    modifier = Modifier.offset(x = cx - 36.dp, y = baseline - 60.dp * k).growIn(juz),
                ) { onPick(juz) }
            }
        }

        // Sky: the bar, the group's own name, one quiet line and a thin line of progress.
        Column(Modifier.fillMaxWidth().statusBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            TopBar(ar, onBack) {
                if (!personal) {
                    MemberStack(group.members.values.toList())
                    Spacer(Modifier.width(8.dp))
                    GardenIconButton(if (copied) Icons.Filled.Check else Icons.Filled.Add, if (ar) "دعوة صديق" else "Invite a friend", onClick = onShare)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                group.name, color = Garden.ink, fontSize = 38.sp, lineHeight = 46.sp, fontFamily = display(ar),
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp),
                style = TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Garden.paper.copy(alpha = 0.8f), blurRadius = 18f)),
            )
            Spacer(Modifier.height(6.dp))
            val left = GroupKhatmah.JUZ_COUNT - group.doneCount
            Text(
                when {
                    group.isComplete -> if (ar) "اكتملت الختمة بحمد الله" else "Khatmah complete, alhamdulillah"
                    ar -> "${finishedLine(group.doneCount, true)} · بقي ${juzLabel(left, true)}"
                    else -> "${finishedLine(group.doneCount, false)} · $left left"
                },
                color = Garden.ink.copy(alpha = 0.78f), fontSize = 15.sp,
                style = TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Garden.paper.copy(alpha = 0.9f), blurRadius = 14f)),
            )
            Spacer(Modifier.height(12.dp))
            val fill = remember { Animatable(0f) }
            val goal = group.doneCount / GroupKhatmah.JUZ_COUNT.toFloat()
            LaunchedEffect(goal) { delay(200); fill.animateTo(goal, tween(900, easing = Ease)) }
            Box(Modifier.width(168.dp).height(4.dp).clip(CircleShape).background(Garden.ink.copy(alpha = 0.14f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(fill.value).clip(CircleShape).background(Garden.accent))
            }
        // One action on the sky. Personal: carry on reading. Group: finish my juz, or choose which juz I'll read.
        if (me != null) {
            Spacer(Modifier.height(18.dp))
            val personalNext = if (personal) ((1..GroupKhatmah.JUZ_COUNT).firstOrNull { group.state(it) == JuzState.READING } ?: nextFree) else null
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    personal && personalNext != null -> SkyPill(
                        plant = if (group.state(personalNext) == JuzState.READING) PLANTS[2] else PLANTS[0],
                        label = if (ar) "تابع الجزء $personalNext" else "Continue juz $personalNext",
                    ) { onPick(personalNext) }
                    !personal && mineReading != null -> {
                        SkyPill(PLANTS[2], if (ar) "أكمل الجزء $mineReading" else "Finish juz $mineReading") { onPick(mineReading) }
                        if (onChoose != null) {
                            Spacer(Modifier.width(8.dp))
                            GardenIconButton(Icons.Outlined.Group, if (ar) "اختر أجزاءك" else "Choose your juz", onClick = onChoose)
                        }
                    }
                    !personal && onChoose != null && nextFree != null ->
                        SkyPill(PLANTS[0], if (ar) "اختر أجزاءك" else "Choose your juz", onClick = onChoose)
                }
            }
        }
        }
    }
}

/** The one action on the garden's sky: a soft pill with a small plant and a label. */
@Composable
private fun SkyPill(plant: Int, label: String, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 0.96f else 1f, tween(140, easing = Ease), label = "pill")
    Row(
        Modifier.height(48.dp).scale(press)
            .shadow(10.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.15f), spotColor = Color.Black.copy(alpha = 0.15f))
            .clip(CircleShape).background(Garden.glass).border(1.dp, Garden.hairline, CircleShape)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(start = 8.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(Garden.accentSoft), contentAlignment = Alignment.Center) {
            Image(art(plant), null, Modifier.size(30.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(label, color = Garden.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(6.dp))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Garden.inkSoft, modifier = Modifier.size(20.dp))
    }
}

/**
 * One juz in the meadow: its plant at the row's size, standing in a soft contact shadow, swaying a little; the face
 * of whoever is reading it now; its number as quiet text with a paper halo (green once finished).
 */
@Composable
private fun MeadowPlant(juz: Int, state: JuzState, avatar: Int?, scale: Float, wind: androidx.compose.runtime.State<Float>, phase: Float, modifier: Modifier, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 0.9f else 1f, tween(140, easing = Ease), label = "plant")
    val sways = state != JuzState.NONE
    val shade = Color(0xFF26301A)
    Column(
        modifier.width(72.dp).clickable(interactionSource = source, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(60.dp * scale), contentAlignment = Alignment.BottomCenter) {
            // Contact shadow: the plant stands in the grass, not on top of the picture.
            Canvas(Modifier.width(54.dp * scale).height(12.dp * scale).offset(y = 3.dp * scale)) {
                drawOval(Brush.radialGradient(listOf(shade.copy(alpha = 0.42f), shade.copy(alpha = 0f)), center = center, radius = size.width / 2f))
            }
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    scaleX = press; scaleY = press
                    // A light wind: each plant leans a degree or two on its own beat, pivoting at the soil.
                    rotationZ = if (sways) kotlin.math.sin(wind.value + phase) * 1.8f * scale else 0f
                    transformOrigin = TransformOrigin(0.5f, 1f)
                },
                contentAlignment = Alignment.BottomCenter,
            ) {
                // Grows only when its state changes (a seed sprouting, a plant flowering); on first draw it just is.
                val grow = remember { Animatable(1f) }
                var shown by remember { mutableStateOf(state) }
                LaunchedEffect(state) {
                    if (state != shown) { shown = state; grow.snapTo(0.4f); grow.animateTo(1f, tween(620, easing = Ease)) }
                }
                Box(
                    Modifier.fillMaxSize().graphicsLayer { scaleX = grow.value; scaleY = grow.value; alpha = 0.3f + 0.7f * ((grow.value - 0.4f) / 0.6f); transformOrigin = TransformOrigin(0.5f, 1f) },
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    when (shown) {
                        JuzState.NONE -> Image(art(R.drawable.khatmah_seed), null, Modifier.width(46.dp * scale).height(19.dp * scale))
                        JuzState.READING -> Image(art(PLANTS[2]), null, Modifier.size(60.dp * scale))
                        JuzState.DONE -> Image(art(PLANTS[3]), null, Modifier.size(60.dp * scale))
                    }
                }
            }
            if (avatar != null && state == JuzState.READING) {
                Box(Modifier.align(Alignment.TopEnd)) { Mascot(avatar, 22.dp * scale, ring = true, ringColor = Garden.onSoil, ringWidth = 1.5.dp) }
            }
        }
        Text(
            juz.toString(),
            color = if (state == JuzState.DONE) Garden.accentInk else Garden.ink.copy(alpha = if (state == JuzState.NONE) 0.72f else 0.95f),
            fontSize = (10f + 4f * scale).sp, fontWeight = if (state == JuzState.NONE) FontWeight.Normal else FontWeight.Medium,
            style = TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Garden.paper, blurRadius = 10f)),
        )
    }
}

/** Friends: invite, and every member with what they finished and what they're reading. One screen. */
@Composable
private fun FriendsTab(group: GroupKhatmah, names: Map<String, String>, me: String?, ar: Boolean, copied: Boolean, onBack: () -> Unit, onShare: () -> Unit, onLeave: () -> Unit, isOwner: Boolean = false, onEnd: () -> Unit = {}) {
    val room = dockRoom()
    Box(Modifier.fillMaxSize()) {
        Painting(R.drawable.khatmah_garden_bg, veil = 0.72f, bias = 0.4f)
        OneScreen(Modifier.statusBarsPadding().padding(bottom = room)) {
            TopBar(ar, onBack)
            Column(Modifier.padding(horizontal = 24.dp)) {
                Text(if (ar) "الأصدقاء" else "Friends", color = Garden.ink, fontSize = 32.sp, fontFamily = display(ar))
                Text(group.name, color = Garden.inkSoft, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(16.dp))
                GlassCard {
                    Column(Modifier.fillMaxWidth().padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MascotCluster(group.members.values.map { it.avatar }.take(3).let { if (it.size < 3) listOf(1, 6, 7) else it }, small = true)
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(if (ar) "ادعُ أصدقاءك" else "Invite friends", color = Garden.ink, fontSize = 22.sp, fontFamily = display(ar))
                                Text(
                                    if (copied) (if (ar) "تم نسخ الكود، أرسله لهم" else "Code copied, send it to them")
                                    else (if (ar) "أرسله لمن تريد دعوته، مرة واحدة فقط" else "Send it once to invite someone"),
                                    color = Garden.inkSoft, fontSize = 14.sp,
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        GardenButton(
                            if (copied) (if (ar) "تم النسخ" else "Copied") else (if (ar) "نسخ كود الدعوة" else "Copy invite code"),
                            onClick = onShare, icon = if (copied) Icons.Filled.Check else Icons.Outlined.ContentCopy, modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                // News: what friends finished lately, one line per friend per day.
                val news = remember(group) { recentNews(group, ar).take(3) }
                if (news.isNotEmpty()) {
                    Spacer(Modifier.height(20.dp))
                    SectionLabel(if (ar) "آخر الأخبار" else "Latest")
                    Spacer(Modifier.height(8.dp))
                    GlassCard {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                            news.forEach { (m, text, at) ->
                                Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (m != null) Mascot(m.avatar, 30.dp)
                                    Spacer(Modifier.width(10.dp))
                                    Text(text, color = Garden.ink, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 2)
                                    Spacer(Modifier.width(8.dp))
                                    Text(whenLabel(at, ar).substringBefore(if (ar) "،" else ","), color = Garden.inkMuted, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                SectionLabel(if (ar) "الترتيب (${group.members.size})" else "Leaderboard (${group.members.size})")
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    group.members.values.sortedWith(compareByDescending<Member> { group.doneBy(it.id) }.thenBy { it.name }).forEachIndexed { rank, m ->
                        val done = group.doneBy(m.id)
                        val reading = group.readingBy(m.id)
                        val label = names[m.id] ?: m.name
                        GlassCard {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                // Rank: the first three get warm metal tones, the rest a quiet number.
                                val medal = when (rank) { 0 -> Color(0xFFC9A24B); 1 -> Color(0xFFA7ABB0); 2 -> Color(0xFFB4835A); else -> null }
                                Box(
                                    Modifier.size(28.dp).clip(CircleShape).background(medal?.copy(alpha = 0.9f) ?: Garden.ink.copy(alpha = 0.06f)),
                                    contentAlignment = Alignment.Center,
                                ) { Text("${rank + 1}", color = if (medal != null) Color.White else Garden.inkSoft, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
                                Spacer(Modifier.width(10.dp))
                                Mascot(m.avatar, 46.dp, ring = m.id == me)
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        if (m.id == me) (if (ar) "$label (أنت)" else "$label (you)") else label,
                                        color = Garden.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        if (reading != null) (if (ar) "يقرأ الجزء $reading" else "Reading juz $reading") else (if (ar) "لا يقرأ جزءاً الآن" else "Not reading now"),
                                        color = Garden.inkSoft, fontSize = 13.sp,
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("$done", color = Garden.ink, fontSize = 24.sp, fontFamily = display(ar))
                                    Text(if (ar) "أتمّ" else "finished", color = Garden.inkMuted, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        if (ar) "مغادرة المجموعة" else "Leave this group",
                        color = Garden.warn, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onLeave).padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
                if (isOwner) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        if (ar) "إنهاء المجموعة للجميع" else "End this group for everyone",
                        color = Garden.warn, fontSize = 14.sp,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onEnd).padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/** Stats on one screen: you or the group, a ring, the week's trend, and where every juz stands. */
@Composable
private fun StatsTab(group: GroupKhatmah, me: String?, ar: Boolean, onBack: () -> Unit) {
    var mine by rememberSaveable { mutableStateOf(me != null) }
    val room = dockRoom()
    Box(Modifier.fillMaxSize()) {
        Painting(R.drawable.khatmah_garden_bg, veil = 0.78f, bias = -0.6f)
        OneScreen(Modifier.statusBarsPadding().padding(bottom = room)) {
            TopBar(ar, onBack)
            Column(Modifier.padding(horizontal = 24.dp)) {
                Text(if (ar) "الإحصائيات" else "Statistics", color = Garden.ink, fontSize = 32.sp, fontFamily = display(ar))
                Spacer(Modifier.height(12.dp))
                if (me != null) {
                    Segmented(listOf(if (ar) "أنت" else "You", if (ar) "المجموعة" else "Group"), if (mine) 0 else 1) { mine = it == 0 }
                    Spacer(Modifier.height(12.dp))
                }
                val who = if (mine) me else null
                val done = if (who != null) group.doneBy(who) else group.doneCount
                GlassCard {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        ProgressRing(done, GroupKhatmah.JUZ_COUNT, ar, 84.dp, 8.dp, 20.sp)
                        Spacer(Modifier.width(20.dp))
                        Column {
                            Text(if (who != null) (if (ar) "تقدّمك" else "Your progress") else (if (ar) "تقدّم المجموعة" else "Group progress"), color = Garden.inkSoft, fontSize = 14.sp)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("$done / ${GroupKhatmah.JUZ_COUNT}", color = Garden.ink, fontSize = 30.sp, fontFamily = display(ar))
                                Spacer(Modifier.width(8.dp))
                                Image(art(PLANTS[1]), null, Modifier.size(26.dp))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                GlassCard {
                    Column(Modifier.padding(16.dp)) {
                        Text(if (ar) "مسار الإتمام" else "Completion trend", color = Garden.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text(if (ar) "الأجزاء المُتمّة خلال آخر ٧ أيام" else "Finished juz over the last 7 days", color = Garden.inkSoft, fontSize = 13.sp)
                        Spacer(Modifier.height(12.dp))
                        TrendChart(group, who, ar)
                    }
                }
                Spacer(Modifier.height(12.dp))
                GlassCard {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        val reading = group.readingCount
                        val notStarted = GroupKhatmah.JUZ_COUNT - group.doneCount - reading
                        Donut(listOf(group.doneCount to Garden.accent, reading to Garden.clay, notStarted to Garden.ink.copy(alpha = 0.14f)), 84.dp)
                        Spacer(Modifier.width(20.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(if (ar) "حسب الحالة" else "By status", color = Garden.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            LegendRow(Garden.accent, if (ar) "أُتمّ" else "Completed", group.doneCount)
                            LegendRow(Garden.clay, if (ar) "قيد القراءة" else "In progress", reading)
                            LegendRow(Garden.ink.copy(alpha = 0.2f), if (ar) "لم يبدأ" else "Not started", notStarted)
                        }
                    }
                }
            }
        }
    }
}

/** Cumulative finished juz per day for the last 7 days (all members, or one). Follows the reading direction. */
@Composable
private fun TrendChart(group: GroupKhatmah, who: String?, ar: Boolean) {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now()
    val days = (6 downTo 0).map { today.minusDays(it.toLong()) }
    val doneAt = group.marks.values.filter { it.done && (who == null || it.by == who) }.map { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() }
    val values = days.map { d -> doneAt.count { !it.isAfter(d) } }
    val top = (values.maxOrNull() ?: 0).coerceAtLeast(4)
    val fmt = DateTimeFormatter.ofPattern("d MMM", Locale(if (ar) "ar" else "en"))
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val line = Garden.accent
    val grid = Garden.ink.copy(alpha = 0.07f)
    val dot = Garden.card
    Canvas(Modifier.fillMaxWidth().height(88.dp)) {
        val w = size.width; val h = size.height
        for (i in 0..2) drawLine(grid, Offset(0f, h * i / 2f), Offset(w, h * i / 2f), 1.dp.toPx())
        val pts = values.mapIndexed { i, v -> Offset(if (rtl) w - w * i / 6f else w * i / 6f, h - h * v / top.toFloat()) }
        val shown = pts.lastIndex
        val path = Path()
        val area = Path()
        for (i in 0..shown) {
            val p = pts[i]
            if (i == 0) { path.moveTo(p.x, p.y); area.moveTo(p.x, h); area.lineTo(p.x, p.y) } else { path.lineTo(p.x, p.y); area.lineTo(p.x, p.y) }
        }
        area.lineTo(pts[shown].x, h); area.close()
        drawPath(area, line.copy(alpha = 0.12f))
        drawPath(path, line, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
        for (i in 0..shown) { drawCircle(dot, 5.dp.toPx(), pts[i]); drawCircle(line, 3.5.dp.toPx(), pts[i]) }
    }
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        days.filterIndexed { i, _ -> i % 2 == 0 }.forEach { Text(fmt.format(it), color = Garden.inkMuted, fontSize = 11.sp) }
    }
}

@Composable
private fun Donut(parts: List<Pair<Int, Color>>, size: Dp) {
    val total = parts.sumOf { it.first }.coerceAtLeast(1)
    Canvas(Modifier.size(size)) {
        val stroke = 14.dp.toPx()
        val inset = stroke / 2
        val arc = Size(this.size.width - stroke, this.size.height - stroke)
        var start = -90f
        parts.forEach { (n, c) ->
            val sweep = 360f * n / total
            if (sweep > 0f) drawArc(c, start, sweep, false, Offset(inset, inset), arc, style = Stroke(stroke))
            start += sweep
        }
    }
}

@Composable
private fun LegendRow(color: Color, label: String, n: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(10.dp))
        Text(label, color = Garden.inkSoft, fontSize = 14.sp, modifier = Modifier.width(96.dp))
        Text("$n", color = Garden.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(23.dp)).background(Garden.glass).padding(4.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(19.dp)).background(if (on) Garden.accentSoft else Color.Transparent)
                    .clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) { Text(label, color = if (on) Garden.accentInk else Garden.inkSoft, fontSize = 15.sp, fontWeight = if (on) FontWeight.Medium else FontWeight.Normal) }
        }
    }
}

/* ================================================================ juz page + completion */

/** One juz, one screen: the plant in its halo, where it stands, the one action, and its history. */
@Composable
private fun JuzPage(juz: Int, group: GroupKhatmah, names: Map<String, String>, me: String?, ar: Boolean, onBack: () -> Unit, onRead: () -> Unit, onSet: (JuzState) -> Unit) {
    BackHandler(onBack = onBack)
    val mark = group.marks[juz]
    val state = group.state(juz)
    val byMe = mark?.by == me
    val who = mark?.let { names[it.by] }
    Box(Modifier.fillMaxSize().background(Garden.paper).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}) {
        Painting(R.drawable.khatmah_garden_bg, veil = 0.8f, bias = 0.9f)
        OneScreen(Modifier.statusBarsPadding().navigationBarsPadding()) {
            Box(Modifier.fillMaxWidth()) {
                TopBar(ar, onBack)
                Text(
                    if (ar) "الجزء $juz" else "Juz $juz", color = Garden.ink, fontSize = 28.sp, fontFamily = display(ar),
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(260.dp).clip(CircleShape).background(Brush.radialGradient(listOf(Garden.accentSoft, Garden.accentSoft.copy(alpha = 0.5f), Color.Transparent))))
                AnimatedContent(
                    targetState = state,
                    transitionSpec = {
                        (fadeIn(tween(300, easing = Ease)) + scaleIn(tween(600, easing = Ease), initialScale = 0.7f, transformOrigin = TransformOrigin(0.5f, 1f)))
                            .togetherWith(fadeOut(tween(160)))
                    },
                    label = "juzPlant",
                ) { s ->
                    when (s) {
                        JuzState.NONE -> Image(art(R.drawable.khatmah_seed), null, Modifier.width(170.dp).height(70.dp).offset(y = 24.dp))
                        JuzState.READING -> Image(painterResource(R.drawable.khatmah_seedling), null, Modifier.width(270.dp).height(170.dp).offset(y = 24.dp))
                        JuzState.DONE -> Image(painterResource(R.drawable.khatmah_flower), null, Modifier.size(240.dp))
                    }
                }
                Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    Image(painterResource(R.drawable.khatmah_corner_bl), null, Modifier.weight(1f).height(84.dp), contentScale = ContentScale.Fit, alignment = Alignment.BottomStart)
                    Spacer(Modifier.width(120.dp))
                    Image(painterResource(R.drawable.khatmah_corner_br), null, Modifier.weight(1f).height(84.dp), contentScale = ContentScale.Fit, alignment = Alignment.BottomEnd)
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val (pill, pillOn) = when (state) {
                    JuzState.NONE -> (if (ar) "لم يبدأ" else "Not started") to false
                    JuzState.READING -> (if (ar) "قيد القراءة" else "In progress") to true
                    JuzState.DONE -> (if (ar) "أُتمّ" else "Completed") to true
                }
                Row(
                    Modifier.clip(CircleShape).background(if (pillOn) Garden.accentSoft else Garden.glass).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (pillOn) { Image(painterResource(if (state == JuzState.DONE) PLANTS[3] else PLANTS[2]), null, Modifier.size(22.dp)); Spacer(Modifier.width(8.dp)) }
                    Text(pill, color = if (pillOn) Garden.accentInk else Garden.inkSoft, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    when {
                        state == JuzState.READING && byMe -> if (ar) "واصل، أنت على الطريق" else "Keep going, you're on track"
                        state == JuzState.READING -> if (ar) "$who يقرأ هذا الجزء" else "$who is reading this juz"
                        state == JuzState.DONE -> if (ar) "أتمّه $who، تقبّل الله" else "Finished by $who, may Allah accept it"
                        else -> if (ar) "لم يأخذه أحد بعد" else "Nobody has taken it yet"
                    },
                    color = Garden.inkSoft, fontSize = 16.sp, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                if (me == null) {
                    Text(if (ar) "انضم إلى المجموعة لتحدّد الأجزاء" else "Join the group to mark juz", color = Garden.inkSoft, fontSize = 15.sp)
                } else when (state) {
                    JuzState.NONE -> GardenButton(if (ar) "سأقرأ هذا الجزء" else "Start reading", onClick = { onSet(JuzState.READING) }, icon = Icons.Outlined.LocalFlorist, modifier = Modifier.fillMaxWidth())
                    JuzState.READING -> if (byMe) {
                        GardenButton(if (ar) "أتممت هذا الجزء" else "Mark as complete", onClick = { onSet(JuzState.DONE) }, icon = Icons.Filled.Check, modifier = Modifier.fillMaxWidth())
                        TextLink(if (ar) "تراجع عن القراءة" else "Stop reading") { onSet(JuzState.NONE) }
                    } else {
                        // Locked: only the one reading it can finish or release it.
                        Row(
                            Modifier.clip(CircleShape).background(Garden.glass).border(1.dp, Garden.hairline, CircleShape).padding(horizontal = 18.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.Lock, null, tint = Garden.inkSoft, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(if (ar) "محجوز لـ$who حتى يُتمّه" else "Kept for $who until they finish it", color = Garden.inkSoft, fontSize = 15.sp)
                        }
                    }
                    JuzState.DONE -> GardenButton(if (ar) "لم يكتمل بعد" else "Mark as not finished", onClick = { onSet(JuzState.NONE) }, primary = false, modifier = Modifier.fillMaxWidth())
                }
                // Straight into the muṣḥaf at this juz's first ayah.
                Spacer(Modifier.height(10.dp))
                GardenButton(
                    if (ar) "افتح الجزء $juz في المصحف" else "Open juz $juz in the muṣḥaf",
                    onClick = onRead, primary = false, icon = Icons.AutoMirrored.Outlined.MenuBook, modifier = Modifier.fillMaxWidth(),
                )
                if (mark != null) {
                    Spacer(Modifier.height(16.dp))
                    GlassCard {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Schedule, null, tint = Garden.inkSoft, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(if (ar) "آخر تغيير" else "History", color = Garden.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                Text("${who ?: ""} · ${whenLabel(mark.at, ar)}", color = Garden.inkSoft, fontSize = 14.sp)
                            }
                            group.members[mark.by]?.let { Mascot(it.avatar, 36.dp) }
                        }
                    }
                }
            }
        }
    }
}

/** A juz was just finished: the flower inside an arch, the du'a, and sharing the new garden. One screen. */
@Composable
private fun Celebration(juz: Int, complete: Boolean, ar: Boolean, copied: Boolean, onShare: () -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val open = remember { Animatable(0f) }
    val words = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { open.animateTo(1f, tween(800, easing = Ease)) }
        delay(300); words.animateTo(1f, tween(450, easing = Ease))
    }
    Box(Modifier.fillMaxSize().background(Garden.paper).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}) {
        Painting(R.drawable.khatmah_welcome_bg, veil = 0.7f)
        OneScreen(Modifier.statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp), center = true) {
            val archFill = Brush.verticalGradient(listOf(Garden.card, Garden.accentSoft))
            val archLine = Garden.gold
            Box(Modifier.width(260.dp).height(310.dp), contentAlignment = Alignment.BottomCenter) {
                Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = open.value }) {
                    val w = size.width; val h = size.height
                    val arch = Path().apply {
                        moveTo(0f, h); lineTo(0f, h * 0.42f)
                        cubicTo(0f, h * 0.16f, w * 0.34f, h * 0.07f, w / 2f, 0f)
                        cubicTo(w * 0.66f, h * 0.07f, w, h * 0.16f, w, h * 0.42f)
                        lineTo(w, h); close()
                    }
                    drawPath(arch, archFill)
                    drawPath(arch, archLine, style = Stroke(1.5.dp.toPx()))
                }
                Image(
                    painterResource(R.drawable.khatmah_flower), null,
                    Modifier.padding(bottom = 22.dp).size(220.dp).graphicsLayer {
                        val k = 0.6f + 0.4f * open.value
                        scaleX = k; scaleY = k; alpha = open.value; transformOrigin = TransformOrigin(0.5f, 0.92f)
                    },
                )
                Row(Modifier.fillMaxWidth().offset(y = 14.dp), verticalAlignment = Alignment.Bottom) {
                    Image(painterResource(R.drawable.khatmah_corner_bl), null, Modifier.weight(1f).height(76.dp), contentScale = ContentScale.Fit, alignment = Alignment.BottomStart)
                    Spacer(Modifier.width(70.dp))
                    Image(painterResource(R.drawable.khatmah_corner_br), null, Modifier.weight(1f).height(76.dp), contentScale = ContentScale.Fit, alignment = Alignment.BottomEnd)
                }
            }
            Spacer(Modifier.height(28.dp))
            Column(
                Modifier.fillMaxWidth().graphicsLayer { alpha = words.value; translationY = (1f - words.value) * 16.dp.toPx() },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    if (complete) (if (ar) "اكتملت الختمة" else "Khatmah complete") else (if (ar) "أتممت الجزء $juz" else "Juz $juz completed"),
                    color = Garden.ink, fontSize = 34.sp, fontFamily = display(ar), textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (complete) (if (ar) "تقبّل الله منكم جميعاً" else "May Allah accept it from all of you") else (if (ar) "تقبّل الله منك" else "May Allah accept it from you"),
                    color = Garden.inkSoft, fontSize = 17.sp, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                GlassCard {
                    Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (ar) "شارك تقدّمك مع أصدقائك" else "Share your progress with your friends", color = Garden.inkSoft, fontSize = 15.sp, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(12.dp))
                        GardenButton(
                            if (copied) (if (ar) "تم نسخ الكود" else "Code copied") else (if (ar) "مشاركة" else "Share"),
                            onClick = onShare, icon = if (copied) Icons.Filled.Check else Icons.Outlined.ContentCopy, modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                TextLink(if (ar) "متابعة" else "Continue", underline = true, onClick = onClose)
            }
        }
    }
}

/* ================================================================ pieces */

/** Set when a garden page leaves for another garden page, so the next one starts on paper (no sky between). */
private var paperHandoff = false

/** A still image of the garden page being left, laid over the next garden page and dissolved there. */
private var handoffShot: androidx.compose.ui.graphics.ImageBitmap? = null

/** Counts garden pages that have drawn their first frame: the page being left waits for the next one's. */
private var paperArrivals by androidx.compose.runtime.mutableIntStateOf(0)

/** Waits (at most a second) until a garden page that opened after [before] has drawn its first frame. */
private suspend fun awaitNextPaper(before: Int) {
    kotlinx.coroutines.withTimeoutOrNull(1000) {
        androidx.compose.runtime.snapshotFlow { paperArrivals }.first { it > before }
    }
}

/**
 * The garden page: picks the light or night look (auto follows Emran's sky), keeps the status-bar icons readable,
 * hides the tab dock while open, and moves the whole page: in with a fade and a small rise, out (Back, or the
 * back button) with a fade and a small settle. Leaving for Emran's sky ([toPaper] false) fades the paper too, so
 * the living sky shows through before the route pops; leaving for another garden page keeps the paper. When the
 * look changes, the change arrives as nightfall (dusk drawn down from the top) or dawn (warm light rising).
 */
@Composable
private fun PaperPage(onBack: () -> Unit, toPaper: Boolean = false, content: @Composable (leave: () -> Unit, handOff: (() -> Unit) -> Unit) -> Unit) {
    val context = LocalContext.current
    GardenLook.load(context)
    val skyNight = LocalSky.current.darkInk < 0.5f
    val want = when (GardenLook.mode) { GardenMode.AUTO -> skyNight; GardenMode.LIGHT -> false; GardenMode.DARK -> true }
    var first by remember { mutableStateOf(true) }
    if (first) { gardenDark = want; first = false } // opening a page: no transition, just the right look
    if (PaperBars.darkIcons == gardenDark) PaperBars.darkIcons = !gardenDark
    val dock = io.github.sammynashed.emran.LocalDockVisible.current
    DisposableEffect(Unit) {
        PaperBars.requests++
        dock.value = false
        onDispose { PaperBars.requests--; dock.value = true }
    }
    val handoff = remember { paperHandoff.also { paperHandoff = false } }
    val motion = remember { Animatable(if (handoff) 1f else 0f) }
    val paper = remember { Animatable(if (handoff) 1f else 0f) }
    LaunchedEffect(Unit) { androidx.compose.runtime.withFrameNanos { }; paperArrivals++ }
    var leaving by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val live = rememberGraphicsLayer()
    // Arriving from another garden page: its still image dissolves over this page.
    val arrivedOver = remember { handoffShot.also { handoffShot = null } }
    val arrive = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        if (arrivedOver != null) launch { androidx.compose.runtime.withFrameNanos { }; arrive.animateTo(0f, tween(340, easing = Ease)) }
        launch { paper.animateTo(1f, tween(420, easing = Ease)) }
        motion.animateTo(1f, tween(if (handoff) 300 else 420, easing = Ease))
    }
    val leave: () -> Unit = {
        if (!leaving) {
            leaving = true
            scope.launch {
                if (toPaper) {
                    // The page beneath is a garden page: it opens on its own paper with this page's still image over
                    // it, dissolving there; nothing in between.
                    handoffShot = runCatching { live.toImageBitmap() }.getOrNull()
                    paperHandoff = true
                    onBack()
                } else {
                    launch { paper.animateTo(0f, tween(260, easing = Ease)) }
                    motion.animateTo(0f, tween(260, easing = Ease))
                    onBack()
                }
            }
        }
    }
    BackHandler(enabled = !leaving, onBack = leave)
    // Opening another garden page: this page's content fades on the paper, then the next one starts on paper.
    val handOff: (() -> Unit) -> Unit = { go ->
        if (!leaving) {
            leaving = true
            scope.launch {
                // The next garden page opens on its own paper with this page's still image over it, dissolving
                // there: one page melting into the next, no empty paper, no sky.
                handoffShot = runCatching { live.toImageBitmap() }.getOrNull()
                paperHandoff = true
                go()
            }
        }
    }

    // Changing the look: a still image of the page as it was is laid over the page, which is already in its new
    // light underneath, and dissolves slowly. Nothing else is drawn over the garden; the garden itself changes.
    var still by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    val dissolve = remember { Animatable(0f) }
    LaunchedEffect(want) {
        if (want != gardenDark) {
            val shot = runCatching { live.toImageBitmap() }.getOrNull()
            still = shot
            dissolve.snapTo(1f)
            gardenDark = want
            if (shot != null) dissolve.animateTo(0f, tween(1100, easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)))
            still = null
        }
    }

    Box(Modifier.fillMaxSize().graphicsLayer { alpha = paper.value }.background(Garden.paper)) {
        Box(
            Modifier.fillMaxSize()
                .drawWithContent {
                    live.record { this@drawWithContent.drawContent() }
                    drawLayer(live)
                }
                .graphicsLayer {
                    alpha = motion.value
                    translationY = (1f - motion.value) * (if (leaving) 18.dp else 12.dp).toPx()
                    val k = if (leaving) 0.97f + 0.03f * motion.value else 1f
                    scaleX = k; scaleY = k
                },
        ) { content(leave, handOff) }
        still?.let { img ->
            Canvas(Modifier.fillMaxSize()) { drawImage(img, alpha = dissolve.value) }
        }
        if (arrivedOver != null && arrive.value > 0f) {
            Canvas(Modifier.fillMaxSize()) { drawImage(arrivedOver, alpha = arrive.value) }
        }
    }
}

/** A painting filling the screen, softened with a wash of paper so cards and text read on it ([veil] 0..1). */
@Composable
private fun Painting(res: Int, veil: Float, bias: Float = 0f) {
    Box(Modifier.fillMaxSize()) {
        Image(art(res), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = BiasAlignment(0f, bias))
        Box(Modifier.fillMaxSize().background(Garden.night))
        Box(Modifier.fillMaxSize().background(Garden.paper.copy(alpha = veil)))
    }
}

/**
 * One screen: the content is laid out to fit. Only when a very large system font makes it taller than the
 * phone does it scroll, so nothing is ever cut off.
 */
@Composable
private fun OneScreen(modifier: Modifier = Modifier, center: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().then(modifier)) {
        val min = maxHeight
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(
                Modifier.fillMaxWidth().then(if (center) Modifier.height(min) else Modifier),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = if (center) Arrangement.Center else Arrangement.Top,
                content = content,
            )
        }
    }
}

/** Back on the start side, any [actions] and the light/dark switch on the end side. */
@Composable
private fun TopBar(ar: Boolean, onBack: () -> Unit, modifier: Modifier = Modifier, actions: @Composable () -> Unit = {}) {
    val context = LocalContext.current
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        GardenIconButton(Icons.AutoMirrored.Filled.ArrowBack, if (ar) "رجوع" else "Back", onClick = onBack)
        Spacer(Modifier.weight(1f))
        actions()
        Spacer(Modifier.width(8.dp))
        LookMenu(ar)
    }
}

/** The look button and its small menu: Auto (follows the sky), Light, Dark, the current one checked. */
@Composable
private fun LookMenu(ar: Boolean) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val icon = when (GardenLook.mode) { GardenMode.AUTO -> Icons.Outlined.BrightnessAuto; GardenMode.LIGHT -> Icons.Outlined.LightMode; GardenMode.DARK -> Icons.Outlined.DarkMode }
    Box {
        GardenIconButton(icon, if (ar) "المظهر" else "Look", onClick = { open = true })
        if (open) {
            val grow = remember { Animatable(0f) }
            LaunchedEffect(Unit) { grow.animateTo(1f, tween(220, easing = Ease)) }
            Popup(
                alignment = Alignment.TopEnd, offset = IntOffset(0, with(LocalDensity.current) { 56.dp.roundToPx() }),
                onDismissRequest = { open = false }, properties = PopupProperties(focusable = true),
            ) {
                Column(
                    Modifier.width(232.dp).graphicsLayer {
                        alpha = grow.value; scaleX = 0.92f + 0.08f * grow.value; scaleY = 0.92f + 0.08f * grow.value
                        transformOrigin = TransformOrigin(if (ar) 0f else 1f, 0f)
                    }.shadow(18.dp, RoundedCornerShape(20.dp), ambientColor = Color.Black.copy(alpha = 0.2f), spotColor = Color.Black.copy(alpha = 0.2f))
                        .clip(RoundedCornerShape(20.dp)).background(Garden.card).border(1.dp, Garden.hairline, RoundedCornerShape(20.dp)).padding(6.dp),
                ) {
                    Text(if (ar) "مظهر الحديقة" else "Garden look", color = Garden.inkMuted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                    listOf(
                        Triple(GardenMode.AUTO, Icons.Outlined.BrightnessAuto, if (ar) "تلقائي مع السماء" else "Auto, follows the sky"),
                        Triple(GardenMode.LIGHT, Icons.Outlined.LightMode, if (ar) "فاتح" else "Light"),
                        Triple(GardenMode.DARK, Icons.Outlined.DarkMode, if (ar) "داكن" else "Dark"),
                    ).forEach { (m, ic, label) ->
                        val on = GardenLook.mode == m
                        Row(
                            Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(14.dp)).background(if (on) Garden.accentSoft else Color.Transparent)
                                .clickable { GardenLook.set(context, m); open = false }.padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(ic, null, tint = if (on) Garden.accentInk else Garden.inkSoft, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(label, color = Garden.ink, fontSize = 15.sp, fontWeight = if (on) FontWeight.Medium else FontWeight.Normal, modifier = Modifier.weight(1f))
                            if (on) Icon(Icons.Filled.Check, null, tint = Garden.accentInk, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Three mascots leaning together: the friends of a garden. */
@Composable
private fun MascotCluster(avatars: List<Int> = listOf(1, 6, 7), small: Boolean = false) {
    val big = if (small) 56.dp else 76.dp
    val side = if (small) 44.dp else 60.dp
    Row(horizontalArrangement = Arrangement.spacedBy((-12).dp), verticalAlignment = Alignment.Bottom) {
        avatars.forEachIndexed { i, a -> Mascot(a, if (avatars.size == 3 && i == 1) big else side, ring = true, ringColor = Garden.card, ringWidth = 2.5.dp) }
    }
}

/** Up to three faces overlapping, then a count. */
@Composable
private fun MemberStack(members: List<Member>) {
    if (members.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy((-10).dp), verticalAlignment = Alignment.CenterVertically) {
        members.take(3).forEach { m -> Mascot(m.avatar, 40.dp, ring = true, ringColor = Garden.card) }
        if (members.size > 3) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Garden.card).border(2.dp, Garden.card, CircleShape), contentAlignment = Alignment.Center) {
                Text("+${members.size - 3}", color = Garden.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun Mascot(avatar: Int, size: Dp, ring: Boolean = false, ringColor: Color = Garden.accent, ringWidth: Dp = 2.dp) {
    Image(
        painterResource(AVATARS[avatar.coerceIn(0, AVATARS.lastIndex)]), null,
        Modifier.size(size).clip(CircleShape).then(if (ring) Modifier.border(ringWidth, ringColor, CircleShape) else Modifier),
    )
}

@Composable
private fun ProgressRing(done: Int, total: Int, ar: Boolean, size: Dp, stroke: Dp, label: TextUnit) {
    val target = done.toFloat() / total.coerceAtLeast(1)
    val ring = remember { Animatable(target) }
    LaunchedEffect(target) { ring.animateTo(target, tween(600, easing = Ease)) }
    val track = Garden.ink.copy(alpha = 0.1f)
    val fill = Garden.accent
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val s = stroke.toPx()
            val inset = s / 2
            val arc = Size(this.size.width - s, this.size.height - s)
            drawArc(track, -90f, 360f, false, Offset(inset, inset), arc, style = Stroke(s))
            if (ring.value > 0f) drawArc(fill, -90f, 360f * ring.value, false, Offset(inset, inset), arc, style = Stroke(s, cap = StrokeCap.Round))
        }
        Text("${100 * done / total.coerceAtLeast(1)}%", color = Garden.ink, fontSize = label, fontFamily = display(ar))
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, color = Garden.ink, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = modifier)
}

/**
 * A card with depth, for the rows you open (your khatmah, your groups): a softly curved body lit from above (a lighter
 * top fading into the card), a bright hairline along the top edge, and a deep soft shadow under it, like a pebble of
 * paper resting on the page.
 */
@Composable
private fun DepthCard(modifier: Modifier = Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 0.97f else 1f, tween(160, easing = Ease), label = "depth")
    val top = if (gardenDark) Color(0xFF2C3325) else Color(0xFFFFFDF7)
    val bottom = if (gardenDark) Color(0xFF1C2117) else Color(0xFFF1EADB)
    Box(
        modifier.fillMaxWidth().scale(press)
            .shadow(if (pressed) 6.dp else 14.dp, shape, ambientColor = Color(0xFF3A3424).copy(alpha = 0.25f), spotColor = Color(0xFF3A3424).copy(alpha = 0.3f))
            .clip(shape)
            .background(Brush.verticalGradient(listOf(top, bottom)))
            .drawBehind {
                // light catching the top edge and a faint inner shade along the bottom: the curve
                drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = if (gardenDark) 0.06f else 0.5f), 0.12f to Color.Transparent))
                drawRect(Brush.verticalGradient(0.82f to Color.Transparent, 1f to Color.Black.copy(alpha = if (gardenDark) 0.18f else 0.05f)))
            }
            .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = if (gardenDark) 0.12f else 0.85f), Garden.hairline)), shape)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
    ) { content() }
}

/** A paper card resting on a painting. */
@Composable
private fun GlassCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxWidth().then(modifier).clip(RoundedCornerShape(22.dp))
            .background(Garden.glass).border(1.dp, Garden.hairline, RoundedCornerShape(22.dp)),
    ) { content() }
}

@Composable
private fun TextLink(label: String, underline: Boolean = false, onClick: () -> Unit) {
    Text(
        label, color = Garden.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium,
        textDecoration = if (underline) TextDecoration.Underline else null,
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
    )
}

@Composable
private fun GardenButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = true, enabled: Boolean = true, icon: ImageVector? = null) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.98f else 1f, tween(140, easing = Ease), label = "btn")
    val shape = RoundedCornerShape(28.dp)
    Row(
        modifier.scale(scale).height(56.dp).clip(shape)
            .background(if (!primary) Garden.glass else if (enabled) Garden.accent else Garden.ink.copy(alpha = 0.12f))
            .then(if (!primary) Modifier.border(1.dp, Garden.ink.copy(alpha = 0.18f), shape) else Modifier)
            .clickable(enabled = enabled, interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (!primary) Garden.ink else if (enabled) Garden.onAccent else Garden.inkMuted
        if (icon != null) { Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
        Text(label, color = tint, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun GardenIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(Garden.glass).border(1.dp, Garden.hairline, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = Garden.ink, modifier = Modifier.size(22.dp)) }
}

/** The code box on the Join card: type or paste; a paste button at its end. */
@Composable
private fun CodeField(value: String, onChange: (String) -> Unit, hint: String, ar: Boolean, modifier: Modifier, onPaste: () -> Unit) {
    Row(
        modifier.height(56.dp).clip(RoundedCornerShape(28.dp)).background(Garden.paper).border(1.dp, Garden.ink.copy(alpha = 0.14f), RoundedCornerShape(28.dp))
            .padding(start = 20.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(hint, color = Garden.inkMuted, fontSize = 16.sp)
            BasicTextField(
                value = value, onValueChange = onChange, singleLine = true,
                textStyle = TextStyle(color = Garden.ink, fontSize = 15.sp), cursorBrush = SolidColor(Garden.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onPaste), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.ContentPaste, if (ar) "لصق" else "Paste", tint = Garden.accentInk, modifier = Modifier.size(20.dp))
        }
    }
}

/** Plants appear one after another when the garden opens (about half a second for all 30). */
private fun Modifier.growIn(index: Int): Modifier = composed {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(160L + index * 16L); a.animateTo(1f, tween(380, easing = Ease)) }
    graphicsLayer {
        alpha = a.value
        val k = 0.85f + 0.15f * a.value
        scaleX = k; scaleY = k
        transformOrigin = TransformOrigin(0.5f, 1f)
    }
}

/* ================================================================ choosing juz, news */

/**
 * Choose which juz I'll read: all 30 at once. Free ones toggle; mine are already chosen (untick to release); a juz
 * someone else is reading is locked with their face; finished ones are done. Confirm keeps the chosen ones for me.
 */
@Composable
private fun JuzPickerSheet(group: GroupKhatmah, names: Map<String, String>, me: String, ar: Boolean, onDismiss: () -> Unit, onConfirm: (take: Set<Int>, release: Set<Int>) -> Unit) {
    val mineNow = remember(group) { (1..GroupKhatmah.JUZ_COUNT).filter { group.state(it) == JuzState.READING && group.marks[it]?.by == me }.toSet() }
    var chosen by remember(group) { mutableStateOf(mineNow) }
    PaperSheet(onDismiss) {
        Text(if (ar) "اختر أجزاءك" else "Choose your juz", color = Garden.ink, fontSize = 28.sp, fontFamily = display(ar))
        Text(
            if (ar) "تُحجز لك ولا يأخذها غيرك حتى تُتمّها" else "They're kept for you until you finish them",
            color = Garden.inkSoft, fontSize = 14.sp,
        )
        Spacer(Modifier.height(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..GroupKhatmah.JUZ_COUNT).chunked(6).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { juz ->
                        val st = group.state(juz)
                        val by = group.marks[juz]?.by
                        val lockedByOther = st == JuzState.READING && by != me
                        val on = juz in chosen
                        val enabled = st == JuzState.NONE || (st == JuzState.READING && by == me)
                        val bg by androidx.compose.animation.animateColorAsState(
                            when { on -> Garden.accent; st == JuzState.DONE -> Garden.accentSoft; else -> Garden.card }, tween(180), label = "chip",
                        )
                        Box(
                            Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(14.dp)).background(bg)
                                .border(1.dp, if (on) Color.Transparent else Garden.hairline, RoundedCornerShape(14.dp))
                                .clickable(enabled = enabled) { chosen = if (on) chosen - juz else chosen + juz },
                            contentAlignment = Alignment.Center,
                        ) {
                            when {
                                lockedByOther -> group.members[by]?.let { Box(Modifier.alpha(0.85f)) { Mascot(it.avatar, 30.dp) } }
                                    ?: Icon(Icons.Outlined.Lock, null, tint = Garden.inkMuted, modifier = Modifier.size(16.dp))
                                st == JuzState.DONE -> Icon(Icons.Filled.Check, null, tint = Garden.accentInk, modifier = Modifier.size(18.dp))
                                else -> Text("$juz", color = if (on) Garden.onAccent else Garden.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        val take = chosen - mineNow
        val release = mineNow - chosen
        GardenButton(
            when {
                take.isEmpty() && release.isEmpty() -> if (ar) "تم" else "Done"
                ar -> "احجز ${juzLabel(chosen.size, true)}"
                else -> "Keep ${chosen.size} juz"
            },
            onClick = { if (take.isEmpty() && release.isEmpty()) onDismiss() else onConfirm(take, release) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** News that arrived with a friend's code, shown once when the garden opens. */
private var pendingNews: String? = null

/** The creator ended the group for everyone: tell me once. */
internal fun khatmahEnded(context: Context, name: String, ar: Boolean) {
    KhatmahNotify.post(context, name, listOf(if (ar) "أنهى صاحب المجموعة الختمة للجميع" else "The creator ended this group for everyone"), ar)
}

/** A sync brought friends' progress in: tell me (notification) and return the banner line, or null if nothing new. */
internal fun khatmahNews(context: Context, change: io.github.sammynashed.emran.data.khatmah.KhatmahSync.Change, ar: Boolean, notify: Boolean = true): String? {
    val lines = newsBetween(change.before, change.after, change.me, ar)
    if (lines.isEmpty()) return null
    if (notify) KhatmahNotify.post(context, change.after.name, lines, ar)
    return if (lines.size == 1) lines.first() else lines.first() + (if (ar) " وغيره" else " and more")
}

/** "Ahmad finished juz 1, 2, 3", per friend: what friends finished in [after] that wasn't finished in [before]. */
private fun newsBetween(before: GroupKhatmah?, after: GroupKhatmah, me: String?, ar: Boolean): List<String> {
    val names = after.displayNames()
    return after.marks.filter { (juz, m) -> m.done && m.by != me && before?.isDone(juz) != true }
        .entries.groupBy({ it.value.by }, { it.key })
        .map { (by, juz) ->
            val who = names[by] ?: (if (ar) "صديق" else "A friend")
            val list = juz.sorted().joinToString(if (ar) "، " else ", ")
            if (ar) (if (juz.size == 1) "$who أتمّ الجزء $list" else "$who أتمّ الأجزاء $list") else "$who finished juz $list"
        }
}

/** Recent finishes in the group, newest first: one line per friend per day. */
private fun recentNews(group: GroupKhatmah, ar: Boolean): List<Triple<Member?, String, Long>> {
    val names = group.displayNames()
    val zone = ZoneId.systemDefault()
    return group.marks.filter { it.value.done }.entries
        .groupBy { it.value.by to Instant.ofEpochMilli(it.value.at).atZone(zone).toLocalDate() }
        .map { (key, items) ->
            val who = names[key.first] ?: (if (ar) "صديق" else "A friend")
            val juz = items.map { it.key }.sorted()
            val list = juz.joinToString(if (ar) "، " else ", ")
            val text = if (ar) (if (juz.size == 1) "$who أتمّ الجزء $list" else "$who أتمّ الأجزاء $list") else "$who finished juz $list"
            Triple(group.members[key.first], text, items.maxOf { it.value.at })
        }
        .sortedByDescending { it.third }
}

/** Posts the news as a notification too (when Emran may notify). */
private object KhatmahNotify {
    private const val CHANNEL = "khatmah_news_v1"
    fun post(context: Context, title: String, lines: List<String>, ar: Boolean) {
        if (lines.isEmpty()) return
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        val nm = androidx.core.app.NotificationManagerCompat.from(context)
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            val ch = android.app.NotificationChannel(CHANNEL, if (ar) "أخبار الختمة الجماعية" else "Group khatmah news", android.app.NotificationManager.IMPORTANCE_DEFAULT)
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).createNotificationChannel(ch)
        }
        val style = androidx.core.app.NotificationCompat.InboxStyle().also { st -> lines.forEach { st.addLine(it) } }
        val n = androidx.core.app.NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_emran)
            .setContentTitle(title)
            .setContentText(lines.first())
            .setStyle(style)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(title.hashCode(), n) }
    }
}

/** A calm banner that slides down from the top for a few seconds. */
@Composable
private fun NewsBanner(text: String, onDone: () -> Unit) {
    val drop = remember { Animatable(0f) }
    LaunchedEffect(text) {
        drop.animateTo(1f, tween(420, easing = Ease))
        delay(4200)
        drop.animateTo(0f, tween(320, easing = Ease))
        onDone()
    }
    Box(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 64.dp, start = 24.dp, end = 24.dp), contentAlignment = Alignment.TopCenter) {
        Row(
            Modifier.graphicsLayer { alpha = drop.value; translationY = (1f - drop.value) * -24.dp.toPx() }
                .shadow(14.dp, RoundedCornerShape(22.dp), ambientColor = Color.Black.copy(alpha = 0.2f), spotColor = Color.Black.copy(alpha = 0.2f))
                .clip(RoundedCornerShape(22.dp)).background(Garden.card).border(1.dp, Garden.hairline, RoundedCornerShape(22.dp))
                .clickable { onDone() }.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(art(PLANTS[3]), null, Modifier.size(28.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, color = Garden.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/* ================================================================ sheets */

/**
 * A paper sheet rising from the bottom edge over a soft dim. Drawn inside the page (not a Dialog window) so it
 * gets the real gesture-bar and keyboard insets. Tapping the dim or Back closes it.
 */
@Composable
private fun PaperSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(onBack = onDismiss)
    val rise = remember { Animatable(1f) }
    LaunchedEffect(Unit) { rise.animateTo(0f, tween(320, easing = Ease)) }
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().graphicsLayer { alpha = 1f - rise.value }.background(Color.Black.copy(alpha = 0.36f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        )
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().graphicsLayer { translationY = rise.value * size.height }
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(Garden.paper)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .imePadding().navigationBarsPadding().padding(horizontal = 24.dp).padding(top = 12.dp, bottom = 16.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(width = 40.dp, height = 4.dp).clip(CircleShape).background(Garden.ink.copy(alpha = 0.16f)))
            Spacer(Modifier.height(20.dp))
            content()
        }
    }
}

/** Create (group name + me) or join (me; the code was already checked). Name and mascot are required. */
@Composable
private fun MemberSheet(ar: Boolean, join: Boolean, onDismiss: () -> Unit, onSubmit: (groupName: String, myName: String, avatar: Int) -> String?) {
    var groupName by remember { mutableStateOf("") }
    var myName by remember { mutableStateOf("") }
    var avatar by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val ready = (join || groupName.isNotBlank()) && myName.isNotBlank() && avatar != null
    PaperSheet(onDismiss) {
        Text(
            if (join) (if (ar) "من أنت في الحديقة؟" else "Who are you in the garden?") else (if (ar) "مجموعة جديدة" else "New group"),
            color = Garden.ink, fontSize = 28.sp, fontFamily = display(ar),
        )
        Spacer(Modifier.height(16.dp))
        if (!join) {
            GardenField(groupName, { groupName = it }, if (ar) "اسم المجموعة" else "Group name")
            Spacer(Modifier.height(12.dp))
        }
        GardenField(myName, { myName = it; error = null }, if (ar) "اسمك" else "Your name")
        error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = Garden.warn, fontSize = 14.sp) }
        Spacer(Modifier.height(16.dp))
        Text(if (ar) "اختر رفيقك" else "Pick your companion", color = Garden.inkSoft, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(12.dp))
        MascotPicker(selected = avatar, onPick = { avatar = it })
        Spacer(Modifier.height(20.dp))
        GardenButton(
            if (join) (if (ar) "انضمام" else "Join") else (if (ar) "إنشاء" else "Create"),
            onClick = { error = onSubmit(groupName.trim(), myName.trim(), avatar ?: 0) },
            enabled = ready, modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun MascotPicker(selected: Int?, onPick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        (0 until Member.AVATAR_COUNT).chunked(6).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                row.forEach { i ->
                    val on = selected == i
                    val k by animateFloatAsState(if (on) 1.08f else 1f, tween(180, easing = Ease), label = "pick")
                    Box(
                        Modifier.size(52.dp).scale(k).clip(CircleShape)
                            .border(if (on) 3.dp else 0.dp, if (on) Garden.accent else Color.Transparent, CircleShape)
                            .clickable { onPick(i) }.padding(if (on) 5.dp else 2.dp),
                        contentAlignment = Alignment.Center,
                    ) { Image(painterResource(AVATARS[i]), null, Modifier.fillMaxSize().clip(CircleShape)) }
                }
            }
        }
    }
}

@Composable
private fun GardenField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true,
        label = { Text(label) },
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Garden.ink, unfocusedTextColor = Garden.ink,
            focusedContainerColor = Garden.card, unfocusedContainerColor = Garden.card,
            focusedBorderColor = Garden.accent, unfocusedBorderColor = Garden.ink.copy(alpha = 0.16f),
            focusedLabelColor = Garden.accentInk, unfocusedLabelColor = Garden.inkSoft,
            cursorColor = Garden.accent,
        ),
    )
}

@Composable
private fun ConfirmSheet(ar: Boolean, title: String, body: String, confirm: String, cancel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    PaperSheet(onDismiss) {
        Text(title, color = Garden.ink, fontSize = 24.sp, fontFamily = display(ar))
        Spacer(Modifier.height(12.dp))
        Text(body, color = Garden.inkSoft, fontSize = 16.sp, lineHeight = 24.sp)
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GardenButton(cancel, onClick = onDismiss, primary = false, modifier = Modifier.weight(1f))
            Box(
                Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(28.dp)).background(Garden.warn).clickable(onClick = onConfirm),
                contentAlignment = Alignment.Center,
            ) { Text(confirm, color = Garden.onAccent, fontSize = 16.sp, fontWeight = FontWeight.Medium) }
        }
    }
}

/* ================================================================ my own khatmah */

/**
 * My own khatmah as a garden: no group, no friends, nothing to mark by hand. Each juz grows from my reading in the
 * muṣḥaf (a seed, then a young plant while partly read, then a flower), and tapping one opens the reader there.
 */
@Composable
fun GroupKhatmahMineScreen(lang: String, juzRead: List<Float>, onBack: () -> Unit, onOpenJuz: (Int) -> Unit) {
    val ar = lang == "ar"
    val me = "MINE1"
    val garden = remember(juzRead, ar) {
        val marks = juzRead.mapIndexedNotNull { i, f ->
            if (f <= 0f) null else (i + 1) to io.github.sammynashed.emran.data.khatmah.JuzMark(done = f >= 0.999f, by = me, at = 0L, reading = f < 0.999f)
        }.toMap()
        GroupKhatmah("mine", if (ar) "ختمتي" else "My khatmah", mapOf(me to Member(me, if (ar) "أنا" else "Me", 0)), marks)
    }
    PaperPage(onBack, toPaper = true) { leave, _ ->
        GardenTab(garden, me, ar, copied = false, onBack = leave, onShare = {}, onPick = onOpenJuz, personal = true)
    }
}
