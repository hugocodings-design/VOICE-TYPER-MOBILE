package com.hugocodings.voicetyper.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceCommandsTest {
    @Test fun lineBreakAbsorbsRecognizerPunctuation() {
        assertEquals("Bonjour,\nMerci", VoiceCommands.apply("Bonjour, à la ligne. merci", Lang.FR))
    }

    @Test fun punctuationCommands() {
        assertEquals("Tu viens ?", VoiceCommands.apply("tu viens point d'interrogation", Lang.FR))
        assertEquals("Oui, non", VoiceCommands.apply("oui virgule non", Lang.FR))
        assertEquals("Hello, world", VoiceCommands.apply("hello comma world", Lang.EN))
    }

    @Test fun curlyApostropheAndHyphenVariants() {
        assertEquals("Fin ; suite", VoiceCommands.apply("fin point-virgule suite", Lang.FR))
        assertEquals("Ah !", VoiceCommands.apply("ah point d’exclamation", Lang.FR))
    }

    @Test fun quotesAndParentheses() {
        assertEquals("Il a dit « bonjour »", VoiceCommands.apply("il a dit ouvrez les guillemets bonjour fermez les guillemets", Lang.FR))
        assertEquals("Voir (annexe)", VoiceCommands.apply("voir ouvrir la parenthèse annexe fermer la parenthèse", Lang.FR))
    }

    @Test fun paragraph() {
        assertEquals("Un.\n\nDeux", VoiceCommands.apply("un. nouveau paragraphe deux", Lang.FR))
    }

    @Test fun autoTriesBothLanguages() {
        assertEquals("Salut,\nOk", VoiceCommands.apply("salut, new line ok", null))
    }

    @Test fun commandWordsInsideOtherWordsAreLeftAlone() {
        assertEquals("Les virgules sont utiles", VoiceCommands.apply("les virgules sont utiles", Lang.FR))
    }

    @Test fun undoOnlyWhenSaidAlone() {
        assertTrue(VoiceCommands.isUndo("Efface ça.", Lang.FR))
        assertTrue(VoiceCommands.isUndo("scratch that", null))
        assertFalse(VoiceCommands.isUndo("il faut que tu effaces ça demain", Lang.FR))
    }
}

class TextTest {
    @Test fun fillersAndRepeats() {
        assertEquals("je pense que le projet avance", Text.removeFillers("euh je pense que le le projet euh avance", Lang.FR))
        assertEquals("nous nous voyons demain", Text.removeFillers("nous nous voyons demain", Lang.FR))
        assertEquals("I think so", Text.removeFillers("um I think uh so", Lang.EN))
    }

    @Test fun whisperHallucinationsRemoved() {
        assertEquals("", Text.stripWhisperHallucinations("Sous-titrage Société Radio-Canada"))
        assertEquals("", Text.stripWhisperHallucinations(" [BLANK_AUDIO] "))
        assertEquals("Bonjour Marc", Text.stripWhisperHallucinations("Bonjour Marc [Musique]"))
        assertEquals("Merci pour le rapport.", Text.stripWhisperHallucinations("Merci pour le rapport."))
    }

    @Test fun fillerInsideWordIsKept() {
        assertEquals("Heureux", Text.removeFillers("Heureux", Lang.FR))
    }

    @Test fun finalPeriod() {
        assertEquals("Ok.", Text.ensureFinalPeriod("Ok"))
        assertEquals("Ok ?", Text.ensureFinalPeriod("Ok ?"))
    }
}

class DictionaryTest {
    @Test fun wholePhraseIgnoringCase() {
        val r = listOf(Replacement("chat GPT", "ChatGPT"))
        assertEquals("J'utilise ChatGPT tous les jours", Dictionary.apply("J'utilise Chat  gpt tous les jours", r))
        assertEquals("chat GPTx", Dictionary.apply("chat GPTx", r))
    }

    @Test fun whisperPromptFromReplacementTargets() {
        assertEquals("ChatGPT, CV", Dictionary.whisperPrompt(listOf(Replacement("chat GPT", "ChatGPT"), Replacement("cv", "CV"), Replacement("x", " "))))
        assertEquals(null, Dictionary.whisperPrompt(emptyList()))
    }

    @Test fun catalogHasRecommendedModel() {
        assertTrue(ModelCatalog.byId(ModelCatalog.RECOMMENDED) != null)
        assertTrue(ModelCatalog.MODELS.all { it.url.startsWith("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-") })
    }

    @Test fun appEntryOverridesGeneral() {
        val general = listOf(Replacement("asap", "dès que possible"), Replacement("cv", "CV"))
        val perApp = listOf(AppDictionary("com.Slack", listOf(Replacement("asap", "ASAP"))))
        val merged = Dictionary.forApp(general, perApp, "com.Slack")
        assertEquals("ASAP CV", Dictionary.apply("asap cv", merged))
        assertEquals("dès que possible CV", Dictionary.apply("asap cv", Dictionary.forApp(general, perApp, "com.whatsapp")))
    }
}

class StylesTest {
    @Test fun messageDropsFinalPeriod() {
        assertEquals("On se voit demain", Styles.message("on se voit demain."))
        assertEquals("Tu viens ?", Styles.message("tu viens ?"))
    }

    @Test fun notesOneIdeaPerLine() {
        assertEquals("• Acheter du lait\n• Appeler Marc\n• Réserver", Styles.notes("acheter du lait. appeler Marc. réserver"))
    }

    @Test fun emailWithNamedRecipientAndClosing() {
        val out = Styles.email(
            "bonjour Marc je t'envoie le rapport. il est complet. dis-moi si ça te convient. merci bonne journée",
            Lang.FR, "Hugo",
        )
        assertEquals(
            "Bonjour Marc,\n\nJe t'envoie le rapport. Il est complet.\n\nDis-moi si ça te convient.\n\nMerci bonne journée,\nHugo",
            out,
        )
    }

    @Test fun emailNameEndsAtPeriod() {
        assertEquals("Bonjour Marc,", Styles.splitGreeting("Bonjour Marc. Je voulais te dire", Lang.FR)!!.line)
        assertEquals("Je voulais te dire", Styles.splitGreeting("Bonjour Marc. Je voulais te dire", Lang.FR)!!.rest)
    }

    @Test fun emailTitleAndName() {
        val g = Styles.splitGreeting("bonjour madame Tremblay voici le document", Lang.FR)!!
        assertEquals("Bonjour Madame Tremblay,", g.line)
        assertEquals("voici le document", g.rest)
    }

    @Test fun emailWithoutGreeting() {
        assertEquals("Hello,\n\nThe file is ready.\n\nThank you,", Styles.email("the file is ready", Lang.EN, ""))
    }
}

class PipelineTest {
    private val settings = Settings(signature = "Hugo", trailingSpace = false)

    @Test fun presetPerApp() {
        assertEquals("email", settings.presetFor("com.google.android.gm").id)
        assertEquals("message", settings.presetFor("com.whatsapp").id)
        assertEquals("normal", settings.presetFor("com.unknown.app").id)
    }

    @Test fun fullPipelineNormal() {
        val p = settings.preset("normal")!!
        assertEquals("Je pense qu'on peut y aller, vraiment.",
            Pipeline.process("euh je pense qu'on peut y aller virgule vraiment", p, settings).text)
    }

    @Test fun fullPipelineMessageWithTrailingSpace() {
        val s = settings.copy(trailingSpace = true)
        assertEquals("J'arrive dans 5 minutes ", Pipeline.process("j'arrive dans 5 minutes", s.preset("message")!!, s).text)
    }

    @Test fun undoDetected() {
        assertTrue(Pipeline.process("efface ça", settings.preset("normal")!!, settings).undo)
    }

    @Test fun languageGuess() {
        assertEquals(Lang.EN, Pipeline.guessLang("I think that the file is ready"))
        assertEquals(Lang.FR, Pipeline.guessLang("je pense que le fichier est prêt"))
    }

    @Test fun oldAutoLanguageMigratesToFrenchOnce() {
        assertEquals(Lang.FR, Settings.decode("""{"settingsVersion":1,"language":null}""").language)
        // a v2 user who chose auto keeps it
        assertEquals(null, Settings.decode("""{"settingsVersion":2,"language":null}""").language)
        assertEquals(Lang.EN, Settings().toggledLanguage().language)
        assertEquals(Lang.FR, Settings().toggledLanguage().toggledLanguage().language)
    }

    @Test fun settingsRoundTripAndSanitize() {
        val s = settings.withAppPreset("com.example", "notes", "Exemple")
        val back = Settings.decode(Settings.encode(s))
        assertEquals("notes", back.presetFor("com.example").id)
        assertEquals(Settings(), Settings.decode("not json"))
        val broken = Settings.decode("""{"presets":[],"defaultPresetId":"zzz"}""")
        assertEquals("normal", broken.defaultPresetId)
    }
}

class InsertionTest {
    @Test fun appendsWithSpaceAndLowercasesMidSentence() {
        val m = Insertion.merge("Je pense que", -1, -1, "Il viendra demain.")
        assertEquals("Je pense que il viendra demain.", m.text)
        assertEquals(m.text.length, m.cursor)
    }

    @Test fun keepsProperNames() {
        assertEquals("Salut Marc", Insertion.merge("Salut", -1, -1, "Marc").text)
    }

    @Test fun afterSentenceEndKeepsCapital() {
        assertEquals("Fini. Il pleut.", Insertion.merge("Fini.", -1, -1, "Il pleut.").text)
    }

    @Test fun replacesSelectionInTheMiddle() {
        val m = Insertion.merge("Bonjour XXX merci", 8, 11, "Marc")
        assertEquals("Bonjour Marc merci", m.text)
        assertEquals(12, m.cursor)
    }

    @Test fun emptyFieldAndPunctuationStart() {
        assertEquals("Ok ", Insertion.merge("", -1, -1, "Ok ").text)
        assertEquals("Oui, non", Insertion.merge("Oui", -1, -1, ", non").text)
    }

    @Test fun noDoubleSpace() {
        assertEquals("a Ok b", Insertion.merge("a  b", 2, 2, "Ok ").text)
    }
}

class PcBoostSettingsTest {
    @Test fun oldSettingsGetEmptyPcAddress() {
        val s = Settings.decode("""{"settingsVersion":2,"engine":"WHISPER","whisperModel":"base"}""")
        assertEquals(Engine.WHISPER, s.engine)
        assertEquals("", s.pcAddress)
    }

    @Test fun pcEngineRoundTrips() {
        val s = Settings(engine = Engine.PC, pcAddress = "mon-pc.tail1234.ts.net")
        val back = Settings.decode(Settings.encode(s))
        assertEquals(Engine.PC, back.engine)
        assertEquals("mon-pc.tail1234.ts.net", back.pcAddress)
    }
}
