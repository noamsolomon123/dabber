package com.dabber.asr

/**
 * Thin Kotlin wrapper over the native whisper.cpp bridge (libdabber_whisper.so).
 *
 * Lifecycle: [load] once with a ggml model path, call [transcribe] per utterance,
 * [release] when done. Not thread-safe; call from a single background thread.
 */
class WhisperEngine {

    private var ctx: Long = 0L

    val isLoaded: Boolean
        get() = ctx != 0L

    /** Loads a ggml model from [modelPath]. Returns true on success. */
    fun load(modelPath: String): Boolean {
        if (ctx != 0L) nativeFree(ctx)
        ctx = nativeInit(modelPath)
        return ctx != 0L
    }

    /**
     * Transcribes 16 kHz mono float PCM (range -1..1) to text.
     * [lang] is an ISO code ("he", "en") or "auto".
     */
    fun transcribe(pcm: FloatArray, lang: String = "he", threads: Int = defaultThreads()): String {
        if (ctx == 0L || pcm.isEmpty()) return ""
        return nativeTranscribe(ctx, pcm, lang, threads)
    }

    fun release() {
        if (ctx != 0L) {
            nativeFree(ctx)
            ctx = 0L
        }
    }

    private external fun nativeInit(modelPath: String): Long
    private external fun nativeTranscribe(ctx: Long, pcm: FloatArray, lang: String, nThreads: Int): String
    private external fun nativeFree(ctx: Long)

    companion object {
        init {
            System.loadLibrary("dabber_whisper")
        }

        /** whisper.cpp scales well to ~half the cores; cap to keep the UI responsive. */
        fun defaultThreads(): Int =
            Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
    }
}
