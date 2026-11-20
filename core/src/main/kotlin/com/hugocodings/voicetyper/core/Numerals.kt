package com.hugocodings.voicetyper.core

import java.util.Locale

/**
 * Numbers said in words → digits ("vingt-cinq" → "25", "trois virgule cinq pour cent" → "3,5 %").
 * Port of desktop Voice Typer `numerals.py` (0.18), same rules and tests.
 *
 * Left in words on purpose:
 * - "un" / "une" / "one" alone (articles), "mille" alone ("mille mercis"), "zéro" / "zero" alone ("repartir à zéro")
 *   — converted when other numbers follow ("un deux trois", "zéro six"), or before "pour cent" / "virgule";
 * - "neuf" when it means *new* ("un vélo neuf"): converted only when a noun follows ("neuf heures");
 * - words joined to another word by a hyphen ("deux-roues", "trois-quarts").
 * Two numbers said one after the other stay separate ("deux trois" → "2 3"), except English years
 * ("nineteen eighty-four" → "1984", "twenty twenty-four" → "2024").
 */
object Numerals {
    private val FR = mapOf(
        "zéro" to 0L, "un" to 1L, "une" to 1L, "deux" to 2L, "trois" to 3L, "quatre" to 4L, "cinq" to 5L, "six" to 6L,
        "sept" to 7L, "huit" to 8L, "neuf" to 9L, "dix" to 10L, "onze" to 11L, "douze" to 12L, "treize" to 13L,
        "quatorze" to 14L, "quinze" to 15L, "seize" to 16L, "vingt" to 20L, "vingts" to 20L, "trente" to 30L,
        "quarante" to 40L, "cinquante" to 50L, "soixante" to 60L, "septante" to 70L, "huitante" to 80L, "octante" to 80L,
        "nonante" to 90L, "cent" to 100L, "cents" to 100L, "mille" to 1000L, "million" to 1_000_000L,
        "millions" to 1_000_000L, "milliard" to 1_000_000_000L, "milliards" to 1_000_000_000L,
    )
    private val EN = mapOf(
        "zero" to 0L, "one" to 1L, "two" to 2L, "three" to 3L, "four" to 4L, "five" to 5L, "six" to 6L, "seven" to 7L,
        "eight" to 8L, "nine" to 9L, "ten" to 10L, "eleven" to 11L, "twelve" to 12L, "thirteen" to 13L,
        "fourteen" to 14L, "fifteen" to 15L, "sixteen" to 16L, "seventeen" to 17L, "eighteen" to 18L,
        "nineteen" to 19L, "twenty" to 20L, "thirty" to 30L, "forty" to 40L, "fifty" to 50L, "sixty" to 60L,
        "seventy" to 70L, "eighty" to 80L, "ninety" to 90L, "hundred" to 100L, "thousand" to 1000L,
        "million" to 1_000_000L, "millions" to 1_000_000L, "billion" to 1_000_000_000L, "billions" to 1_000_000_000L,
    )
    private val BIG = setOf(1000L, 1_000_000L, 1_000_000_000L)
    private val HUNDRED_WORDS = setOf("cent", "cents", "hundred")
    private val BIG_WORDS = setOf("mille", "thousand", "million", "millions", "milliard", "milliards", "billion", "billions")
    private val ALONE_KEPT = setOf("un", "une", "one", "mille", "zéro", "zero")
    private val PERCENT_NEXT = setOf("pour", "pourcent", "pourcents", "percent")
    private val FUNCTION_WORDS = setOf(
        "et", "ou", "mais", "que", "qui", "comme", "pour", "à", "a", "de", "du", "des", "en", "dans", "sur", "avec",
        "sans", "chez", "par", "le", "la", "les", "un", "une", "ce", "cette", "il", "elle", "je", "tu", "on",
        "nous", "vous", "ils", "elles", "est", "sont", "était", "aussi", "pas", "plus", "très", "non", "oui",
    )
    private val TOKEN = Regex("[\\p{L}\\p{M}]+(?:[-‑][\\p{L}\\p{M}]+)*|\\d+|\\s+|.", RegexOption.DOT_MATCHES_ALL)
    private val HYPHEN = Regex("[-‑]")

    private data class Item(val word: String, val value: Long, val lang: String)

    private fun vocab(language: String?): List<Pair<String, Map<String, Long>>> = when (language) {
        "fr" -> listOf("fr" to FR)
        "en" -> listOf("en" to EN)
        else -> listOf("fr" to FR, "en" to EN)
    }

    private fun lookup(word: String, vocabs: List<Pair<String, Map<String, Long>>>): Pair<Long, String>? {
        val w = word.lowercase()
        for ((lang, table) in vocabs) table[w]?.let { return it to lang }
        return null
    }

    private fun isAlphaStart(s: String) = s.isNotEmpty() && s[0].isLetter()
    private fun isDigits(s: String) = s.isNotEmpty() && s.all { it.isDigit() }
    private fun isSpace(s: String) = s.isNotEmpty() && s.all { it.isWhitespace() }

    /** [(word, value, lang)] for a number token ("quatre-vingt-dix", "trente-et-un"), else null. */
    private fun wordItems(token: String, vocabs: List<Pair<String, Map<String, Long>>>): List<Item>? {
        if (!isAlphaStart(token)) return null
        val parts = token.split(HYPHEN)
        val items = mutableListOf<Item>()
        for (p in parts) {
            if (p.lowercase() == "et" && parts.size >= 3) continue
            val hit = lookup(p, vocabs) ?: return null
            items += Item(p.lowercase(), hit.first, hit.second)
        }
        return items.ifEmpty { null }
    }

    /** One number read word by word (French and English grammar of numbers). */
    private class Num {
        var total = 0L
        var current = 0L
        var lastBig = 0L
        var bigWord = ""
        var lastWord = ""
        var lastLang = ""
        val words = mutableListOf<String>()

        fun copy() = Num().also {
            it.total = total; it.current = current; it.lastBig = lastBig; it.bigWord = bigWord
            it.lastWord = lastWord; it.lastLang = lastLang; it.words += words
        }

        val value get() = total + current

        fun canTake(word: String, v: Long, lang: String): Boolean {
            if (words.isEmpty()) return true
            if (v == 0L || lastWord == "zéro" || lastWord == "zero") return false
            val r = current % 100
            if (v == 100L) {
                if ((word == "cent" || word == "cents") && lastLang != "fr" && lastWord !in BIG_WORDS) return false
                return current < 100 && lastWord !in HUNDRED_WORDS
            }
            if (v in BIG) return (lastBig == 0L || v < lastBig) && lastWord !in BIG_WORDS
            if (v == 20L && lastWord == "quatre" && r == 4L) return true          // quatre-vingt(s)
            if (r == 0L) return true                                              // "cent deux", "two thousand five"
            if (r >= 20 && r % 10 == 0L && v < 10) return true                    // vingt-deux, twenty-one
            if ((r == 60L || r == 80L) && v in 10..19 && lang == "fr") return true // soixante-douze, quatre-vingt-dix
            return r == 10L && v in 7..9 && lastWord == "dix"                     // dix-sept
        }

        fun take(word: String, v: Long, lang: String) {
            when {
                v == 100L -> current = (if (current == 0L) 1 else current) * 100
                v in BIG -> {
                    total += (if (current == 0L) 1 else current) * v
                    current = 0; lastBig = v
                    bigWord = if (v >= 1_000_000L) word else ""
                }
                v == 20L && lastWord == "quatre" && current % 100 == 4L -> current += 76
                else -> current += v
            }
            lastWord = word; lastLang = lang
            words += word
        }

        fun text(lang: String): String {
            if (bigWord.isNotEmpty() && value % lastBig == 0L) return "${group(value / lastBig, lang)} $bigWord"
            return group(value, lang)
        }
    }

    private fun group(value: Long, lang: String): String {
        if (value < 10000) return value.toString()            // years and small numbers: "2024", "1500"
        val s = String.format(Locale.US, "%,d", value)
        return if (lang == "fr") s.replace(",", " ") else s // "12 500" never cut at a line end
    }

    private class Reader(text: String, val language: String?) {
        val tokens: List<String> = TOKEN.findAll(text).map { it.value }.toList()
        val vocabs = vocab(language)

        fun nextWord(start: Int): Pair<String, Int> {
            var j = start
            while (j < tokens.size && isSpace(tokens[j])) j++
            return if (j < tokens.size) tokens[j].lowercase() to j else "" to j
        }

        /** The longest number starting at token i: (number, index after its last token). */
        fun read(i: Int): Pair<Num, Int>? {
            var num = Num()
            var j = i
            var end = i
            if (tokens[i].lowercase() == "a" && language != "fr") {             // "a hundred", "a thousand"
                val (nxt, _) = nextWord(i + 1)
                if (nxt !in setOf("hundred", "thousand", "million", "billion")) return null
                num.take("a", 1, "en")
                j = i + 1; end = i + 1
            }
            while (j < tokens.size) {
                val t = tokens[j]
                if (isSpace(t)) { j++; continue }
                val low = t.lowercase()
                if ((low == "et" || low == "and") && num.words.isNotEmpty()) { // "vingt et un", "a hundred and five"
                    val (nxt, _) = nextWord(j + 1)
                    val r = num.current % 100
                    if (low == "et" && r in setOf(20L, 30L, 40L, 50L, 60L) && nxt in setOf("un", "une", "onze")) { j++; continue }
                    val hit = lookup(nxt, listOf("en" to EN))
                    if (low == "and" && num.value != 0L && r == 0L && hit != null && hit.first in 1..99) { j++; continue }
                    break
                }
                val items = wordItems(t, vocabs) ?: break
                val trial = num.copy()
                var ok = true
                for (it in items) {
                    if (!trial.canTake(it.word, it.value, it.lang)) { ok = false; break }
                    trial.take(it.word, it.value, it.lang)
                }
                if (!ok) break
                num = trial
                j++; end = j
            }
            return if (num.words.isNotEmpty() && end > i) num to end else null
        }

        fun startsNumber(j: Int): Boolean {
            val (nxt, k) = nextWord(j)
            return nxt.isNotEmpty() && (isDigits(nxt) || wordItems(tokens[k], vocabs) != null)
        }

        fun keepInWords(num: Num, end: Int, out: List<String>): Boolean {
            if (num.words.size != 1) return false
            val word = num.words[0]
            val prev = out.lastOrNull { !isSpace(it) }?.lowercase() ?: ""
            if ((word == "cent" || word == "cents") && (prev == "pour" || isDigits(prev) || word == "cents")) return true
            val (nxt, k) = nextWord(end)
            if (word == "neuf") return !isAlphaStart(nxt) || nxt in FUNCTION_WORDS // "neuf heures" → 9 heures
            if (word !in ALONE_KEPT) return false
            if (nxt in PERCENT_NEXT || (nxt == "virgule" || nxt == "point") && startsNumber(k + 1)) return false
            if (word == "mille") return true
            if (startsNumber(end)) return false                                  // "un deux trois"
            if (word == "zéro" || word == "zero") return !isDigits(prev)         // "… six zéro"
            return true
        }
    }

    /** [language]: Lang.FR / Lang.EN, or null = both. */
    fun toDigits(text: String, language: Lang?): String {
        val lang = when (language) { Lang.FR -> "fr"; Lang.EN -> "en"; null -> null }
        val rd = Reader(text, lang)
        val tokens = rd.tokens
        val out = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            val got = if (isAlphaStart(tokens[i])) rd.read(i) else null
            if (got == null) { out += tokens[i]; i++; continue }
            val (num, end) = got
            val nl = num.lastLang.ifEmpty { "fr" }
            // English years said in two halves: "nineteen eighty-four", "twenty twenty-four"
            if (nl == "en" && (num.value == 19L || num.value == 20L) && num.words.size == 1) {
                val (nxt, k) = rd.nextWord(end)
                val after = if (isAlphaStart(nxt)) rd.read(k) else null
                if (after != null && after.first.lastLang == "en" && after.first.value in 10..99 && after.first.lastBig == 0L) {
                    out += (num.value * 100 + after.first.value).toString()
                    i = after.second
                    continue
                }
            }
            if (rd.keepInWords(num, end, out)) out += tokens.subList(i, end) else out += num.text(nl)
            i = end
        }
        return decimals(out.joinToString(""), lang)
    }

    private val U = setOf(RegexOption.IGNORE_CASE)
    private fun decimals(input: String, language: String?): String {
        var text = input
        if (language != "en") {
            text = Regex("\\b(\\d+) virgule (\\d+)\\b", U).replace(text, "$1,$2")
            text = Regex("\\b(\\d+(?:,\\d+)?) ?(?:pour ?cents?|pourcents?)\\b", U).replace(text, "$1 %")
        }
        if (language != "fr") {
            text = Regex("\\b(\\d+) point (\\d+)\\b", U).replace(text, "$1.$2")
            text = Regex("\\b(\\d+(?:\\.\\d+)?) ?percent\\b", U).replace(text, "$1%")
        }
        return text
    }
}
