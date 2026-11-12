package com.hugocodings.voicetyper.core

/** Merging a dictation into the existing text of a field at the cursor (pure, so it is unit-tested). */
object Insertion {

    data class Merged(val text: String, val cursor: Int, val inserted: String, val at: Int)

    /**
     * Inserts [dictation] in [existing] replacing the selection [selStart, selEnd] (-1 = end of text).
     * Adds a space when glued to the previous word, and lowercases the first letter in the middle of a
     * sentence ("Je pense que | Il viendra" → "… que il viendra") unless [keepCase].
     */
    fun merge(existing: String, selStart: Int, selEnd: Int, dictation: String, keepCase: Boolean = false): Merged {
        val len = existing.length
        val a = (if (selStart < 0) len else selStart).coerceIn(0, len)
        val b = (if (selEnd < 0) a else selEnd).coerceIn(a, len)
        val before = existing.substring(0, a)
        val after = existing.substring(b)
        var ins = dictation
        if (before.isNotEmpty() && ins.isNotEmpty()) {
            val prev = before.last()
            val startsWithPunct = ins.first() in ",.;:!?…)]}»”\n"
            if (!prev.isWhitespace() && prev !in "([{«“'’-/" && !startsWithPunct) ins = " $ins"
            val lastVisible = before.trimEnd().lastOrNull()
            val midSentence = lastVisible != null && lastVisible !in ".!?…\n:"
            if (midSentence && !keepCase) ins = lowerFirstWordIfCommon(ins)
        }
        // avoid a double space when the field already has one after the cursor
        if (ins.endsWith(" ") && after.startsWith(" ")) ins = ins.dropLast(1)
        return Merged(before + ins + after, before.length + ins.length, ins, before.length)
    }

    /** "Il" → "il" but keep "Marc", "ChatGPT", "OK", "I". */
    private fun lowerFirstWordIfCommon(text: String): String {
        val m = Regex("^(\\s*)(\\p{Lu})(\\p{Ll}*)(?![\\p{L}])").find(text) ?: return text
        val word = m.groupValues[2] + m.groupValues[3]
        if (word.length == 1 && word == "I") return text
        if (word !in COMMON_STARTS) return text
        return m.groupValues[1] + word.lowercase() + text.substring(m.range.last + 1)
    }

    private val COMMON_STARTS = setOf(
        "Je", "Tu", "Il", "Elle", "On", "Nous", "Vous", "Ils", "Elles", "Le", "La", "Les", "Un", "Une", "Des",
        "Et", "Mais", "Ou", "Donc", "Car", "Que", "Qui", "Ce", "Cette", "Ces", "Mon", "Ma", "Mes", "Ton", "Ta",
        "Son", "Sa", "Ses", "Pour", "Avec", "Dans", "Sur", "En", "Au", "Aux", "Du", "De", "Pas", "Puis", "Alors",
        "Oui", "Non", "Merci", "C'est", "J'ai", "Ça",
        "The", "A", "An", "And", "But", "Or", "So", "We", "You", "He", "She", "It", "They", "This", "That",
        "My", "Your", "For", "With", "In", "On", "To", "Then", "Yes", "No", "Thanks", "It's",
    )
}
