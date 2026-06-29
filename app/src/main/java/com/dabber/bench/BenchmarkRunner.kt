package com.dabber.bench

import android.content.Context
import android.os.Build
import com.dabber.asr.WhisperEngine
import com.dabber.audio.WavReader
import com.dabber.model.ModelDownloader
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Timing + accuracy for a single benchmark clip transcribed by one variant. */
data class ClipResult(
    val name: String,
    val ms: Long,
    val audioSec: Double,
    val wer: Double,
    val text: String,
)

/**
 * Aggregated benchmark result for one [Variant] run over all fixtures.
 *
 * @property avgMs   mean wall-clock transcription time per clip.
 * @property totalRtf real-time factor over the whole set: `(sum ms / 1000) / sum audioSec`.
 *                    `< 1.0` means faster than real time.
 * @property avgWer  mean per-clip Word Error Rate (see [HebrewWer]).
 */
data class VariantResult(
    val variantId: String,
    val label: String,
    val sizeMb: Int,
    val deviceModel: String,
    val threads: Int,
    val clips: List<ClipResult>,
) {
    val avgMs: Long
        get() = if (clips.isEmpty()) 0L else clips.sumOf { it.ms } / clips.size

    val totalRtf: Double
        get() {
            val totalAudio = clips.sumOf { it.audioSec }
            if (totalAudio <= 0.0) return 0.0
            val totalMs = clips.sumOf { it.ms }
            return (totalMs / 1000.0) / totalAudio
        }

    val avgWer: Double
        get() = if (clips.isEmpty()) 0.0 else clips.sumOf { it.wer } / clips.size
}

/**
 * Runs the bundled on-device benchmark for a chosen [Variant].
 *
 * Fixtures live in `assets/bench/`: a `refs.json` map of `<clip>.wav -> reference`
 * plus the matching 16 kHz mono WAVs. They are decoded once and cached so repeated
 * runs (e.g. comparing q4 → q5 → q8) reuse the same PCM without re-copying.
 *
 * The runner owns a *private* [WhisperEngine] instance so it never disturbs the shared
 * [com.dabber.core.DictationCore] engine that powers the live overlay/scratchpad.
 *
 * **Blocking — performs network, disk and native CPU work; call off the main thread.**
 */
object BenchmarkRunner {

    private const val BENCH_DIR = "bench"
    private const val REFS_FILE = "refs.json"
    private const val SAMPLE_RATE = 16000.0

    /** A decoded benchmark clip plus its Hebrew reference transcript. */
    private class Clip(
        val name: String,
        val pcm: FloatArray,
        val audioSec: Double,
        val ref: String,
    )

    @Volatile
    private var cachedClips: List<Clip>? = null

    /**
     * Downloads/verifies [variant] (reporting download % via [onProgress]), loads it into
     * a private engine, transcribes every fixture while timing it with [System.nanoTime],
     * scores each against its reference with [HebrewWer], releases the engine and returns
     * the aggregated [VariantResult].
     *
     * @throws IOException if the model cannot be downloaded, verified or loaded.
     */
    @Throws(IOException::class)
    fun run(context: Context, variant: Variant, onProgress: (String) -> Unit): VariantResult {
        val clips = loadClips(context)

        val modelFile = ModelDownloader.ensure(
            context,
            variant.url,
            variant.sha256,
            variant.fileName,
        ) { p -> onProgress("מוריד… $p%") }

        val engine = WhisperEngine()
        val threads = WhisperEngine.defaultThreads()
        val results = ArrayList<ClipResult>(clips.size)
        try {
            onProgress("טוען מודל…")
            if (!engine.load(modelFile.absolutePath)) {
                throw IOException("טעינת המודל נכשלה: ${variant.fileName}")
            }
            clips.forEachIndexed { index, clip ->
                onProgress("מתמלל ${index + 1}/${clips.size}…")
                val t0 = System.nanoTime()
                val text = engine.transcribe(clip.pcm, "he", threads)
                val ms = (System.nanoTime() - t0) / 1_000_000L
                results += ClipResult(
                    name = clip.name,
                    ms = ms,
                    audioSec = clip.audioSec,
                    wer = HebrewWer.wer(clip.ref, text),
                    text = text,
                )
            }
        } finally {
            engine.release()
        }

        return VariantResult(
            variantId = variant.id,
            label = variant.label,
            sizeMb = variant.sizeMb,
            deviceModel = Build.MODEL,
            threads = threads,
            clips = results,
        )
    }

    /** Parses `refs.json` and decodes each fixture WAV once; cached for later runs. */
    private fun loadClips(context: Context): List<Clip> {
        cachedClips?.let { return it }
        synchronized(this) {
            cachedClips?.let { return it }

            val refs = JSONObject(readAsset(context, "$BENCH_DIR/$REFS_FILE"))
            // Sort names for a stable, deterministic clip order across runs.
            val names = refs.keys().asSequence().toList().sorted()

            val clips = names.map { name ->
                val ref = refs.getString(name)
                val wav = copyAssetToCache(context, "$BENCH_DIR/$name", name)
                val pcm = WavReader.readPcm16Mono(wav)
                Clip(name = name, pcm = pcm, audioSec = pcm.size / SAMPLE_RATE, ref = ref)
            }
            cachedClips = clips
            return clips
        }
    }

    private fun copyAssetToCache(context: Context, assetPath: String, fileName: String): File {
        val out = File(context.cacheDir, fileName)
        context.assets.open(assetPath).use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        }
        return out
    }

    private fun readAsset(context: Context, assetPath: String): String =
        context.assets.open(assetPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
}
