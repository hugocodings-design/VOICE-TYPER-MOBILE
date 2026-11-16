package com.hugocodings.voicetyper.core

/**
 * Rule-based email subject from the dictated text (no LLM): skip the greeting and small talk, take the
 * first real sentence, drop the « je vous écris pour… » lead-in, keep ~8 words.
 * "Bonjour Marc, j'espère que tu vas bien. Je t'écris pour confirmer la réunion de lundi à 10 h." →
 * "Confirmer la réunion de lundi à 10 h".
 */
object EmailSubject {

    private const val MAX_WORDS = 8
    private const val MAX_CHARS = 70

    private val SMALL_TALK = mapOf(
        Lang.FR to Regex("^(j'espère que (vous allez|tu vas|tout va) bien|comment (allez-vous|vas-tu|ça va)|" +
            "ça va|j'espère que (vous avez|tu as) passé une? bonne? .*|merci pour (votre|ton) (message|courriel|retour))\\b.*$",
            RegexOption.IGNORE_CASE),
        Lang.EN to Regex("^(i hope (you're|you are|this email finds you|all is) .*|how are you( doing)?|" +
            "thanks for your (message|email|reply))\\b.*$", RegexOption.IGNORE_CASE),
    )

    private val INTROS = mapOf(
        Lang.FR to listOf(
            "je (vous |te |t')(écris|contacte|relance) (pour|afin de|au sujet (?:de |du |des |d')|concernant|à propos (?:de |du |des |d'))",
            "je me permets de (vous |te |t')(écrire|contacter|relancer) (pour|afin de|au sujet (?:de |du |des |d')|concernant|à propos (?:de |du |des |d'))",
            "je me permets de",
            "je voulais (juste |simplement )?(vous |te |t')(dire|informer|signaler|rappeler|demander|faire savoir|prévenir)( que| si| de| d')?",
            "je voulais (juste |simplement )?(savoir|vérifier|confirmer|voir)( si| que| avec (vous|toi))?( si| que)?",
            "(un )?petit (mot|message|courriel) pour( vous| te)?( dire| rappeler| informer| demander)?( que| si| de)?",
            "(c'est |ceci est )?(juste |simplement )?pour( vous| te)?( dire| rappeler| informer| demander)?( que| si| de)?",
            "je (vous |te |t')(informe|rappelle|confirme|signale)( que)?",
        ),
        Lang.EN to listOf(
            "i('m| am) (writing|reaching out|contacting you|following up) (to|about|regarding|on|with you about)",
            "i wanted to (let you know|tell you|remind you|ask you|ask|check|confirm|see)( that| if| whether| about)?",
            "(just )?a (quick )?(note|message|reminder) (to|about)( let you know| remind you| tell you)?( that)?",
            "(this is )?just to( let you know| remind you| tell you)?( that)?",
        ),
    )

    private val TRAILING_STOP = setOf(
        "de", "du", "des", "la", "le", "les", "l'", "d'", "et", "ou", "à", "au", "aux", "pour", "avec", "en", "un", "une",
        "que", "qui", "si", "mais", "sur", "dans", "par", "ce", "cette", "mon", "ma", "mes", "votre", "vos", "ton", "ta", "tes",
        "the", "a", "an", "of", "and", "or", "to", "for", "with", "on", "in", "at", "that", "if", "but", "my", "your",
    )

    fun generate(text: String, lang: Lang): String? {
        val body = Styles.splitGreeting(text, lang)?.rest ?: text
        val sentences = Text.sentences(body.trim())
        val small = SMALL_TALK.getValue(lang)
        val (withoutClosing, _) = Styles.splitClosing(sentences, lang)
        val first = withoutClosing.firstOrNull { !small.matches(it.trim().trimEnd('.', '!', '?', ' ')) } ?: return null

        var s = first.trim().trimEnd('.', '!', '?', '…', ' ', ',')
        val intro = INTROS.getValue(lang).joinToString("|") { "(?:$it)" }
        s = Regex("^(?:$intro)\\s*", RegexOption.IGNORE_CASE).replace(s, "").trim()
        // a long run-on sentence (recognizers often give no punctuation): stop at the first comma
        val comma = s.indexOf(',')
        if (comma > 0 && s.substring(0, comma).split(Regex("\\s+")).size >= 3) s = s.substring(0, comma)

        var words = s.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        if (words.size > MAX_WORDS) words = words.take(MAX_WORDS)
        while (words.size > 1 && words.last().lowercase().trimEnd(',', ';', ':') in TRAILING_STOP) words = words.dropLast(1)
        var subject = words.joinToString(" ").trimEnd(',', ';', ':', '.', ' ')
        if (subject.length > MAX_CHARS) subject = subject.take(MAX_CHARS).substringBeforeLast(' ')
        subject = Text.capitalizeFirst(subject)
        return subject.takeIf { it.any { c -> c.isLetter() } }
    }

    /** The first line written when no subject field could be filled. */
    fun line(subject: String, lang: Lang): String = if (lang == Lang.FR) "Objet : $subject" else "Subject: $subject"
}
