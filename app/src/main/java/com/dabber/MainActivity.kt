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

    @Volatile private var dictating = false

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
        b.benchmarkBtn.setOnClickListener {
            startActivity(Intent(this, com.dabber.bench.BenchmarkActivity::class.java))
        }

        intent?.getStringExtra("transcribe_wav")?.let { runDebugTranscription(it) }
        intent?.getStringExtra("onnx_wav")?.let { runOnnxDebugTranscription(it) }
        intent?.getStringExtra("qnn_wav")?.let { runQnnDebugTranscription(it) }
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

        b.bubbleBtn.isEnabled = micOk && overlayOk && modelOk && a11yOk && !dictating
        b.dictateBtn.isEnabled = micOk && modelOk && !dictating
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
                val loaded = DictationCore.loadModel(f.absolutePath)
                runOnUiThread {
                    b.modelProgressBar.visibility = View.GONE
                    if (loaded) {
                        b.modelProgress.text = getString(R.string.model_done)
                    } else {
                        b.modelProgress.text = getString(R.string.model_failed, "load")
                        b.downloadBtn.isEnabled = true
                    }
                    refresh()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    b.modelProgressBar.visibility = View.GONE
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

    /**
     * Debug-only: transcribe a 16 kHz mono WAV with the native-Kotlin ONNX Runtime engine
     * ([com.dabber.npu.OnnxWhisperEngine], CPU EP). Models must be pushed to
     * `filesDir/onnx-turbo/` (encoder_model.onnx + .onnx_data sidecar, decoder_model.onnx).
     * Triggered via:
     *   `adb shell am start -n com.dabber/.MainActivity --es onnx_wav <path>`
     * Runs in parallel to the whisper.cpp `transcribe_wav` hook; logs under "DabberOnnx".
     */
    private fun runOnnxDebugTranscription(path: String) {
        Thread {
            val onnxDir = File(filesDir, "onnx-turbo")
            Log.i(ONNX_TAG, "onnxDir=${onnxDir.absolutePath} exists=${onnxDir.exists()}")
            val engine = com.dabber.npu.OnnxWhisperEngine()
            try {
                if (!engine.load(onnxDir)) {
                    Log.e(ONNX_TAG, "ONNX LOAD FAILED (need encoder_model.onnx + decoder_model.onnx)")
                    return@Thread
                }
                val pcm = WavReader.readPcm16Mono(File(path))
                Log.i(ONNX_TAG, "wav samples=${pcm.size}")
                val t0 = System.currentTimeMillis()
                val text = engine.transcribe(this, pcm, "he")
                Log.i(ONNX_TAG, "ONNX(${System.currentTimeMillis() - t0}ms): $text")
            } catch (e: Exception) {
                Log.e(ONNX_TAG, "ONNX transcription failed", e)
            } finally {
                engine.close()
            }
        }.start()
    }

    /**
     * Debug-only: transcribe a 16 kHz mono WAV on the Hexagon NPU via the QNN execution
     * provider ([com.dabber.npu.QnnWhisperEngine]). Models must be pushed to
     * `filesDir/npu-qnn/` (HfWhisperEncoder.onnx + HfWhisperEncoder_qairt_context.bin and
     * HfWhisperDecoder.onnx + HfWhisperDecoder_qairt_context.bin — each .onnx beside its .bin).
     * Triggered via:
     *   `adb shell am start -n com.dabber/.MainActivity --es qnn_wav <path>`
     * Runs in parallel to the onnx_wav / transcribe_wav hooks; logs under "DabberQnn".
     * arm64/phone only — the QNN HTP libs do not exist on the x86_64 emulator.
     */
    private fun runQnnDebugTranscription(path: String) {
        Thread {
            val qnnDir = File(filesDir, "npu-qnn")
            Log.i(QNN_TAG, "qnnDir=${qnnDir.absolutePath} exists=${qnnDir.exists()}")
            val engine = com.dabber.npu.QnnWhisperEngine()
            try {
                if (!engine.load(qnnDir)) {
                    Log.e(QNN_TAG, "QNN LOAD FAILED (need HfWhisperEncoder/Decoder .onnx + _qairt_context.bin; arm64 device only)")
                    return@Thread
                }
                val pcm = WavReader.readPcm16Mono(File(path))
                Log.i(QNN_TAG, "wav samples=${pcm.size}")
                val t0 = System.currentTimeMillis()
                val text = engine.transcribe(this, pcm, "he")
                Log.i(QNN_TAG, "QNN(${System.currentTimeMillis() - t0}ms): $text")
            } catch (e: Exception) {
                Log.e(QNN_TAG, "QNN transcription failed", e)
            } finally {
                engine.close()
            }
        }.start()
    }

    // --- Bubble --------------------------------------------------------------

    private fun toggleBubble() {
        if (!isAccessibilityEnabled()) {
            toast(getString(R.string.perm_a11y_grant))
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); return
        }
        OverlayService.start(this)
        toast(getString(R.string.bubble_started))
    }

    // --- In-app dictation test ----------------------------------------------

    private fun dictateIntoScratchpad() {
        if (!DictationCore.modelLoaded) { toast(getString(R.string.no_model)); return }
        if (!AudioRecorder.hasPermission(this)) { requestMic(); return }
        if (dictating) return
        dictating = true
        b.dictateBtn.isEnabled = false
        b.dictateBtn.text = getString(R.string.dictating)
        Thread {
            try {
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
                }
            } finally {
                runOnUiThread {
                    dictating = false
                    b.dictateBtn.text = getString(R.string.dictate_btn)
                    refresh()
                }
            }
        }.start()
    }

    // --- Helpers -------------------------------------------------------------

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val TAG = "DabberTest"
        private const val ONNX_TAG = "DabberOnnx"
        private const val QNN_TAG = "DabberQnn"
    }
}
