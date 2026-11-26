package com.hugocodings.voicetyper

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.hugocodings.voicetyper.core.HomePc
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * What the app knows about the PC link: a background /v1/ping whenever the bubble shows (throttled) tells the next
 * dictation whether to record for the PC or use the phone's real-time recognition right away. Every result goes to
 * [history] (shown in the PC card) to find what triggers drops.
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
    suspend fun warmUp(address: String, key: String, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (checking || !HomePc.usesHome(address, key)) return
        if (!force && now - maxOf(lastOkAt, lastFailAt) < MIN_INTERVAL_MS) return
        checking = true
        try {
            val t0 = System.currentTimeMillis()
            val model = PcEngine.ping(address, key)
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

/**
 * « Voice Typer Mobile is open »: while the bubble shows because the user is typing (PC engine set up), Home
 * Connections is told every [HomePc.PRESENCE_EVERY_MS] (`POST /v1/presence`), so it loads Whisper right away and keeps
 * it ready; when the bubble goes, one `{"active":false}` (after a short grace: the keyboard flickers between fields)
 * and Whisper sleeps after its delay on the PC. Which models load is chosen in Home Connections (Applications).
 */
object PcPresence {
    private var loop: Job? = null
    private var leaving: Job? = null
    private var target: Pair<String, String>? = null

    fun update(scope: CoroutineScope, active: Boolean, address: String, key: String) {
        val wanted = address to key
        if (active && HomePc.usesHome(address, key)) {
            leaving?.cancel()
            if (loop?.isActive == true && target == wanted) return
            loop?.cancel()
            target = wanted
            loop = scope.launch {
                while (isActive) {
                    PcEngine.presence(address, key, true)
                    delay(HomePc.PRESENCE_EVERY_MS)
                }
            }
        } else if (loop != null) {
            loop?.cancel()
            loop = null
            val (a, k) = target ?: return
            leaving = scope.launch {
                delay(3_000)
                PcEngine.presence(a, k, false)
            }
        }
    }
}

/**
 * Client of Home Connections on the user's home PC ([HomePc]): public HTTPS address (Tailscale Funnel), key checked
 * by the PC, no Tailscale on the phone. GET /v1/ping, POST /v1/wake, POST /v1/transcribe (zlib PCM).
 */
object PcEngine {
    /** Returns the model name the PC runs. */
    suspend fun ping(address: String, key: String): String = withContext(Dispatchers.IO) {
        val conn = open(address, "/v1/ping", connectMs = 5000, readMs = 8000, key = key)
        try {
            val json = JSONObject(read(conn))
            val whisper = json.optJSONObject("services")?.optJSONObject("whisper")
            val state = whisper?.optString("state").orEmpty()
            val model = json.optString("whisper").ifBlank { "modèle en chargement" }
            val where = if (json.optString("device") == "cuda") "$model, carte graphique" else model
            if (state == "asleep" || state == "off") "$where, en veille" else where
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Asks the PC to load Whisper now (a recording just started), so it is awake when the audio arrives. Best effort:
     * a failure is only logged, the dictation itself reports any real problem.
     */
    suspend fun wake(address: String, key: String) = withContext(Dispatchers.IO) {
        try {
            val conn = open(address, "/v1/wake", connectMs = 5000, readMs = 8000, key = key)
            try {
                val body = HomePc.WAKE_BODY.toByteArray()
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
                read(conn)
            } finally {
                conn.disconnect()
            }
        } catch (e: IOException) {
            DiagnosticLog.add("PC : réveil de Whisper raté (${e.message})")
        }
    }

    /** « This app is open » (or no longer, [active] false). Best effort: failures are ignored. */
    suspend fun presence(address: String, key: String, active: Boolean) = withContext(Dispatchers.IO) {
        try {
            val conn = open(address, "/v1/presence", connectMs = 5000, readMs = 8000, key = key)
            try {
                val body = HomePc.presenceBody(active).toByteArray()
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
                conn.responseCode
            } finally {
                conn.disconnect()
            }
        } catch (e: IOException) {
            // ignored: presence is a hint, the next one (15 s) or the dictation itself reports a real problem
        }
    }

    /**
     * Returns the text; throws [IOException] with a user-facing message. A connection that fails right away
     * (network switching from Wi-Fi to mobile data) is retried once.
     */
    suspend fun transcribe(address: String, pcm: FloatArray, lang: String, key: String,
                           hotwords: String? = null): String = try {
        transcribeOnce(address, pcm, lang, key, hotwords)
    } catch (e: ConnectFailed) {
        DiagnosticLog.add("PC : connexion ratée (${e.message}), nouvel essai")
        delay(700)
        try {
            transcribeOnce(address, pcm, lang, key, hotwords)
        } catch (e2: ConnectFailed) {
            throw IOException(e2.message)
        }
    }

    /** The connection itself failed (nothing was sent): safe to retry. */
    private class ConnectFailed(message: String) : IOException(message)

    private suspend fun transcribeOnce(address: String, pcm: FloatArray, lang: String, key: String,
                                       hotwords: String?): String = try {
        transcribeRaw(address, pcm, lang, key, hotwords).also { PcHealth.record(true, "Dictée transcrite par le PC") }
    } catch (e: IOException) {
        PcHealth.record(false, e.message ?: e.javaClass.simpleName)
        throw e
    }

    private suspend fun transcribeRaw(address: String, pcm: FloatArray, lang: String, key: String,
                                      hotwords: String?): String = withContext(Dispatchers.IO) {
        val secs = pcm.size / AudioCapture.RATE
        if (secs > HomePc.MAX_SECONDS) throw IOException("dictée de plus de 5 minutes : trop longue pour le PC")
        val conn = open(address, "/v1/transcribe", connectMs = 5000, readMs = 20_000 + secs * 1000, key = key)
        try {
            val body = HomePc.encodeAudio(pcm)
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.setRequestProperty("X-VT-Options", HomePc.options(lang, hotwords))
            conn.setFixedLengthStreamingMode(body.size)
            val t0 = System.currentTimeMillis()
            try {
                conn.connect()
            } catch (e: SocketTimeoutException) {
                throw IOException(describeConnect(e)) // PC off/asleep: no retry, the phone takes over sooner
            } catch (e: IOException) {
                throw ConnectFailed(describeConnect(e))
            }
            conn.outputStream.use { it.write(body) }
            val text = JSONObject(read(conn)).optString("text").trim()
            DiagnosticLog.add("PC : $secs s d'audio transcrit en ${System.currentTimeMillis() - t0} ms (envoi compris)")
            text
        } finally {
            conn.disconnect()
        }
    }

    private fun open(address: String, path: String, connectMs: Int, readMs: Int, key: String): HttpURLConnection {
        if (key.isBlank()) throw IOException("clé du PC manquante (Home Connections : onglet Connexion → « Copier la clé »)")
        val base = HomePc.baseUrl(address)
            ?: throw IOException("adresse du PC invalide (exemple : https://mon-pc.tail1234.ts.net)")
        val conn = URL(base + path).openConnection() as HttpURLConnection
        conn.connectTimeout = connectMs
        conn.readTimeout = readMs
        conn.useCaches = false
        conn.setRequestProperty("Authorization", "Bearer ${key.trim()}")
        conn.setRequestProperty("X-App", HomePc.APP)
        conn.setRequestProperty("User-Agent", "VoiceTyperMobile")
        return conn
    }

    private fun describeConnect(e: IOException): String = when (e) {
        is UnknownHostException -> "adresse du PC inconnue (pas de réseau ? adresse mal recopiée ?)"
        is SocketTimeoutException -> "le PC ne répond pas (allumé ? pas en veille ?)"
        is ConnectException, is NoRouteToHostException -> "PC injoignable (allumé ? Home Connections lancé ?)"
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
        throw IOException(HomePc.describeStatus(status))
    }
}
