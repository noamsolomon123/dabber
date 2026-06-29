package com.dabber.bench

import android.Manifest
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.dabber.R
import com.dabber.audio.AudioRecorder
import com.dabber.databinding.ActivityBenchmarkBinding
import java.util.Locale
import java.util.concurrent.Executors

/**
 * On-device benchmark screen (Hebrew/RTL).
 *
 * ### Primary flow — record once, compare all three models
 * The user taps one button; the app records *their own* voice (privacy-safe — never leaves the
 * device, no bundled personal file needed), then runs every quantization (q4_0 → q5_0 → q8_0)
 * over that single recording. For each model it downloads/verifies it once, loads a *private*
 * [com.dabber.asr.WhisperEngine], times one Hebrew transcription, and releases it. Results are a
 * comparison table — model · time (s) · RTF — with the transcribed text under each row so quality
 * is judged by reading. No WER here (a personal recording has no ground-truth reference).
 *
 * ### Secondary flow — accuracy (WER)
 * An optional button runs the bundled-FLEURS accuracy path ([BenchmarkRunner.run]) with
 * [HebrewWer] over `assets/bench`, showing ms · RTF · WER% with an expandable per-clip breakdown.
 *
 * ### Robustness
 * Every run is wrapped so the UI can **never** freeze: a `finally` always re-enables the buttons,
 * hides the spinner, stops the live timer and clears the running flag. [UnsatisfiedLinkError] and
 * [OutOfMemoryError] get dedicated messages, and each model has its own try/catch so one failure
 * still lets the others finish and appear in the table. A live elapsed timer keeps the status
 * line moving while a model transcribes so it never looks stuck.
 *
 * Heavy work runs on a single-thread [Executors] pool; the UI is touched only via [runOnUiThread]
 * (or the main-looper [ui] handler that drives the timer).
 */
class BenchmarkActivity : AppCompatActivity() {

    private lateinit var b: ActivityBenchmarkBinding

    /** Serializes benchmark runs; one at a time, off the main thread. */
    private val executor = Executors.newSingleThreadExecutor()

    /** Main-looper handler that drives the live elapsed timer. */
    private val ui = Handler(Looper.getMainLooper())

    private val recorder = AudioRecorder()

    @Volatile
    private var running = false

    /** On grant, kick off the recording run the user just asked for. */
    private val micPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startRecordRun() else setStatus(getString(R.string.need_mic), R.color.brand_accent)
        }

    // --- Live elapsed timer --------------------------------------------------

    private var timerStart = 0L
    private var timerLabel = ""

    /** Repaints the status line with the running model's elapsed seconds every ~500ms. */
    private val timerTick = object : Runnable {
        override fun run() {
            val sec = (System.currentTimeMillis() - timerStart) / 1000L
            b.benchStatus.text = getString(R.string.bench_running_elapsed, timerLabel, sec)
            ui.postDelayed(this, TIMER_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityBenchmarkBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.backBtn.setOnClickListener { finish() }
        b.benchRecordBtn.setOnClickListener { onRecordTapped() }
        b.benchWerBtn.setOnClickListener { startWerRun() }
    }

    override fun onDestroy() {
        ui.removeCallbacks(timerTick)
        executor.shutdownNow()
        super.onDestroy()
    }

    // --- Primary flow: record once, benchmark all three models ----------------

    private fun onRecordTapped() {
        if (running) return
        if (!AudioRecorder.hasPermission(this)) {
            micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startRecordRun()
    }

    private fun startRecordRun() {
        if (running) return
        beginRun()
        executor.execute {
            try {
                runOnUiThread { setStatus(getString(R.string.bench_recording), R.color.brand_working) }
                val pcm = recorder.record()
                if (pcm.isEmpty()) {
                    runOnUiThread { setStatus(getString(R.string.no_speech), R.color.brand_accent) }
                    return@execute
                }
                val audioSec = pcm.size / SAMPLE_RATE_HZ
                runOnUiThread { prepareResultsTable(::addRecordHeader) }

                for (variant in ModelVariants.ALL) {
                    val result = runOneRecordModel(variant, pcm, audioSec)
                    runOnUiThread { addRecordRow(result) }
                }
                runOnUiThread { setStatus(getString(R.string.bench_done), R.color.brand_success) }
            } catch (e: UnsatisfiedLinkError) {
                runOnUiThread { setStatus(getString(R.string.bench_err_native), R.color.brand_accent) }
            } catch (e: OutOfMemoryError) {
                runOnUiThread { setStatus(getString(R.string.bench_err_oom), R.color.brand_accent) }
            } catch (t: Throwable) {
                runOnUiThread { setStatus(getString(R.string.bench_failed, t.message ?: ""), R.color.brand_accent) }
            } finally {
                runOnUiThread { endRun() }
            }
        }
    }

    /** Downloads, loads, times and releases one model — never throws (failures become a row). */
    private fun runOneRecordModel(variant: Variant, pcm: FloatArray, audioSec: Double): RecordResult {
        return try {
            val modelFile = BenchmarkRunner.ensureModel(this, variant) { percent ->
                runOnUiThread {
                    if (running) setStatus(getString(R.string.bench_downloading, variant.id, percent), R.color.muted)
                }
            }
            runOnUiThread { startElapsedTimer(variant.id) }
            val (ms, text) = BenchmarkRunner.transcribePcm(modelFile, pcm)
            RecordResult(variant.id, ms, audioSec, text, error = null)
        } catch (e: UnsatisfiedLinkError) {
            RecordResult(variant.id, 0L, audioSec, "", getString(R.string.bench_err_native))
        } catch (e: OutOfMemoryError) {
            RecordResult(variant.id, 0L, audioSec, "", getString(R.string.bench_err_oom))
        } catch (t: Throwable) {
            RecordResult(variant.id, 0L, audioSec, "", t.message ?: getString(R.string.bench_err_generic))
        } finally {
            runOnUiThread { stopElapsedTimer() }
        }
    }

    // --- Secondary flow: bundled-FLEURS accuracy (WER) ------------------------

    private fun startWerRun() {
        if (running) return
        beginRun()
        executor.execute {
            try {
                runOnUiThread { prepareResultsTable(::addWerHeader) }
                for (variant in ModelVariants.ALL) {
                    val outcome = runOneWerModel(variant)
                    runOnUiThread { addWerRow(outcome) }
                }
                runOnUiThread { setStatus(getString(R.string.bench_done), R.color.brand_success) }
            } catch (e: UnsatisfiedLinkError) {
                runOnUiThread { setStatus(getString(R.string.bench_err_native), R.color.brand_accent) }
            } catch (e: OutOfMemoryError) {
                runOnUiThread { setStatus(getString(R.string.bench_err_oom), R.color.brand_accent) }
            } catch (t: Throwable) {
                runOnUiThread { setStatus(getString(R.string.bench_failed, t.message ?: ""), R.color.brand_accent) }
            } finally {
                runOnUiThread { endRun() }
            }
        }
    }

    /** Runs the existing accuracy path for one model — never throws (failures become a row). */
    private fun runOneWerModel(variant: Variant): WerOutcome {
        return try {
            val result = BenchmarkRunner.run(this, variant) { msg ->
                runOnUiThread { if (running) setStatus(msg, R.color.muted) }
            }
            WerOutcome(variant.id, result, error = null)
        } catch (e: UnsatisfiedLinkError) {
            WerOutcome(variant.id, null, getString(R.string.bench_err_native))
        } catch (e: OutOfMemoryError) {
            WerOutcome(variant.id, null, getString(R.string.bench_err_oom))
        } catch (t: Throwable) {
            WerOutcome(variant.id, null, t.message ?: getString(R.string.bench_err_generic))
        }
    }

    /** One model's result for the WER table; [result] is null when the model failed. */
    private class WerOutcome(val variantId: String, val result: VariantResult?, val error: String?)

    // --- Run lifecycle (crash-proof) -----------------------------------------

    private fun beginRun() {
        running = true
        b.benchRecordBtn.isEnabled = false
        b.benchWerBtn.isEnabled = false
        b.benchProgress.visibility = View.VISIBLE
        setStatus(getString(R.string.bench_preparing), R.color.muted)
    }

    /** Always restores an interactive screen — guarantees we never freeze on "מכין…". */
    private fun endRun() {
        stopElapsedTimer()
        running = false
        b.benchRecordBtn.isEnabled = true
        b.benchWerBtn.isEnabled = true
        b.benchProgress.visibility = View.GONE
    }

    private fun startElapsedTimer(label: String) {
        timerLabel = label
        timerStart = System.currentTimeMillis()
        b.benchStatus.setTextColor(color(R.color.brand_working))
        ui.removeCallbacks(timerTick)
        ui.post(timerTick)
    }

    private fun stopElapsedTimer() {
        ui.removeCallbacks(timerTick)
    }

    // --- Results table -------------------------------------------------------

    private fun prepareResultsTable(addHeader: () -> Unit) {
        b.benchDevice.text = getString(R.string.bench_device_model, Build.MODEL)
        b.benchResultsCard.visibility = View.VISIBLE
        b.benchTable.removeAllViews()
        addHeader()
    }

    private fun addRecordHeader() {
        val header = headerRow()
        header.addView(headerCell(getString(R.string.bench_col_model), 1.6f, Gravity.START or Gravity.CENTER_VERTICAL))
        header.addView(headerCell(getString(R.string.bench_col_sec), 1.4f, Gravity.CENTER))
        header.addView(headerCell(getString(R.string.bench_col_rtf), 1.4f, Gravity.CENTER))
        b.benchTable.addView(header)
    }

    /** Appends a model row (model · seconds · RTF) plus its transcribed text (RTL) below it. */
    private fun addRecordRow(r: RecordResult) {
        b.benchTable.addView(divider(dp(8)))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(6))
        }
        row.addView(cell(r.variantId, 1.6f, Gravity.START or Gravity.CENTER_VERTICAL, bold = true, colorRes = R.color.on_surface))
        if (r.ok) {
            row.addView(cell(sec(r.seconds), 1.4f, Gravity.CENTER, bold = false, colorRes = R.color.on_surface))
            row.addView(cell(rtf(r.rtf), 1.4f, Gravity.CENTER, bold = false, colorRes = R.color.on_surface))
        } else {
            row.addView(cell(DASH, 1.4f, Gravity.CENTER, bold = false, colorRes = R.color.muted))
            row.addView(cell(DASH, 1.4f, Gravity.CENTER, bold = false, colorRes = R.color.muted))
        }
        b.benchTable.addView(row)

        b.benchTable.addView(transcriptView(r.ok, r.text, r.error))
    }

    private fun addWerHeader() {
        val header = headerRow()
        header.addView(headerCell(getString(R.string.bench_col_model), 1.8f, Gravity.START or Gravity.CENTER_VERTICAL))
        header.addView(headerCell(getString(R.string.bench_col_ms), 1.7f, Gravity.CENTER))
        header.addView(headerCell(getString(R.string.bench_col_rtf), 1.6f, Gravity.CENTER))
        header.addView(headerCell(getString(R.string.bench_col_wer), 1.3f, Gravity.CENTER))
        b.benchTable.addView(header)
    }

    /** Appends a WER summary row; on success it expands to a per-clip breakdown when tapped. */
    private fun addWerRow(o: WerOutcome) {
        b.benchTable.addView(divider(dp(8)))
        val r = o.result

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        if (r != null) {
            row.addView(cell("${o.variantId}  ▾", 1.8f, Gravity.START or Gravity.CENTER_VERTICAL, bold = true, colorRes = R.color.brand_primary))
            row.addView(cell(r.avgMs.toString(), 1.7f, Gravity.CENTER, bold = false, colorRes = R.color.on_surface))
            row.addView(cell(rtf(r.totalRtf), 1.6f, Gravity.CENTER, bold = false, colorRes = R.color.on_surface))
            row.addView(cell(pct(r.avgWer), 1.3f, Gravity.CENTER, bold = true, colorRes = werColor(r.avgWer)))
            b.benchTable.addView(row)

            val clips = buildWerDetails(r)
            row.setOnClickListener { clips.visibility = if (clips.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
            b.benchTable.addView(clips)
        } else {
            row.addView(cell(o.variantId, 1.8f, Gravity.START or Gravity.CENTER_VERTICAL, bold = true, colorRes = R.color.on_surface))
            row.addView(cell(DASH, 1.7f, Gravity.CENTER, bold = false, colorRes = R.color.muted))
            row.addView(cell(DASH, 1.6f, Gravity.CENTER, bold = false, colorRes = R.color.muted))
            row.addView(cell(DASH, 1.3f, Gravity.CENTER, bold = false, colorRes = R.color.muted))
            b.benchTable.addView(row)
            b.benchTable.addView(transcriptView(ok = false, text = "", error = o.error))
        }
    }

    private fun buildWerDetails(r: VariantResult): LinearLayout {
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(12), 0, dp(12), dp(8))
        }
        for (clip in r.clips) {
            val line = TextView(this).apply {
                text = getString(R.string.bench_clip_line, clip.name, clip.ms, sec(clip.audioSec), pct(clip.wer))
                setTextColor(color(R.color.on_surface))
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
            }
            val text = TextView(this).apply {
                text = clip.text.ifBlank { getString(R.string.bench_clip_empty) }
                setTextColor(color(R.color.muted))
                textSize = 13f
                textDirection = View.TEXT_DIRECTION_RTL
                setPadding(0, dp(2), 0, dp(10))
                setLineSpacing(dp(2).toFloat(), 1f)
            }
            list.addView(line)
            list.addView(text)
        }
        return list
    }

    // --- View builders / formatting ------------------------------------------

    /** Transcript (RTL) on success, or the failure reason in accent colour. */
    private fun transcriptView(ok: Boolean, text: String, error: String?): TextView = TextView(this).apply {
        if (ok) {
            this.text = text.ifBlank { getString(R.string.bench_clip_empty) }
            setTextColor(color(if (text.isBlank()) R.color.muted else R.color.on_surface))
        } else {
            this.text = getString(R.string.bench_model_failed, error ?: "")
            setTextColor(color(R.color.brand_accent))
        }
        textSize = 14f
        textDirection = View.TEXT_DIRECTION_RTL
        setPadding(dp(12), 0, dp(12), dp(12))
        setLineSpacing(dp(2).toFloat(), 1f)
    }

    private fun headerRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.bg_scratch)
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }

    private fun headerCell(value: String, weight: Float, gravity: Int): TextView = TextView(this).apply {
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
        text = value
        this.gravity = gravity
        textSize = 12f
        setTextColor(color(R.color.muted))
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun cell(
        value: String,
        weight: Float,
        gravity: Int,
        bold: Boolean,
        colorRes: Int,
    ): TextView = TextView(this).apply {
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
        text = value
        this.gravity = gravity
        textSize = 14f
        setTextColor(color(colorRes))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun divider(topMargin: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).also {
            it.topMargin = topMargin
        }
        setBackgroundColor(color(R.color.outline))
    }

    private fun setStatus(msg: String, colorRes: Int) {
        b.benchStatus.setTextColor(color(colorRes))
        b.benchStatus.text = msg
    }

    private fun color(res: Int): Int = ContextCompat.getColor(this, res)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun pct(wer: Double): String = String.format(Locale.US, "%.1f%%", wer * 100.0)

    private fun rtf(value: Double): String = String.format(Locale.US, "%.2f×", value)

    private fun sec(value: Double): String = String.format(Locale.US, "%.1f", value)

    /** Green for low WER, amber for moderate, red for high — a quick at-a-glance signal. */
    private fun werColor(wer: Double): Int = when {
        wer <= 0.15 -> R.color.brand_success
        wer <= 0.30 -> R.color.brand_working
        else -> R.color.brand_accent
    }

    private companion object {
        /** Matches WhisperEngine / AudioRecorder capture rate; used to derive clip length. */
        const val SAMPLE_RATE_HZ = 16_000.0
        const val TIMER_INTERVAL_MS = 500L
        const val DASH = "—"
    }
}
