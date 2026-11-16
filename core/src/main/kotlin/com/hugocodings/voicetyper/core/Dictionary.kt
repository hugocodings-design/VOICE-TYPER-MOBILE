package com.hugocodings.voicetyper.core

import kotlinx.serialization.Serializable
import java.text.Normalizer

@Serializable
data class Replacement(val from: String, val to: String)

/** Per-app extra replacements (e.g. only in Gmail), like the desktop "dictionnaire par application". */
@Serializable
data class AppDictionary(val app: String, val replacements: List<Replacement> = emptyList())

object Dictionary {
    /** General replacements + those of [app]; an app entry overrides a general one with the same `from`. */
    fun forApp(general: List<Replacement>, perApp: List<AppDictionary>, app: String?): List<Replacement> {
        val rule = perApp.firstOrNull { app != null && it.app.equals(app, ignoreCase = true) } ?: return general
        val overridden = rule.replacements.map { it.from.trim().lowercase() }.toSet()
        return rule.replacements + general.filter { it.from.trim().lowercase() !in overridden }
    }

    /**
     * Words Whisper should expect (the replacement targets, e.g. "ChatGPT"), as an initial prompt —
     * the mobile equivalent of the desktop `hotwords`. Null when there is nothing to hint.
     */
    fun whisperPrompt(replacements: List<Replacement>, vocabulary: List<String> = emptyList()): String? =
        (vocabulary.map { it.trim() } + replacements.map { it.to.trim() })
            .filter { it.isNotEmpty() && it.length <= 40 }.distinctBy { it.lowercase() }
            .take(30).joinToString(", ").ifEmpty { null }

    /**
     * Vocabulary words (no « entendu » needed): any close spelling of the word in the text is rewritten
     * exactly as the user typed it — case, accents, spaces and hyphens are ignored when matching,
     * so "tail scale" / "tailscale" → "Tailscale", "chat-gpt" → "ChatGPT", "eric" → "Éric".
     */
    fun applyVocabulary(text: String, vocabulary: List<String>): String {
        var out = text
        for (word in vocabulary.map { it.trim() }.filter { it.length >= 2 }.distinctBy { it.lowercase() }.sortedByDescending { it.length }) {
            val parts = word.split(Regex("[\\s\\-]+")).flatMap { splitCamel(it) }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) continue
            // long parts may be heard split anywhere ("tail scale"); short ones only at their boundaries
            val body = parts.joinToString("[\\s\\-]*") { part ->
                part.map { charPattern(it) }.joinToString(if (part.length >= 5) "[\\s\\-]?" else "")
            }
            val pattern = Regex("(?<![\\p{L}\\p{N}_])$body(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE)
            out = pattern.replace(out) { word }
        }
        return out
    }

    /** "ChatGPT" → ["Chat", "GPT"], "Win11" → ["Win", "11"]: where a speech engine may put a space. */
    private fun splitCamel(s: String): List<String> =
        s.split(Regex("(?<=\\p{Ll})(?=\\p{Lu})|(?<=\\p{L})(?=\\p{N})|(?<=\\p{N})(?=\\p{L})|(?<=\\p{Lu})(?=\\p{Lu}\\p{Ll})"))

    private val ACCENTS = mapOf(
        'a' to "aàâäá", 'e' to "eéèêë", 'i' to "iîïí", 'o' to "oôöó", 'u' to "uùûüú", 'c' to "cç", 'y' to "yÿ", 'n' to "nñ",
    )

    private fun charPattern(c: Char): String {
        val base = Normalizer.normalize(c.toString(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
        val variants = if (base.length == 1) ACCENTS[base[0]] else null
        return if (variants != null) "[$variants${variants.uppercase()}]" else Regex.escape(c.toString())
    }

    /** Whole words/phrases, ignoring case: "chat GPT" → "ChatGPT". */
    fun apply(text: String, replacements: List<Replacement>): String {
        var out = text
        for (r in replacements) {
            val src = r.from.trim()
            if (src.isEmpty()) continue
            val pattern = "(?<![\\p{L}\\p{N}_])" +
                src.split(Regex("\\s+")).joinToString("\\s+") { Regex.escape(it) } + "(?![\\p{L}\\p{N}_])"
            out = Regex(pattern, RegexOption.IGNORE_CASE).replace(out) { r.to }
        }
        return out
    }
}
