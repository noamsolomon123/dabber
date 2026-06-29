package com.dabber.core

import com.dabber.asr.WhisperEngine
import com.dabber.text.TextCleaner

/**
 * Process-wide holder for the loaded [WhisperEngine] plus the transcribe→clean pipeline.
 *
 * The native engine is not thread-safe, so every call here is [Synchronized]; callers
 * (the overlay service and the in-app scratchpad) therefore serialize on a single model
 * instance instead of each loading their own copy into memory.
 *
 * All methods block and must be called off the main thread.
 */
object DictationCore {

    private val engine = WhisperEngine()

    @Volatile
    var modelLoaded = false
        private set

    @Volatile
    private var loadedPath: String? = null

    /** Loads the ggml model at [path] (idempotent for the same path). Returns true on success. */
    @Synchronized
    fun loadModel(path: String): Boolean {
        if (modelLoaded && path == loadedPath) return true
        modelLoaded = engine.load(path)
        loadedPath = if (modelLoaded) path else null
        return modelLoaded
    }

    /**
     * Transcribes normalized 16 kHz mono [pcm] to text and applies light Hebrew cleanup.
     * Returns "" if no model is loaded or [pcm] is empty.
     */
    @Synchronized
    fun transcribeClean(pcm: FloatArray, lang: String = "he"): String {
        if (!modelLoaded || pcm.isEmpty()) return ""
        return TextCleaner.clean(engine.transcribe(pcm, lang))
    }
}
