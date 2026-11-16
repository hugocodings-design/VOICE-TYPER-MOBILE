package com.hugocodings.voicetyper

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.content.ContextCompat
import com.hugocodings.voicetyper.core.BubbleMode
import com.hugocodings.voicetyper.core.Dictionary
import com.hugocodings.voicetyper.core.Engine
import com.hugocodings.voicetyper.core.ModelCatalog
import com.hugocodings.voicetyper.core.Settings
import com.hugocodings.voicetyper.core.Text
import com.hugocodings.voicetyper.core.WhisperModel
import kotlinx.coroutines.Job
import com.hugocodings.voicetyper.core.Lang
import com.hugocodings.voicetyper.core.Pipeline
import com.hugocodings.voicetyper.core.Preset
import com.hugocodings.voicetyper.core.Style
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The heart of the app: watches which text field has focus (any app), shows the floating mic bubble,
 * runs the dictation and writes the result into that field with the app's preset.
 */
class VoiceAccessibilityService : AccessibilityService(), OverlayController.Callbacks, SpeechEngine.Listener,
    AudioCapture.Listener {

    private val scope = MainScope()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var overlay: OverlayController
    private lateinit var speech: SpeechEngine
    private lateinit var inserter: TextInserter

    private var target: AccessibilityNodeInfo? = null
    private var targetPkg: String? = null
    private var dictationPreset: Preset? = null
    private var dictationPkg: String? = null
    /** The field the dictation started in: the result goes there, never into a field focused later. */
    private var dictationTarget: AccessibilityNodeInfo? = null
    private var lastDictation: String = ""
    /** Dictation about to start (waiting for the mic service): a tap or long press cancels it. */
    private var pendingStart: Runnable? = null
    /** Whisper path: recording with [capture], then [transcribing] in the background. */
    private lateinit var capture: AudioCapture
    private var capturing = false
    private var transcribing: Job? = null
    private var dictationLang: Lang? = null
    /** Whisper model of the current dictation (chosen one, or the fallback when the offline language is missing). */
    private var dictationModel: WhisperModel? = null
    /** This dictation is transcribed by the Whisper server on the user's PC (via Tailscale). */
    private var dictationPc = false
    /** Updates the "Transcription… N s" caption while Whisper works. */
    private var transcribeTicker: Job? = null
    /** Loudest level of the last dictation (either engine): tells "mic hears nothing" from "nothing recognized". */
    private var lastMaxLevel = -100f

    /** True during any dictation step (listening, recording, transcribing). */
    private val busy: Boolean get() = speech.isActive || capturing || transcribing?.isActive == true

    /** Listening, about to listen or transcribing (the in-app button shows « Arrêter »). */
    val isDictating: Boolean get() = pendingStart != null || (::speech.isInitialized && busy)

    private fun cancelPendingStart(): Boolean {
        val p = pendingStart ?: return false
        main.removeCallbacks(p)
        pendingStart = null
        MicService.stop(this)
        overlay.bubble.state = BubbleView.State.IDLE
        overlay.showCaption("Dictée annulée", 1200)
        return true
    }

    private val settings get() = SettingsRepository.current

    override fun onServiceConnected() {
        super.onServiceConnected()
        SettingsRepository.init(this)
        overlay = OverlayController(this, this)
        speech = SpeechEngine(this, this)
        capture = AudioCapture(this)
        inserter = TextInserter(this)
        running = true
        instance = this
        // pick the fastest Whisper build for this processor now (cheap), so the first dictation does not wait
        scope.launch(kotlinx.coroutines.Dispatchers.Default) { WhisperNative.ensureBackends(this@VoiceAccessibilityService) }
        scope.launch {
            SettingsRepository.state.collect {
                if (::overlay.isInitialized) {
                    overlay.resizeBubble(it.bubbleSizeDp)
                    refreshBubble()
                }
                preloadWhisper(it)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                val src = event.source
                if (src != null && src.isEditable) setTarget(src)
                refreshBubble()
            }
            else -> refreshBubble()
        }
    }

    private fun setTarget(node: AccessibilityNodeInfo) {
        target = node
        targetPkg = node.packageName?.toString()
    }

    /** Show / hide the bubble for the current screen; never hides it in the middle of a dictation. */
    private fun refreshBubble() {
        val focused = runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
        if (focused != null && focused.isEditable) setTarget(focused)
        val imeVisible = runCatching { windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } }.getOrDefault(false)
        val typing = (focused?.isEditable == true) || (imeVisible && target != null)
        val activePkg = focused?.packageName?.toString() ?: rootInActiveWindow?.packageName?.toString()
        val s = settings
        val hiddenHere = activePkg != null && activePkg in s.hiddenApps
        val show = busy || (!hiddenHere && (s.bubbleMode == BubbleMode.ALWAYS || typing))

        main.removeCallbacks(hideLater)
        if (show) {
            overlay.showBubble(s.bubbleSizeDp, s.bubbleX, s.bubbleY)
            if (!busy) overlay.bubble.label = languageBadge()
        } else {
            main.postDelayed(hideLater, 700) // the keyboard flickers between fields/screens: don't blink
        }
    }

    private val hideLater = Runnable { if (!busy) overlay.hideBubble() }

    // ---- bubble ----

    override fun onBubbleTap() {
        if (cancelPendingStart()) return
        if (capturing) { finishWhisperRecording(); return }
        if (transcribing?.isActive == true) {
            if (sameFieldAsDictation()) {
                overlay.showCaption("Transcription en cours… (appui long = annuler)", 2500)
                return
            }
            // the user moved on to another field: drop the old dictation instead of blocking the bubble
            cancelTranscription()
            DiagnosticLog.add("Transcription précédente annulée (nouveau champ)")
            overlay.showCaption("Dictée précédente annulée", 1200)
        }
        if (speech.isActive) {
            overlay.bubble.state = BubbleView.State.WORKING
            speech.stop()
            return
        }
        startDictation(settings.presetFor(targetPkg))
    }

    override fun onBubbleLongPress() {
        if (cancelPendingStart()) return
        if (capturing || transcribing?.isActive == true) {
            capturing = false
            capture.stop()
            cancelTranscription()
            MicService.stop(this)
            overlay.bubble.state = BubbleView.State.IDLE
            overlay.showCaption("Dictée annulée", 1500)
            return
        }
        if (speech.isActive) { // long press while listening = cancel (like Esc on desktop)
            speech.cancel()
            MicService.stop(this)
            overlay.bubble.state = BubbleView.State.IDLE
            overlay.showCaption("Dictée annulée", 1500)
            return
        }
        val pkg = targetPkg
        val menuPkg = pkg ?: rootInActiveWindow?.packageName?.toString()
        overlay.showMenu(settings.presets, settings.presetFor(menuPkg).id, appLabel(menuPkg), lastDictation.isNotEmpty(), languageName())
    }

    override fun onBubbleMoved(x: Int, y: Int) = SettingsRepository.update { it.copy(bubbleX = x, bubbleY = y) }

    override fun onMenuPreset(preset: Preset, remember: Boolean) {
        val pkg = targetPkg ?: rootInActiveWindow?.packageName?.toString()
        if (remember && pkg != null && settings.presetFor(pkg).id != preset.id) {
            SettingsRepository.update { it.withAppPreset(pkg, preset.id, appLabel(pkg)) }
            DiagnosticLog.add("Style « ${preset.name} » mémorisé pour ${appLabel(pkg)}")
            startDictation(preset, note = "mémorisé pour ${appLabel(pkg)} · ")
            return
        }
        startDictation(preset)
    }

    override fun onMenuRepaste() {
        if (lastDictation.isNotEmpty()) write(lastDictation, keepCase = true, node = currentTarget())
    }

    override fun onMenuToggleLanguage() {
        SettingsRepository.update { it.toggledLanguage() }
        overlay.bubble.label = languageBadge()
        overlay.showCaption("Langue de dictée : ${languageName()}", 1500)
    }

    private fun languageBadge(): String = when (settings.language) {
        Lang.FR -> "FR"; Lang.EN -> "EN"; null -> "AUTO"
    }

    private fun languageName(): String = when (settings.language) {
        Lang.FR -> "Français"; Lang.EN -> "English"; null -> "Auto (${SpeechEngine.languageTag(null)})"
    }

    override fun onMenuHideHere() {
        val pkg = rootInActiveWindow?.packageName?.toString() ?: targetPkg ?: return
        SettingsRepository.update { it.copy(hiddenApps = (it.hiddenApps + pkg).distinct()) }
        overlay.hideBubble()
        overlay.showCaption("Bulle masquée dans ${appLabel(pkg)} (réactivable dans Voice Typer)", 2500)
    }

    // ---- dictation ----

    private fun startDictation(preset: Preset, note: String = "") {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            overlay.showCaption("Autorisez le micro dans Voice Typer", 3000)
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        if (target == null && settings.bubbleMode == BubbleMode.ALWAYS) {
            overlay.showCaption("Touchez d'abord un champ de texte", 2000)
            return
        }
        dictationPreset = preset
        dictationPkg = targetPkg
        dictationTarget = target
        lastMaxLevel = -100f
        DiagnosticLog.add("Dictée dans ${targetPkg ?: "?"} (préréglage ${preset.id})")
        overlay.hideMenu()
        MicService.start(this)
        haptic()
        overlay.bubble.state = BubbleView.State.LISTENING
        val s = settings
        val lang = preset.language ?: s.language
        dictationLang = lang
        val usePc = s.engine == Engine.PC && s.pcAddress.isNotBlank()
        dictationPc = usePc
        // PC chosen but not set up yet: say so, and use Whisper on the phone meanwhile (Android recognition otherwise)
        val pcMissing = s.engine == Engine.PC && !usePc
        val model = if (usePc) null else whisperModelIfReady(s) ?: if (pcMissing) downloadedWhisperFallback() else null
        dictationModel = model
        if (s.engine == Engine.WHISPER && model == null) {
            DiagnosticLog.add("Whisper choisi mais modèle ${s.whisperModel} absent : reconnaissance Android")
        }
        if (pcMissing) DiagnosticLog.add("PC choisi mais nom du PC manquant : " +
            (model?.let { "Whisper ${it.id} sur le téléphone" } ?: "reconnaissance Android"))
        val engineName = when {
            usePc && !PcEngine.tailscaleUp(this) -> "Tailscale désactivé : téléphone"
            usePc -> "PC (boosté)"
            model != null -> "Whisper ${model.name}"
            else -> SpeechEngine.languageTag(lang)
        }
        overlay.showCaption((if (pcMissing) "PC non configuré (nom du PC dans Voice Typer) · " else "") +
            "${preset.name} · $note$engineName · ${lang?.tag ?: "auto"} — parlez…")
        // give the microphone foreground service time to be up before the mic is opened:
        // Android hands silence to a background app that opens it too early
        val start = Runnable {
            pendingStart = null
            if (usePc || model != null) { capturing = true; capture.start(stopOnSilence = !s.tapToStop) }
            else speech.start(lang, s.offlineOnly, s.tapToStop)
        }
        pendingStart = start
        main.postDelayed(start, 400)
    }

    override fun onListening() {}

    /**
     * Android recognition failed because the language pack is not installed offline: if a Whisper model is
     * downloaded, record with it instead of failing (the user keeps talking, nothing to set up).
     */
    override fun onLanguageMissing(langTag: String): Boolean {
        val model = downloadedWhisperFallback() ?: return false
        dictationModel = model
        capturing = true
        capture.start(stopOnSilence = !settings.tapToStop)
        overlay.bubble.state = BubbleView.State.LISTENING
        overlay.showCaption("« $langTag » non installé hors ligne : Whisper ${model.name} à la place — parlez…")
        return true
    }

    /** The chosen Whisper model if downloaded, else the most useful downloaded one (fast before slow). */
    private fun downloadedWhisperFallback(): WhisperModel? {
        if (!WhisperNative.available) return null
        val downloaded = ModelCatalog.MODELS.filter { ModelManager.isDownloaded(this, it) }
        downloaded.firstOrNull { it.id == settings.whisperModel }?.let { return it }
        return listOf("small", "base", "tiny", "turbo").firstNotNullOfOrNull { id -> downloaded.firstOrNull { it.id == id } }
    }

    // ---- Whisper path ----

    private fun whisperModelIfReady(s: Settings): WhisperModel? {
        if (s.engine != Engine.WHISPER || !WhisperNative.available) return null
        return ModelCatalog.byId(s.whisperModel)?.takeIf { ModelManager.isDownloaded(this, it) }
    }

    private var preloaded: String? = null

    /** Load the model as soon as Whisper is chosen, so the first dictation does not wait for it. */
    private fun preloadWhisper(s: Settings) {
        val model = whisperModelIfReady(s) ?: return
        if (preloaded == model.id) return
        preloaded = model.id
        scope.launch { WhisperEngine.ensureLoaded(this@VoiceAccessibilityService, model)?.let { DiagnosticLog.add(it) } }
    }

    override fun onSilenceStop() { if (capturing) finishWhisperRecording() }

    override fun onCaptureError(message: String) {
        capturing = false
        onError(message)
    }

    private fun finishWhisperRecording() {
        capturing = false
        val pcm = capture.stop()
        lastMaxLevel = capture.maxLevel
        MicService.stop(this)
        val pc = dictationPc
        val phoneModel = dictationModel ?: ModelCatalog.byId(settings.whisperModel)
        if (!pc && phoneModel == null) return onError("Modèle inconnu")
        if (lastMaxLevel < 1f) { // pure silence: Whisper would only invent captions
            DiagnosticLog.add("Capture : silence (niveau max ${"%.1f".format(lastMaxLevel)})")
            return deliverFinal("")
        }
        overlay.bubble.state = BubbleView.State.WORKING
        val audioSecs = pcm.size / AudioCapture.RATE.toFloat()
        val lang = dictationLang?.tag ?: "auto"
        val prompt = Dictionary.whisperPrompt(settings.replacements, settings.vocabularyFor(dictationPreset))
        val s = settings
        transcribing = scope.launch {
            val t0 = System.currentTimeMillis()
            var usedModel: WhisperModel? = null
            val text = try {
                if (pc) {
                    try {
                        if (!PcEngine.tailscaleUp(this@VoiceAccessibilityService)) {
                            throw java.io.IOException("Tailscale désactivé sur le téléphone")
                        }
                        timed("PC") { PcEngine.transcribe(s.pcAddress, pcm, lang) }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // PC off / other network: Whisper on the phone takes over, if a model is downloaded
                        val fb = downloadedWhisperFallback()
                            ?: throw IllegalStateException("PC : ${e.message}")
                        DiagnosticLog.add("PC indisponible (${e.message}) : Whisper ${fb.id} sur le téléphone")
                        usedModel = fb
                        timed("PC indisponible · Whisper ${fb.name}") {
                            WhisperEngine.transcribe(this@VoiceAccessibilityService, fb, pcm, lang, prompt)
                        }
                    }
                } else {
                    val m = phoneModel!!
                    usedModel = m
                    timed("Whisper ${m.name}") { WhisperEngine.transcribe(this@VoiceAccessibilityService, m, pcm, lang, prompt) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                transcribing = null
                onError(e.message ?: "La transcription a échoué")
                return@launch
            }
            transcribing = null
            deliverFinal(Text.stripWhisperHallucinations(text))
            val model = usedModel ?: return@launch
            val tookSecs = (System.currentTimeMillis() - t0) / 1000f
            if (text.isNotBlank() && tookSecs > 10f && tookSecs > audioSecs * 3 && model.id != "tiny") {
                overlay.showCaption("Whisper ${model.name} est lent sur ce téléphone " +
                    "(${tookSecs.toInt()} s pour ${audioSecs.toInt()} s) : essayez un modèle plus léger dans Voice Typer", 5000)
            }
        }
    }

    /** Runs [block] while the caption shows "Transcription (label)… N s": a slow model must not look stuck. */
    private suspend fun timed(label: String, block: suspend () -> String): String {
        val t0 = System.currentTimeMillis()
        transcribeTicker?.cancel()
        transcribeTicker = scope.launch {
            while (true) {
                val secs = (System.currentTimeMillis() - t0) / 1000
                overlay.showCaption("Transcription ($label)…" + if (secs >= 2) " $secs s" else "")
                kotlinx.coroutines.delay(1000)
            }
        }
        try {
            return block()
        } finally {
            transcribeTicker?.cancel()
        }
    }

    override fun onLevel(rmsDb: Float) = overlay.bubble.setLevel(rmsDb)

    override fun onPartial(text: String) {
        if (text.isNotBlank()) overlay.showCaption(text)
    }

    override fun onFinal(text: String) {
        if (!capturing && transcribing == null) lastMaxLevel = maxOf(lastMaxLevel, speech.maxRms)
        deliverFinal(text)
    }

    private fun deliverFinal(text: String) {
        MicService.stop(this)
        overlay.bubble.state = BubbleView.State.IDLE
        val preset = dictationPreset ?: settings.presetFor(targetPkg)
        val result = Pipeline.process(text, preset, settings, dictationPkg)
        DiagnosticLog.add("Résultat : ${result.text.length} caractères" + if (result.undo) " (efface ça)" else "")
        when {
            result.undo -> {
                val node = dictationNode()
                val ok = node != null && inserter.undo(node)
                overlay.showCaption(if (ok) "Dernière dictée effacée" else "Rien à effacer ici", 1500)
            }
            // -100 = no level reported at all (Android's on-device recognizer often sends none): not proof of silence
            result.text.isBlank() -> overlay.showCaption(
                if (lastMaxLevel > -100f && lastMaxLevel < 1f) "Le micro ne capte rien (voir Diagnostic dans Voice Typer)"
                else "Rien reconnu (langue de dictée : ${languageName()})", 3500)
            else -> {
                lastDictation = result.text
                overlay.hideCaption()
                val keepCase = preset.style in setOf(Style.EMAIL, Style.NOTES, Style.RAW)
                if (result.subject != null) writeEmail(result, keepCase) else write(result.text, keepCase)
                haptic()
            }
        }
        refreshBubble()
    }

    override fun onError(message: String) {
        MicService.stop(this)
        overlay.bubble.state = BubbleView.State.ERROR
        overlay.showCaption(message, 4000)
        main.postDelayed({ overlay.bubble.state = BubbleView.State.IDLE; refreshBubble() }, 1500)
    }

    private fun currentTarget(): AccessibilityNodeInfo? {
        val t = target
        if (t != null && t.refresh() && t.isEditable) return t
        return runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()?.takeIf { it.isEditable }
    }

    /**
     * The field the dictation was made for: the same node if it is still there, else the focused field of the
     * same app (the app may have rebuilt it). Never a field of another app the user switched to meanwhile.
     */
    private fun dictationNode(): AccessibilityNodeInfo? {
        val t = dictationTarget
        if (t != null && t.refresh() && t.isEditable) return t
        val focused = runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()?.takeIf { it.isEditable }
        return focused?.takeIf { dictationPkg == null || it.packageName?.toString() == dictationPkg }
    }

    private fun sameFieldAsDictation(): Boolean {
        val t = dictationTarget ?: return true
        val now = target ?: return true
        return now == t || (now.packageName == t.packageName && now.viewIdResourceName == t.viewIdResourceName)
    }

    private fun cancelTranscription() {
        transcribing?.cancel()
        transcribing = null
        WhisperEngine.abort()
        transcribeTicker?.cancel()
    }

    private fun write(text: String, keepCase: Boolean, node: AccessibilityNodeInfo? = dictationNode()) {
        if (node != null && inserter.insert(node, text, keepCase)) { DiagnosticLog.add("Texte écrit dans le champ"); return }
        DiagnosticLog.add("Champ introuvable ou refusé : texte copié")
        // field gone (user left the app) or not writable (some games / secure fields): leave it on the clipboard
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Voice Typer", text))
        val where = if (dictationPkg != null && node == null) " de ${appLabel(dictationPkg)}" else ""
        overlay.showCaption("Champ$where plus accessible : le texte est copié, collez-le", 3500)
    }

    /**
     * Courriel with a generated subject: dictating in the subject field writes only the subject; otherwise the
     * app's empty subject field (Gmail, Outlook…) is filled, else « Objet : … » goes first in an empty body.
     */
    private fun writeEmail(result: Pipeline.Result, keepCase: Boolean) {
        val subject = result.subject ?: return write(result.text, keepCase)
        if (dictationPkg == packageName) return write(result.text, keepCase) // Voice Typer's reply card shows its own subject
        val node = dictationNode()
        if (node != null && isSubjectField(node)) { write(subject, keepCase = true, node = node); return }
        var text = result.text
        when (fillSubjectField(node, subject)) {
            SubjectFill.FILLED -> overlay.showCaption("Objet : $subject", 2000)
            SubjectFill.ALREADY_SET -> Unit
            SubjectFill.NO_FIELD -> {
                val empty = node == null || node.isShowingHintText || node.text.isNullOrEmpty()
                if (empty && result.subjectLine != null) text = result.subjectLine + "\n\n" + text
            }
        }
        DiagnosticLog.add("Objet généré (${subject.length} caractères)")
        write(text, keepCase, node)
    }

    private enum class SubjectFill { FILLED, ALREADY_SET, NO_FIELD }

    private fun fillSubjectField(body: AccessibilityNodeInfo?, subject: String): SubjectFill {
        val root = body?.window?.root ?: rootInActiveWindow ?: return SubjectFill.NO_FIELD
        val field = findNode(root) { it.isEditable && it != body && isSubjectField(it) } ?: return SubjectFill.NO_FIELD
        if (!field.isShowingHintText && !field.text.isNullOrBlank()) return SubjectFill.ALREADY_SET
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, subject) }
        return if (field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) SubjectFill.FILLED else SubjectFill.NO_FIELD
    }

    private fun isSubjectField(n: AccessibilityNodeInfo): Boolean {
        val id = n.viewIdResourceName?.substringAfter(":id/")?.lowercase().orEmpty()
        if ("subject" in id || "objet" in id) return true
        val hint = (n.hintText ?: if (n.isShowingHintText) n.text else null)?.toString()?.trim()?.lowercase().orEmpty()
        val desc = n.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
        return listOf(hint, desc).any { it == "objet" || it == "subject" || it == "add a subject" || it == "ajouter un objet" }
    }

    private fun findNode(root: AccessibilityNodeInfo, depth: Int = 0, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (match(root)) return root
        if (depth > 40) return null
        for (i in 0 until root.childCount) {
            val c = root.getChild(i) ?: continue
            findNode(c, depth + 1, match)?.let { return it }
        }
        return null
    }

    private fun haptic() {
        if (!settings.haptics) return
        overlay.bubble.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY,
        )
    }

    private fun appLabel(pkg: String?): String {
        if (pkg == null) return "cette application"
        settings.appRules.firstOrNull { it.packageName == pkg && it.label.isNotEmpty() }?.let { return it.label }
        return runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }

    override fun onInterrupt() {
        if (::speech.isInitialized) speech.cancel()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        running = false
        if (instance === this) instance = null
        if (::speech.isInitialized) speech.cancel()
        if (::capture.isInitialized) { capturing = false; capture.stop() }
        transcribing?.cancel()
        transcribeTicker?.cancel()
        if (::overlay.isInitialized) overlay.hideBubble()
        MicService.stop(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        running = false
        if (instance === this) instance = null
        scope.cancel()
        super.onDestroy()
    }

    /**
     * « Dicter la réponse » button of Voice Typer's client-reply card: same as a bubble tap on the focused reply
     * field, with the first Courriel preset. A second press stops (like the bubble). False if no field is focused.
     */
    fun dictateFromApp(): Boolean {
        if (isDictating) { onBubbleTap(); return true }
        refreshBubble()
        if (currentTarget() == null) return false
        val preset = settings.presets.firstOrNull { it.style == Style.EMAIL } ?: settings.presetFor(targetPkg)
        startDictation(preset)
        return true
    }

    companion object {
        /** True while the service is connected (shown on the app's status card). */
        @Volatile var running: Boolean = false
            private set

        /** The connected service, for the in-app dictation button (same process). */
        @Volatile var instance: VoiceAccessibilityService? = null
            private set
    }
}
