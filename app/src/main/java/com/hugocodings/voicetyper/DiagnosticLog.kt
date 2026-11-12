package com.hugocodings.voicetyper

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Last dictation events (in memory only, never the dictated text itself), shown on the app screen so a
 * failing phone can be diagnosed: which recognizer, did the mic level move, which error code.
 */
object DiagnosticLog {
    private const val MAX = 60
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> get() = _lines

    @Synchronized
    fun add(message: String) {
        _lines.value = (_lines.value + "${fmt.format(Date())}  $message").takeLast(MAX)
    }

    fun clear() { _lines.value = emptyList() }

    fun text(): String = _lines.value.joinToString("\n")
}
