package com.hugocodings.voicetyper

/** whisper.cpp through JNI (`app/src/main/cpp`). */
object WhisperNative {
    /** False if the native library could not be loaded (unsupported CPU/ABI). */
    val available: Boolean = runCatching { System.loadLibrary("whisper_jni") }
        .onFailure { DiagnosticLog.add("Whisper indisponible : ${it.message.orEmpty().take(120)}") }
        .isSuccess

    @JvmStatic external fun init(path: String): Long
    @JvmStatic external fun free(handle: Long)
    @JvmStatic external fun abort()
    @JvmStatic external fun systemInfo(): String
    @JvmStatic external fun transcribe(handle: Long, pcm: FloatArray, language: String, prompt: String?, threads: Int): String?
}
