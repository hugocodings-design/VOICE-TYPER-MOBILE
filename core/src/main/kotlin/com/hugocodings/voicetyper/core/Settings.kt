package com.hugocodings.voicetyper.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class BubbleMode { WHEN_TYPING, ALWAYS }

@Serializable
data class Settings(
    val settingsVersion: Int = 1,
    /** null = auto (phone language). */
    val language: Lang? = null,
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

    fun withAppPreset(packageName: String, presetId: String?, label: String = ""): Settings {
        val others = appRules.filterNot { it.packageName == packageName }
        return copy(appRules = if (presetId == null) others else others + AppRule(packageName, presetId, label))
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

        fun decode(text: String?): Settings =
            if (text.isNullOrBlank()) Settings()
            else runCatching { json.decodeFromString<Settings>(text) }.getOrElse { Settings() }.sanitized()

        fun encode(settings: Settings): String = json.encodeToString(settings)
    }

    /** Never leave the app without presets or with a dangling default. */
    fun sanitized(): Settings {
        val ps = presets.ifEmpty { Defaults.presets() }.distinctBy { it.id }
        val def = if (ps.any { it.id == defaultPresetId }) defaultPresetId else ps.first().id
        return copy(presets = ps, defaultPresetId = def, appRules = appRules.filter { r -> ps.any { it.id == r.presetId } })
    }
}
