package com.hugocodings.voicetyper

import android.content.Context

/** whisper.cpp through JNI (`app/src/main/cpp`). */
object WhisperNative {
    /** False if the native library could not be loaded (unsupported CPU/ABI). */
    val available: Boolean = runCatching { System.loadLibrary("whisper_jni") }
        .onFailure { DiagnosticLog.add("Whisper indisponible : ${it.message.orEmpty().take(120)}") }
        .isSuccess

    @Volatile private var backendsReady = false

    /**
     * Loads the ggml CPU backend best suited to this processor (arm64: dotprod / fp16 / i8mm variants, about
     * twice as fast on recent phones). Call before the first [init]; later calls do nothing.
     */
    fun ensureBackends(context: Context) {
        if (backendsReady || !available) return
        synchronized(this) {
            if (backendsReady) return
            val info = runCatching { loadBackends(context.applicationInfo.nativeLibraryDir) }.getOrElse { "erreur ${it.javaClass.simpleName}" }
            val features = runCatching { systemInfo() }.getOrDefault("")
                .split('|').map { it.trim() }.filter { it.endsWith("= 1") }.joinToString(" ") { it.substringBefore(" =") }
            DiagnosticLog.add("Whisper processeur : ${info.ifEmpty { "standard" }}" + if (features.isNotEmpty()) " · $features" else "")
            backendsReady = true
        }
    }

    @JvmStatic private external fun loadBackends(dir: String): String
    @JvmStatic external fun init(path: String): Long
    @JvmStatic external fun free(handle: Long)
    @JvmStatic external fun abort()
    @JvmStatic external fun systemInfo(): String
    @JvmStatic external fun transcribe(handle: Long, pcm: FloatArray, language: String, prompt: String?, threads: Int): String?
}
