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
class VoiceAccessibilityService : AccessibilityService(), OverlayController.Callbacks, SpeechEngine.Listener {

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
        inserter = TextInserter(this)
        running = true
        scope.launch {
            SettingsRepository.state.collect {
                if (::overlay.isInitialized) {
                    overlay.resizeBubble(it.bubbleSizeDp)
                    refreshBubble()
                }
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
        val show = speech.isActive || (!hiddenHere && (s.bubbleMode == BubbleMode.ALWAYS || typing))

        main.removeCallbacks(hideLater)
        if (show) {
            overlay.showBubble(s.bubbleSizeDp, s.bubbleX, s.bubbleY)
            if (!speech.isActive) overlay.bubble.label = languageBadge()
        } else {
            main.postDelayed(hideLater, 700) // the keyboard flickers between fields/screens: don't blink
        }
    }

    private val hideLater = Runnable { if (!speech.isActive) overlay.hideBubble() }

    // ---- bubble ----

    override fun onBubbleTap() {
        if (cancelPendingStart()) return
        if (speech.isActive) {
            overlay.bubble.state = BubbleView.State.WORKING
            speech.stop()
            return
        }
        startDictation(settings.presetFor(targetPkg))
    }

    override fun onBubbleLongPress() {
        if (cancelPendingStart()) return
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
        DiagnosticLog.add("Dictée dans ${targetPkg ?: "?"} (préréglage ${preset.id})")
        overlay.hideMenu()
        MicService.start(this)
        haptic()
        overlay.bubble.state = BubbleView.State.LISTENING
        val s = settings
        val lang = preset.language ?: s.language
        overlay.showCaption("${preset.name} · ${SpeechEngine.languageTag(lang)} — parlez…")
        // give the microphone foreground service time to be up before the recognizer opens the mic:
        // Android hands silence to a background app that opens it too early
        val start = Runnable { pendingStart = null; speech.start(lang, s.offlineOnly, s.tapToStop) }
        pendingStart = start
        main.postDelayed(start, 400)
    }

    override fun onListening() {}

    override fun onLevel(rmsDb: Float) = overlay.bubble.setLevel(rmsDb)

    override fun onPartial(text: String) {
        if (text.isNotBlank()) overlay.showCaption(text)
    }

    override fun onFinal(text: String) {
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
                if (speech.maxRms < 1f) "Le micro ne capte rien (voir Diagnostic dans Voice Typer)" else "Rien reconnu", 3000)
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
