package com.hugocodings.voicetyper.core

/** Raw recognizer text → what gets inserted, for a given preset and target app. */
object Pipeline {

    /**
     * [subject] (Courriel style): generated email subject; [subjectLine] = "Objet : …" to put first when
     * the app has no empty subject field to fill.
     */
    data class Result(val text: String, val undo: Boolean = false, val subject: String? = null, val subjectLine: String? = null)

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

    /** [useClient]: apply the pasted client context (default: Courriel presets; any preset in the reply card). */
    fun process(raw: String, preset: Preset, settings: Settings, app: String? = null,
                useClient: Boolean = preset.style == Style.EMAIL): Result {
        val text = raw.trim()
        if (text.isEmpty()) return Result("")
        val declared = preset.language ?: settings.language
        if (VoiceCommands.isUndo(text, declared)) return Result("", undo = true)
        val lang = declared ?: guessLang(text)
        var t = text
        // before voice commands, like desktop: « trois virgule cinq » → « 3,5 », not « 3, 5 »
        if (settings.numbersAsDigits) t = Numerals.toDigits(t, declared)
        if (preset.voiceCommands) t = VoiceCommands.apply(t, lang)
        t = Dictionary.apply(t, Dictionary.forApp(settings.replacements, settings.appDictionaries, app))
        val client = if (useClient) settings.client(lang) else null
        t = Dictionary.applyVocabulary(t, settings.vocabulary + (client?.terms ?: emptyList()))
        if (preset.removeFillers) t = Text.removeFillers(t, lang)
        if (t.isBlank()) return Result("")
        // Courriel: the language of what was said decides greeting, closing and signature (FR/EN); when the text
        // does not tell, the client's email does, then the dictation language
        val mailLang = if (preset.style == Style.EMAIL) {
            guessLang(t, client?.let { guessLang(settings.clientContext, lang) } ?: lang)
        } else lang
        val subject = if (preset.style == Style.EMAIL && preset.emailSubject) client?.replySubject ?: EmailSubject.generate(t, mailLang) else null
        t = Styles.apply(preset.style, t, mailLang, settings.signatureFor(mailLang), client?.firstName, settings.signatureBlankLine)
        t = preset.prefix + t + preset.suffix
        if (settings.trailingSpace && preset.style !in setOf(Style.EMAIL, Style.NOTES) && !t.endsWith("\n")) t += " "
        return Result(t, subject = subject, subjectLine = subject?.let { EmailSubject.line(it, mailLang) })
    }
}
