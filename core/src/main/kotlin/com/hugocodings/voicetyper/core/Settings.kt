package com.hugocodings.voicetyper.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class BubbleMode { WHEN_TYPING, ALWAYS }

@Serializable
data class Settings(
    val settingsVersion: Int = CURRENT_VERSION,
    /** null = auto (phone language). French by default, like the desktop app. */
    val language: Lang? = Lang.FR,
    val presets: List<Preset> = Defaults.presets(),
    val defaultPresetId: String = "normal",
    val appRules: List<AppRule> = Defaults.appRules(),
    val replacements: List<Replacement> = listOf(Replacement("chat GPT", "ChatGPT")),
    val appDictionaries: List<AppDictionary> = emptyList(),
    val signature: String = "",
    val bubbleMode: BubbleMode = BubbleMode.WHEN_TYPING,
    /** Apps where the bubble never shows (long press → « Masquer la bulle ici »). */
    val hiddenApps: List<String> = emptyList(),
    val bubbleX: Int = -1,
    val bubbleY: Int = -1,
    val bubbleSizeDp: Int = 56,
    val trailingSpace: Boolean = true,
    /** ANDROID = the phone's speech service; WHISPER = a downloaded Whisper model run by the app. */
    val engine: Engine = Engine.ANDROID,
    val whisperModel: String = ModelCatalog.RECOMMENDED,
    /** Only use the on-device recognizer (nothing leaves the phone). */
    val offlineOnly: Boolean = true,
    val haptics: Boolean = true,
    /** Tap = start, tap = stop (true) or stop automatically on silence (false). */
    val tapToStop: Boolean = true,
) {
    fun preset(id: String?): Preset? = presets.firstOrNull { it.id == id }

    /** The preset for [packageName]: its app rule, else the default preset. */
    fun presetFor(packageName: String?): Preset {
        val rule = appRules.firstOrNull { packageName != null && it.packageName == packageName }
        return preset(rule?.presetId) ?: preset(defaultPresetId) ?: presets.firstOrNull() ?: Defaults.presets().first()
    }

    /** Bubble menu switch: FR ↔ EN (auto → FR). */
    fun toggledLanguage(): Settings = copy(language = if (language == Lang.FR) Lang.EN else Lang.FR)

    fun withAppPreset(packageName: String, presetId: String?, label: String = ""): Settings {
        val others = appRules.filterNot { it.packageName == packageName }
        return copy(appRules = if (presetId == null) others else others + AppRule(packageName, presetId, label))
    }

    companion object {
        const val CURRENT_VERSION = 2
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

        fun decode(text: String?): Settings =
            if (text.isNullOrBlank()) Settings()
            else runCatching { json.decodeFromString<Settings>(text) }.getOrElse { Settings() }.migrated().sanitized()

        fun encode(settings: Settings): String = json.encodeToString(settings)
    }

    /**
     * v1 → v2: "auto" followed the phone language, so French speech failed on a phone set to English.
     * Version 0.1.x installs had auto by default: switch them to French once.
     */
    fun migrated(): Settings {
        var s = this
        if (s.settingsVersion < 2 && s.language == null) s = s.copy(language = Lang.FR)
        return s.copy(settingsVersion = CURRENT_VERSION)
    }

    /** Never leave the app without presets or with a dangling default. */
    fun sanitized(): Settings {
        val ps = presets.ifEmpty { Defaults.presets() }.distinctBy { it.id }
        val def = if (ps.any { it.id == defaultPresetId }) defaultPresetId else ps.first().id
        return copy(presets = ps, defaultPresetId = def, appRules = appRules.filter { r -> ps.any { it.id == r.presetId } })
    }
}
