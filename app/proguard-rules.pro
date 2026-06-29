# Keep JNI entry points used by the native whisper bridge.
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.dabber.asr.WhisperEngine { *; }
