package com.hugocodings.voicetyper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Client of the PC's Whisper server (the one FLASH LINGO uses: `FLASH LINGO/tools/whisper_server.py`), reached
 * through Tailscale (`tailscale serve` on the PC forwards `<pc>.<tailnet>.ts.net:8766` to 127.0.0.1:8766).
 * Tailscale is the access control and the encryption: only the user's own devices are in their tailnet.
 * Protocol: GET /health → {"status","model"}; POST /transcribe?lang=fr|en, body = WAV 16 kHz mono 16-bit → {"text"}.
 */
object PcEngine {
    const val DEFAULT_PORT = 8766
    /** The server refuses more than one minute of audio (16000 × 2 × 60 bytes, WAV header included). */
    const val MAX_SECONDS = 59

    /** "hugo.tailXXXX.ts.net", "host:8766" or "http://…" → base URL. */
    fun baseUrl(address: String): String? {
        val a = address.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
        if (a.isEmpty() || a.any { it.isWhitespace() || it == '/' || it == '?' || it == '@' }) return null
        val hasPort = if (a.startsWith("[")) a.contains("]:") else a.contains(':') // [IPv6]:port or host:port
        return "http://" + if (hasPort) a else "$a:$DEFAULT_PORT"
    }

    /** Returns the model name the PC runs. */
    suspend fun ping(address: String): String = withContext(Dispatchers.IO) {
        val conn = open(address, "/health", connectMs = 4000, readMs = 5000)
        try {
            JSONObject(read(conn)).optString("model")
        } finally {
            conn.disconnect()
        }
    }

    /** Returns the text; throws [IOException] with a user-facing message. */
    suspend fun transcribe(address: String, pcm: FloatArray, lang: String): String = withContext(Dispatchers.IO) {
        val secs = pcm.size / AudioCapture.RATE
        if (secs > MAX_SECONDS) throw IOException("dictée de plus d'une minute : trop longue pour le PC")
        val path = "/transcribe" + if (lang == "fr" || lang == "en") "?lang=$lang" else ""
        val conn = open(address, path, connectMs = 4000, readMs = 20_000 + secs * 1000)
        try {
            val wav = wav16(pcm)
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "audio/wav")
            conn.setFixedLengthStreamingMode(wav.size)
            val t0 = System.currentTimeMillis()
            conn.outputStream.use { it.write(wav) }
            val text = JSONObject(read(conn)).optString("text").trim()
            DiagnosticLog.add("PC : $secs s d'audio transcrit en ${System.currentTimeMillis() - t0} ms (envoi compris)")
            text
        } finally {
            conn.disconnect()
        }
    }

    /** 16 kHz mono 16-bit PCM WAV. */
    private fun wav16(pcm: FloatArray): ByteArray {
        val dataBytes = pcm.size * 2
        val b = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(AudioCapture.RATE).putInt(AudioCapture.RATE * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(dataBytes)
        for (f in pcm) b.putShort((f.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        return b.array()
    }

    private fun open(address: String, path: String, connectMs: Int, readMs: Int): HttpURLConnection {
        val base = baseUrl(address) ?: throw IOException("adresse du PC invalide")
        val conn = URL(base + path).openConnection() as HttpURLConnection
        conn.connectTimeout = connectMs
        conn.readTimeout = readMs
        conn.useCaches = false
        return conn
    }

    private fun read(conn: HttpURLConnection): String {
        val status = try {
            conn.responseCode
        } catch (e: UnknownHostException) {
            throw IOException("nom du PC inconnu (Tailscale activé sur le téléphone ?)")
        } catch (e: SocketTimeoutException) {
            throw IOException("le PC ne répond pas (allumé ? Tailscale actif ?)")
        } catch (e: IOException) {
            throw IOException("PC injoignable (allumé ? serveur Whisper lancé ? Tailscale actif ?)")
        }
        val text = (if (status in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (status in 200..299) return text
        throw IOException(when (status) {
            404 -> "le PC ne trouve pas le serveur Whisper (utilisez le nom en .ts.net, pas l'adresse 100.…)"
            400 -> "dictée vide ou trop longue pour le PC"
            502, 503 -> "serveur Whisper du PC arrêté"
            else -> "erreur du PC ($status)"
        })
    }
}
