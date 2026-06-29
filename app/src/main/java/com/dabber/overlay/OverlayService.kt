package com.dabber.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
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
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
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
import java.util.concurrent.Executors

/**
 * Foreground service that draws the premium, animated mic bubble and runs one dictation turn
 * per tap: record (VAD auto-stop) → transcribe (whisper.cpp) → clean → insert into the
 * focused field via [InsertionService] (clipboard-paste fallback).
 *
 * Tap while idle = start listening; tap while listening = stop early; drag = reposition.
 *
 * ### Visibility
 * The bubble only appears while an editable text field has input focus (i.e. the user is
 * about to type / the keyboard is up). [InsertionService] tracks editable focus and pushes
 * it here via [notifyEditableFocus]; the window is shown immediately on focus and hidden
 * after a short debounce on focus loss. While accessibility is OFF (no [InsertionService]),
 * the bubble falls back to always-visible so the app still works.
 */
class OverlayService : Service() {

    private val wm by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private val recorder = AudioRecorder()
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var root: FrameLayout? = null
    private var bubble: ImageView? = null
    private var rings: RingsView? = null
    private var bg: GradientDrawable? = null
    private lateinit var lp: WindowManager.LayoutParams

    private var idleSize = 0
    private var activeSize = 0

    // Live fill colour (the value GradientDrawable.setColor is currently showing).
    private var bgColor = GREY
    private var colorAnim: ValueAnimator? = null
    private var breatheAnim: AnimatorSet? = null

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
        breatheAnim?.cancel(); breatheAnim = null
        rings?.stop()
        bubble?.animate()?.cancel()
        root?.let { runCatching { wm.removeView(it) } }
        root = null; bubble = null; rings = null; bg = null
        worker.shutdownNow()
        super.onDestroy()
    }

    // --- Bubble UI -----------------------------------------------------------

    private fun addBubble() {
        idleSize = dp(72)
        activeSize = dp(120)

        val ringsView = RingsView(this)
        val fill = (ContextCompat.getDrawable(this, R.drawable.bubble_bg)?.mutate()
            as? GradientDrawable) ?: GradientDrawable().apply { shape = GradientDrawable.OVAL }
        bgColor = idleColor()
        fill.setColor(bgColor)
        bg = fill

        val iv = ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher)
            setColorFilter(0xFFFFFFFF.toInt())
            background = fill
            val p = dp(14)
            setPadding(p, p, p, p)
            alpha = REST_ALPHA
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) = o.setOval(0, 0, v.width, v.height)
            }
            elevation = dp(6).toFloat()
            setOnTouchListener(DragTapListener())
        }

        val container = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            visibility = View.GONE
            addView(
                ringsView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            addView(
                iv,
                FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER),
            )
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

        wm.addView(container, lp)
        root = container
        bubble = iv
        rings = ringsView

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

    /** Scale-down + full-opacity ripple feel while the finger is down. */
    private fun pressIn() {
        val iv = bubble ?: return
        iv.animate().cancel()
        val a = iv.animate().alpha(1f).setDuration(110)
        if (state == State.IDLE) a.scaleX(0.92f).scaleY(0.92f)
        a.start()
    }

    private fun pressOut() {
        val iv = bubble ?: return
        if (state != State.IDLE) return // active states own their own scale/alpha
        iv.animate().cancel()
        iv.animate().scaleX(1f).scaleY(1f).alpha(REST_ALPHA).setDuration(150).start()
    }

    // --- State / animation ---------------------------------------------------

    private fun idleColor(): Int = if (DictationCore.modelLoaded) INDIGO else GREY

    /** Applies the look (colour, rings, breathing, window size) for [target] and sets [state]. */
    private fun enterState(target: State) {
        state = target
        val iv = bubble ?: return
        when (target) {
            State.IDLE -> {
                stopBreathing()
                rings?.stop()
                setWindowSize(idleSize)
                iv.animate().cancel()
                iv.animate().alpha(REST_ALPHA).scaleX(1f).scaleY(1f).setDuration(220).start()
                animateColorTo(idleColor(), 250)
            }
            State.LISTENING -> {
                setWindowSize(activeSize)
                iv.animate().cancel()
                iv.animate().alpha(1f).setDuration(150).start()
                animateColorTo(RED, 250)
                rings?.setMode(RingsView.Mode.LISTENING, RED)
                startBreathing()
            }
            State.WORKING -> {
                stopBreathing()
                iv.animate().cancel()
                iv.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(150).start()
                animateColorTo(AMBER, 250)
                rings?.setMode(RingsView.Mode.WORKING, AMBER)
            }
        }
        applyVisibility()
    }

    /** Green flash + scale pop when text lands, then settle back to idle indigo. */
    private fun playSuccess() {
        state = State.IDLE
        val iv = bubble ?: return
        stopBreathing()
        rings?.stop()
        setWindowSize(idleSize)
        iv.animate().cancel()
        iv.animate().scaleX(1.18f).scaleY(1.18f).alpha(1f).setDuration(150)
            .withEndAction {
                iv.animate().scaleX(1f).scaleY(1f).alpha(REST_ALPHA).setDuration(220).start()
            }.start()
        animateColorTo(GREEN, 150) { animateColorTo(idleColor(), 320) }
        applyVisibility()
    }

    private fun animateColorTo(target: Int, duration: Long, end: (() -> Unit)? = null) {
        colorAnim?.cancel()
        val a = ValueAnimator.ofObject(ArgbEvaluator(), bgColor, target).apply {
            this.duration = duration
            addUpdateListener {
                bgColor = it.animatedValue as Int
                bg?.setColor(bgColor)
            }
            if (end != null) addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = end()
            })
        }
        colorAnim = a
        a.start()
    }

    private fun startBreathing() {
        stopBreathing()
        val iv = bubble ?: return
        iv.animate().cancel()
        val sx = ObjectAnimator.ofFloat(iv, View.SCALE_X, 1f, 1.06f)
        val sy = ObjectAnimator.ofFloat(iv, View.SCALE_Y, 1f, 1.06f)
        for (a in arrayOf(sx, sy)) {
            a.duration = 900
            a.repeatCount = ValueAnimator.INFINITE
            a.repeatMode = ValueAnimator.REVERSE
            a.interpolator = AccelerateDecelerateInterpolator()
        }
        breatheAnim = AnimatorSet().apply { playTogether(sx, sy); start() }
    }

    private fun stopBreathing() {
        breatheAnim?.cancel()
        breatheAnim = null
        bubble?.let { it.scaleX = 1f; it.scaleY = 1f }
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

    private fun computeShouldShow(): Boolean =
        state != State.IDLE || !InsertionService.isEnabled || lastEditableFocused

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
                var produced = false
                if (text.isNotBlank()) {
                    val inserted = InsertionService.instance?.insertText(text) ?: false
                    if (!inserted) {
                        copyToClipboard(text)
                        toast(getString(R.string.copied_clip))
                    }
                    produced = true
                }
                if (produced) playSuccess() else enterState(State.IDLE)
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
     * Decorative animation layer behind the mic button: concentric pulsing rings while
     * LISTENING and a rotating indeterminate arc while WORKING. A single repeating
     * [ValueAnimator] drives [invalidate]; it is cancelled on [stop] / mode change so it
     * never leaks past the visible state.
     */
    private class RingsView(context: Context) : View(context) {

        enum class Mode { NONE, LISTENING, WORKING }

        private val density = context.resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val oval = RectF()
        private var anim: ValueAnimator? = null
        private var phase = 0f
        private var mode = Mode.NONE
        private var color = 0xFFEF4444.toInt()

        fun setMode(m: Mode, tint: Int) {
            color = tint
            if (m == mode) return
            mode = m
            anim?.cancel()
            if (m == Mode.NONE) {
                anim = null; invalidate(); return
            }
            anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = if (m == Mode.LISTENING) 1600L else 900L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { phase = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        fun stop() {
            anim?.cancel(); anim = null
            mode = Mode.NONE
            invalidate()
        }

        private fun dp(v: Float) = v * density

        private fun withAlpha(a: Int) = (color and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val btnR = dp(28f)
            when (mode) {
                Mode.LISTENING -> {
                    val span = min(cx, cy) - dp(2f) - btnR
                    if (span <= 0f) return
                    val count = 3
                    for (i in 0 until count) {
                        val p = (phase + i.toFloat() / count) % 1f
                        paint.color = withAlpha(((1f - p) * 130f).toInt())
                        paint.strokeWidth = dp(1f) + dp(2.5f) * (1f - p)
                        canvas.drawCircle(cx, cy, btnR + span * p, paint)
                    }
                }
                Mode.WORKING -> {
                    val r = btnR + dp(7f)
                    oval.set(cx - r, cy - r, cx + r, cy + r)
                    paint.strokeCap = Paint.Cap.ROUND
                    paint.strokeWidth = dp(3f)
                    paint.color = withAlpha(0x33)            // faint full track
                    canvas.drawCircle(cx, cy, r, paint)
                    paint.color = withAlpha(0xFF)            // bright sweeping arc
                    canvas.drawArc(oval, phase * 360f, 90f, false, paint)
                }
                Mode.NONE -> Unit
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "dabber_overlay"
        private const val NOTIF_ID = 1

        // Palette (non-const: .toInt() isn't a compile-time constant expression).
        private val INDIGO = 0xFF6366F1.toInt()
        private val RED = 0xFFEF4444.toInt()
        private val AMBER = 0xFFF59E0B.toInt()
        private val GREEN = 0xFF10B981.toInt()
        private val GREY = 0xFF9AA0A6.toInt()

        private const val REST_ALPHA = 0.92f
        private const val HIDE_DELAY_MS = 1000L

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
