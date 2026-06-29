package com.dabber.bench

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.dabber.R
import com.dabber.databinding.ActivityBenchmarkBinding
import com.google.android.material.button.MaterialButton
import java.util.Locale
import java.util.concurrent.Executors

/**
 * On-device benchmark screen (Hebrew/RTL).
 *
 * The user picks a quantization (q4_0 / q5_0 / q8_0), runs it, and the app downloads the
 * model once, transcribes the three bundled fixtures on the *real* hardware, and appends a
 * comparison row (avg ms · RTF · WER%). Running several variants accumulates rows so the
 * speed/accuracy trade-off can be read side by side, with an expandable per-clip breakdown.
 *
 * All heavy work runs on a single-thread [Executors] pool; the UI is touched only via
 * [runOnUiThread]. A private [WhisperEngine][com.dabber.asr.WhisperEngine] inside
 * [BenchmarkRunner] keeps the live dictation engine untouched.
 */
class BenchmarkActivity : AppCompatActivity() {

    private lateinit var b: ActivityBenchmarkBinding

    /** Serializes benchmark runs; one at a time, off the main thread. */
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    private var running = false

    /** Toggle-group buttons in registry order, so button index maps to [ModelVariants.ALL]. */
    private val variantButtons: List<MaterialButton> by lazy { listOf(b.btnQ4, b.btnQ5, b.btnQ8) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityBenchmarkBinding.inflate(layoutInflater)
        setContentView(b.root)

        // Label the toggle buttons from the single source of truth (ModelVariants).
        variantButtons.forEachIndexed { i, btn -> btn.text = ModelVariants.ALL[i].label }
        b.variantToggle.check(b.btnQ4.id)

        b.backBtn.setOnClickListener { finish() }
        b.benchRunBtn.setOnClickListener { startRun() }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    // --- Run orchestration ---------------------------------------------------

    private fun startRun() {
        if (running) return
        val variant = selectedVariant() ?: return

        running = true
        b.benchRunBtn.isEnabled = false
        b.benchProgress.visibility = View.VISIBLE
        b.benchStatus.setTextColor(color(R.color.muted))
        b.benchStatus.text = getString(R.string.bench_preparing)

        executor.execute {
            try {
                val result = BenchmarkRunner.run(this, variant) { msg ->
                    runOnUiThread { b.benchStatus.text = msg }
                }
                runOnUiThread { onRunFinished(result) }
            } catch (e: Exception) {
                runOnUiThread { onRunFailed(e) }
            }
        }
    }

    private fun onRunFinished(result: VariantResult) {
        b.benchDevice.text = getString(R.string.bench_device, result.deviceModel, result.threads)
        b.benchResultsCard.visibility = View.VISIBLE
        addSummaryRow(result)
        addDetailBlock(result)

        b.benchStatus.setTextColor(color(R.color.brand_success))
        b.benchStatus.text = getString(R.string.bench_done)
        finishRun()
    }

    private fun onRunFailed(e: Exception) {
        b.benchStatus.setTextColor(color(R.color.brand_accent))
        b.benchStatus.text = getString(R.string.bench_failed, e.message ?: "")
        finishRun()
    }

    private fun finishRun() {
        running = false
        b.benchRunBtn.isEnabled = true
        b.benchProgress.visibility = View.GONE
    }

    private fun selectedVariant(): Variant? {
        val index = variantButtons.indexOfFirst { it.id == b.variantToggle.checkedButtonId }
        return if (index >= 0) ModelVariants.ALL[index] else null
    }

    // --- Comparison table ----------------------------------------------------

    /** Appends one weighted, 4-column row mirroring the static header in the layout. */
    private fun addSummaryRow(r: VariantResult) {
        if (b.benchTable.childCount > 0) b.benchTable.addView(divider(topMargin = 0))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        row.addView(cell(r.variantId, 1.8f, Gravity.START or Gravity.CENTER_VERTICAL, bold = true, colorRes = R.color.on_surface))
        row.addView(cell(r.avgMs.toString(), 1.7f, Gravity.CENTER, bold = false, colorRes = R.color.on_surface))
        row.addView(cell(rtf(r.totalRtf), 1.6f, Gravity.CENTER, bold = false, colorRes = R.color.on_surface))
        row.addView(cell(pct(r.avgWer), 1.3f, Gravity.CENTER, bold = true, colorRes = werColor(r.avgWer)))
        b.benchTable.addView(row)
    }

    // --- Expandable per-clip detail ------------------------------------------

    private fun addDetailBlock(r: VariantResult) {
        val block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { if (b.benchDetails.childCount > 0) it.topMargin = dp(8) }
        }

        val clipList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(4), dp(8), dp(4), dp(4))
        }

        val header = TextView(this).apply {
            text = getString(R.string.bench_detail_collapsed, r.label)
            setTextColor(color(R.color.brand_primary))
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(4), dp(8), dp(4), dp(8))
            setOnClickListener {
                val show = clipList.visibility != View.VISIBLE
                clipList.visibility = if (show) View.VISIBLE else View.GONE
                text = getString(
                    if (show) R.string.bench_detail_expanded else R.string.bench_detail_collapsed,
                    r.label,
                )
            }
        }

        for (clip in r.clips) {
            val line = TextView(this).apply {
                text = getString(
                    R.string.bench_clip_line,
                    clip.name,
                    clip.ms,
                    sec(clip.audioSec),
                    pct(clip.wer),
                )
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
            clipList.addView(line)
            clipList.addView(text)
        }

        block.addView(header)
        block.addView(clipList)
        b.benchDetails.addView(block)
    }

    // --- View builders / formatting ------------------------------------------

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
}
