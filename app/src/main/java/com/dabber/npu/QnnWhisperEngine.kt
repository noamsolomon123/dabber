package com.dabber.npu

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Hexagon-NPU (Qualcomm QNN HTP) ONNX Runtime Whisper engine for the Qualcomm AI Hub
 * `hf_whisper` export of ivrit-ai/whisper-large-v3-turbo.
 *
 * This is a faithful Kotlin port of the proven AI Hub greedy decode loop
 * (`qai_hub_models.models._shared.hf_whisper.app.py::_transcribe_single_chunk` +
 * `model.py`). The encoder produces the constant cross-attention KV cache; the decoder is
 * called once per token with a FIXED-shape self-attention KV cache that is threaded
 * out -> in, a right-aligned sliding [attention_mask], and an incrementing position id.
 *
 * Execution provider: QNN (HTP / NPU), arm64-v8a only — there are no QNN HTP .so libs for
 * x86_64, so [load] will fail on the emulator. That is expected; this path is phone-only.
 *
 * Model files (NOT bundled — push to filesDir/npu-qnn/ via adb, see MainActivity):
 *   HfWhisperEncoder.onnx + HfWhisperEncoder_qairt_context.bin   (EPContext wrapper + 1.75GB ctx)
 *   HfWhisperDecoder.onnx + HfWhisperDecoder_qairt_context.bin   (EPContext wrapper + 453MB ctx)
 * The tiny .onnx are EPContext graphs that reference the .bin by relative name, so the .bin
 * MUST sit beside the .onnx in a real directory (filesDir qualifies; assets do not — there
 * is no real path for an asset, hence the adb-push-to-filesDir contract).
 *
 * Verified model I/O (names/shapes are still resolved dynamically from the sessions, so a
 * differing export degrades gracefully):
 *   encoder in : input_features            float32 [1,128,3000]
 *   encoder out: k_cache_cross_{0..3}      float32 [20,1,64,1500]
 *               v_cache_cross_{0..3}       float32 [20,1,1500,64]
 *   decoder in : input_ids                 int     [1,1]
 *               attention_mask             float32 [1,1,1,200]
 *               k_cache_self_{i}_in        float32 [20,1,64,199]
 *               v_cache_self_{i}_in        float32 [20,1,199,64]
 *               k_cache_cross_{i}          float32 [20,1,64,1500]   (from encoder, constant)
 *               v_cache_cross_{i}          float32 [20,1,1500,64]   (from encoder, constant)
 *               position_ids               int     [1]             (OPTIONAL — see note)
 *   decoder out: logits                    float32 [1,V,1,1]
 *               k_cache_self_{i}_out / v_cache_self_{i}_out (fed back as the next *_in)
 *
 * ASSUMPTIONS (flagged):
 *  - All listed IO is float32 as verified. The AI Hub float export uses `--quantize_io
 *    --quantize_full_type float16`; if the actual graph presents FP16 IO instead, the
 *    [floatBuffer] reads here would need an fp16<->fp32 conversion. enable_htp_fp16_precision
 *    keeps the math in fp16 on HTP while (per the verified spec) IO stays fp32.
 *  - `position_ids` is present in the AI Hub model.py spec but is NOT in the task's
 *    enumerated decoder inputs; it is fed ONLY if the resolved graph actually declares it.
 *  - 4 decoder layers (large-v3-turbo); derived at runtime from the self-KV input names.
 *  - Single 30s window (WhisperMel pads/trims to 480000); audio > 30s is truncated, matching
 *    OnnxWhisperEngine. The AI Hub app additionally chunks long audio — not ported here.
 *  - Greedy seed is the FORCED Hebrew prompt [sot,<|he|>,transcribe,notimestamps] (Dabber
 *    forces language, like OnnxWhisperEngine), whereas the AI Hub reference seeds with [sot]
 *    only and lets the model auto-detect. The loop mechanics are otherwise identical.
 *
 * Not thread-safe; drive from a single background thread.
 */
class QnnWhisperEngine {

    private var env: OrtEnvironment? = null
    private var encoder: OrtSession? = null
    private var decoder: OrtSession? = null

    private var numLayers = NUM_LAYERS_DEFAULT

    // Resolved tensor names (filled in [resolveNames]).
    private var encInName = "input_features"
    private lateinit var encKCross: Array<String>
    private lateinit var encVCross: Array<String>

    private var decIdsName = "input_ids"
    private var decAttnName = "attention_mask"
    private var decPosName: String? = null
    private lateinit var kSelfInName: Array<String>
    private lateinit var vSelfInName: Array<String>
    private lateinit var kCrossInName: Array<String>
    private lateinit var vCrossInName: Array<String>
    private var decLogitsName = "logits"
    private lateinit var kSelfOutName: Array<String>
    private lateinit var vSelfOutName: Array<String>

    private var idsIsInt64 = false
    private var posIsInt64 = false

    val isLoaded: Boolean
        get() = encoder != null && decoder != null

    /**
     * Creates the QNN (HTP) sessions for encoder + decoder from [dir] (filesDir/npu-qnn),
     * which must contain the four model files. Resolves all tensor names/types/shapes from
     * the loaded graphs. Returns true on success (false on the emulator — no QNN HTP libs).
     */
    fun load(dir: File): Boolean {
        return try {
            val encOnnx = File(dir, "HfWhisperEncoder.onnx")
            val decOnnx = File(dir, "HfWhisperDecoder.onnx")
            if (!encOnnx.exists() || !decOnnx.exists()) {
                Log.e(TAG, "missing models in $dir enc=${encOnnx.exists()} dec=${decOnnx.exists()}")
                return false
            }
            val e = OrtEnvironment.getEnvironment()
            // EPContext: load straight from the real path so each .onnx finds its .bin beside it.
            val encSession = makeSession(e, encOnnx.absolutePath)
            val decSession = makeSession(e, decOnnx.absolutePath)
            resolveNames(encSession, decSession)
            env = e
            encoder = encSession
            decoder = decSession
            Log.i(
                TAG,
                "loaded numLayers=$numLayers pos=${decPosName ?: "<none>"} " +
                    "idsInt64=$idsIsInt64 enc[$encInName] dec[$decIdsName,$decAttnName->$decLogitsName]",
            )
            true
        } catch (t: Throwable) {
            Log.e(TAG, "load failed (expected on x86_64 emulator: no QNN HTP libs)", t)
            false
        }
    }

    /**
     * Transcribes 16 kHz mono float PCM (-1..1). Builds log-mel features (reusing
     * [WhisperMel]), runs the encoder once for the cross-KV cache, then ports the AI Hub
     * greedy decode loop (see class doc) prompted with [sot,<|lang|>,transcribe,notimestamps].
     * Returns the decoded text via [WhisperTokenizer.decode]. All OnnxTensors are closed.
     */
    fun transcribe(context: Context, pcm: FloatArray, lang: String = "he"): String {
        val e = env ?: return ""
        val enc = encoder ?: return ""
        val dec = decoder ?: return ""
        WhisperTokenizer.ensureLoaded(context)

        // ---- features -------------------------------------------------------
        val mel = WhisperMel.logMel(context, pcm) // flat [128*3000], [mel, frame]

        // ---- encoder: produce the constant cross-attention KV cache ---------
        val crossK = arrayOfNulls<OnnxTensor>(numLayers)
        val crossV = arrayOfNulls<OnnxTensor>(numLayers)
        val melTensor = floatTensor(e, mel, longArrayOf(1, 128, 3000))
        try {
            enc.run(java.util.Collections.singletonMap(encInName, melTensor)).use { res ->
                for (i in 0 until numLayers) {
                    val kt = res.get(encKCross[i]).get() as OnnxTensor
                    val vt = res.get(encVCross[i]).get() as OnnxTensor
                    crossK[i] = floatTensor(e, extract(kt), kt.info.shape)
                    crossV[i] = floatTensor(e, extract(vt), vt.info.shape)
                }
            }
        } finally {
            melTensor.close()
        }

        // ---- decoder: greedy KV-cache loop (AI Hub port) --------------------
        // Self-attention KV cache: fixed shapes, initialised to zeros, threaded out -> in.
        val kSelfShape = Array(numLayers) { i ->
            staticShape(inShape(dec, kSelfInName[i]), longArrayOf(20, 1, 64, CACHE_LEN.toLong()))
        }
        val vSelfShape = Array(numLayers) { i ->
            staticShape(inShape(dec, vSelfInName[i]), longArrayOf(20, 1, CACHE_LEN.toLong(), 64))
        }
        val kSelf = Array(numLayers) { FloatArray(prod(kSelfShape[it])) }
        val vSelf = Array(numLayers) { FloatArray(prod(vSelfShape[it])) }

        // attention_mask: [1,1,1,200], filled with MASK_NEG; revealed right-to-left per step.
        val attnShape = staticShape(
            inShape(dec, decAttnName), longArrayOf(1, 1, 1, MEAN_DECODE_LEN.toLong()),
        )
        val attn = FloatArray(MEAN_DECODE_LEN) { MASK_NEG }

        // Forced prompt (Hebrew transcribe, no timestamps).
        val sot = WhisperTokenizer.sot
        val eot = WhisperTokenizer.eot
        val tokens = ArrayList<Int>()
        tokens.add(sot)
        tokens.add(WhisperTokenizer.langToken(lang))
        tokens.add(WhisperTokenizer.transcribe)
        tokens.add(WhisperTokenizer.notimestamps)
        val promptLen = tokens.size // output_length in app.py

        var position = 0
        try {
            for (n in 0 until MEAN_DECODE_LEN - 1) { // n = 0..198
                val curId = tokens[n]                       // output_ids[:, n:n+1]
                attn[MEAN_DECODE_LEN - n - 1] = 0f          // reveal position 199-n

                val toClose = ArrayList<OnnxTensor>(3 + numLayers * 2)
                val inputs = HashMap<String, OnnxTensor>()

                val idsT = idTensor(e, curId, idsIsInt64); toClose.add(idsT); inputs[decIdsName] = idsT
                val attnT = floatTensor(e, attn, attnShape); toClose.add(attnT); inputs[decAttnName] = attnT
                for (i in 0 until numLayers) {
                    val kt = floatTensor(e, kSelf[i], kSelfShape[i]); toClose.add(kt); inputs[kSelfInName[i]] = kt
                    val vt = floatTensor(e, vSelf[i], vSelfShape[i]); toClose.add(vt); inputs[vSelfInName[i]] = vt
                    inputs[kCrossInName[i]] = crossK[i]!! // constant, created once, closed at end
                    inputs[vCrossInName[i]] = crossV[i]!!
                }
                decPosName?.let { name ->
                    val pt = idTensor1d(e, position, posIsInt64); toClose.add(pt); inputs[name] = pt
                }

                var nextId = eot
                try {
                    dec.run(inputs).use { res ->
                        nextId = argmax(res.get(decLogitsName).get() as OnnxTensor)
                        for (i in 0 until numLayers) {
                            copyInto(res.get(kSelfOutName[i]).get() as OnnxTensor, kSelf[i])
                            copyInto(res.get(vSelfOutName[i]).get() as OnnxTensor, vSelf[i])
                        }
                    }
                } finally {
                    for (t in toClose) t.close()
                }

                // Append / stop logic — exactly app.py.
                val isLast = (n + 1 == MEAN_DECODE_LEN - 1)
                if (isLast || nextId == eot) {
                    tokens.add(nextId)
                    break
                }
                if (n >= promptLen - 1) {
                    tokens.add(nextId)
                }
                position += 1
            }
        } finally {
            for (i in 0 until numLayers) {
                crossK[i]?.close()
                crossV[i]?.close()
            }
        }

        // Decode generated content (specials, incl. any trailing eot, are skipped by decode).
        val generated = ArrayList<Int>(tokens.size - promptLen)
        for (i in promptLen until tokens.size) generated.add(tokens[i])
        return WhisperTokenizer.decode(generated)
    }

    /** Closes both sessions. The shared [OrtEnvironment] singleton is left intact. */
    fun close() {
        try { encoder?.close() } catch (_: Throwable) {}
        try { decoder?.close() } catch (_: Throwable) {}
        encoder = null
        decoder = null
        env = null
    }

    // --- session / name resolution ------------------------------------------

    private fun makeSession(e: OrtEnvironment, path: String): OrtSession {
        val opts = OrtSession.SessionOptions()
        return try {
            opts.addQnn(
                mapOf(
                    "backend_path" to "libQnnHtp.so",
                    "htp_performance_mode" to "burst",
                    "enable_htp_fp16_precision" to "1",
                ),
            )
            e.createSession(path, opts)
        } finally {
            opts.close()
        }
    }

    private fun resolveNames(enc: OrtSession, dec: OrtSession) {
        val encIn = enc.inputNames
        val encOut = enc.outputNames
        val decIn = dec.inputNames
        val decOut = dec.outputNames

        // Derive layer count from the decoder self-KV input names (expect 4).
        val re = Regex("k_cache_self_(\\d+)_in")
        numLayers = decIn.mapNotNull { re.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull()?.plus(1) ?: NUM_LAYERS_DEFAULT

        encInName = pick(encIn, "input_features")
        encKCross = Array(numLayers) { i -> pick(encOut, "k_cache_cross_$i") }
        encVCross = Array(numLayers) { i -> pick(encOut, "v_cache_cross_$i") }

        decIdsName = pick(decIn, "input_ids")
        decAttnName = pick(decIn, "attention_mask")
        decPosName = decIn.firstOrNull { it == "position_ids" }
        kSelfInName = Array(numLayers) { i -> pick(decIn, "k_cache_self_${i}_in") }
        vSelfInName = Array(numLayers) { i -> pick(decIn, "v_cache_self_${i}_in") }
        kCrossInName = Array(numLayers) { i -> pick(decIn, "k_cache_cross_$i") }
        vCrossInName = Array(numLayers) { i -> pick(decIn, "v_cache_cross_$i") }
        decLogitsName = pick(decOut, "logits")
        kSelfOutName = Array(numLayers) { i -> pick(decOut, "k_cache_self_${i}_out") }
        vSelfOutName = Array(numLayers) { i -> pick(decOut, "v_cache_self_${i}_out") }

        idsIsInt64 = typeOf(dec, decIdsName) == OnnxJavaType.INT64
        decPosName?.let { posIsInt64 = typeOf(dec, it) == OnnxJavaType.INT64 }
    }

    // --- tensor helpers ------------------------------------------------------

    private fun floatTensor(e: OrtEnvironment, data: FloatArray, shape: LongArray): OnnxTensor {
        val buf = ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        buf.put(data)
        buf.rewind()
        return OnnxTensor.createTensor(e, buf, shape)
    }

    /** input_ids tensor, shape [1,1], int32 or int64 to match the graph. */
    private fun idTensor(e: OrtEnvironment, id: Int, int64: Boolean): OnnxTensor =
        if (int64) {
            val b = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder()).asLongBuffer()
            b.put(id.toLong()); b.rewind()
            OnnxTensor.createTensor(e, b, longArrayOf(1, 1))
        } else {
            val b = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer()
            b.put(id); b.rewind()
            OnnxTensor.createTensor(e, b, longArrayOf(1, 1))
        }

    /** position_ids tensor, shape [1], int32 or int64 to match the graph. */
    private fun idTensor1d(e: OrtEnvironment, id: Int, int64: Boolean): OnnxTensor =
        if (int64) {
            val b = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder()).asLongBuffer()
            b.put(id.toLong()); b.rewind()
            OnnxTensor.createTensor(e, b, longArrayOf(1))
        } else {
            val b = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer()
            b.put(id); b.rewind()
            OnnxTensor.createTensor(e, b, longArrayOf(1))
        }

    private fun extract(t: OnnxTensor): FloatArray {
        val fb = t.floatBuffer
        val a = FloatArray(fb.remaining())
        fb.get(a)
        return a
    }

    private fun copyInto(t: OnnxTensor, dst: FloatArray) {
        val fb = t.floatBuffer
        val n = minOf(dst.size, fb.remaining())
        fb.get(dst, 0, n)
    }

    /** Greedy argmax over the (batch=1, seq=1) logits — the whole buffer is the vocab row. */
    private fun argmax(t: OnnxTensor): Int {
        val fb = t.floatBuffer
        var best = 0
        var bestVal = Float.NEGATIVE_INFINITY
        var i = 0
        while (fb.hasRemaining()) {
            val v = fb.get()
            if (v > bestVal) { bestVal = v; best = i }
            i++
        }
        return best
    }

    private fun typeOf(s: OrtSession, name: String): OnnxJavaType =
        ((s.inputInfo[name]!!.info) as TensorInfo).type

    private fun inShape(s: OrtSession, name: String): LongArray =
        ((s.inputInfo[name]!!.info) as TensorInfo).shape

    /** Returns [shape] if every dim is concrete (>0), else [fallback] (handles dynamic dims). */
    private fun staticShape(shape: LongArray, fallback: LongArray): LongArray =
        if (shape.isNotEmpty() && shape.all { it > 0 }) shape else fallback

    private fun prod(shape: LongArray): Int {
        var p = 1L
        for (d in shape) p *= d
        return p.toInt()
    }

    private fun pick(names: Set<String>, vararg prefs: String): String {
        for (p in prefs) if (names.contains(p)) return p
        Log.w(TAG, "input/output name not found: ${prefs.joinToString()} in $names")
        return prefs.first()
    }

    companion object {
        private const val TAG = "DabberQnn"
        private const val MEAN_DECODE_LEN = 200       // MEAN_DECODE_LEN
        private const val CACHE_LEN = MEAN_DECODE_LEN - 1 // 199, self-KV cache length
        private const val MASK_NEG = -100f            // MASK_NEG
        private const val NUM_LAYERS_DEFAULT = 4
    }
}
