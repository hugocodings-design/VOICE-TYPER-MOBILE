package com.hugocodings.voicetyper.core

/**
 * What a pasted client email tells us, extracted locally with rules (no LLM): who wrote it (for « Bonjour Marie, »),
 * their address, the reply subject and the proper nouns they use (people, companies, products, acronyms) so the
 * dictated reply spells them like the client does.
 */
data class ClientContext(
    val name: String?,
    val firstName: String?,
    val email: String?,
    val replySubject: String?,
    val terms: List<String>,
)

object ClientContexts {

    private const val MAX_TERMS = 40

    private val EMAIL = Regex("[\\w.+\\-]+@[\\w\\-]+(\\.[\\w\\-]+)+")
    private val FROM = Regex("^\\s*(?:de|from|expéditeur)\\s*:\\s*(.*)$", RegexOption.IGNORE_CASE)
    private val SUBJECT = Regex("^\\s*(?:objet|subject|sujet)\\s*:\\s*(.+)$", RegexOption.IGNORE_CASE)
    private val REPLY_PREFIX = Regex("^\\s*((re|tr|fw|fwd|réf)\\s*:\\s*)+", RegexOption.IGNORE_CASE)
    private val HEADER = Regex("^\\s*(de|from|à|to|cc|cci|bcc|objet|subject|sujet|envoyé|sent|date|le)\\s*:", RegexOption.IGNORE_CASE)
    private val CLOSING = Regex("^\\s*(cordialement|salutations( distinguées)?|bien à vous|merci( beaucoup)?|au plaisir|bonne (journée|soirée|fin de journée)|" +
        "à bientôt|best( regards)?|kind regards|regards|thanks|thank you|cheers|sincerely)\\b[\\s,.!]*$", RegexOption.IGNORE_CASE)
    private val TITLES = setOf("m.", "mme", "mlle", "me", "dr", "mr", "mrs", "ms", "monsieur", "madame")

    /** Capitalised words that are not names (sentence starters, greetings, header labels, days, months). */
    private val COMMON = setOf(
        "je", "j'ai", "tu", "il", "elle", "on", "nous", "vous", "ils", "elles", "le", "la", "les", "l'", "un", "une", "des", "du", "de",
        "ce", "cette", "ces", "ça", "cela", "mon", "ma", "mes", "votre", "vos", "notre", "nos", "si", "et", "ou", "mais", "donc", "car",
        "pour", "par", "avec", "sans", "dans", "en", "à", "au", "aux", "sur", "est", "c'est", "il y", "voici", "merci", "bonjour",
        "bonsoir", "salut", "allô", "cordialement", "salutations", "objet", "sujet", "envoyé", "date", "cc", "re", "tr", "tél", "tel",
        "téléphone", "cell", "courriel", "site", "adresse", "monsieur", "madame", "cher", "chère", "bien", "bonne", "oui", "non",
        "lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche", "janvier", "février", "mars", "avril", "mai", "juin",
        "juillet", "août", "septembre", "octobre", "novembre", "décembre",
        "i", "i'm", "we", "you", "he", "she", "they", "it", "the", "a", "an", "this", "that", "my", "our", "your", "hi", "hello",
        "dear", "thanks", "thank", "regards", "best", "please", "from", "to", "subject", "sent", "phone", "mobile", "email",
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", "january", "february", "march", "april",
        "june", "july", "august", "september", "october", "november", "december", "yes", "no", "if", "and", "or", "but",
    )

    fun extract(text: String, lang: Lang = Lang.FR, ignored: List<String> = emptyList()): ClientContext {
        val lines = text.lines()
        val email = lines.firstNotNullOfOrNull { l -> FROM.find(l)?.let { EMAIL.find(it.groupValues[1])?.value } }
            ?: EMAIL.find(text)?.value

        val name = senderFromHeader(lines) ?: senderFromSignature(lines) ?: email?.let { nameFromAddress(it) }
        val firstName = name?.split(Regex("\\s+"))?.let { parts ->
            if (parts.first().lowercase() in TITLES) parts.joinToString(" ") else parts.first()
        }

        val subject = lines.firstNotNullOfOrNull { SUBJECT.find(it)?.groupValues?.get(1) }
            ?.let { REPLY_PREFIX.replace(it, "").trim() }?.takeIf { it.isNotEmpty() }
        val replySubject = subject?.let { (if (lang == Lang.FR) "RE : " else "Re: ") + it }

        val skip = ignored.map { it.lowercase() }.toSet()
        val terms = (listOfNotNull(name) + (name?.split(Regex("\\s+"))?.filter { it.lowercase() !in TITLES } ?: emptyList()) +
            properNouns(listOfNotNull(subject?.let { "$it." }) + lines.filterNot { HEADER.containsMatchIn(it) }))
            .map { t -> t.trim().trimStart('(', '«', '"').let { if (COMPANY_END.containsMatchIn(it)) it else it.trimEnd(',', '.', ';', ':', '!', '?', ')') } }
            .filter { it.length >= 2 && it.lowercase() !in COMMON && it.lowercase() !in skip && !EMAIL.containsMatchIn(it) }
            .distinctBy { it.lowercase() }
            .take(MAX_TERMS)
        return ClientContext(name, firstName, email, replySubject, terms)
    }

    private fun senderFromHeader(lines: List<String>): String? {
        val raw = lines.firstNotNullOfOrNull { FROM.find(it)?.groupValues?.get(1) } ?: return null
        val n = raw.substringBefore('<').replace(EMAIL, "").trim().trim('"', '\'', ' ', ',')
        return n.takeIf { it.isNotEmpty() && it.split(Regex("\\s+")).size <= 5 }
    }

    /** The short line right after the last closing formula (« Cordialement, / Marie Tremblay »). */
    private fun senderFromSignature(lines: List<String>): String? {
        val idx = lines.indexOfLast { CLOSING.matches(it) }
        if (idx < 0) return null
        val next = lines.drop(idx + 1).firstOrNull { it.isNotBlank() }?.trim() ?: return null
        val words = next.trimEnd(',', '.').split(Regex("\\s+"))
        val looksLikeName = words.size in 1..4 && words.all { w -> w.first().isUpperCase() && w.none { it.isDigit() } } && !next.contains('@')
        return if (looksLikeName) next.trimEnd(',', '.') else null
    }

    /** "marie.tremblay@acme.com" → "Marie Tremblay". */
    private fun nameFromAddress(address: String): String? {
        val parts = address.substringBefore('@').split('.', '_', '-').filter { it.length >= 2 && it.all(Char::isLetter) }
        return if (parts.isEmpty() || parts.size > 3) null else parts.joinToString(" ") { Text.capitalizeFirst(it.lowercase()) }
    }

    private val COMPANY_END = Regex("\\b(inc|ltd|ltée|corp|co)\\.?$", RegexOption.IGNORE_CASE)
    private val GREETINGS = setOf("bonjour", "bonsoir", "salut", "allô", "allo", "hi", "hello", "hey", "dear", "cher", "chère", "coucou")
    private val TOKEN = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}'’&\\-.]*")
    private val JOINERS = setOf("de", "du", "des", "la", "le", "&", "et", "of", "and", "inc.", "inc", "ltée", "ltd", "ltd.", "llc")

    /**
     * Proper nouns: capitalised words not at the start of a sentence, ALL-CAPS words (acronyms), words with digits
     * or an inner capital (iPhone, SAP4), grouped into phrases (« Construction Lévesque inc. », « Ville de Québec »).
     * In short lines without a final period (signatures, headers) a capitalised first word counts too.
     */
    private fun properNouns(lines: List<String>): List<String> {
        val out = mutableListOf<String>()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || EMAIL.matches(trimmed) || trimmed.startsWith("http")) continue
            val tokens = TOKEN.findAll(trimmed).toList()
            // « Bonjour Hugo, » names the user, not the client
            if (tokens.size <= 4 && tokens.firstOrNull()?.value?.lowercase() in GREETINGS) continue
            val endsSentence = trimmed.last() in ".!?" && !COMPANY_END.containsMatchIn(trimmed)
            val shortLine = tokens.size <= 6 && !endsSentence
            var phrase = mutableListOf<String>()
            fun flush() {
                while (phrase.isNotEmpty() && phrase.last().lowercase() in JOINERS - setOf("inc.", "inc", "ltée", "ltd", "ltd.", "llc")) phrase.removeAt(phrase.size - 1)
                if (phrase.isNotEmpty()) out += phrase.joinToString(" ")
                phrase = mutableListOf()
            }
            for ((i, m) in tokens.withIndex()) {
                val w = m.value.trimEnd('.').let { if (m.value.endsWith(".") && it.lowercase() in setOf("inc", "ltd")) m.value else it }
                val before = trimmed.substring(0, m.range.first).trimEnd()
                val sentenceStart = i == 0 || before.endsWith('.') || before.endsWith('!') || before.endsWith('?') || before.endsWith(':')
                val isAcronym = w.length >= 2 && w.count { it.isLetter() } >= 2 && w.filter { it.isLetter() }.all { it.isUpperCase() }
                val isOdd = w.any { it.isDigit() } && w.any { it.isLetter() } || (w.length > 1 && w.drop(1).any { it.isUpperCase() } && !isAcronym)
                val cap = w.first().isUpperCase()
                val proper = (cap && (!sentenceStart || shortLine) && w.lowercase() !in COMMON) || isAcronym || isOdd
                when {
                    proper && w.lowercase() !in COMMON -> phrase += w
                    phrase.isNotEmpty() && w.lowercase().trimEnd('.') in setOf("inc", "ltée", "ltd", "llc") -> phrase += w
                    phrase.isNotEmpty() && w.lowercase() in JOINERS && i + 1 < tokens.size -> phrase += w
                    else -> flush()
                }
            }
            flush()
        }
        return out
    }
}
