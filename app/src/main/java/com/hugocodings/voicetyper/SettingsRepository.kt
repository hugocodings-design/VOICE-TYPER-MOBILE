package com.hugocodings.voicetyper

import android.content.Context
import com.hugocodings.voicetyper.core.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Settings shared by the app screen and the accessibility service (same process), saved as JSON. */
object SettingsRepository {
    private const val PREFS = "voice_typer"
    private const val KEY = "settings"

    private val _state = MutableStateFlow(Settings())
    val state: StateFlow<Settings> get() = _state
    private var loaded = false
    private lateinit var appContext: Context

    fun init(context: Context) {
        if (loaded) return
        appContext = context.applicationContext
        _state.value = Settings.decode(prefs().getString(KEY, null))
        loaded = true
    }

    val current: Settings get() = _state.value

    fun update(transform: (Settings) -> Settings) {
        val next = transform(_state.value).sanitized()
        _state.value = next
        prefs().edit().putString(KEY, Settings.encode(next)).apply()
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
