package com.hugocodings.voicetyper.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater

/**
 * « Voice Typer Home » protocol (desktop repo `home_server/`): the home PC behind Tailscale **Funnel** has a public
 * HTTPS address (`https://<pc>.<tailnet>.ts.net`) and checks a key — no Tailscale needed on the phone.
 *   GET  /v1/ping        (Authorization: Bearer <key>) → {"whisper","device",…}
 *   POST /v1/transcribe  body = zlib(16-bit little-endian PCM, 16 kHz mono), header X-VT-Options (JSON) → {"text"}
 * Used when [Settings.pcKey] is set; without a key the older FLASH LINGO server through Tailscale is used.
 */
object HomePc {
    const val MAX_SECONDS = 300

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

    /** User-facing message for an HTTP status from Voice Typer Home. */
    fun describeStatus(status: Int): String = when (status) {
        401, 403 -> "clé refusée (recopiez-la depuis l'icône de Voice Typer Home : « Copier la clé »)"
        429 -> "trop de mauvaises clés : réessayez dans une minute"
        413 -> "dictée trop longue pour le PC"
        503 -> "le PC prépare le modèle (première fois : téléchargement)"
        404, 502 -> "Voice Typer Home n'est pas lancé sur le PC (ou Funnel n'est pas actif)"
        else -> "erreur du PC ($status)"
    }
}
