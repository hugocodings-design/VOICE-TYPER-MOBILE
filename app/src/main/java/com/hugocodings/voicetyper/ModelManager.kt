package com.hugocodings.voicetyper

import android.content.Context
import com.hugocodings.voicetyper.core.WhisperModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads Whisper models from Hugging Face into the app's private storage (the only network use of
 * the app, like model downloads on desktop). A download goes to `<file>.part` and is renamed when complete.
 */
object ModelManager {
    /** model id → progress 0…1 while downloading. */
    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val progress: StateFlow<Map<String, Float>> get() = _progress
    /** Bumped when files appear/disappear so screens refresh. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> get() = _version
    @Volatile private var cancelled = setOf<String>()

    fun dir(context: Context): File = File(context.filesDir, "models").apply { mkdirs() }

    fun file(context: Context, model: WhisperModel): File = File(dir(context), model.file)

    fun isDownloaded(context: Context, model: WhisperModel): Boolean = file(context, model).length() > 1_000_000

    fun delete(context: Context, model: WhisperModel) {
        file(context, model).delete()
        _version.value++
    }

    fun cancel(model: WhisperModel) { cancelled = cancelled + model.id }

    /** Returns null on success, else an error message (in French, shown to the user). */
    suspend fun download(context: Context, model: WhisperModel): String? = withContext(Dispatchers.IO) {
        if (_progress.value.containsKey(model.id)) return@withContext null
        cancelled = cancelled - model.id
        _progress.value = _progress.value + (model.id to 0f)
        val target = file(context, model)
        val part = File(target.path + ".part")
        try {
            var url = URL(model.url)
            var conn: HttpURLConnection
            var hops = 0
            while (true) { // Hugging Face redirects to its CDN
                conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "VoiceTyperMobile")
                }
                val code = conn.responseCode
                if (code in 300..399 && hops++ < 5) {
                    url = URL(url, conn.getHeaderField("Location")); conn.disconnect(); continue
                }
                if (code != 200) return@withContext "Téléchargement refusé (HTTP $code)"
                break
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: (model.sizeMb * 1_048_576L)
            var done = 0L
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        if (model.id in cancelled) return@withContext "Téléchargement annulé"
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        _progress.value = _progress.value + (model.id to (done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            if (conn.contentLengthLong > 0 && done != conn.contentLengthLong) return@withContext "Téléchargement incomplet, réessayez"
            if (!part.renameTo(target)) return@withContext "Impossible d'enregistrer le modèle"
            DiagnosticLog.add("Modèle ${model.id} téléchargé (${done / 1_048_576} Mo)")
            null
        } catch (e: Exception) {
            "Échec du téléchargement : ${e.javaClass.simpleName} ${e.message.orEmpty().take(80)}"
        } finally {
            if (!target.exists()) part.delete()
            _progress.value = _progress.value - model.id
            _version.value++
        }
    }
}
