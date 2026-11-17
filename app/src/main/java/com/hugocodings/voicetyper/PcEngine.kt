package com.hugocodings.voicetyper

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
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
/**
 * What the app knows about the PC link: a background /health check whenever the bubble shows (throttled) keeps
 * the Tailscale tunnel warm and tells the next dictation whether to record for the PC or use the phone's real-time
 * recognition right away. Every result goes to [history] (shown in the PC card) to find what triggers drops.
 */
object PcHealth {
    data class Event(val at: Long, val ok: Boolean, val what: String)

    @Volatile var lastOkAt = 0L; private set
    @Volatile var lastFailAt = 0L; private set
    @Volatile var lastError: String? = null; private set
    @Volatile private var checking = false
    private val events = ArrayDeque<Event>()

    val history: List<Event> get() = synchronized(events) { events.toList().asReversed() }

    /** The last check failed less than [FRESH_MS] ago and nothing succeeded since. */
    fun knownDown(now: Long = System.currentTimeMillis()): Boolean =
        lastFailAt > lastOkAt && now - lastFailAt < FRESH_MS

    fun record(ok: Boolean, what: String) {
        val now = System.currentTimeMillis()
        if (ok) { lastOkAt = now; lastError = null } else { lastFailAt = now; lastError = what }
        synchronized(events) {
            // collapse a run of identical results: one line per change, refreshed time
            val last = events.lastOrNull()
            if (last != null && last.ok == ok && last.what == what) events.removeLast()
            events.addLast(Event(now, ok, what))
            while (events.size > 40) events.removeFirst()
        }
    }

    /** Background check, at most every [MIN_INTERVAL_MS] (sooner after a failure is not useful either). */
    suspend fun warmUp(context: Context, address: String, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (checking || address.isBlank()) return
        if (!force && now - maxOf(lastOkAt, lastFailAt) < MIN_INTERVAL_MS) return
        checking = true
        try {
            if (!PcEngine.tailscaleUp(context)) { record(false, "Tailscale désactivé sur le téléphone"); return }
            val t0 = System.currentTimeMillis()
            val model = PcEngine.ping(address)
            record(true, "PC joignable ($model, ${System.currentTimeMillis() - t0} ms)")
        } catch (e: Exception) {
            record(false, e.message ?: e.javaClass.simpleName)
        } finally {
            checking = false
        }
    }

    const val FRESH_MS = 90_000L
    const val MIN_INTERVAL_MS = 25_000L
}

object PcEngine {
    const val DEFAULT_PORT = 8766
    /** The server refuses more than one minute of audio (16000 × 2 × 60 bytes, WAV header included). */
    const val MAX_SECONDS = 59

    /**
     * True when a VPN (Tailscale) is connected. Without it the PC's .ts.net name cannot be reached, so the
     * caller goes straight to Whisper on the phone instead of waiting for a timeout.
     */
    fun tailscaleUp(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        @Suppress("DEPRECATION")
        cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    }.getOrDefault(true)

    /** "hugo.tailXXXX.ts.net", "host:8766" or "http://…" → base URL. */
    fun baseUrl(address: String): String? {
        val a = address.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
        if (a.isEmpty() || a.any { it.isWhitespace() || it == '/' || it == '?' || it == '@' }) return null
        val hasPort = if (a.startsWith("[")) a.contains("]:") else a.contains(':') // [IPv6]:port or host:port
        return "http://" + if (hasPort) a else "$a:$DEFAULT_PORT"
    }

    /** Returns the model name the PC runs. */
    suspend fun ping(address: String): String = withContext(Dispatchers.IO) {
        val conn = open(address, "/health", connectMs = 5000, readMs = 5000)
        try {
            JSONObject(read(conn)).optString("model")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Returns the text; throws [IOException] with a user-facing message. A connection that fails right away
     * (network switching from Wi-Fi to mobile data, Tailscale reconnecting) is retried once.
     */
    suspend fun transcribe(address: String, pcm: FloatArray, lang: String): String = try {
        transcribeOnce(address, pcm, lang)
    } catch (e: ConnectFailed) {
        DiagnosticLog.add("PC : connexion ratée (${e.message}), nouvel essai")
        delay(700)
        try {
            transcribeOnce(address, pcm, lang)
        } catch (e2: ConnectFailed) {
            throw IOException(e2.message)
        }
    }

    /** The connection itself failed (nothing was sent): safe to retry. */
    private class ConnectFailed(message: String) : IOException(message)

    private suspend fun transcribeOnce(address: String, pcm: FloatArray, lang: String): String = try {
        transcribeRaw(address, pcm, lang).also { PcHealth.record(true, "Dictée transcrite par le PC") }
    } catch (e: IOException) {
        PcHealth.record(false, e.message ?: e.javaClass.simpleName)
        throw e
    }

    private suspend fun transcribeRaw(address: String, pcm: FloatArray, lang: String): String = withContext(Dispatchers.IO) {
        val secs = pcm.size / AudioCapture.RATE
        if (secs > MAX_SECONDS) throw IOException("dictée de plus d'une minute : trop longue pour le PC")
        val path = "/transcribe" + if (lang == "fr" || lang == "en") "?lang=$lang" else ""
        val conn = open(address, path, connectMs = 5000, readMs = 20_000 + secs * 1000)
        try {
            val wav = wav16(pcm)
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "audio/wav")
            conn.setFixedLengthStreamingMode(wav.size)
            val t0 = System.currentTimeMillis()
            try {
                conn.connect()
            } catch (e: SocketTimeoutException) {
                throw IOException(describeConnect(e)) // PC off/asleep: no retry, the phone takes over sooner
            } catch (e: IOException) {
                throw ConnectFailed(describeConnect(e))
            }
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

    private fun describeConnect(e: IOException): String = when (e) {
        is UnknownHostException -> "nom du PC inconnu (Tailscale activé sur le téléphone ?)"
        is SocketTimeoutException -> "le PC ne répond pas (allumé ? Tailscale actif ?)"
        is ConnectException, is NoRouteToHostException -> "PC injoignable (allumé ? serveur Whisper lancé ? Tailscale actif ?)"
        else -> "PC injoignable (${e.javaClass.simpleName})"
    }

    private fun read(conn: HttpURLConnection): String {
        val status = try {
            conn.responseCode
        } catch (e: SocketTimeoutException) {
            throw IOException("le PC met trop de temps à répondre")
        } catch (e: IOException) {
            throw IOException(describeConnect(e))
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
