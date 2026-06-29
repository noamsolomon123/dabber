package com.dabber.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
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
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Foreground service that draws the always-on-top mic bubble and runs one dictation turn
 * per tap: record (VAD auto-stop) → transcribe (whisper.cpp) → clean → insert into the
 * focused field via [InsertionService] (clipboard-paste fallback).
 *
 * Tap while idle = start listening; tap while listening = stop early; drag = reposition.
 */
class OverlayService : Service() {

    private val wm by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private var bubble: ImageView? = null
    private lateinit var lp: WindowManager.LayoutParams
    private val recorder = AudioRecorder()
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var state = State.IDLE

    private enum class State { IDLE, LISTENING, WORKING }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        addBubble()
        worker.execute {
            val model = ModelStore.modelFile(this)
            if (model.exists()) DictationCore.loadModel(model.absolutePath)
            main.post { refreshBubble() }
        }
    }

    override fun onDestroy() {
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        worker.shutdownNow()
        super.onDestroy()
    }

    // --- Bubble UI -----------------------------------------------------------

    private fun addBubble() {
        val iv = ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher)
            setColorFilter(0xFFFFFFFF.toInt())
            setBackgroundResource(R.drawable.bubble_bg)
            val p = dp(12)
            setPadding(p, p, p, p)
        }
        lp = WindowManager.LayoutParams(
            dp(56), dp(56),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(220)
        }
        iv.setOnTouchListener(DragTapListener())
        wm.addView(iv, lp)
        bubble = iv
        refreshBubble()
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
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (abs(dx) > dp(8) || abs(dy) > dp(8)) moved = true
                    lp.x = startX + dx.toInt()
                    lp.y = startY + dy.toInt()
                    runCatching { wm.updateViewLayout(v, lp) }
                }
                MotionEvent.ACTION_UP -> if (!moved) onTap()
            }
            return true
        }
    }

    private fun refreshBubble() {
        val iv = bubble ?: return
        val color = when {
            !DictationCore.modelLoaded -> 0xFF9AA0A6.toInt() // grey: no model
            state == State.LISTENING -> 0xFFE5484D.toInt()   // red: listening
            state == State.WORKING -> 0xFFF5A623.toInt()     // amber: transcribing
            else -> 0xFF5B5BD6.toInt()                        // purple: idle
        }
        iv.background?.setTint(color)
    }

    // --- Dictation turn ------------------------------------------------------

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
        state = State.LISTENING; refreshBubble()
        worker.execute {
            val pcm = recorder.record()
            main.post { state = State.WORKING; refreshBubble() }
            val text = DictationCore.transcribeClean(pcm, ModelConfig.LANG)
            main.post {
                if (text.isNotBlank()) {
                    val inserted = InsertionService.instance?.insertText(text) ?: false
                    if (!inserted) {
                        copyToClipboard(text)
                        toast(getString(R.string.copied_clip))
                    }
                }
                state = State.IDLE; refreshBubble()
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

    companion object {
        private const val CHANNEL_ID = "dabber_overlay"
        private const val NOTIF_ID = 1

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, OverlayService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, OverlayService::class.java))
        }
    }
}
