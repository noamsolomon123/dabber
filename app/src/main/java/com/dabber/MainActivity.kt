package com.dabber

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.dabber.audio.AudioRecorder
import com.dabber.audio.WavReader
import com.dabber.core.DictationCore
import com.dabber.databinding.ActivityMainBinding
import com.dabber.model.ModelConfig
import com.dabber.model.ModelDownloader
import com.dabber.model.ModelStore
import com.dabber.overlay.OverlayService
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.io.File

/**
 * Single-screen control panel (Hebrew/RTL): grant the three permissions, load the model,
 * toggle the floating bubble, and a built-in scratchpad to test dictation without leaving
 * the app (works even before accessibility/overlay are enabled).
 *
 * The UI is a polished Material 3 layout (see res/layout/activity_main.xml); all behaviour
 * and the runtime contracts (permissions, model download/load, bubble service, in-app
 * dictation, debug transcription hook) are unchanged from the original programmatic screen.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    private val testRecorder = AudioRecorder()

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.micGrantBtn.setOnClickListener { requestMic() }
        b.overlayGrantBtn.setOnClickListener { requestOverlay() }
        b.a11yGrantBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        b.downloadBtn.setOnClickListener { downloadModel() }
        b.bubbleBtn.setOnClickListener { toggleBubble() }
        b.dictateBtn.setOnClickListener { dictateIntoScratchpad() }

        intent?.getStringExtra("transcribe_wav")?.let { runDebugTranscription(it) }
    }

    override fun onResume() {
        super.onResume()
        maybeLoadModel()
        refresh()
    }

    // --- State refresh -------------------------------------------------------

    private fun refresh() {
        val micOk = AudioRecorder.hasPermission(this)
        val overlayOk = Settings.canDrawOverlays(this)
        val a11yOk = isAccessibilityEnabled()
        val modelOk = DictationCore.modelLoaded

        bindPermCard(b.micCard, b.micStatus, b.micGrantBtn, b.micCheck, R.string.card_mic_desc, micOk)
        bindPermCard(b.overlayCard, b.overlayStatus, b.overlayGrantBtn, b.overlayCheck, R.string.card_overlay_desc, overlayOk)
        bindPermCard(b.a11yCard, b.a11yStatus, b.a11yGrantBtn, b.a11yCheck, R.string.card_a11y_desc, a11yOk)

        b.modelStatus.text =
            if (modelOk) getString(R.string.model_loaded) else getString(R.string.model_missing)
        b.modelStatus.setTextColor(
            ContextCompat.getColor(this, if (modelOk) R.color.brand_success else R.color.muted),
        )
        if (modelOk) {
            b.modelProgressBar.visibility = View.GONE
            b.modelProgress.text = ""
        }

        b.bubbleBtn.isEnabled = micOk && overlayOk && modelOk
        b.dictateBtn.isEnabled = micOk && modelOk
        b.downloadBtn.visibility =
            if (!modelOk && ModelConfig.hasRemoteSource) View.VISIBLE else View.GONE
    }

    /**
     * Styles one setup card to reflect [ok]: granted → faint-green tint, green stroke, a
     * "מופעל" status, a check icon and a hidden CTA; not granted → neutral surface, the
     * descriptive subtitle and a visible "הגדר" button.
     */
    private fun bindPermCard(
        card: MaterialCardView,
        status: TextView,
        grantBtn: MaterialButton,
        check: View,
        descRes: Int,
        ok: Boolean,
    ) {
        if (ok) {
            card.setCardBackgroundColor(ContextCompat.getColor(this, R.color.success_container))
            card.setStrokeColor(ContextCompat.getColor(this, R.color.success_outline))
            status.text = getString(R.string.status_granted)
            status.setTextColor(ContextCompat.getColor(this, R.color.on_success_container))
            grantBtn.visibility = View.GONE
            check.visibility = View.VISIBLE
        } else {
            card.setCardBackgroundColor(ContextCompat.getColor(this, R.color.app_surface))
            card.setStrokeColor(ContextCompat.getColor(this, R.color.outline))
            status.text = getString(descRes)
            status.setTextColor(ContextCompat.getColor(this, R.color.muted))
            grantBtn.visibility = View.VISIBLE
            check.visibility = View.GONE
        }
    }

    private fun downloadModel() {
        if (!ModelConfig.hasRemoteSource) return
        b.downloadBtn.isEnabled = false
        b.modelProgressBar.isIndeterminate = false
        b.modelProgressBar.visibility = View.VISIBLE
        Thread {
            try {
                val f = ModelDownloader.ensure(
                    this,
                    ModelConfig.URL,
                    ModelConfig.SHA256,
                    ModelConfig.FILE_NAME,
                ) { p ->
                    runOnUiThread {
                        b.modelProgress.text = getString(R.string.model_downloading, p)
                        b.modelProgressBar.setProgressCompat(p, true)
                    }
                }
                DictationCore.loadModel(f.absolutePath)
                runOnUiThread {
                    b.modelProgress.text = getString(R.string.model_done)
                    refresh()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    b.modelProgress.text = getString(R.string.model_failed, e.message ?: "")
                    b.downloadBtn.isEnabled = true
                }
            }
        }.start()
    }

    // --- Permissions ---------------------------------------------------------

    private fun requestMic() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        permLauncher.launch(perms.toTypedArray())
    }

    private fun requestOverlay() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            ),
        )
    }

    private fun isAccessibilityEnabled(): Boolean {
        val flat = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return flat.split(":").any {
            it.startsWith("$packageName/") && it.contains("InsertionService")
        }
    }

    // --- Model ---------------------------------------------------------------

    private fun maybeLoadModel() {
        if (DictationCore.modelLoaded) return
        val model = ModelStore.modelFile(this)
        if (!model.exists()) return
        Thread {
            DictationCore.loadModel(model.absolutePath)
            runOnUiThread { refresh() }
        }.start()
    }

    /**
     * Debug-only: transcribe a 16 kHz mono WAV and log the result. Triggered via
     * `adb shell am start -n com.dabber/.MainActivity --es transcribe_wav <path>`.
     * Used to validate the on-device engine against a known clip without live mic input.
     */
    private fun runDebugTranscription(path: String) {
        Thread {
            val model = ModelStore.modelFile(this)
            val loaded = DictationCore.modelLoaded || DictationCore.loadModel(model.absolutePath)
            Log.i(TAG, "model loaded=$loaded path=${model.absolutePath} exists=${model.exists()}")
            if (!loaded) {
                Log.e(TAG, "MODEL LOAD FAILED")
                return@Thread
            }
            val pcm = WavReader.readPcm16Mono(File(path))
            Log.i(TAG, "wav samples=${pcm.size}")
            val t0 = System.currentTimeMillis()
            val text = DictationCore.transcribeClean(pcm, ModelConfig.LANG)
            Log.i(TAG, "TRANSCRIPT(${System.currentTimeMillis() - t0}ms): $text")
        }.start()
    }

    // --- Bubble --------------------------------------------------------------

    private fun toggleBubble() {
        OverlayService.start(this)
        toast(getString(R.string.bubble_started))
    }

    // --- In-app dictation test ----------------------------------------------

    private fun dictateIntoScratchpad() {
        if (!DictationCore.modelLoaded) { toast(getString(R.string.no_model)); return }
        if (!AudioRecorder.hasPermission(this)) { requestMic(); return }
        b.dictateBtn.isEnabled = false
        b.dictateBtn.text = getString(R.string.dictating)
        Thread {
            val pcm = testRecorder.record()
            runOnUiThread { b.dictateBtn.text = getString(R.string.transcribing) }
            val text = DictationCore.transcribeClean(pcm, ModelConfig.LANG)
            runOnUiThread {
                if (text.isNotBlank()) {
                    val at = b.scratch.selectionStart.coerceIn(0, b.scratch.text.length)
                    b.scratch.text.insert(at, text)
                } else {
                    toast(getString(R.string.no_speech))
                }
                b.dictateBtn.isEnabled = true
                b.dictateBtn.text = getString(R.string.dictate_btn)
            }
        }.start()
    }

    // --- Helpers -------------------------------------------------------------

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val TAG = "DabberTest"
    }
}
