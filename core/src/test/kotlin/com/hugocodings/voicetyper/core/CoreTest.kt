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

    @Test fun vocabularyFixesCloseSpellings() {
        val v = listOf("Tailscale", "ChatGPT", "Éric", "FLASH LINGO")
        assertEquals("J'ouvre Tailscale et ChatGPT", Dictionary.applyVocabulary("J'ouvre tail scale et chat-gpt", v))
        assertEquals("Merci Éric, FLASH LINGO marche", Dictionary.applyVocabulary("Merci eric, flash lingo marche", v))
        assertEquals("ChatGPT", Dictionary.applyVocabulary("chatgpt", v))
        assertEquals("Tailscale ericsson", Dictionary.applyVocabulary("tailscales ericsson", v))
        assertEquals("a b", Dictionary.applyVocabulary("a b", listOf("A", "$1")))
    }

    @Test fun vocabularySoundsAlike() {
        val v = listOf("Kampwise", "Erika Jacques", "Chic-Chocs", "Saint-Laurent", "Lévis")
        assertEquals("J'utilise Kampwise tous les jours", Dictionary.applyVocabulary("J'utilise Campwise tous les jours", v))
        assertEquals("J'utilise Kampwise", Dictionary.applyVocabulary("J'utilise camp wise", v))
        assertEquals("Merci Erika Jacques pour", Dictionary.applyVocabulary("Merci Érica Jack pour", v))
        assertEquals("dans les Chic-Chocs l'hiver", Dictionary.applyVocabulary("dans les chic chocs l'hiver", v))
        assertEquals("le fleuve Saint-Laurent", Dictionary.applyVocabulary("le fleuve Saint Laurant", v))
        // ordinary words are left alone
        val plain = "Les vis du camp sont prêtes, on se voit mercredi à la station touristique."
        assertEquals(plain, Dictionary.applyVocabulary(plain, v))
    }

    @Test fun vocabularyInWhisperPromptAndPipeline() {
        assertEquals("Tailscale, ChatGPT", Dictionary.whisperPrompt(listOf(Replacement("chat GPT", "ChatGPT")), listOf("Tailscale", "ChatGPT")))
        val s = Settings(vocabulary = listOf("Tailscale"))
        val raw = Pipeline.process("j'ai activé tail scale", s.preset("normal")!!, s).text
        assertTrue(raw.contains("Tailscale"), raw)
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

class EmailSubjectTest {
    @Test fun skipsGreetingSmallTalkAndLeadIn() {
        assertEquals("Confirmer la réunion de lundi à 10 h",
            EmailSubject.generate("Bonjour Marc, j'espère que tu vas bien. Je t'écris pour confirmer la réunion de lundi à 10 h. Merci", Lang.FR))
        assertEquals("Le rapport trimestriel est prêt",
            EmailSubject.generate("bonjour je voulais vous dire que le rapport trimestriel est prêt", Lang.FR))
        assertEquals("The invoice for October",
            EmailSubject.generate("Hi Sarah, I hope you're doing well. I'm writing about the invoice for October.", Lang.EN))
    }

    @Test fun longRunOnIsShortened() {
        val s = EmailSubject.generate("je vous écris au sujet du budget de l'équipe pour l'année prochaine et des embauches prévues en mars", Lang.FR)!!
        assertEquals("Budget de l'équipe pour l'année prochaine", s)
        assertTrue(s.split(" ").size <= 8)
    }

    @Test fun nothingButGreetingGivesNoSubject() {
        assertEquals(null, EmailSubject.generate("Bonjour Marc, merci", Lang.FR))
    }

    @Test fun pipelineGivesSubjectOnlyForEmail() {
        val s = Settings()
        val r = Pipeline.process("bonjour je t'écris pour le souper de vendredi", s.preset("email")!!, s)
        assertEquals("Le souper de vendredi", r.subject)
        assertEquals("Objet : Le souper de vendredi", r.subjectLine)
        assertEquals(null, Pipeline.process("le souper de vendredi", s.preset("normal")!!, s).subject)
    }
}

class ClientContextTest {
    private val mail = """
        De : Marie-Ève Tremblay <me.tremblay@construction-levesque.ca>
        Envoyé : 14 novembre 2026 09:12
        À : Hugo
        Objet : RE: Soumission pour le chantier de Lévis

        Bonjour Hugo,

        Suite à notre appel, pouvez-vous confirmer le prix du béton pour le projet Les Jardins Saint-Romuald ?
        Notre contrôleur, Jean-Philippe Côté, doit approuver avec la SCHL avant vendredi.

        Merci,
        Marie-Ève Tremblay
        Construction Lévesque inc.
        418 555-1234
    """.trimIndent()

    @Test fun extractsSenderSubjectAndTerms() {
        val c = ClientContexts.extract(mail)
        assertEquals("Marie-Ève Tremblay", c.name)
        assertEquals("Marie-Ève", c.firstName)
        assertEquals("me.tremblay@construction-levesque.ca", c.email)
        assertEquals("RE : Soumission pour le chantier de Lévis", c.replySubject)
        for (t in listOf("Marie-Ève", "Tremblay", "Jardins Saint-Romuald", "Jean-Philippe Côté", "SCHL", "Construction Lévesque inc.", "Lévis"))
            assertTrue(c.terms.any { it == t }, "$t missing in ${c.terms}")
        for (t in listOf("Bonjour", "Merci", "Notre", "Hugo", "Suite", "Envoyé", "Soumission"))
            assertTrue(c.terms.none { it == t }, "$t should not be in ${c.terms}")
    }

    @Test fun signatureNameWithoutHeader() {
        val c = ClientContexts.extract("Allo, peux-tu m'envoyer le plan?\n\nCordialement,\nLuc Gagnon\nGroupe Alpha")
        assertEquals("Luc", c.firstName)
        assertEquals(null, c.replySubject)
        assertTrue("Groupe Alpha" in c.terms, c.terms.toString())
    }

    @Test fun replyCardAppliesClientToAnyPreset() {
        val s = Settings(clientContext = mail)
        assertEquals("email", s.replyPreset().id)
        assertEquals("notes", s.copy(replyPresetId = "notes").replyPreset().id)
        assertEquals("email", s.copy(replyPresetId = "deleted").replyPreset().id)
        val r = Pipeline.process("on a parlé à la schl", s.preset("message")!!, s, useClient = true)
        assertEquals("On a parlé à la SCHL ", r.text)
        assertEquals(null, r.subject)
        assertTrue("SCHL" in s.vocabularyFor(s.preset("notes"), useClient = true))
        assertTrue("SCHL" !in s.vocabularyFor(s.preset("notes")))
    }

    @Test fun ignoredTermsAreDropped() {
        assertTrue(ClientContexts.extract(mail, ignored = listOf("SCHL")).terms.none { it == "SCHL" })
    }

    @Test fun emailReplyUsesClientContext() {
        val s = Settings(clientContext = mail)
        val r = Pipeline.process("je vous confirme le prix pour les jardins saint-romuald et j'en parle à jean-philippe côté", s.preset("email")!!, s)
        assertTrue(r.text.startsWith("Bonjour Marie-Ève,\n\n"), r.text)
        assertTrue("les Jardins Saint-Romuald" in r.text, r.text)
        assertTrue("Jean-Philippe Côté" in r.text, r.text)
        assertEquals("RE : Soumission pour le chantier de Lévis", r.subject)
        // other styles ignore the client
        assertTrue("jardins saint-romuald" in Pipeline.process("les jardins saint-romuald", s.preset("message")!!, s).text.lowercase())
        assertEquals("Les jardins saint-romuald", Pipeline.process("les jardins saint-romuald", s.preset("message")!!, s).text.trim())
    }
}

class StylesTest {
    @Test fun signatureFollowsEmailLanguage() {
        val s = Settings(language = Lang.FR, signature = "Hugo\nMerci et à bientôt.", signatureEn = "Hugo\nThanks and talk soon.")
        val email = s.preset("email")!!
        val fr = Pipeline.process("bonjour Marc je vous envoie le contrat pour la semaine prochaine", email, s).text
        assertTrue(fr.endsWith("Hugo\nMerci et à bientôt."), fr)
        val en = Pipeline.process("hello Mark I am sending you the contract and the invoice for next week", email, s).text
        assertTrue(en.startsWith("Hello Mark,"), en)
        assertTrue(en.endsWith("Hugo\nThanks and talk soon."), en)
        // no English signature: the French one is used
        val noEn = s.copy(signatureEn = "")
        assertTrue(Pipeline.process("hello Mark I am sending you the contract", email, noEn).text.endsWith("Merci et à bientôt."))
        // short text that says nothing: the client's email language decides
        val withClient = s.copy(clientContext = "From: Mark Smith <mark@acme.com>\nHi Hugo, can you send the contract to us? Thanks, Mark")
        assertTrue(Pipeline.process("ok done", email, withClient).text.endsWith("Thanks and talk soon."))
    }

    @Test fun noDefaultClosingWhenSignatureHasOne() {
        val sig = "N'hésitez pas à me rappeler au 438-844-5989 pour toute question.\n\nMerci et à bientôt."
        val out = Styles.email("bonjour Marc le contrat est signé", Lang.FR, sig)
        assertEquals("Bonjour Marc,\n\nLe contrat est signé.\n\n$sig", out)
        // without a closing in the signature, « Merci, » is still added
        assertTrue(Styles.email("bonjour Marc le contrat est signé", Lang.FR, "Hugo").endsWith("Merci,\nHugo"))
        // a dictated closing is kept
        assertTrue(Styles.email("bonjour Marc le contrat est signé. Bonne journée", Lang.FR, sig).contains("Bonne journée,\n$sig"))
    }

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
    @Test fun oldSettingsGetDefaultPcAddress() {
        val s = Settings.decode("""{"settingsVersion":2,"engine":"WHISPER","whisperModel":"base"}""")
        assertEquals(Engine.WHISPER, s.engine)
        assertEquals(HomePc.DEFAULT_ADDRESS, s.pcAddress)
        assertEquals(HomePc.DEFAULT_ADDRESS, Settings().pcAddress)
    }

    @Test fun oldServerAddressesMigrateToHomeConnections() {
        for (old in listOf("", "hugo", "100.101.102.103", "hugo:8766", "http://hugo.tail3001ff.ts.net:8766")) {
            val s = Settings.decode("""{"settingsVersion":2,"engine":"PC","pcAddress":"$old"}""")
            assertEquals(HomePc.DEFAULT_ADDRESS, s.pcAddress, old)
        }
        // a Funnel address is kept, and nothing is rewritten once migrated (the user may clear it on purpose)
        assertEquals("mon-pc.tail1234.ts.net",
            Settings.decode("""{"settingsVersion":2,"pcAddress":"mon-pc.tail1234.ts.net"}""").pcAddress)
        assertEquals("", Settings.decode("""{"settingsVersion":3,"pcAddress":""}""").pcAddress)
    }

    @Test fun pcEngineRoundTrips() {
        val s = Settings(engine = Engine.PC, pcAddress = "mon-pc.tail1234.ts.net")
        val back = Settings.decode(Settings.encode(s))
        assertEquals(Engine.PC, back.engine)
        assertEquals("mon-pc.tail1234.ts.net", back.pcAddress)
    }
}

class SilenceDetectorTest {
    private fun run(levels: List<Float>): Int? {
        val d = SilenceDetector(stepMs = 100)
        levels.forEachIndexed { i, l -> if (d.feed(l)) return i }
        return null
    }

    @Test fun stopsInNoisyRoomAfterSpeech() {
        // office noise at level 5 (above the old fixed threshold 3), speech at 9-10, then back to noise
        val noise = List(10) { 5f }
        val speech = List(20) { if (it % 3 == 0) 9f else 10f }
        val stopAt = run(noise + speech + List(30) { 5f + (it % 2) * 0.5f })
        assertTrue(stopAt != null && stopAt in 45..50, "stopAt=$stopAt")
    }

    @Test fun keepsListeningWhileTalkingWithShortPauses() {
        val talk = (1..8).flatMap { List(15) { 9f } + List(8) { 2f } } // 0.8 s pauses
        assertEquals(null, run(List(5) { 2f } + talk))
    }

    @Test fun quietRoomAndNoSpeechTimeout() {
        assertTrue(run(List(5) { 0f } + List(10) { 8f } + List(20) { 0f })!! in 29..31)
        assertEquals(99, run(List(150) { 0f })) // nothing said: stops after 10 s
    }
}

class HomePcTest {
    @Test fun funnelAddressIsHttpsOnly() {
        assertEquals("https://hugo.tail3001ff.ts.net", HomePc.baseUrl("hugo.tail3001ff.ts.net"))
        assertEquals("https://hugo.tail3001ff.ts.net", HomePc.baseUrl(" https://hugo.tail3001ff.ts.net/ "))
        assertEquals(null, HomePc.baseUrl("http://hugo.tail3001ff.ts.net"))
        assertEquals(null, HomePc.baseUrl("hugo.ts.net/evil"))
    }

    @Test fun pcNeedsAddressAndKey() {
        assertTrue(HomePc.usesHome("hugo.tail3001ff.ts.net", "abc"))
        assertFalse(HomePc.usesHome("hugo.tail3001ff.ts.net", " ".trim()))
        assertFalse(HomePc.usesHome("", "abc"))
        val back = Settings.decode(Settings.encode(Settings(engine = Engine.PC, pcAddress = "a.ts.net", pcKey = "k")))
        assertEquals("k", back.pcKey)
        assertEquals("", Settings.decode("""{"engine":"PC","pcAddress":"a.ts.net"}""").pcKey)
    }

    @Test fun audioIsZlibCompressed16BitPcm() {
        val pcm = FloatArray(16000) { i -> if (i % 2 == 0) 0.5f else -0.5f }
        val packed = HomePc.encodeAudio(pcm)
        val inflater = java.util.zip.Inflater()
        inflater.setInput(packed)
        val raw = ByteArray(pcm.size * 2)
        assertEquals(raw.size, inflater.inflate(raw))
        val bb = java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(16383, bb.getShort(0).toInt())
        assertEquals(-16383, bb.getShort(2).toInt())
        assertTrue(packed.size < raw.size / 10)
    }

    @Test fun homeConnectionsStandard() {
        assertEquals("voicetyper-mobile", HomePc.APP)
        assertEquals("""{"models":["whisper"]}""", HomePc.WAKE_BODY)
        assertEquals("""{"active":true}""", HomePc.presenceBody(true))
        assertEquals("""{"active":false}""", HomePc.presenceBody(false))
        assertTrue(HomePc.PRESENCE_EVERY_MS < 30_000)    // shorter than the smallest sleep delay on the PC
        assertTrue(HomePc.describeStatus(401).contains("Home Connections"))
        assertTrue(HomePc.describeStatus(503).contains("relais"))
    }

    @Test fun connectionCode() {
        val key = "AbC-_" + "x".repeat(40)
        assertEquals("https://hugo.tail3001ff.ts.net" to key,
            HomePc.parseConnectionCode("  https://hugo.tail3001ff.ts.net/#key=$key\n"))
        assertEquals("" to key, HomePc.parseConnectionCode(key))
        assertEquals("https://hugo.tail3001ff.ts.net" to "", HomePc.parseConnectionCode("https://hugo.tail3001ff.ts.net/"))
        for (bad in listOf(null, "", "bonjour", "https://x.ts.net#key=court", "ftp://x#key=$key", "#key=$key")) {
            assertEquals(null, HomePc.parseConnectionCode(bad), bad)
        }
    }

    @Test fun optionsHeader() {
        assertEquals("""{"language":"fr","hotwords":"Gagnon, \"CSP\"","mode":"auto","sample_rate":16000}""",
            HomePc.options("fr", "Gagnon, \"CSP\""))
        assertEquals("""{"language":null,"hotwords":null,"mode":"auto","sample_rate":16000}""", HomePc.options("auto", " "))
    }
}
