package com.hugocodings.voicetyper.core

/** The text shaping of each [Style]. Input already has voice commands, dictionary and fillers applied. */
object Styles {

    fun apply(style: Style, text: String, lang: Lang, signature: String, greetName: String? = null,
              blankLineBeforeSignature: Boolean = false): String = when (style) {
        Style.RAW -> Text.capitalizeFirst(text)
        Style.CLEAN -> Text.ensureFinalPeriod(Text.capitalizeSentences(Text.capitalizeFirst(text)))
        Style.MESSAGE -> message(text)
        Style.EMAIL -> email(text, lang, signature, greetName, blankLineBeforeSignature)
        Style.NOTES -> notes(text)
    }

    /** Chat tone: capitalised, no trailing period (but ? and ! are kept). */
    fun message(text: String): String {
        val t = Text.capitalizeSentences(Text.capitalizeFirst(text.trim()))
        return if (t.endsWith(".") && !t.endsWith("..")) t.dropLast(1) else t
    }

    /** One idea per line as a bulleted list. Existing line breaks are kept as separate items. */
    fun notes(text: String): String =
        Text.sentences(text)
            .map { it.trimEnd('.', ' ').removePrefix("- ").removePrefix("• ") }
            .filter { it.isNotBlank() }
            .joinToString("\n") { "• " + Text.capitalizeFirst(it) }

    private val GREETINGS = mapOf(
        Lang.FR to listOf("bonjour", "bonsoir", "salut", "allô", "allo", "cher", "chère", "coucou"),
        Lang.EN to listOf("hello", "hi", "hey", "dear", "good morning", "good afternoon"),
    )
    private val TITLES = setOf("monsieur", "madame", "mademoiselle", "maître", "docteur", "mr", "mrs", "ms", "dr")
    private val CLOSINGS = mapOf(
        Lang.FR to listOf("merci beaucoup", "merci", "bonne journée", "bonne soirée", "bonne fin de journée",
            "cordialement", "salutations", "au plaisir", "à bientôt", "bien à vous"),
        Lang.EN to listOf("thank you", "thanks", "best regards", "kind regards", "regards", "best",
            "have a nice day", "cheers", "talk soon"),
    )

    data class Greeting(val line: String, val rest: String)

    /** "bonjour Marc je voulais…" → ("Bonjour Marc,", "je voulais…"). Names = following capitalised words. */
    fun splitGreeting(text: String, lang: Lang): Greeting? {
        val alt = GREETINGS.getValue(lang).sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
        val m = Regex("^\\s*($alt)(?![\\p{L}])[\\s,!.]*", RegexOption.IGNORE_CASE).find(text) ?: return null
        val greet = Text.capitalizeFirst(m.groupValues[1].lowercase())
        val who = mutableListOf<String>()
        var pos = m.range.last + 1
        val word = Regex("([\\p{L}'’\\-]+)(\\.?)([\\s,]*)")
        // optional title ("madame", "Mr."), then up to 3 capitalised words (recognizers capitalise names);
        // a period or comma after a name ends the name ("Bonjour Marc. Je voulais…")
        word.matchAt(text, pos)?.takeIf { it.groupValues[1].lowercase() in TITLES }?.let {
            who += Text.capitalizeFirst(it.groupValues[1].lowercase()); pos = it.range.last + 1
        }
        while (who.count { it.lowercase() !in TITLES } < 3 && pos < text.length) {
            val w = word.matchAt(text, pos) ?: break
            if (!w.groupValues[1][0].isUpperCase()) break
            if (w.groupValues[1] in setOf("I", "I'm", "I’m", "I'd", "I'll", "I've")) break // English "I" is not a name
            who += w.groupValues[1]; pos = w.range.last + 1
            if (w.groupValues[2].isNotEmpty() || "," in w.groupValues[3]) break
        }
        val line = (if (who.isEmpty()) greet else greet + " " + who.joinToString(" ")) + ","
        return Greeting(line, text.substring(minOf(pos, text.length)).trimStart(' ', ',', '.', '!'))
    }

    /** Last sentence if it is only a short closing formula ("merci, bonne journée"). */
    fun splitClosing(sentences: List<String>, lang: Lang): Pair<List<String>, String?> {
        if (sentences.isEmpty()) return sentences to null
        val last = sentences.last()
        val bare = last.lowercase().trimEnd('.', '!', ',', ' ')
        val known = CLOSINGS.getValue(lang)
        val parts = bare.split(Regex("[,.!]\\s*|\\s+et\\s+|\\s+and\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
        val isClosing = parts.isNotEmpty() && parts.all { p -> known.any { p == it || p.startsWith("$it ") && p.split(" ").size <= 4 } }
        return if (isClosing) sentences.dropLast(1) to Text.capitalizeFirst(last.trimEnd('.', ' ').trimEnd(',')) + ","
        else sentences to null
    }

    /** [greetName]: the client's name from the pasted email, used when the dictation has no greeting. */
    /** A line of the signature starts with a closing formula (« Merci et à bientôt. », « Cordialement, »). */
    fun signatureHasClosing(signature: String): Boolean =
        signature.lines().map { it.trim().lowercase() }.any { line ->
            CLOSINGS.values.flatten().any { c -> line == c || line.startsWith("$c ") || line.startsWith("$c,") || line.startsWith("$c.") || line.startsWith("$c!") }
        }

    fun email(text: String, lang: Lang, signature: String, greetName: String? = null,
              blankLineBeforeSignature: Boolean = false): String {
        val greeting = splitGreeting(text, lang)
        val hello = if (lang == Lang.FR) "Bonjour" else "Hello"
        val greetLine = greeting?.line ?: if (greetName.isNullOrBlank()) "$hello," else "$hello $greetName,"
        val body = greeting?.rest ?: text
        val (sentences, closing) = splitClosing(
            Text.sentences(Text.capitalizeSentences(Text.capitalizeFirst(body.trim()))), lang,
        )
        // paragraphs of 2 sentences, like the desktop "Courriel" preset
        val paragraphs = sentences.map { Text.ensureFinalPeriod(Text.capitalizeFirst(it)) }
            .chunked(2).joinToString("\n\n") { it.joinToString(" ") }
        // default « Merci, » only when neither the dictation nor the signature already has a closing formula
        val close = closing ?: if (signatureHasClosing(signature)) null else if (lang == Lang.FR) "Merci," else "Thank you,"
        return buildString {
            append(greetLine).append("\n\n")
            if (paragraphs.isNotEmpty()) append(paragraphs).append("\n\n")
            if (close != null) append(close)
            if (signature.isNotBlank()) {
                if (close != null) append(if (blankLineBeforeSignature) "\n\n" else "\n")
                append(signature.trim())
            }
        }.trimEnd()
    }
}
