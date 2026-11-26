package com.hugocodings.voicetyper.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater

/**
 * « Home Connections » protocol (repo `Home-Connections`, the home PC's one front door, which replaced Voice Typer
 * Home at the same address and key): the home PC behind Tailscale **Funnel** has a public HTTPS address
 * (`https://<pc>.<tailnet>.ts.net`) and checks a key — no Tailscale needed on the phone.
 *   GET  /v1/ping        (Authorization: Bearer <key>) → {"whisper","device","services",…}
 *   POST /v1/wake        {"models":["whisper"]} → loads Whisper now (sent when a recording starts)
 *   POST /v1/transcribe  body = zlib(16-bit little-endian PCM, 16 kHz mono), header X-VT-Options (JSON) → {"text"}
 * Every request also carries `X-App: voicetyper-mobile` (the PC's Activité tab). Address + key are both required:
 * the older key-less Whisper server through Tailscale (port 8766) is retired.
 */
object HomePc {
    const val MAX_SECONDS = 300
    const val APP = "voicetyper-mobile"
    const val WAKE_BODY = """{"models":["whisper"]}"""

    /** Address and key both set: the PC engine can be used. */
    fun usesHome(address: String, key: String): Boolean = address.isNotBlank() && key.isNotBlank()

    /** "hugo.tail3001ff.ts.net", "https://hugo.tail3001ff.ts.net/" → "https://hugo.tail3001ff.ts.net". HTTPS only. */
    fun baseUrl(address: String): String? {
        val a = address.trim().removePrefix("https://").trimEnd('/')
        if (a.isEmpty() || a.startsWith("http://") || a.any { it.isWhitespace() || it == '/' || it == '?' || it == '@' }) {
            return null
        }
        return "https://$a"
    }

    fun encodeAudio(pcm: FloatArray): ByteArray {
        val raw = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (f in pcm) raw.putShort((f.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        val deflater = Deflater(6)
        deflater.setInput(raw.array())
        deflater.finish()
        val out = ByteArrayOutputStream(pcm.size / 2 + 64)
        val buf = ByteArray(16 * 1024)
        while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
        deflater.end()
        return out.toByteArray()
    }

    /** X-VT-Options header: language fr/en (else auto), hotwords from the dictionary, "auto" speed. */
    fun options(lang: String?, hotwords: String?): String {
        fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\""
        val language = if (lang == "fr" || lang == "en") q(lang) else "null"
        val hw = hotwords?.trim()?.takeIf { it.isNotEmpty() }?.take(1000)?.let { q(it) } ?: "null"
        return """{"language":$language,"hotwords":$hw,"mode":"auto","sample_rate":16000}"""
    }

    /** User-facing message for an HTTP status from Home Connections. */
    fun describeStatus(status: Int): String = when (status) {
        401, 403 -> "clé refusée (recopiez-la depuis Home Connections : onglet Connexion → « Copier la clé »)"
        429 -> "trop de mauvaises clés : réessayez dans une minute"
        413 -> "dictée trop longue pour le PC"
        503 -> "le PC charge Whisper (il dormait) : le téléphone prend le relais"
        404, 502 -> "Home Connections n'est pas lancé sur le PC (ou Funnel n'est pas actif)"
        else -> "erreur du PC ($status)"
    }
}
