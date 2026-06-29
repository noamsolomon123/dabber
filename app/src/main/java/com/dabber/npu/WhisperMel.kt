package com.dabber.npu

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

/**
 * Whisper log-mel front-end, pure Kotlin (no native deps), matching the reference
 * `whisper.audio.log_mel_spectrogram` numerics so the features line up with the
 * optimum ONNX export of openai/whisper-large-v3-turbo.
 *
 * Pipeline for one 30 s window:
 *   1. pad/trim 16 kHz mono PCM (-1..1) to exactly [N_SAMPLES] (480000).
 *   2. reflect-pad by n_fft/2 (200) both ends, then STFT with n_fft=400, hop=160,
 *      periodic Hann window -> 3000 frames of 201 power bins (|X|^2).
 *   3. apply the bundled 128x201 mel filterbank (assets/npu/mel_128.bin, little-endian
 *      float32, row-major mel x fft) -> 128 mel x 3000 frames.
 *   4. Whisper normalization: log10(max(.,1e-10)); clamp to (globalMax-8); (.+4)/4.
 *
 * Output is the flat float array for ORT input shape [1, 128, 3000], laid out as
 * [mel, frame] (index = mel*3000 + frame).
 *
 * The DFT is computed with precomputed cos/sin tables (n_fft=400 is not a power of two,
 * so a radix-2 FFT does not apply). This is the CPU validation path; correctness and
 * clarity are prioritised over peak throughput.
 */
object WhisperMel {

    private const val N_FFT = 400
    private const val HOP = 160
    private const val N_MEL = 128
    private const val N_FRAMES = 3000
    private const val N_BINS = N_FFT / 2 + 1 // 201
    private const val N_SAMPLES = 480000 // 30 s @ 16 kHz
    private const val PAD = N_FFT / 2 // 200, reflect padding each side

    /** Periodic Hann window: w[n] = 0.5 - 0.5*cos(2*pi*n/N). */
    private val hann: FloatArray = FloatArray(N_FFT) { n ->
        (0.5 - 0.5 * cos(2.0 * Math.PI * n / N_FFT)).toFloat()
    }

    /** cosT[bin*N_FFT + k] = cos(2*pi*bin*k/N_FFT); sinT likewise. */
    private val cosT: FloatArray = FloatArray(N_BINS * N_FFT)
    private val sinT: FloatArray = FloatArray(N_BINS * N_FFT)

    init {
        for (bin in 0 until N_BINS) {
            val o = bin * N_FFT
            for (k in 0 until N_FFT) {
                val theta = 2.0 * Math.PI * bin * k / N_FFT
                cosT[o + k] = cos(theta).toFloat()
                sinT[o + k] = sin(theta).toFloat()
            }
        }
    }

    /** Bundled mel filterbank, lazily loaded once: [N_MEL*N_BINS] row-major (mel x fft). */
    @Volatile
    private var melFilter: FloatArray? = null

    private fun filterbank(context: Context): FloatArray {
        melFilter?.let { return it }
        synchronized(this) {
            melFilter?.let { return it }
            val bytes = context.assets.open("npu/mel_128.bin").use { it.readBytes() }
            val expected = N_MEL * N_BINS * 4
            require(bytes.size >= expected) {
                "mel_128.bin too small: ${bytes.size} < $expected"
            }
            val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val arr = FloatArray(N_MEL * N_BINS)
            fb.get(arr)
            melFilter = arr
            return arr
        }
    }

    /**
     * Computes the flat log-mel features for [pcm] (16 kHz mono, -1..1).
     * @return FloatArray of length [N_MEL]*[N_FRAMES] (384000) in [mel, frame] order.
     */
    fun logMel(context: Context, pcm: FloatArray): FloatArray {
        val filters = filterbank(context)

        // 1. pad/trim to exactly N_SAMPLES.
        val audio = FloatArray(N_SAMPLES)
        val copy = if (pcm.size < N_SAMPLES) pcm.size else N_SAMPLES
        System.arraycopy(pcm, 0, audio, 0, copy)

        // 2. reflect-pad by PAD on both ends (matches torch.stft center=True).
        val padded = FloatArray(N_SAMPLES + 2 * PAD)
        System.arraycopy(audio, 0, padded, PAD, N_SAMPLES)
        for (j in 0 until PAD) {
            padded[j] = audio[PAD - j]                       // left reflection
            padded[PAD + N_SAMPLES + j] = audio[N_SAMPLES - 2 - j] // right reflection
        }

        val out = FloatArray(N_MEL * N_FRAMES) // [mel, frame]
        val windowed = FloatArray(N_FFT)
        val power = FloatArray(N_BINS)

        // 3. STFT + mel projection, frame by frame.
        for (t in 0 until N_FRAMES) {
            val base = t * HOP
            for (k in 0 until N_FFT) windowed[k] = padded[base + k] * hann[k]

            for (bin in 0 until N_BINS) {
                var re = 0f
                var im = 0f
                val o = bin * N_FFT
                for (k in 0 until N_FFT) {
                    val x = windowed[k]
                    re += x * cosT[o + k]
                    im += x * sinT[o + k]
                }
                power[bin] = re * re + im * im
            }

            for (m in 0 until N_MEL) {
                var s = 0f
                val mo = m * N_BINS
                for (f in 0 until N_BINS) s += filters[mo + f] * power[f]
                out[m * N_FRAMES + t] = s
            }
        }

        // 4. Whisper normalization.
        var gmax = Float.NEGATIVE_INFINITY
        for (i in out.indices) {
            val v = log10(maxOf(out[i], 1e-10f))
            out[i] = v
            if (v > gmax) gmax = v
        }
        val floor = gmax - 8f
        for (i in out.indices) {
            var v = out[i]
            if (v < floor) v = floor
            out[i] = (v + 4f) / 4f
        }
        return out
    }
}
