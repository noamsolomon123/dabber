package com.dabber.npu

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Native-Kotlin ONNX Runtime Whisper engine (CPU EP) for large-v3-turbo.
 *
 * This is the foundation for the future Hexagon-NPU path; today it runs on the ORT CPU
 * provider so the pipeline can be validated on an emulator. The encoder/decoder are the
 * standard optimum export (no KV-cache decoder): each greedy step re-feeds the full
 * growing token sequence.
 *
 * Tensor-name assumptions (resolved dynamically from the sessions, with these as the
 * preferred names; if the export differs, the first matching / first available name is
 * used instead):
 *   encoder  in : "input_features"        [1, 128, 3000]   float32
 *   encoder  out: "last_hidden_state"     [1, 1500, d]     float32  (a.k.a. encoder_hidden_states)
 *   decoder  in : "input_ids"             [1, seqLen]      int64
 *   decoder  in : "encoder_hidden_states" [1, 1500, d]     float32
 *   decoder  out: "logits"                [1, seqLen, V]   float32
 *
 * Not thread-safe across [load]/[transcribe]; drive from a single background thread.
 */
class OnnxWhisperEngine {

    private var env: OrtEnvironment? = null
    private var encoder: OrtSession? = null
    private var decoder: OrtSession? = null

    private var encInName = "input_features"
    private var encOutName = "last_hidden_state"
    private var decIdsName = "input_ids"
    private var decEncName = "encoder_hidden_states"
    private var decLogitsName = "logits"

    val isLoaded: Boolean
        get() = encoder != null && decoder != null

    /**
     * Creates the ORT environment and both sessions (CPU EP) from [onnxDir], which must
     * contain encoder_model.onnx (+ its .onnx_data sidecar) and decoder_model.onnx.
     * Resolves the actual tensor names from each session. Returns true on success.
     */
    fun load(onnxDir: File): Boolean {
        return try {
            val enc = File(onnxDir, "encoder_model.onnx")
            val dec = File(onnxDir, "decoder_model.onnx")
            if (!enc.exists() || !dec.exists()) {
                Log.e(TAG, "missing models: enc=${enc.exists()} dec=${dec.exists()} dir=$onnxDir")
                return false
            }
            val e = OrtEnvironment.getEnvironment()
            // Default SessionOptions => CPU execution provider. The NPU swap later replaces
            // the dependency with onnxruntime-android-qnn and adds the QNN EP here.
            val encSession = OrtSession.SessionOptions().use { e.createSession(enc.absolutePath, it) }
            val decSession = OrtSession.SessionOptions().use { e.createSession(dec.absolutePath, it) }

            encInName = pick(encSession.inputNames, "input_features")
            encOutName = pick(encSession.outputNames, "last_hidden_state", "encoder_hidden_states")
            decIdsName = pick(decSession.inputNames, "input_ids")
            decEncName = decSession.inputNames.firstOrNull { it == "encoder_hidden_states" }
                ?: decSession.inputNames.firstOrNull { it.contains("encoder") }
                ?: decSession.inputNames.first { it != decIdsName }
            decLogitsName = pick(decSession.outputNames, "logits")

            env = e
            encoder = encSession
            decoder = decSession
            Log.i(
                TAG,
                "loaded. enc[$encInName->$encOutName] " +
                    "dec[$decIdsName,$decEncName->$decLogitsName]",
            )
            true
        } catch (t: Throwable) {
            Log.e(TAG, "load failed", t)
            false
        }
    }

    /**
     * Transcribes 16 kHz mono float PCM (-1..1). Builds log-mel features, runs the encoder
     * once, then greedily decodes (max [MAX_TOKENS] new tokens) prompted with
     * [sot, <|lang|>, <|transcribe|>, <|notimestamps|>]. Returns the decoded text.
     */
    fun transcribe(context: Context, pcm: FloatArray, lang: String = "he"): String {
        val e = env ?: return ""
        val enc = encoder ?: return ""
        val dec = decoder ?: return ""
        WhisperTokenizer.ensureLoaded(context)

        val mel = WhisperMel.logMel(context, pcm)
        val melTensor = floatTensor(e, mel, longArrayOf(1, 128, 3000))

        // Encoder pass -> own a copy of the hidden states so it can be re-fed each step.
        var encArr = FloatArray(0)
        var encShape = LongArray(0)
        try {
            enc.run(java.util.Collections.singletonMap(encInName, melTensor)).use { res ->
                val out = res.get(encOutName).get() as OnnxTensor
                encShape = out.info.shape
                val fb = out.floatBuffer
                encArr = FloatArray(fb.remaining())
                fb.get(encArr)
            }
        } finally {
            melTensor.close()
        }

        val encHidden = floatTensor(e, encArr, encShape)

        val sot = WhisperTokenizer.sot
        val transcribe = WhisperTokenizer.transcribe
        val notimestamps = WhisperTokenizer.notimestamps
        val eot = WhisperTokenizer.eot
        val langId = WhisperTokenizer.langToken(lang)

        val tokens = ArrayList<Long>()
        tokens.add(sot.toLong())
        tokens.add(langId.toLong())
        tokens.add(transcribe.toLong())
        tokens.add(notimestamps.toLong())
        val promptLen = tokens.size

        try {
            var generated = 0
            while (generated < MAX_TOKENS) {
                val ids = LongArray(tokens.size) { tokens[it] }
                val idsTensor = longTensor(e, ids, longArrayOf(1, tokens.size.toLong()))
                val inputs = HashMap<String, OnnxTensor>(2)
                inputs[decIdsName] = idsTensor
                inputs[decEncName] = encHidden

                var next = eot
                try {
                    dec.run(inputs).use { res ->
                        val logits = res.get(decLogitsName).get() as OnnxTensor
                        val shape = logits.info.shape // [1, seq, vocab]
                        val seq = shape[1].toInt()
                        val vocab = shape[2].toInt()
                        val fb = logits.floatBuffer
                        val row = FloatArray(vocab)
                        fb.position((seq - 1) * vocab)
                        fb.get(row)
                        var best = 0
                        var bestVal = Float.NEGATIVE_INFINITY
                        for (i in 0 until vocab) {
                            if (row[i] > bestVal) {
                                bestVal = row[i]
                                best = i
                            }
                        }
                        next = best
                    }
                } finally {
                    idsTensor.close()
                }

                tokens.add(next.toLong())
                generated++
                if (next == eot) break
            }
        } finally {
            encHidden.close()
        }

        val generatedIds = ArrayList<Int>(tokens.size - promptLen)
        for (i in promptLen until tokens.size) generatedIds.add(tokens[i].toInt())
        return WhisperTokenizer.decode(generatedIds)
    }

    /** Closes both sessions. The shared [OrtEnvironment] singleton is left intact. */
    fun close() {
        try { encoder?.close() } catch (_: Throwable) {}
        try { decoder?.close() } catch (_: Throwable) {}
        encoder = null
        decoder = null
        env = null
    }

    // --- helpers -------------------------------------------------------------

    private fun pick(names: Set<String>, vararg prefs: String): String {
        for (p in prefs) if (names.contains(p)) return p
        return names.first()
    }

    private fun floatTensor(e: OrtEnvironment, data: FloatArray, shape: LongArray): OnnxTensor {
        val buf = ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        buf.put(data)
        buf.rewind()
        return OnnxTensor.createTensor(e, buf, shape)
    }

    private fun longTensor(e: OrtEnvironment, data: LongArray, shape: LongArray): OnnxTensor {
        val buf = ByteBuffer.allocateDirect(data.size * 8)
            .order(ByteOrder.nativeOrder())
            .asLongBuffer()
        buf.put(data)
        buf.rewind()
        return OnnxTensor.createTensor(e, buf, shape)
    }

    companion object {
        private const val TAG = "DabberOnnx"
        private const val MAX_TOKENS = 200
    }
}
