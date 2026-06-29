#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "whisper.h"

#define LOG_TAG "DabberWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jlong JNICALL
Java_com_dabber_asr_WhisperEngine_nativeInit(JNIEnv *env, jobject, jstring modelPath) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);
    if (ctx == nullptr) {
        LOGE("whisper_init failed for model");
    } else {
        LOGI("whisper model loaded");
    }
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_dabber_asr_WhisperEngine_nativeTranscribe(
        JNIEnv *env, jobject, jlong ptr, jfloatArray pcm, jstring lang, jint nThreads) {
    auto *ctx = reinterpret_cast<whisper_context *>(ptr);
    if (ctx == nullptr) return env->NewStringUTF("");

    jsize n = env->GetArrayLength(pcm);
    std::vector<float> samples(static_cast<size_t>(n));
    env->GetFloatArrayRegion(pcm, 0, n, samples.data());

    const char *l = env->GetStringUTFChars(lang, nullptr);
    whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.language = l;
    wparams.translate = false;
    wparams.print_progress = false;
    wparams.print_realtime = false;
    wparams.print_timestamps = false;
    wparams.no_context = true;
    wparams.n_threads = nThreads > 0 ? nThreads : 4;

    std::string result;
    if (whisper_full(ctx, wparams, samples.data(), n) == 0) {
        int segs = whisper_full_n_segments(ctx);
        for (int i = 0; i < segs; ++i) {
            result += whisper_full_get_segment_text(ctx, i);
        }
    } else {
        LOGE("whisper_full failed");
    }
    env->ReleaseStringUTFChars(lang, l);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_dabber_asr_WhisperEngine_nativeFree(JNIEnv *, jobject, jlong ptr) {
    auto *ctx = reinterpret_cast<whisper_context *>(ptr);
    if (ctx != nullptr) whisper_free(ctx);
}
