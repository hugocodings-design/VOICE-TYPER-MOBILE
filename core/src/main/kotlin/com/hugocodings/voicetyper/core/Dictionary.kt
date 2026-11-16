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
     * so "tail scale" / "tailscale" → "Tailscale", "chat-gpt" → "ChatGPT", "eric" → "Éric";
     * then words that sound alike ([soundsLike]): "Campwise" → "Kampwise".
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
        return soundsLike(out, vocabulary)
    }

    private val WORD = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}'’\\-]*")

    /**
     * Second pass: words that *sound* like a vocabulary word ("Campwise" → "Kampwise", "Érica Jack" → "Erika Jacques").
     * Compares phonetic keys (C/K/QU, PH/F, W/V, Y/I, doubled letters, silent endings…) with a small edit distance:
     * none under 5 key letters, 1 up to 7, 2 beyond; same first sound; a recognizer may split or join one word.
     */
    fun soundsLike(text: String, vocabulary: List<String>): String {
        var out = text
        for (term in vocabulary.map { it.trim() }.filter { it.length >= 3 }.distinctBy { it.lowercase() }.sortedByDescending { it.length }) {
            val termKey = phrasesKey(term)
            if (termKey.length < 3) continue
            val n = term.split(Regex("[\\s\\-]+")).count { it.isNotBlank() }
            val maxDist = when { termKey.length < 5 -> 0; termKey.length < 8 -> 1; else -> 2 }
            val sizes = listOf(n, n + 1, n - 1).filter { it >= 1 }.distinct()
            var pos = 0
            while (true) {
                val words = WORD.findAll(out, pos).take(n + 1).toList()
                if (words.isEmpty()) break
                var replaced = false
                for (w in sizes) {
                    if (w > words.size) continue
                    if (w != n && termKey.length < 5) continue
                    val start = words.first().range.first
                    val end = words[w - 1].range.last + 1
                    val candidate = out.substring(start, end)
                    if (candidate == term) { pos = end; replaced = true; break }
                    val key = phrasesKey(candidate)
                    if (key.isEmpty() || key[0] != termKey[0] || kotlin.math.abs(key.length - termKey.length) > maxDist) continue
                    if (levenshtein(key, termKey) <= maxDist) {
                        out = out.substring(0, start) + term + out.substring(end)
                        pos = start + term.length; replaced = true; break
                    }
                }
                if (!replaced) pos = words.first().range.last + 1
                if (pos >= out.length) break
            }
        }
        return out
    }

    private fun phrasesKey(s: String): String =
        WORD.findAll(s).joinToString("") { phoneticKey(it.value) }

    /** Rough French/English sound key of one word: "Jacques" → "jak", "Campwise" → "kanpvi" = "Kampwise". */
    fun phoneticKey(word: String): String {
        var t = Normalizer.normalize(word.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9]"), "")
        val rules = listOf(
            "ph" to "f", "qu" to "k", "ck" to "k", "q" to "k", "ch" to "x", "sh" to "x", "c(?=[eiy])" to "s", "c" to "k",
            "gu(?=[eiy])" to "g", "w" to "v", "y" to "i", "z" to "s", "x(?=.)" to "ks", "h" to "", "eau" to "o", "au" to "o",
            "ai" to "e", "ei" to "e", "m(?=[bp])" to "n", "(.)\\1+" to "$1",
        )
        for ((from, to) in rules) t = t.replace(Regex(from), to)
        if (t.length > 3) t = t.replace(Regex("(?<=.{2})[estdx]+$"), "")
        return t
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
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
