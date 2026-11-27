package io.github.sammynashed.emran.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sammynashed.emran.service.FloatingDhikr
import io.github.sammynashed.emran.ui.design.EmranIndigo
import io.github.sammynashed.emran.ui.design.EmranOrange
import io.github.sammynashed.emran.ui.design.drawEmranMark
import io.github.sammynashed.emran.ui.theme.InterFontFamily
import io.github.sammynashed.emran.ui.theme.ReadexFontFamily
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density

/** Motion values the overlay window drives (all 0..1). */
class CardMotion(
    val appear: MutableFloatState,
    val held: MutableFloatState,
    val toX: MutableFloatState,
    val remaining: MutableFloatState,
    val leaving: MutableState<Boolean>,
)

/** How the card is dressed: its style, an accent colour (null = the style's own), and a text-size multiplier. */
class DhikrLook(val style: FloatingDhikr.Style = FloatingDhikr.Style.CLASSIC, val accent: Color? = null, val scale: Float = 1f)

private fun seg(x: Float, a: Float, b: Float) = ((x - a) / (b - a)).coerceIn(0f, 1f)
private fun easeOut(x: Float) = 1f - (1f - x) * (1f - x) * (1f - x)
private fun easeBack(x: Float): Float { val c1 = 1.4f; val c3 = c1 + 1f; val t = x - 1f; return 1f + c3 * t * t * t + c1 * t * t }
private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t
/** A spring-like ease: overshoots a little past 1 and settles. [c1] is the overshoot (1.4 is gentle, 2.2 is bouncy). */
private fun springy(x: Float, c1: Float): Float { val c3 = c1 + 1f; val t = x - 1f; return 1f + c3 * t * t * t + c1 * t * t }

private val CARD_BG = Color(0xFF0C0C12)
private val CARD_SHAPE = RoundedCornerShape(26.dp)
private val PAPER = Color(0xFFF4EAD3)
private val PAPER_INK = Color(0xFF2A2016)
private val PAPER_SOFT = Color(0xFF7A6849)
private val GOLD = Color(0xFFB8975A)
private val PAPER_SHAPE = RoundedCornerShape(16.dp)

/**
 * The floating card in one of three looks ([DhikrLook]), all calm: Classic is Emran's dark tile with its five pillars
 * rising; Mushaf unfolds like a page; Minimal is only text. Whatever the look, the
 * entrance and exit are driven by [CardMotion.appear] (0 gone, 1 shown), so the exit is the entrance played backwards.
 */
@Composable
fun FloatingDhikrCard(
    dhikr: FloatingDhikr.Dhikr,
    lang: String,
    motion: CardMotion,
    widthPx: Int,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    look: DhikrLook = DhikrLook(),
) {
    val ar = lang == "ar"
    val density = LocalDensity.current
    val a = motion.appear.floatValue
    val held = motion.held.floatValue
    val toX = motion.toX.floatValue
    val style = look.style
    val k = look.scale
    val leaving = motion.leaving.value
    val paper = style == FloatingDhikr.Style.MUSHAF
    val minimal = style == FloatingDhikr.Style.MINIMAL
    val cardIn = when {
        leaving -> easeOut(seg(a, 0f, 1f))
        else -> easeBack(seg(a, 0f, 0.55f))
    }
    val textIn = easeOut(seg(a, 0.42f, 0.85f))
    val sweep = seg(a, 0.5f, 1f)

    val ink = if (paper) PAPER_INK else Color.White
    val inkSoft = if (paper) PAPER_SOFT else Color.White.copy(alpha = 0.5f)
    val inkFaint = if (paper) PAPER_SOFT.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.42f)
    val inkEn = if (paper) PAPER_INK.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.72f)
    val accent = look.accent
    val barBrush = when {
        accent != null -> Brush.horizontalGradient(listOf(accent, accent.copy(alpha = 0.55f)))
        paper -> Brush.horizontalGradient(listOf(GOLD, GOLD.copy(alpha = 0.5f)))
        else -> Brush.horizontalGradient(listOf(EmranIndigo, EmranOrange))
    }
    val glowColor = accent ?: EmranIndigo
    val shadowText = if (minimal) Shadow(Color.Black.copy(alpha = 0.9f), Offset(0f, 2f), 12f) else null

    val breathe = rememberInfiniteTransition(label = "breathe")
    val glow by breathe.animateFloat(
        0.22f, 0.42f, infiniteRepeatable(tween(2800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "glow",
    )
    val clickable = Modifier.clickable(remember { MutableInteractionSource() }, indication = null, onClick = onTap)
    val widthDp = with(density) { widthPx.toDp() }

    val container: Modifier = when (style) {
        FloatingDhikr.Style.CLASSIC -> Modifier
            .width(widthDp)
            .graphicsLayer {
                val s = (0.94f + 0.06f * cardIn) * (1f + 0.035f * held) * (1f - 0.5f * toX)
                scaleX = s; scaleY = s
                alpha = (seg(a, 0f, 0.35f)) * (1f - 0.35f * toX)
                translationY = (1f - cardIn) * -22.dp.toPx()
            }
            .shadow((14 + 10 * held).dp, CARD_SHAPE, ambientColor = glowColor.copy(alpha = 0.5f), spotColor = Color.Black)
            .clip(CARD_SHAPE)
            .background(CARD_BG)
            .background(Brush.verticalGradient(listOf(glowColor.copy(alpha = 0.16f), (accent ?: EmranOrange).copy(alpha = 0.10f))))
            .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.26f + 0.2f * held), Color.White.copy(alpha = 0.05f))), CARD_SHAPE)
            .drawWithContent {
                drawContent()
                if (sweep in 0.001f..0.999f) {           // one slow diagonal light, then never again
                    val x = -size.width * 0.5f + size.width * 2f * sweep
                    drawRect(
                        Brush.linearGradient(
                            listOf(Color.Transparent, Color.White.copy(alpha = 0.10f), Color.Transparent),
                            start = Offset(x - size.width * 0.25f, 0f), end = Offset(x + size.width * 0.25f, size.height),
                        ),
                    )
                }
            }
            .then(clickable)

        FloatingDhikr.Style.MUSHAF -> Modifier
            .width(widthDp)
            .graphicsLayer {
                val s = (1f + 0.035f * held) * (1f - 0.5f * toX)
                scaleX = s; scaleY = mix(0.8f, 1f, cardIn) * s
                transformOrigin = TransformOrigin(0.5f, 0f)
                alpha = seg(a, 0f, 0.4f) * (1f - 0.35f * toX)
            }
            .shadow((10 + 8 * held).dp, PAPER_SHAPE, ambientColor = GOLD.copy(alpha = 0.5f), spotColor = Color.Black)
            .clip(PAPER_SHAPE)
            .background(PAPER)
            .border(1.2.dp, GOLD.copy(alpha = 0.9f), PAPER_SHAPE)
            .drawWithContent {
                drawContent()
                val inset = 5.dp.toPx()
                drawRoundRect(
                    GOLD.copy(alpha = 0.45f), topLeft = Offset(inset, inset), size = Size(size.width - 2 * inset, size.height - 2 * inset),
                    cornerRadius = CornerRadius(11.dp.toPx()), style = Stroke(0.8.dp.toPx()),
                )
            }
            .then(clickable)

        FloatingDhikr.Style.MINIMAL -> Modifier
            .width(widthDp)
            .graphicsLayer {
                val s = (1f + 0.02f * held) * (1f - 0.5f * toX)
                scaleX = s; scaleY = s
                alpha = seg(a, 0f, 0.45f) * (1f - 0.35f * toX)
                translationY = (1f - easeOut(seg(a, 0f, 0.7f))) * 14.dp.toPx()
            }
            // Only text, but a faint shade behind it keeps it readable over a bright app.
            .drawBehind { drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.40f), Color.Transparent))) }
            .then(clickable)
    }

    CompositionLocalProvider(LocalLayoutDirection provides if (ar) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        Box(modifier.fillMaxWidth().padding(14.dp), contentAlignment = Alignment.TopCenter) {
            Box(container) {
                Column(
                    Modifier.fillMaxWidth()
                        .padding(start = 16.dp, end = 18.dp, top = if (minimal) 22.dp else 14.dp, bottom = 12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!minimal) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(46.dp)) {
                                if (paper) {
                                    Text("۞", color = GOLD, fontSize = (30 * k).sp, fontFamily = HafsFontFamily)
                                } else {
                                    Canvas(Modifier.size(46.dp)) {
                                        drawCircle(Brush.radialGradient(listOf(glowColor.copy(alpha = glow), Color.Transparent)), radius = size.minDimension * 0.75f)
                                    }
                                    Canvas(Modifier.size(38.dp)) {
                                        drawEmranMark(center, size.minDimension) { i -> easeBack(seg(a, 0.18f + i * 0.07f, 0.5f + i * 0.07f)) }
                                    }
                                }
                            }
                            Spacer(Modifier.width(14.dp))
                        }
                        Column(Modifier.weight(1f).graphicsLayer { alpha = textIn; translationY = (1f - textIn) * 8.dp.toPx() }) {
                            Text(
                                if (ar) "الأَحْمَدِيّ · ذِكْر" else "الأَحْمَدِيّ · Dhikr",
                                color = inkSoft, fontSize = (11 * k).sp, fontWeight = FontWeight.Medium,
                                fontFamily = if (ar) ReadexFontFamily else InterFontFamily,
                                style = TextStyle(shadow = shadowText),
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                dhikr.ar, color = ink, fontSize = (21 * k).sp, lineHeight = (36 * k).sp, fontFamily = HafsFontFamily,
                                maxLines = 3, overflow = TextOverflow.Ellipsis, textAlign = if (minimal) TextAlign.Center else TextAlign.Start,
                                style = TextStyle(textDirection = androidx.compose.ui.text.style.TextDirection.Rtl, shadow = shadowText),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (!ar && dhikr.en != dhikr.ar) Text(
                                dhikr.en, color = inkEn, fontSize = (12.5 * k).sp, lineHeight = (17 * k).sp,
                                fontFamily = InterFontFamily, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                style = TextStyle(shadow = shadowText),
                            )
                            val src = if (ar) dhikr.sourceAr else dhikr.sourceEn ?: dhikr.sourceAr
                            if (!src.isNullOrBlank()) {
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    src, color = inkFaint, fontSize = (10.5 * k).sp, lineHeight = (15 * k).sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    fontFamily = if (ar) ReadexFontFamily else InterFontFamily,
                                    style = TextStyle(shadow = shadowText),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    // Time left: a hairline in the accent that shortens towards the start edge.
                    Box(Modifier.fillMaxWidth().height(2.5.dp).clip(CircleShape).background((if (paper) PAPER_INK else Color.White).copy(alpha = 0.08f))) {
                        Box(
                            Modifier.fillMaxWidth(motion.remaining.floatValue.coerceIn(0.001f, 1f)).height(2.5.dp).clip(CircleShape)
                                .background(barBrush),
                        )
                    }
                }
            }
        }
    }
}

/** The X that rises at the bottom while the card is held. [hot] = the card is over it (1 = let go to dismiss). */
@Composable
fun FloatingDismissTarget(shown: Boolean, hot: Float) {
    val s by animateFloatAsState(if (shown) 1f else 0f, spring(dampingRatio = 0.62f, stiffness = 420f), label = "x")
    val h by animateFloatAsState(hot, tween(120, easing = LinearEasing), label = "hot")
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            if (h > 0f) drawCircle(Brush.radialGradient(listOf(Color(0xFFE5484D).copy(alpha = 0.35f * h * s), Color.Transparent)), radius = size.minDimension / 2f)
        }
        Box(
            Modifier
                .size((68 + 16 * h).dp)
                .graphicsLayer { alpha = s; translationY = (1f - s) * 70.dp.toPx(); scaleX = 0.6f + 0.4f * s; scaleY = 0.6f + 0.4f * s }
                .shadow(12.dp, CircleShape)
                .clip(CircleShape)
                .background(lerp(Color(0xEB0B0B10), Color(0xFFE5484D), h * 0.9f))
                .border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size((26 + 4 * h).dp))
        }
    }
}

/** A live, looping copy of the card for the settings page, so people see exactly what will float in. */
@Composable
fun FloatingDhikrPreview(dhikr: FloatingDhikr.Dhikr, lang: String, seconds: Int, widthPx: Int, look: DhikrLook = DhikrLook()) {
    val loop = rememberInfiniteTransition(label = "preview")
    val outMs = 520f; val restMs = 900f
    val showEnd = 900f + seconds * 1000f
    val total = (showEnd + outMs + restMs).toInt()
    val t by loop.animateFloat(0f, total.toFloat(), infiniteRepeatable(tween(total, easing = LinearEasing)), label = "t")
    val motion = remember {
        CardMotion(
            androidx.compose.runtime.mutableFloatStateOf(0f), androidx.compose.runtime.mutableFloatStateOf(0f),
            androidx.compose.runtime.mutableFloatStateOf(0f), androidx.compose.runtime.mutableFloatStateOf(1f),
            androidx.compose.runtime.mutableStateOf(false),
        )
    }
    motion.leaving.value = t > showEnd
    motion.appear.floatValue = when {
        t < 900f -> FastOutSlowInEasing.transform(t / 900f)
        t < showEnd -> 1f
        t < showEnd + outMs -> 1f - (t - showEnd) / outMs
        else -> 0f
    }
    motion.remaining.floatValue = (1f - (t - 900f) / (seconds * 1000f)).coerceIn(0f, 1f)
    FloatingDhikrCard(dhikr, lang, motion, widthPx, onTap = {}, look = look)
}

/**
 * A still, scaled-down picture of the card in a style, on a dim "wallpaper", for the style picker: the real card
 * drawn at full size (so it is exactly what will appear) and shrunk to fit [modifier]'s width.
 */
@Composable
fun FloatingDhikrMock(dhikr: FloatingDhikr.Dhikr, lang: String, look: DhikrLook, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val motion = remember {
        CardMotion(
            androidx.compose.runtime.mutableFloatStateOf(1f), androidx.compose.runtime.mutableFloatStateOf(0f),
            androidx.compose.runtime.mutableFloatStateOf(0f), androidx.compose.runtime.mutableFloatStateOf(0.62f),
            androidx.compose.runtime.mutableStateOf(false),
        )
    }
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier.clip(RoundedCornerShape(16.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF46567A), Color(0xFF1C2338)))),
    ) {
        val full = 368.dp                                       // the card's real width (340) plus its 14 dp margins
        val scale = maxWidth.value / full.value
        Box(
            Modifier.wrapContentSize(Alignment.TopCenter, unbounded = true).requiredWidth(full)
                .graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0.5f, 0f); translationY = 6.dp.toPx() },
        ) {
            FloatingDhikrCard(dhikr, lang, motion, with(density) { 340.dp.toPx().toInt() }, onTap = {}, look = look)
        }
    }
}
