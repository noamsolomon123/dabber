package com.dabber.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Microphone capture with energy-based Voice Activity Detection (VAD).
 *
 * Records 16 kHz mono PCM16 from [MediaRecorder.AudioSource.VOICE_RECOGNITION],
 * normalises it to a `FloatArray` in `-1f..1f` (the format expected by
 * `com.dabber.asr.WhisperEngine.transcribe`), and auto-stops once the speaker
 * goes quiet.
 *
 * ### Threading contract
 * [record] **blocks the calling thread** for as long as audio is being captured
 * (up to `maxMs`). It performs blocking [AudioRecord.read] calls and MUST be
 * invoked from a background thread — never the Android main/UI thread.
 *
 * [requestStop] and [hasPermission] are safe to call from any thread; stopping
 * is signalled through an [AtomicBoolean] that [record] polls each frame.
 *
 * A single instance may be reused sequentially (one [record] call at a time);
 * it is **not** safe to run concurrent [record] calls on the same instance.
 */
class AudioRecorder {

    /** Set by [requestStop], polled by the capture loop. Reset on each [record]. */
    private val stopRequested = AtomicBoolean(false)

    /**
     * Captures one utterance and returns it as normalised mono float PCM.
     *
     * Blocks until one of the following happens:
     *  - speech started and then stayed below the VAD threshold for [silenceMs]
     *    continuous milliseconds (trailing silence is trimmed from the result),
     *  - [maxMs] of audio has been captured,
     *  - [requestStop] is invoked, or
     *  - an [AudioRecord] init/read failure occurs.
     *
     * @param maxMs hard cap on capture duration in milliseconds.
     * @param silenceMs continuous trailing silence (after speech onset) that ends capture.
     * @return samples in `-1f..1f`, or an **empty array** if recording could not start.
     */
    fun record(maxMs: Int = 60_000, silenceMs: Int = 1200): FloatArray {
        stopRequested.set(false)

        val minBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBytes <= 0) return EMPTY

        // Give AudioRecord generous headroom so we never drop frames while transcribing.
        val bufferBytes = maxOf(minBytes, FRAME_SAMPLES * Short.SIZE_BYTES * 4)

        val recorder: AudioRecord = try {
            @Suppress("MissingPermission") // Caller must gate on hasPermission(); guarded below.
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL,
                ENCODING,
                bufferBytes,
            )
        } catch (_: IllegalArgumentException) {
            return EMPTY
        } catch (_: SecurityException) {
            return EMPTY
        }

        try {
            if (recorder.state != AudioRecord.STATE_INITIALIZED) return EMPTY

            val maxSamples = (maxMs.toLong() * SAMPLE_RATE / 1000L)
                .coerceAtLeast(FRAME_SAMPLES.toLong())
                .toInt()
            val pcm = FloatArray(maxSamples)
            val frame = ShortArray(FRAME_SAMPLES)

            try {
                recorder.startRecording()
            } catch (_: IllegalStateException) {
                return EMPTY
            }
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) return EMPTY

            var written = 0                 // total samples stored in pcm
            var lastSpeechEnd = 0           // sample index just past the last speech frame
            var speechStarted = false
            var silenceAccumMs = 0f
            var noiseFloor = -1f            // adaptive background RMS; -1 = uninitialised

            while (written < maxSamples && !stopRequested.get()) {
                val want = minOf(FRAME_SAMPLES, maxSamples - written)
                val read = recorder.read(frame, 0, want)
                if (read <= 0) break // ERROR_*, or no data — bail out cleanly.

                // Convert PCM16 -> float and accumulate frame energy in one pass.
                var sumSquares = 0.0
                for (i in 0 until read) {
                    val s = frame[i] / PCM16_SCALE
                    pcm[written + i] = s
                    sumSquares += (s * s).toDouble()
                }
                written += read
                val rms = sqrt(sumSquares / read).toFloat()

                if (noiseFloor < 0f) noiseFloor = rms
                val threshold = maxOf(ABSOLUTE_SPEECH_FLOOR, noiseFloor * SPEECH_MULTIPLIER)
                val frameMs = read * 1000f / SAMPLE_RATE

                if (rms > threshold) {
                    speechStarted = true
                    silenceAccumMs = 0f
                    lastSpeechEnd = written
                } else {
                    // Track background noise only while quiet so speech can't inflate it.
                    noiseFloor = noiseFloor * (1f - NOISE_EMA_ALPHA) + rms * NOISE_EMA_ALPHA
                    if (speechStarted) {
                        silenceAccumMs += frameMs
                        if (silenceAccumMs >= silenceMs) break
                    }
                }
            }

            return if (speechStarted) {
                // Keep a short tail past the last speech frame, then trim the rest.
                val tail = (TRAILING_KEEP_MS * SAMPLE_RATE / 1000)
                val end = minOf(written, lastSpeechEnd + tail)
                if (end <= 0) EMPTY else pcm.copyOf(end)
            } else if (written > 0) {
                // VAD never triggered (very quiet speech, or manual stop): return raw capture.
                pcm.copyOf(written)
            } else {
                EMPTY
            }
        } finally {
            try {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    recorder.stop()
                }
            } catch (_: IllegalStateException) {
                // Already stopped/uninitialised — nothing to do.
            }
            recorder.release()
        }
    }

    /**
     * Requests that an in-progress [record] call stop as soon as possible.
     * Thread-safe and non-blocking; a no-op if no capture is running.
     */
    fun requestStop() {
        stopRequested.set(true)
    }

    companion object {
        /** Target sample rate; matches WhisperEngine's expected input. */
        private const val SAMPLE_RATE = 16_000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /** 20 ms VAD frame at 16 kHz — fine-grained enough for snappy endpointing. */
        private const val FRAME_SAMPLES = 320

        /** Divisor to map a signed 16-bit sample to `-1f..1f`. */
        private const val PCM16_SCALE = 32_768f

        /** Speech is flagged when frame RMS exceeds this multiple of the noise floor. */
        private const val SPEECH_MULTIPLIER = 3.0f

        /** Floor under which audio is treated as silence regardless of the noise estimate. */
        private const val ABSOLUTE_SPEECH_FLOOR = 0.012f

        /** Smoothing factor for the background-noise RMS estimate (updated while quiet). */
        private const val NOISE_EMA_ALPHA = 0.10f

        /** Audio retained after the final speech frame so word tails aren't clipped. */
        private const val TRAILING_KEEP_MS = 120

        private val EMPTY = FloatArray(0)

        /**
         * @return true if `RECORD_AUDIO` is currently granted to [context].
         */
        fun hasPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
    }
}
