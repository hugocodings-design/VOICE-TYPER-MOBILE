package com.hugocodings.voicetyper.core

/** Raw recognizer text → what gets inserted, for a given preset and target app. */
object Pipeline {

    data class Result(val text: String, val undo: Boolean = false)

    private val FR_HINTS = setOf("je", "tu", "le", "la", "les", "et", "est", "que", "pour", "pas", "une", "des", "bonjour", "merci", "avec", "vous", "nous", "c'est")
    private val EN_HINTS = setOf("i", "you", "the", "and", "is", "that", "for", "not", "a", "an", "of", "hello", "thanks", "with", "we", "it's", "to")

    /** Cheap FR/EN guess when the language is "auto" (the recognizer itself used the phone language). */
    fun guessLang(text: String, fallback: Lang = Lang.FR): Lang {
        val words = text.lowercase().split(Regex("[^\\p{L}'’]+")).filter { it.isNotEmpty() }
        val fr = words.count { it in FR_HINTS }
        val en = words.count { it in EN_HINTS }
        return when {
            fr > en -> Lang.FR
            en > fr -> Lang.EN
            else -> fallback
        }
    }

    fun process(raw: String, preset: Preset, settings: Settings, app: String? = null): Result {
        val text = raw.trim()
        if (text.isEmpty()) return Result("")
        val declared = preset.language ?: settings.language
        if (VoiceCommands.isUndo(text, declared)) return Result("", undo = true)
        val lang = declared ?: guessLang(text)
        var t = text
        if (preset.voiceCommands) t = VoiceCommands.apply(t, lang)
        t = Dictionary.apply(t, Dictionary.forApp(settings.replacements, settings.appDictionaries, app))
        if (preset.removeFillers) t = Text.removeFillers(t, lang)
        if (t.isBlank()) return Result("")
        t = Styles.apply(preset.style, t, lang, settings.signature)
        t = preset.prefix + t + preset.suffix
        if (settings.trailingSpace && preset.style !in setOf(Style.EMAIL, Style.NOTES) && !t.endsWith("\n")) t += " "
        return Result(t)
    }
}
