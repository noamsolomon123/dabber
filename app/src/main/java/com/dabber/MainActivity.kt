package com.dabber

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.dabber.audio.AudioRecorder
import com.dabber.audio.WavReader
import com.dabber.core.DictationCore
import com.dabber.model.ModelConfig
import com.dabber.model.ModelDownloader
import com.dabber.model.ModelStore
import com.dabber.overlay.OverlayService
import java.io.File

/**
 * Single-screen control panel (Hebrew/RTL): grant the three permissions, load the model,
 * toggle the floating bubble, and a built-in scratchpad to test dictation without leaving
 * the app (works even before accessibility/overlay are enabled).
 */
class MainActivity : AppCompatActivity() {

    private val testRecorder = AudioRecorder()

    private lateinit var micStatus: TextView
    private lateinit var overlayStatus: TextView
    private lateinit var a11yStatus: TextView
    private lateinit var modelStatus: TextView
    private lateinit var modelProgress: TextView
    private lateinit var downloadBtn: Button
    private lateinit var bubbleBtn: Button
    private lateinit var dictateBtn: Button
    private lateinit var scratch: EditText

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        intent?.getStringExtra("transcribe_wav")?.let { runDebugTranscription(it) }
    }

    override fun onResume() {
        super.onResume()
        maybeLoadModel()
        refresh()
    }

    private fun buildUi(): View {
        val pad = dp(20)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(pad, pad, pad, pad)
        }

        col.addView(title(getString(R.string.app_name), 28f))
        col.addView(label(getString(R.string.app_tagline)))

        col.addView(header(getString(R.string.section_perms)))
        micStatus = label("")
        overlayStatus = label("")
        a11yStatus = label("")
        col.addView(micStatus)
        col.addView(button(getString(R.string.perm_mic_grant)) { requestMic() })
        col.addView(overlayStatus)
        col.addView(button(getString(R.string.perm_overlay_grant)) { requestOverlay() })
        col.addView(a11yStatus)
        col.addView(button(getString(R.string.perm_a11y_grant)) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        col.addView(header(getString(R.string.section_model)))
        modelStatus = label("")
        col.addView(modelStatus)
        modelProgress = label("")
        col.addView(modelProgress)
        downloadBtn = button(getString(R.string.model_download)) { downloadModel() }
        col.addView(downloadBtn)

        col.addView(header(getString(R.string.section_bubble)))
        bubbleBtn = button(getString(R.string.bubble_start)) { toggleBubble() }
        col.addView(bubbleBtn)

        col.addView(header(getString(R.string.section_test)))
        scratch = EditText(this).apply {
            hint = getString(R.string.scratch_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            gravity = Gravity.TOP or Gravity.END
            textDirection = View.TEXT_DIRECTION_RTL
        }
        col.addView(scratch)
        dictateBtn = button(getString(R.string.dictate_btn)) { dictateIntoScratchpad() }
        col.addView(dictateBtn)

        return ScrollView(this).apply { addView(col) }
    }

    // --- State refresh -------------------------------------------------------

    private fun refresh() {
        val micOk = AudioRecorder.hasPermission(this)
        val overlayOk = Settings.canDrawOverlays(this)
        val a11yOk = isAccessibilityEnabled()
        val modelOk = DictationCore.modelLoaded

        micStatus.text = statusLine(R.string.perm_mic, micOk)
        overlayStatus.text = statusLine(R.string.perm_overlay, overlayOk)
        a11yStatus.text = statusLine(R.string.perm_a11y, a11yOk)
        modelStatus.text =
            if (modelOk) getString(R.string.model_loaded) else getString(R.string.model_missing)

        bubbleBtn.isEnabled = micOk && overlayOk && modelOk
        dictateBtn.isEnabled = micOk && modelOk
        downloadBtn.visibility =
            if (!modelOk && ModelConfig.hasRemoteSource) View.VISIBLE else View.GONE
    }

    private fun downloadModel() {
        if (!ModelConfig.hasRemoteSource) return
        downloadBtn.isEnabled = false
        Thread {
            try {
                val f = ModelDownloader.ensure(
                    this,
                    ModelConfig.URL,
                    ModelConfig.SHA256,
                    ModelConfig.FILE_NAME,
                ) { p -> runOnUiThread { modelProgress.text = getString(R.string.model_downloading, p) } }
                DictationCore.loadModel(f.absolutePath)
                runOnUiThread {
                    modelProgress.text = getString(R.string.model_done)
                    refresh()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    modelProgress.text = getString(R.string.model_failed, e.message ?: "")
                    downloadBtn.isEnabled = true
                }
            }
        }.start()
    }

    private fun statusLine(labelRes: Int, ok: Boolean): String {
        val mark = if (ok) "✓" else "✗"
        return "$mark  ${getString(labelRes)}"
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
        dictateBtn.isEnabled = false
        dictateBtn.text = getString(R.string.dictating)
        Thread {
            val pcm = testRecorder.record()
            runOnUiThread { dictateBtn.text = getString(R.string.transcribing) }
            val text = DictationCore.transcribeClean(pcm, ModelConfig.LANG)
            runOnUiThread {
                if (text.isNotBlank()) {
                    val at = scratch.selectionStart.coerceIn(0, scratch.text.length)
                    scratch.text.insert(at, text)
                } else {
                    toast(getString(R.string.no_speech))
                }
                dictateBtn.isEnabled = true
                dictateBtn.text = getString(R.string.dictate_btn)
            }
        }.start()
    }

    // --- Tiny view builders --------------------------------------------------

    private fun title(text: String, size: Float) = TextView(this).apply {
        this.text = text; textSize = size; setPadding(0, dp(8), 0, dp(8))
    }

    private fun header(text: String) = TextView(this).apply {
        this.text = text; textSize = 18f; setPadding(0, dp(20), 0, dp(6))
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text; textSize = 15f; setPadding(0, dp(4), 0, dp(4))
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text; setOnClickListener { onClick() }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "DabberTest"
    }
}
