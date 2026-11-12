package com.hugocodings.voicetyper.core

/** Small text helpers shared by the pipeline and the styles. */
object Text {
    private val SENTENCE_START = Regex("(^|\\n|[.!?…]\\s+)(\\p{Ll})")

    /** Capital letter at the start, at each line start and after a sentence end. */
    fun capitalizeSentences(text: String): String =
        SENTENCE_START.replace(text) { m -> m.groupValues[1] + m.groupValues[2].uppercase() }

    fun capitalizeFirst(text: String): String {
        val i = text.indexOfFirst { it.isLetter() }
        return if (i < 0) text else text.substring(0, i) + text[i].uppercase() + text.substring(i + 1)
    }

    fun endsWithPunctuation(text: String): Boolean =
        text.trimEnd().lastOrNull()?.let { it in ".!?…:;»”)" } ?: true

    /** Adds a final period when the text has none (recognizers often omit it). */
    fun ensureFinalPeriod(text: String): String {
        val t = text.trimEnd()
        return if (t.isEmpty() || endsWithPunctuation(t)) t else "$t."
    }

    /** Splits on sentence ends while keeping the punctuation. */
    fun sentences(text: String): List<String> =
        text.split(Regex("(?<=[.!?…])\\s+|\\n+")).map { it.trim() }.filter { it.isNotEmpty() }

    private val FILLERS = mapOf(
        Lang.FR to listOf("euh", "heu", "euhm", "hum", "hmm", "bah", "ben euh"),
        Lang.EN to listOf("uh", "um", "uhm", "erm", "hmm"),
    )

    /** Removes hesitations ("euh", "um") and accidental repeated words ("le le"). */
    fun removeFillers(text: String, lang: Lang?): String {
        val words = (if (lang != null) FILLERS.getValue(lang) else FILLERS.values.flatten())
            .sortedByDescending { it.length }
            .joinToString("|") { Regex.escape(it) }
        var t = Regex("(?i)(?<![\\p{L}])(?:$words)(?![\\p{L}])[,.]?\\s*").replace(text, "")
        t = Regex("(?i)(?<![\\p{L}])(\\p{L}+)(\\s+\\1)+(?![\\p{L}])").replace(t) { m ->
            val w = m.groupValues[1]
            // "nous nous", "vous vous" are correct French: keep them
            if (w.lowercase() in setOf("nous", "vous")) m.value else w
        }
        return t.replace(Regex("[ \\t]{2,}"), " ").trim()
    }
}
