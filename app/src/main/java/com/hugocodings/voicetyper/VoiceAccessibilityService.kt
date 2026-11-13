package com.hugocodings.voicetyper

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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
    /** Updates the "Transcription… N s" caption while Whisper works. */
    private var transcribeTicker: Job? = null
    /** Loudest level of the last dictation (either engine): tells "mic hears nothing" from "nothing recognized". */
    private var lastMaxLevel = -100f

    /** True during any dictation step (listening, recording, transcribing). */
    private val busy: Boolean get() = speech.isActive || capturing || transcribing?.isActive == true

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
        if (transcribing?.isActive == true) { overlay.showCaption("Transcription en cours…"); return }
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
            transcribing?.cancel(); WhisperEngine.abort()
            transcribeTicker?.cancel()
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
        overlay.showMenu(settings.presets, settings.presetFor(pkg).id, appLabel(pkg), lastDictation.isNotEmpty(), languageName())
    }

    override fun onBubbleMoved(x: Int, y: Int) = SettingsRepository.update { it.copy(bubbleX = x, bubbleY = y) }

    override fun onMenuPreset(preset: Preset, remember: Boolean) {
        val pkg = targetPkg
        if (remember && pkg != null) SettingsRepository.update { it.withAppPreset(pkg, preset.id, appLabel(pkg)) }
        startDictation(preset)
    }

    override fun onMenuRepaste() {
        if (lastDictation.isNotEmpty()) write(lastDictation, keepCase = true)
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

    private fun startDictation(preset: Preset) {
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
        lastMaxLevel = -100f
        DiagnosticLog.add("Dictée dans ${targetPkg ?: "?"} (préréglage ${preset.id})")
        overlay.hideMenu()
        MicService.start(this)
        haptic()
        overlay.bubble.state = BubbleView.State.LISTENING
        val s = settings
        val lang = preset.language ?: s.language
        dictationLang = lang
        val model = whisperModelIfReady(s)
        dictationModel = model
        if (s.engine == Engine.WHISPER && model == null) {
            DiagnosticLog.add("Whisper choisi mais modèle ${s.whisperModel} absent : reconnaissance Android")
        }
        val engineName = if (model != null) "Whisper ${model.name}" else SpeechEngine.languageTag(lang)
        overlay.showCaption("${preset.name} · $engineName · ${lang?.tag ?: "auto"} — parlez…")
        // give the microphone foreground service time to be up before the mic is opened:
        // Android hands silence to a background app that opens it too early
        val start = Runnable {
            pendingStart = null
            if (model != null) { capturing = true; capture.start(stopOnSilence = !s.tapToStop) }
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
        val model = dictationModel ?: ModelCatalog.byId(settings.whisperModel) ?: return onError("Modèle inconnu")
        if (lastMaxLevel < 1f) { // pure silence: Whisper would only invent captions
            DiagnosticLog.add("Capture : silence (niveau max ${"%.1f".format(lastMaxLevel)})")
            return deliverFinal("")
        }
        overlay.bubble.state = BubbleView.State.WORKING
        val audioSecs = pcm.size / AudioCapture.RATE.toFloat()
        val t0 = System.currentTimeMillis()
        // a big model can take a minute on a phone: show that it is still working, not stuck
        transcribeTicker?.cancel()
        transcribeTicker = scope.launch {
            while (true) {
                val secs = (System.currentTimeMillis() - t0) / 1000
                overlay.showCaption("Transcription (Whisper ${model.name})…" + if (secs >= 2) " $secs s" else "")
                kotlinx.coroutines.delay(1000)
            }
        }
        val lang = dictationLang?.tag ?: "auto"
        val prompt = Dictionary.whisperPrompt(settings.replacements)
        transcribing = scope.launch {
            val text = try {
                WhisperEngine.transcribe(this@VoiceAccessibilityService, model, pcm, lang, prompt)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                transcribing = null
                transcribeTicker?.cancel()
                onError(e.message ?: "La transcription a échoué")
                return@launch
            }
            transcribing = null
            transcribeTicker?.cancel()
            deliverFinal(Text.stripWhisperHallucinations(text))
            val tookSecs = (System.currentTimeMillis() - t0) / 1000f
            if (text.isNotBlank() && tookSecs > 10f && tookSecs > audioSecs * 3 && model.id != "tiny") {
                overlay.showCaption("Whisper ${model.name} est lent sur ce téléphone " +
                    "(${tookSecs.toInt()} s pour ${audioSecs.toInt()} s) : essayez un modèle plus léger dans Voice Typer", 5000)
            }
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
        when {
            result.undo -> {
                val node = currentTarget()
                val ok = node != null && inserter.undo(node)
                overlay.showCaption(if (ok) "Dernière dictée effacée" else "Rien à effacer ici", 1500)
            }
            result.text.isBlank() -> overlay.showCaption(
                if (lastMaxLevel < 1f) "Le micro ne capte rien (voir Diagnostic dans Voice Typer)" else "Rien reconnu", 3000)
            else -> {
                lastDictation = result.text
                overlay.hideCaption()
                write(result.text, keepCase = preset.style in setOf(Style.EMAIL, Style.NOTES, Style.RAW))
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

    private fun write(text: String, keepCase: Boolean) {
        val node = currentTarget()
        if (node != null && inserter.insert(node, text, keepCase)) { DiagnosticLog.add("Texte écrit dans le champ"); return }
        DiagnosticLog.add("Champ introuvable ou refusé : texte copié")
        // field not reachable (some games / secure fields): leave it on the clipboard
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Voice Typer", text))
        overlay.showCaption("Champ inaccessible : le texte est copié, collez-le", 3000)
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
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** True while the service is connected (shown on the app's status card). */
        @Volatile var running: Boolean = false
            private set
    }
}
