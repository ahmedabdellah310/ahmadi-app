package io.github.sammynashed.emran.service

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.sammynashed.emran.ui.screens.CardMotion
import io.github.sammynashed.emran.ui.screens.DhikrLook
import io.github.sammynashed.emran.ui.screens.FloatingDhikrCard
import io.github.sammynashed.emran.ui.screens.FloatingDismissTarget
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The card itself, drawn over other apps in its own small window (so the rest of the screen keeps
 * every touch). Drag it anywhere; while it is held an X rises at the bottom, and letting go on the
 * X dismisses it. Left alone, it leaves by itself after the chosen number of seconds.
 *
 * A window the user can see keeps the process at "perceptible" priority, so no service is needed
 * for the few seconds it is up.
 */
object FloatingDhikrOverlay {
    private val main = Handler(Looper.getMainLooper())
    private var session: Session? = null

    val isShowing get() = session != null

    fun show(context: Context, dhikr: FloatingDhikr.Dhikr) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { show(context, dhikr) }; return }
        if (session != null || !FloatingDhikr.canDraw(context)) return
        session = runCatching { Session(context.applicationContext, dhikr).also { it.open() } }
            .onFailure { android.util.Log.e("FloatingDhikr", "overlay failed", it) }.getOrNull()
    }

    fun hide() { session?.close(animated = true) }

    /** A minimal owner so Compose can run in a window that belongs to no activity. */
    private class Owner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
        private val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
        override val viewModelStore = ViewModelStore()
        fun start() {
            saved.performRestore(null)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        fun stop() {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            viewModelStore.clear()
        }
        fun attach(v: View) {
            v.setViewTreeLifecycleOwner(this); v.setViewTreeSavedStateRegistryOwner(this); v.setViewTreeViewModelStoreOwner(this)
        }
    }

    private class Session(val ctx: Context, val dhikr: FloatingDhikr.Dhikr) {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = ctx.resources.displayMetrics
        fun dp(v: Float) = (v * dm.density).roundToInt()
        val owner = Owner()
        val lang = FloatingDhikr.language(ctx)
        val look = DhikrLook(
            FloatingDhikr.style(ctx),
            FloatingDhikr.ACCENTS[FloatingDhikr.accentIndex(ctx)].argb?.let { androidx.compose.ui.graphics.Color(it) },
            FloatingDhikr.TEXT_SCALES[FloatingDhikr.textSizeIndex(ctx)],
        )
        // Classic and Mushaf glide in from the screen edge; Minimal appears in place (the card itself fades).
        val slides = look.style == FloatingDhikr.Style.CLASSIC || look.style == FloatingDhikr.Style.MUSHAF
        val bottom = FloatingDhikr.atBottom(ctx)

        // Shared with the Compose card: entrance/exit progress, how "held" it is, how close to the X.
        val motion = CardMotion(
            appear = mutableFloatStateOf(0f), held = mutableFloatStateOf(0f), toX = mutableFloatStateOf(0f),
            remaining = mutableFloatStateOf(1f), leaving = mutableStateOf(false),
        )
        val targetShown = mutableStateOf(false)
        val targetHot = mutableFloatStateOf(0f)

        val pad = dp(14f)                                   // room for the card's soft shadow inside its window
        val cardW = minOf(dm.widthPixels - dp(28f), dp(400f))
        val winW = cardW + 2 * pad

        lateinit var frame: DragFrame
        lateinit var cardParams: WindowManager.LayoutParams
        var target: ComposeView? = null
        var closed = false
        val showMs = FloatingDhikr.showSeconds(ctx) * 1000L
        var shownAt = 0L
        var pausedFor = 0L
        var holdStart = 0L
        var restY = 0
        var armed = false
        var dragged = false

        private val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        fun open() {
            owner.start()
            val compose = ComposeView(ctx).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent {
                    FloatingDhikrCard(dhikr = dhikr, lang = lang, motion = motion, widthPx = cardW, onTap = { openApp() }, look = look)
                }
            }
            frame = DragFrame(ctx).apply { addView(compose) }
            owner.attach(frame)
            // Always the same calm spot: top centre, just under the status bar. Where it was dropped last time is forgotten.
            val (sx, sy) = ((dm.widthPixels - winW) / 2 to statusBar() + dp(10f))
            restY = sy
            cardParams = WindowManager.LayoutParams(
                winW, WindowManager.LayoutParams.WRAP_CONTENT, overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = sx.coerceIn(0, maxOf(0, dm.widthPixels - winW)); y = sy.coerceIn(0, dm.heightPixels - dp(160f))
                windowAnimations = 0
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            frame.alpha = 0f   // invisible until it has been measured and parked above the screen: no flash
            wm.addView(frame, cardParams)
            shownAt = System.currentTimeMillis()
            frame.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or2: Int, ob: Int) {
                    if (b - t <= 0 || closed) return
                    v.removeOnLayoutChangeListener(this)
                    slideIn(b - t)
                }
            })
            main.post(tick)
        }

        /** Glides down from above the screen, slowly settling, while it fades in: calm, never a pop. */
        private fun slideIn(h: Int) {
            val rest = when {
                bottom -> dm.heightPixels - h - dp(104f)
                else -> restY
            }
            restY = rest
            shownAt = System.currentTimeMillis()
            if (!slides) {                      // the card itself does the entrance
                cardParams.y = rest
                runCatching { wm.updateViewLayout(frame, cardParams) }
                frame.alpha = 1f
                animate(motion.appear.floatValue, 1f, 900) { motion.appear.floatValue = it }
                return
            }
            val from = if (bottom) dm.heightPixels + dp(12f) else -(h + dp(12f))
            cardParams.y = from
            runCatching { wm.updateViewLayout(frame, cardParams) }
            frame.alpha = 1f
            animate(motion.appear.floatValue, 1f, 900) { motion.appear.floatValue = it }
            animate(0f, 1f, 800, android.view.animation.PathInterpolator(0.16f, 1f, 0.3f, 1f)) { f ->
                if (dragged) return@animate
                cardParams.y = (from + (rest - from) * f).roundToInt()
                runCatching { wm.updateViewLayout(frame, cardParams) }
            }
        }

        private val tick = object : Runnable {
            override fun run() {
                if (closed) return
                val held = frame.dragging || frame.touching
                if (held) { if (holdStart == 0L) holdStart = System.currentTimeMillis() }
                else if (holdStart != 0L) { pausedFor += System.currentTimeMillis() - holdStart; holdStart = 0L }
                val elapsed = System.currentTimeMillis() - shownAt - pausedFor - (if (holdStart != 0L) System.currentTimeMillis() - holdStart else 0L)
                motion.remaining.floatValue = (1f - (elapsed - 900f) / showMs).coerceIn(0f, 1f)
                if (!held && elapsed > showMs + 900) close(animated = true) else main.postDelayed(this, 32)
            }
        }

        fun statusBar(): Int {
            val id = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
            return if (id > 0) ctx.resources.getDimensionPixelSize(id) else dp(28f)
        }

        fun openApp() {
            close(animated = true)
            ctx.startActivity(
                Intent(ctx, io.github.sammynashed.emran.MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("ROUTE", "adhkar_detail/${dhikr.folderId}?item=${android.net.Uri.encode(dhikr.id)}"),
            )
        }

        // ── the X ────────────────────────────────────────────────────────
        val targetSize get() = dp(150f)
        fun targetCenter(): Pair<Float, Float> = dm.widthPixels / 2f to (dm.heightPixels - dp(120f)).toFloat()

        fun showTarget() {
            if (target != null) return
            val v = ComposeView(ctx).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent { FloatingDismissTarget(shown = targetShown.value, hot = targetHot.floatValue) }
            }
            owner.attach(v)
            val (cx, cy) = targetCenter()
            val p = WindowManager.LayoutParams(
                targetSize, targetSize, overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (cx - targetSize / 2f).roundToInt(); y = (cy - targetSize / 2f).roundToInt(); windowAnimations = 0
            }
            runCatching { wm.addView(v, p); target = v; targetShown.value = true }
        }

        fun hideTarget() {
            val v = target ?: return
            targetShown.value = false
            main.postDelayed({ runCatching { wm.removeView(v) }; if (target === v) target = null }, 260)
        }

        // ── dragging ────────────────────────────────────────────────────
        fun cardCenter(): Pair<Float, Float> = cardParams.x + winW / 2f to cardParams.y + frame.height / 2f

        fun onDragStart() {
            dragged = true
            frame.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            animate(motion.held.floatValue, 1f, 220) { motion.held.floatValue = it }
            showTarget()
        }

        /** Distance from the X to the finger, or to the card's middle, whichever is closer: either one over it counts. */
        private fun nearness(fx: Float, fy: Float): Float {
            val (tx, ty) = targetCenter(); val (cx, cy) = cardCenter()
            return minOf(hypot(fx - tx, fy - ty), hypot(cx - tx, cy - ty))
        }

        fun onDragMove(x: Int, y: Int, fx: Float, fy: Float) {
            var nx = x.coerceIn(-winW / 2, dm.widthPixels - winW / 2)
            var ny = y.coerceIn(-dp(40f), dm.heightPixels - dp(60f))
            cardParams.x = nx; cardParams.y = ny
            val d = nearness(fx, fy)
            // Easy to get in (130 dp), hard to fall out of by accident (190 dp): no flicker at the edge.
            val was = armed
            armed = if (armed) d < dp(190f) else d < dp(130f)
            if (armed) {
                // A magnet: the card is drawn most of the way onto the X, so it is obvious letting go will dismiss it.
                val (tx, ty) = targetCenter()
                val ex = tx - winW / 2f; val ey = ty - frame.height / 2f
                cardParams.x = (nx + (ex - nx) * 0.55f).roundToInt(); cardParams.y = (ny + (ey - ny) * 0.55f).roundToInt()
                if (!was) frame.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
            runCatching { wm.updateViewLayout(frame, cardParams) }
            val hot = if (armed) 1f else (1f - (d - dp(130f)) / dp(200f)).coerceIn(0f, 0.85f)
            targetHot.floatValue = hot
            motion.toX.floatValue = hot
        }

        fun onDragEnd() {
            animate(motion.held.floatValue, 0f, 260) { motion.held.floatValue = it }
            if (armed) {
                // Into the X: the card slides onto it and is gone.
                frame.performHapticFeedback(HapticFeedbackConstants.CONFIRM.takeIf { Build.VERSION.SDK_INT >= 30 } ?: HapticFeedbackConstants.VIRTUAL_KEY)
                val (tx, ty) = targetCenter()
                val sx = cardParams.x; val sy = cardParams.y
                val ex = (tx - winW / 2f).roundToInt(); val ey = (ty - frame.height / 2f).roundToInt()
                animate(0f, 1f, 260) { f ->
                    cardParams.x = (sx + (ex - sx) * f).roundToInt(); cardParams.y = (sy + (ey - sy) * f).roundToInt()
                    runCatching { wm.updateViewLayout(frame, cardParams) }
                    motion.toX.floatValue = 1f
                    motion.appear.floatValue = 1f - f
                }
                main.postDelayed({ hideTarget(); close(animated = false) }, 280)
            } else {
                hideTarget()
                armed = false
                targetHot.floatValue = 0f; motion.toX.floatValue = 0f
                // Keep it fully on screen where it was let go. Nothing is remembered: next time it starts at the top again.
                val x = cardParams.x.coerceIn(0, maxOf(0, dm.widthPixels - winW))
                val y = cardParams.y.coerceIn(statusBar(), dm.heightPixels - frame.height - dp(24f))
                val sx = cardParams.x; val sy = cardParams.y
                animate(0f, 1f, 360, OvershootInterpolator(1.1f)) { f ->
                    cardParams.x = (sx + (x - sx) * f).roundToInt(); cardParams.y = (sy + (y - sy) * f).roundToInt()
                    runCatching { wm.updateViewLayout(frame, cardParams) }
                }
            }
        }

        fun close(animated: Boolean) {
            if (closed) return
            closed = true
            main.removeCallbacks(tick)
            val finish = {
                hideTarget()
                runCatching { wm.removeView(frame) }
                owner.stop()
                if (session === this) session = null
            }
            if (animated) {
                motion.leaving.value = true
                animate(motion.appear.floatValue, 0f, 520) { motion.appear.floatValue = it }
                if (slides) {
                    val y0 = cardParams.y; val away = if (bottom) dm.heightPixels + dp(12f) else -(frame.height + dp(12f))
                    animate(0f, 1f, 520, android.view.animation.AccelerateInterpolator(1.4f)) { f ->
                        cardParams.y = (y0 + (away - y0) * f).roundToInt()
                        runCatching { wm.updateViewLayout(frame, cardParams) }
                    }
                }
                main.postDelayed(finish, 560)
            } else finish()
        }

        fun animate(from: Float, to: Float, ms: Long, interp: android.animation.TimeInterpolator? = null, onFrame: (Float) -> Unit) {
            ValueAnimator.ofFloat(from, to).apply {
                duration = ms
                interp?.let { interpolator = it }
                addUpdateListener { onFrame(it.animatedValue as Float) }
                start()
            }
        }

        /** Passes taps to the card; once a finger really moves, takes over and drags the whole window by raw screen coordinates. */
        inner class DragFrame(c: Context) : FrameLayout(c) {
            var dragging = false
            var touching = false
            private val slop = ViewConfiguration.get(c).scaledTouchSlop
            private var downRawX = 0f; private var downRawY = 0f
            private var startX = 0; private var startY = 0
            private val longPress = Runnable { if (touching && !dragging) { dragging = true; onDragStart() } }

            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        touching = true; dragging = false
                        downRawX = ev.rawX; downRawY = ev.rawY; startX = cardParams.x; startY = cardParams.y
                        postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                    }
                    MotionEvent.ACTION_MOVE -> if (!dragging && hypot(ev.rawX - downRawX, ev.rawY - downRawY) > slop) {
                        removeCallbacks(longPress); dragging = true; onDragStart(); return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        removeCallbacks(longPress); touching = false
                        // A long press that never moved: the release still has to end the drag.
                        if (dragging) { dragging = false; onDragEnd(); return true }
                    }
                }
                return dragging
            }

            override fun onTouchEvent(ev: MotionEvent): Boolean {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_MOVE -> if (dragging) onDragMove(startX + (ev.rawX - downRawX).roundToInt(), startY + (ev.rawY - downRawY).roundToInt(), ev.rawX, ev.rawY)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        removeCallbacks(longPress)
                        touching = false
                        if (dragging) { dragging = false; onDragEnd() }
                    }
                }
                return true
            }
        }
    }
}
