package com.hugocodings.voicetyper

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import com.hugocodings.voicetyper.core.SilenceDetector
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * 16 kHz mono microphone capture for Whisper (the desktop recorder's job). Levels and the automatic stop
 * (silence after speech, when tap-to-stop is off) are reported on the main thread.
 */
class AudioCapture(private val listener: Listener) {

    interface Listener {
        /** Level in dB-ish units comparable to SpeechRecognizer's onRmsChanged (≈ -2…10). */
        fun onLevel(level: Float)
        fun onSilenceStop()
        fun onCaptureError(message: String)
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var thread: Thread? = null
    private var samples = FloatArray(0)
    private var count = 0
    var maxLevel = -100f
        private set

    @SuppressLint("MissingPermission") // checked by the caller
    fun start(stopOnSilence: Boolean) {
        stop()
        samples = FloatArray(RATE * 30)
        count = 0
        maxLevel = -100f
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE / 5 * 2) * 2)
        }.getOrNull()
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            rec?.release()
            listener.onCaptureError("Impossible d'ouvrir le micro (une autre app l'utilise ?)")
            return
        }
        running = true
        thread = Thread({ loop(rec, stopOnSilence) }, "voice-capture").apply { start() }
        DiagnosticLog.add("Capture micro démarrée (Whisper)")
    }

    private fun loop(rec: AudioRecord, stopOnSilence: Boolean) {
        val buf = ShortArray(RATE / 10) // 100 ms
        val silence = SilenceDetector(stepMs = 100)
        try {
            rec.startRecording()
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                var sum = 0.0
                synchronized(this) {
                    if (count + n > samples.size) {
                        if (samples.size >= MAX_SAMPLES) { running = false; main.post { listener.onSilenceStop() }; return@synchronized }
                        samples = samples.copyOf(minOf(samples.size * 2, MAX_SAMPLES))
                    }
                    for (i in 0 until n) {
                        val f = buf[i] / 32768f
                        if (count < samples.size) samples[count++] = f
                        sum += f * f
                    }
                }
                val rms = sqrt(sum / n)
                // map ~-60…-10 dBFS onto the recognizer's -2…10 scale used by the bubble
                val db = (20 * log10(rms.coerceAtLeast(1e-6))).toFloat()
                val level = ((db + 60f) / 50f * 12f - 2f).coerceIn(-2f, 10f)
                if (level > maxLevel) maxLevel = level
                main.post { listener.onLevel(level) }
                if (stopOnSilence && silence.feed(level)) {
                    running = false
                    DiagnosticLog.add("Capture : arrêt au silence")
                    main.post { listener.onSilenceStop() }
                }
            }
        } catch (e: Exception) {
            running = false
            main.post { listener.onCaptureError("Micro : ${e.javaClass.simpleName}") }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
    }

    /** Stops recording and returns the audio captured so far. */
    fun stop(): FloatArray {
        running = false
        thread?.join(500)
        thread = null
        return synchronized(this) { samples.copyOf(count) }
    }

    companion object {
        const val RATE = 16000
        private const val MAX_SAMPLES = RATE * 300 // 5 minutes
    }
}
