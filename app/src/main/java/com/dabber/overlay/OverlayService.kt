package com.dabber.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dabber.MainActivity
import com.dabber.R
import com.dabber.a11y.InsertionService
import com.dabber.audio.AudioRecorder
import com.dabber.core.DictationCore
import com.dabber.model.ModelConfig
import com.dabber.model.ModelStore
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin
import java.util.concurrent.Executors

/**
 * Foreground service that draws the small, animated mic bubble and runs one dictation turn
 * per tap: record (VAD auto-stop) → transcribe (whisper.cpp) → clean → insert into the
 * focused field via [InsertionService] (clipboard-paste fallback).
 *
 * Tap while idle = start listening; tap while listening = stop early; drag = reposition.
 *
 * ### Visibility (strict keyboard-only)
 * The bubble appears **only** while an editable text field has input focus (keyboard context).
 * [InsertionService] tracks editable focus and pushes it here via [notifyEditableFocus]; the
 * window is shown immediately on focus and hidden after a short debounce on focus loss. There
 * is no "always-visible" fallback: the bubble needs the accessibility service to insert text,
 * so with accessibility off it simply stays hidden. The one exception is while a dictation turn
 * is in flight (LISTENING / WORKING) — it stays up so it can't vanish mid-utterance.
 *
 * ### Look
 * A single [BubbleView] renders everything: a soft drop-shadowed coloured circle plus the
 * per-state foreground — a crisp white mic glyph (IDLE), an original 5-bar voice **waveform**
 * (LISTENING), or a sweeping spinner (WORKING). Colours: indigo idle, red listening, amber
 * working, brief green success.
 */
class OverlayService : Service() {

    private val wm by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private val recorder = AudioRecorder()
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var root: BubbleView? = null
    private lateinit var lp: WindowManager.LayoutParams

    private var idleSize = 0
    private var activeSize = 0

    // Live fill colour (the value BubbleView is currently showing).
    private var bgColor = GREY
    private var colorAnim: ValueAnimator? = null

    // Visibility bookkeeping (all touched on the main thread only).
    private var windowShown = false
    @Volatile private var lastEditableFocused = false

    @Volatile
    private var state = State.IDLE

    private enum class State { IDLE, LISTENING, WORKING }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onCreate() {
        super.onCreate()
        serviceInstance = this
        startAsForeground()
        addBubble()
        worker.execute {
            val model = ModelStore.modelFile(this)
            if (model.exists()) DictationCore.loadModel(model.absolutePath)
            main.post { enterState(State.IDLE) } // re-tints idle (grey → indigo) once loaded
        }
    }

    override fun onDestroy() {
        serviceInstance = null
        main.removeCallbacks(hideRunnable)
        colorAnim?.cancel(); colorAnim = null
        root?.let {
            it.animate().cancel()
            it.stop()                       // cancels the waveform/spinner animator
            runCatching { wm.removeView(it) }
        }
        root = null
        worker.shutdownNow()
        super.onDestroy()
    }

    // --- Bubble UI -----------------------------------------------------------

    private fun addBubble() {
        idleSize = dp(IDLE_DP)
        activeSize = dp(ACTIVE_DP)

        bgColor = idleColor()
        val view = BubbleView(this).apply {
            visibility = View.GONE
            circleColor = bgColor
            setOnTouchListener(DragTapListener())
        }

        lp = WindowManager.LayoutParams(
            idleSize, idleSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            BASE_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(200)
        }

        wm.addView(view, lp)
        root = view

        enterState(State.IDLE)

        // Seed visibility from the current keyboard/focus state, then reveal if appropriate.
        lastEditableFocused = InsertionService.editableFocused
        InsertionService.instance?.forceEvaluate()
        applyVisibility()
    }

    private inner class DragTapListener : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = lp.x; startY = lp.y; moved = false
                    pressIn()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (abs(dx) > dp(8) || abs(dy) > dp(8)) moved = true
                    lp.x = startX + dx.toInt()
                    lp.y = startY + dy.toInt()
                    runCatching { wm.updateViewLayout(root, lp) }
                }
                MotionEvent.ACTION_UP -> {
                    pressOut()
                    if (!moved) onTap()
                }
                MotionEvent.ACTION_CANCEL -> pressOut()
            }
            return true
        }
    }

    /** Subtle scale-down while the finger is down (idle only; active states own their motion). */
    private fun pressIn() {
        val view = root ?: return
        if (state != State.IDLE) return
        view.animate().cancel()
        view.animate().scaleX(0.92f).scaleY(0.92f).setDuration(110).start()
    }

    private fun pressOut() {
        val view = root ?: return
        if (state != State.IDLE) return
        view.animate().cancel()
        view.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
    }

    // --- State / animation ---------------------------------------------------

    private fun idleColor(): Int = if (DictationCore.modelLoaded) INDIGO else GREY

    /** Applies the look (colour, foreground, window size) for [target] and sets [state]. */
    private fun enterState(target: State) {
        state = target
        val view = root ?: return
        val prevWindow = lp.width
        when (target) {
            State.IDLE -> {
                setWindowSize(idleSize)
                view.setMode(BubbleView.Mode.MIC)
                animateColorTo(idleColor(), TRANSITION_MS)
            }
            State.LISTENING -> {
                setWindowSize(activeSize)
                view.setMode(BubbleView.Mode.WAVEFORM)
                animateColorTo(RED, TRANSITION_MS)
            }
            State.WORKING -> {
                setWindowSize(activeSize)
                view.setMode(BubbleView.Mode.SPINNER)
                animateColorTo(AMBER, TRANSITION_MS)
            }
        }
        applyVisibility()
        animateGrow(prevWindow)
    }

    /** Green flash + scale pop when text lands, then settle back to idle. */
    private fun playSuccess() {
        state = State.IDLE
        val view = root ?: return
        setWindowSize(idleSize)
        view.setMode(BubbleView.Mode.MIC)
        view.animate().cancel()
        view.scaleX = 1f; view.scaleY = 1f
        view.animate().scaleX(1.16f).scaleY(1.16f).setDuration(150)
            .withEndAction {
                view.animate().scaleX(1f).scaleY(1f).setDuration(220).start()
            }.start()
        animateColorTo(GREEN, 150) { animateColorTo(idleColor(), 320) }
        applyVisibility()
    }

    /** Smoothly scales [root] from its previous footprint up/down to the new window size. */
    private fun animateGrow(prevWindow: Int) {
        val view = root ?: return
        val cur = lp.width
        if (cur <= 0 || prevWindow <= 0 || prevWindow == cur) return
        val from = prevWindow.toFloat() / cur.toFloat()
        view.animate().cancel()
        view.scaleX = from; view.scaleY = from
        view.animate().scaleX(1f).scaleY(1f)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .setDuration(TRANSITION_MS).start()
    }

    private fun animateColorTo(target: Int, duration: Long, end: (() -> Unit)? = null) {
        colorAnim?.cancel()
        val a = ValueAnimator.ofObject(ArgbEvaluator(), bgColor, target).apply {
            this.duration = duration
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                bgColor = it.animatedValue as Int
                root?.circleColor = bgColor
            }
            if (end != null) addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = end()
            })
        }
        colorAnim = a
        a.start()
    }

    /** Grows/shrinks the window around its current centre so the bubble doesn't jump. */
    private fun setWindowSize(size: Int) {
        val r = root ?: return
        if (lp.width == size) return
        val cx = lp.x + lp.width / 2
        val cy = lp.y + lp.height / 2
        lp.width = size; lp.height = size
        lp.x = cx - size / 2; lp.y = cy - size / 2
        runCatching { wm.updateViewLayout(r, lp) }
    }

    // --- Visibility (show only when an editable field is focused) -------------

    /** Called by [InsertionService] (any thread) when editable-focus changes. */
    fun onEditableFocus(focused: Boolean) {
        main.post {
            lastEditableFocused = focused
            applyVisibility()
        }
    }

    /**
     * Strict keyboard-only rule: show while an editable field is focused, or while a dictation
     * turn is mid-flight so the bubble can't disappear out from under the user.
     */
    private fun computeShouldShow(): Boolean =
        state != State.IDLE || lastEditableFocused

    private fun applyVisibility() {
        if (computeShouldShow()) showWindow() else requestHide()
    }

    private val hideRunnable = Runnable { if (!computeShouldShow()) hideWindow() }

    private fun requestHide() {
        main.removeCallbacks(hideRunnable)
        main.postDelayed(hideRunnable, HIDE_DELAY_MS)
    }

    private fun showWindow() {
        main.removeCallbacks(hideRunnable)
        val r = root ?: return
        if (windowShown) return
        windowShown = true
        lp.flags = BASE_FLAGS
        r.visibility = View.VISIBLE
        r.animate().cancel()
        r.alpha = 0f
        runCatching { wm.updateViewLayout(r, lp) }
        r.animate().alpha(1f).setDuration(180).start()
    }

    private fun hideWindow() {
        val r = root ?: return
        if (!windowShown) return
        windowShown = false
        r.animate().cancel()
        r.animate().alpha(0f).setDuration(180).withEndAction {
            r.visibility = View.GONE
            // Make the (now invisible) window pass touches through to the app beneath.
            lp.flags = BASE_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            runCatching { wm.updateViewLayout(r, lp) }
        }.start()
    }

    // --- Dictation turn (orchestration preserved exactly) --------------------

    private fun onTap() {
        when (state) {
            State.IDLE -> startTurn()
            State.LISTENING -> recorder.requestStop()
            State.WORKING -> Unit
        }
    }

    private fun startTurn() {
        if (!DictationCore.modelLoaded) {
            toast(getString(R.string.no_model)); return
        }
        if (!AudioRecorder.hasPermission(this)) {
            toast(getString(R.string.need_mic)); return
        }
        enterState(State.LISTENING)
        worker.execute {
            val pcm = recorder.record()
            main.post { enterState(State.WORKING) }
            val text = DictationCore.transcribeClean(pcm, ModelConfig.LANG)
            main.post {
                if (text.isBlank()) {
                    // Nothing to insert — tell the user instead of silently resetting.
                    toast(getString(R.string.no_speech))
                    enterState(State.IDLE)
                    return@post
                }
                // Try the focused-field insertion; on ANY failure (or no a11y service),
                // always fall back to the clipboard + a short toast so the user never gets
                // a silent "nothing happened".
                val inserted = runCatching { InsertionService.instance?.insertText(text) }
                    .getOrNull() ?: false
                if (!inserted) {
                    copyToClipboard(text)
                    toast(getString(R.string.copied_clip))
                }
                playSuccess()
            }
        }
    }

    // --- Helpers -------------------------------------------------------------

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Dabber", NotificationManager.IMPORTANCE_LOW),
        )
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.overlay_running))
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("dabber", text))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /**
     * Self-contained mic bubble: a soft drop-shadowed circle (colour set by the service per
     * state) with a state-specific white foreground drawn on top.
     *
     *  - [Mode.MIC]      static white microphone glyph (idle / success).
     *  - [Mode.WAVEFORM] 5 vertical bars oscillating with staggered sine phases — an original,
     *                    generic "live voice" equaliser look (listening).
     *  - [Mode.SPINNER]  a sweeping arc over a faint track (working / transcribing).
     *
     * A single repeating [ValueAnimator] drives [invalidate] for the animated modes and is
     * cancelled in [setMode]/[stop], so it never leaks past the visible state.
     */
    private class BubbleView(context: Context) : View(context) {

        enum class Mode { MIC, WAVEFORM, SPINNER }

        private val density = context.resources.displayMetrics.density
        private fun dp(v: Float) = v * density

        /** Animated fill colour, pushed by the service's ArgbEvaluator. */
        var circleColor: Int = 0xFF6366F1.toInt()
            set(value) {
                if (field != value) { field = value; invalidate() }
            }

        private var mode = Mode.MIC
        private var phase = 0f
        private var anim: ValueAnimator? = null

        private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1f)
            color = 0x40FFFFFF
        }
        private val fgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        private val oval = RectF()
        private val mic: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_mic)
            ?.mutate()?.apply { setTint(0xFFFFFFFF.toInt()) }

        init {
            // setShadowLayer requires a software layer.
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }

        fun setMode(m: Mode) {
            if (m == mode) return
            mode = m
            anim?.cancel(); anim = null
            if (m == Mode.WAVEFORM || m == Mode.SPINNER) {
                anim = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = if (m == Mode.WAVEFORM) 1100L else 900L
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    addUpdateListener { phase = it.animatedValue as Float; invalidate() }
                    start()
                }
            }
            invalidate()
        }

        fun stop() {
            anim?.cancel(); anim = null
            mode = Mode.MIC
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = min(cx, cy) - SHADOW_MARGIN_DP * density
            if (r <= 0f) return

            // Soft drop shadow + filled circle.
            circlePaint.color = circleColor
            circlePaint.setShadowLayer(dp(5f), 0f, dp(2f), SHADOW_COLOR)
            canvas.drawCircle(cx, cy, r, circlePaint)
            circlePaint.clearShadowLayer()
            // Premium edge highlight.
            canvas.drawCircle(cx, cy, r - dp(0.5f), rimPaint)

            when (mode) {
                Mode.MIC -> drawMic(canvas, cx, cy, r)
                Mode.WAVEFORM -> drawWaveform(canvas, cx, cy, r)
                Mode.SPINNER -> drawSpinner(canvas, cx, cy, r)
            }
        }

        private fun drawMic(canvas: Canvas, cx: Float, cy: Float, r: Float) {
            val d = mic ?: return
            val half = (r * 0.52f).toInt()
            d.setBounds((cx - half).toInt(), (cy - half).toInt(),
                (cx + half).toInt(), (cy + half).toInt())
            d.draw(canvas)
        }

        /** 5 vertical bars, each a staggered sine wave with a gentle centre-weighted envelope. */
        private fun drawWaveform(canvas: Canvas, cx: Float, cy: Float, r: Float) {
            fgPaint.style = Paint.Style.FILL
            fgPaint.alpha = 255
            val bars = 5
            val barW = dp(3f)
            val gap = dp(3f)
            val totalW = bars * barW + (bars - 1) * gap
            val maxH = r * 0.95f
            val minH = r * 0.28f
            var x = cx - totalW / 2f + barW / 2f
            for (i in 0 until bars) {
                val t = (phase + i * 0.16f) * TWO_PI
                val osc = 0.5f + 0.5f * sin(t)               // 0..1, smooth
                val env = 1f - 0.4f * abs(i - 2) / 2f        // centre bars a touch taller
                val h = minH + (maxH - minH) * osc * env
                oval.set(x - barW / 2f, cy - h / 2f, x + barW / 2f, cy + h / 2f)
                canvas.drawRoundRect(oval, barW / 2f, barW / 2f, fgPaint)
                x += barW + gap
            }
        }

        /** A bright arc sweeping over a faint full-circle track. */
        private fun drawSpinner(canvas: Canvas, cx: Float, cy: Float, r: Float) {
            val ringR = r * 0.52f
            fgPaint.style = Paint.Style.STROKE
            fgPaint.strokeWidth = dp(3f)
            fgPaint.strokeCap = Paint.Cap.ROUND
            oval.set(cx - ringR, cy - ringR, cx + ringR, cy + ringR)
            fgPaint.alpha = 0x40                              // faint track
            canvas.drawCircle(cx, cy, ringR, fgPaint)
            fgPaint.alpha = 0xFF                              // sweeping arc
            canvas.drawArc(oval, phase * 360f, 110f, false, fgPaint)
            fgPaint.alpha = 255
        }

        private companion object {
            private const val SHADOW_MARGIN_DP = 8f
            private const val SHADOW_COLOR = 0x55000000
            private val TWO_PI = (2.0 * Math.PI).toFloat()
        }
    }

    companion object {
        private const val CHANNEL_ID = "dabber_overlay"
        private const val NOTIF_ID = 1

        /** Visible idle bubble ≈ IDLE_DP − 2·shadow-margin; window adds room for the shadow. */
        private const val IDLE_DP = 62      // ~46dp circle
        private const val ACTIVE_DP = 76    // ~60dp circle (well under the 64dp cap)

        // Palette (non-const: .toInt() isn't a compile-time constant expression).
        private val INDIGO = 0xFF6366F1.toInt()
        private val RED = 0xFFEF4444.toInt()
        private val AMBER = 0xFFF59E0B.toInt()
        private val GREEN = 0xFF10B981.toInt()
        private val GREY = 0xFF9AA0A6.toInt()

        private const val TRANSITION_MS = 200L
        private const val HIDE_DELAY_MS = 800L

        private val BASE_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

        /** Running instance, for the [InsertionService] → overlay visibility bridge. */
        @Volatile
        private var serviceInstance: OverlayService? = null

        /** Called by [InsertionService] whenever editable input-focus changes. */
        fun notifyEditableFocus(focused: Boolean) {
            serviceInstance?.onEditableFocus(focused)
        }

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, OverlayService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, OverlayService::class.java))
        }
    }
}
