package com.hugocodings.voicetyper.core

/**
 * Spoken commands turned into formatting: "à la ligne" → line break, "virgule" → ",", etc.
 * Port of `voice_typer/commands.py` (desktop). Applied right after recognition, before the preset style.
 * Only phrases very unlikely in normal speech are commands ("deux points" is not one).
 */
object VoiceCommands {

    enum class Kind { BREAK, PUNCT, OPEN, CLOSE }

    data class Command(val phrases: List<String>, val output: String, val kind: Kind, val label: String)

    val COMMANDS: Map<Lang, List<Command>> = mapOf(
        Lang.FR to listOf(
            Command(listOf("nouveau paragraphe"), "\n\n", Kind.BREAK, "Nouveau paragraphe (ligne vide)"),
            Command(listOf("à la ligne", "retour à la ligne", "nouvelle ligne"), "\n", Kind.BREAK, "Retour à la ligne"),
            Command(listOf("point à la ligne"), ".\n", Kind.BREAK, "Point + retour à la ligne"),
            Command(listOf("point d'interrogation"), " ?", Kind.PUNCT, "?"),
            Command(listOf("point d'exclamation"), " !", Kind.PUNCT, "!"),
            Command(listOf("point-virgule", "point virgule"), " ;", Kind.PUNCT, ";"),
            Command(listOf("virgule"), ",", Kind.PUNCT, ","),
            Command(listOf("point final"), ".", Kind.PUNCT, "."),
            Command(listOf("points de suspension"), "…", Kind.PUNCT, "…"),
            Command(listOf("ouvrez les guillemets", "ouvrir les guillemets", "ouvre les guillemets"), "« ", Kind.OPEN, "«"),
            Command(listOf("fermez les guillemets", "fermer les guillemets", "ferme les guillemets"), " »", Kind.CLOSE, "»"),
            Command(listOf("ouvrez la parenthèse", "ouvrir la parenthèse", "ouvre la parenthèse"), "(", Kind.OPEN, "("),
            Command(listOf("fermez la parenthèse", "fermer la parenthèse", "ferme la parenthèse"), ")", Kind.CLOSE, ")"),
        ),
        Lang.EN to listOf(
            Command(listOf("new paragraph"), "\n\n", Kind.BREAK, "New paragraph (blank line)"),
            Command(listOf("new line", "next line"), "\n", Kind.BREAK, "Line break"),
            Command(listOf("question mark"), "?", Kind.PUNCT, "?"),
            Command(listOf("exclamation mark", "exclamation point"), "!", Kind.PUNCT, "!"),
            Command(listOf("semicolon"), ";", Kind.PUNCT, ";"),
            Command(listOf("comma"), ",", Kind.PUNCT, ","),
            Command(listOf("full stop"), ".", Kind.PUNCT, "."),
            Command(listOf("ellipsis"), "…", Kind.PUNCT, "…"),
            Command(listOf("open quote", "open quotes"), "“", Kind.OPEN, "“"),
            Command(listOf("close quote", "close quotes", "end quote"), "”", Kind.CLOSE, "”"),
            Command(listOf("open parenthesis", "open bracket"), "(", Kind.OPEN, "("),
            Command(listOf("close parenthesis", "close bracket"), ")", Kind.CLOSE, ")"),
        ),
    )

    private const val PUNCT = "[,;:.!?…]"
    // \b is ASCII-only in Java without UNICODE_CHARACTER_CLASS: use explicit letter look-arounds.
    private const val WB_START = "(?<![\\p{L}\\p{N}])"
    private const val WB_END = "(?![\\p{L}\\p{N}])"

    private fun phraseRegex(phrase: String): String =
        phrase.split(Regex("[\\s\\-]+")).joinToString("[\\s\\-]+") { part ->
            Regex.escape(part).replace("'", "\\E['’]\\Q")
        }

    private val cache = HashMap<Lang, List<Pair<Regex, Command>>>()

    private fun compile(lang: Lang): List<Pair<Regex, Command>> = cache.getOrPut(lang) {
        COMMANDS.getValue(lang)
            .sortedByDescending { c -> c.phrases.maxOf { it.length } }
            .map { c ->
                val alt = c.phrases.sortedByDescending { it.length }.joinToString("|") { phraseRegex(it) }
                val core = "$WB_START(?:$alt)$WB_END"
                val pat = when (c.kind) {
                    Kind.BREAK -> "[ \\t]*$core[ \\t]*$PUNCT?[ \\t]*"
                    Kind.CLOSE -> "[ \\t]*$PUNCT?[ \\t]*$core"
                    else -> "[ \\t]*$PUNCT?[ \\t]*$core[ \\t]*$PUNCT?[ \\t]*"
                }
                Regex(pat, setOf(RegexOption.IGNORE_CASE)) to c
            }
    }

    /** Replace spoken commands in [text]. `lang == null` (auto) tries French then English. */
    fun apply(text: String, lang: Lang?): String {
        if (text.isEmpty()) return text
        var out = text
        for (l in if (lang != null) listOf(lang) else listOf(Lang.FR, Lang.EN)) {
            for ((re, cmd) in compile(l)) {
                out = when (cmd.kind) {
                    Kind.OPEN -> re.replace(out) { " " + cmd.output }
                    Kind.BREAK -> re.replace(out) { cmd.output }
                    else -> re.replace(out) { cmd.output + " " }
                }
            }
        }
        return tidy(out)
    }

    internal fun tidy(input: String): String {
        var t = input
        t = t.replace(Regex("[ \\t]+\\n"), "\n")
        t = t.replace(Regex("\\n[ \\t]+"), "\n")
        t = t.replace(Regex("[ \\t]{2,}"), " ")
        t = Regex("([(«“])\\s+(?=\\S)").replace(t) { m -> m.groupValues[1] + if (m.groupValues[1] == "«") " " else "" }
        t = t.replace(Regex("(?<=\\S)\\s+([)”])"), "$1")
        t = t.replace(Regex(",(?=[.!?…])"), "")
        t = t.replace(Regex("(?<=[»”)]) +(?=[.,…])"), "")
        t = t.replace(Regex(" +$", RegexOption.MULTILINE), "")
        t = Text.capitalizeSentences(t)
        return t.trim(' ', '\t')
    }

    val UNDO_PHRASES: Map<Lang, List<String>> = mapOf(
        Lang.FR to listOf("efface ça", "efface cela", "efface ca", "annule ça", "annule la dernière dictée", "supprime ça"),
        Lang.EN to listOf("delete that", "scratch that", "undo that", "erase that"),
    )

    private fun bare(text: String): String =
        text.lowercase().replace("-", " ").replace(Regex("[^\\p{L}\\p{N}\\s'’]"), " ")
            .split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    /** True when the WHOLE dictation is just « efface ça » (said alone). */
    fun isUndo(text: String, lang: Lang?): Boolean {
        val b = bare(text)
        if (b.isEmpty() || b.length > 40) return false
        val langs = if (lang != null) listOf(lang) else Lang.entries
        return langs.any { l -> UNDO_PHRASES.getValue(l).any { bare(it) == b } }
    }
}
