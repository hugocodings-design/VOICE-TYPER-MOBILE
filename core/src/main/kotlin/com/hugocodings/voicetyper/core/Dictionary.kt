package com.hugocodings.voicetyper.core

import kotlinx.serialization.Serializable

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
    fun whisperPrompt(replacements: List<Replacement>): String? =
        replacements.map { it.to.trim() }.filter { it.isNotEmpty() && it.length <= 40 }.distinct()
            .take(30).joinToString(", ").ifEmpty { null }

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
