package com.hugocodings.voicetyper

import android.content.Context
import com.hugocodings.voicetyper.core.WhisperModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Keeps one Whisper model loaded (like desktop: the model stays in memory) and transcribes with it. */
object WhisperEngine {
    private val lock = Mutex()
    private var handle = 0L
    private var loadedFile: String? = null

    /** Loads [model] if needed; returns an error message or null. */
    suspend fun ensureLoaded(context: Context, model: WhisperModel): String? = withContext(Dispatchers.Default) {
        if (!WhisperNative.available) return@withContext "Whisper n'est pas pris en charge sur ce téléphone"
        WhisperNative.ensureBackends(context)
        val f = ModelManager.file(context, model)
        if (!ModelManager.isDownloaded(context, model)) return@withContext "Modèle ${model.name} non téléchargé"
        lock.withLock {
            if (handle != 0L && loadedFile == f.path) return@withLock null
            if (handle != 0L) { WhisperNative.free(handle); handle = 0L; loadedFile = null }
            val t0 = System.currentTimeMillis()
            val h = WhisperNative.init(f.path)
            if (h == 0L) return@withLock "Le modèle ${model.name} n'a pas pu être chargé (fichier abîmé ? retéléchargez-le)"
            handle = h; loadedFile = f.path
            DiagnosticLog.add("Modèle ${model.id} chargé en ${System.currentTimeMillis() - t0} ms")
            null
        }
    }

    /** Returns the text, or throws with a user-facing message. */
    suspend fun transcribe(context: Context, model: WhisperModel, pcm: FloatArray, language: String, prompt: String?): String {
        ensureLoaded(context, model)?.let { throw IllegalStateException(it) }
        return withContext(Dispatchers.Default) {
            lock.withLock {
                val t0 = System.currentTimeMillis()
                // pad very short clips: whisper needs ≥ 1 s
                val audio = if (pcm.size < AudioCapture.RATE) pcm.copyOf(AudioCapture.RATE) else pcm
                val text = WhisperNative.transcribe(handle, audio, language, prompt, 0)
                    ?: throw IllegalStateException("La transcription a échoué")
                val secs = pcm.size / AudioCapture.RATE.toFloat()
                DiagnosticLog.add("Whisper ${model.id} : ${"%.1f".format(secs)} s d'audio en ${System.currentTimeMillis() - t0} ms")
                text.trim()
            }
        }
    }

    fun abort() { if (WhisperNative.available) WhisperNative.abort() }
}
