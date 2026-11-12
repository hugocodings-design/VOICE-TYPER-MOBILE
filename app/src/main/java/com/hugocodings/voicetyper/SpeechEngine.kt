package com.hugocodings.voicetyper

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.hugocodings.voicetyper.core.Lang
import java.util.Locale

/**
 * Wraps Android's SpeechRecognizer, on-device when available (Android 12+), so audio never leaves the phone.
 *
 * Tap-to-stop mode: the recognizer stops by itself after a pause, so each finished segment is kept and
 * listening restarts until the user taps the bubble again; then all segments are delivered together.
 * Must be used from the main thread.
 */
class SpeechEngine(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onListening()
        fun onLevel(rmsDb: Float)
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(message: String)
    }

    private var recognizer: SpeechRecognizer? = null
    private val segments = mutableListOf<String>()
    private var lastPartial = ""
    private var continuous = false
    private var stopRequested = false
    private var active = false
    private var onDevice = false
    private var langTag = ""
    private var offlineOnly = true

    val isActive: Boolean get() = active

    fun start(lang: Lang?, offlineOnly: Boolean, continuous: Boolean) {
        cancel()
        this.continuous = continuous
        this.offlineOnly = offlineOnly
        stopRequested = false
        segments.clear()
        lastPartial = ""
        langTag = languageTag(lang)
        active = true
        if (!SpeechRecognizer.isRecognitionAvailable(context) && !onDeviceAvailable()) {
            fail("Aucun service de reconnaissance vocale sur ce téléphone (installez « Google » ou « Speech Services by Google »).")
            return
        }
        listenOnce()
    }

    /** Tap again: stop listening and deliver what was said. */
    fun stop() {
        if (!active) return
        stopRequested = true
        recognizer?.stopListening() ?: deliver()
    }

    fun cancel() {
        active = false
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
    }

    private fun onDeviceAvailable(): Boolean =
        Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    private fun listenOnce() {
        recognizer?.destroy()
        onDevice = offlineOnly && onDeviceAvailable()
        val r = if (onDevice && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(callbacks)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, langTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, offlineOnly)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            if (continuous) {
                // honoured by some recognizers only; the restart loop covers the others
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
            }
            if (Build.VERSION.SDK_INT >= 33) putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
        }
        r.startListening(intent)
    }

    private fun currentText(): String = (segments + lastPartial).filter { it.isNotBlank() }.joinToString(" ")

    private fun deliver() {
        val text = segments.filter { it.isNotBlank() }.joinToString(" ")
        cancel()
        listener.onFinal(text)
    }

    private fun fail(message: String) {
        cancel()
        listener.onError(message)
    }

    private val callbacks = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = listener.onListening()
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) = listener.onLevel(rmsdB)
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            lastPartial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            listener.onPartial(currentText())
        }

        override fun onResults(results: Bundle?) {
            if (!active) return
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            segments += text.ifBlank { lastPartial }
            lastPartial = ""
            if (continuous && !stopRequested) listenOnce() else deliver()
        }

        override fun onError(error: Int) {
            if (!active) return
            val silence = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            if (silence) {
                if (lastPartial.isNotBlank()) segments += lastPartial
                lastPartial = ""
                if (continuous && !stopRequested) listenOnce() else deliver()
                return
            }
            if (stopRequested && segments.isNotEmpty()) { deliver(); return }
            fail(describe(error))
        }
    }

    private fun describe(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Autorisez le micro dans l'application Voice Typer."
        SpeechRecognizer.ERROR_AUDIO -> "Le micro est indisponible (une autre application l'utilise ?)."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "La reconnaissance vocale est occupée, réessayez."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
            "Langue hors ligne non installée : téléchargez-la (Voice Typer → Reconnaissance hors ligne)."
        else -> if (Build.VERSION.SDK_INT >= 31 && (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)) {
            "Langue « $langTag » non disponible hors ligne : téléchargez-la (Voice Typer → Reconnaissance hors ligne)."
        } else "Erreur de reconnaissance vocale ($error)."
    }

    companion object {
        /** fr-CA / en-CA on a Canadian phone, fr-FR / en-US otherwise; auto = the phone language. */
        fun languageTag(lang: Lang?): String {
            val sys = Locale.getDefault()
            return when (lang) {
                null -> sys.toLanguageTag()
                Lang.FR -> if (sys.country in setOf("CA", "BE", "CH")) "fr-${sys.country}" else "fr-FR"
                Lang.EN -> when (sys.country) { "CA", "GB", "AU" -> "en-${sys.country}"; else -> "en-US" }
            }
        }
    }
}
