package com.hugocodings.voicetyper.core

import kotlinx.serialization.Serializable

/** How a preset shapes the dictation. Rule-based and 100 % local (no LLM yet, see README roadmap). */
@Serializable
enum class Style(val label: String, val description: String) {
    RAW("Brut", "Le texte tel que dicté (commandes vocales appliquées)."),
    CLEAN("Correction", "Retire les hésitations, majuscules et point final."),
    MESSAGE("Message", "Ton texto : sans hésitations, pas de point final."),
    EMAIL("Courriel", "Salutation, petits paragraphes, formule de politesse et signature."),
    NOTES("Notes", "Une idée par ligne, en liste à puces."),
}

@Serializable
data class Preset(
    val id: String,
    val name: String,
    val style: Style,
    /** null = follow the global dictation language. */
    val language: Lang? = null,
    val voiceCommands: Boolean = true,
    val removeFillers: Boolean = true,
    /** Text added before / after every dictation with this preset (e.g. "— envoyé à la voix"). */
    val prefix: String = "",
    val suffix: String = "",
    /** Bubble colour (ARGB) so you can see which preset is active. */
    val color: Long = 0xFF22C55E,
)

/** Maps an Android app (package name) to a preset, like `app_presets` on desktop (Outlook → Courriel). */
@Serializable
data class AppRule(val packageName: String, val presetId: String, val label: String = "")

object Defaults {
    fun presets(): List<Preset> = listOf(
        Preset("normal", "Normal", Style.CLEAN, color = 0xFF22C55E),
        Preset("message", "Message rapide", Style.MESSAGE, color = 0xFF3B82F6),
        Preset("email", "Courriel", Style.EMAIL, color = 0xFFF59E0B),
        Preset("notes", "Notes en points", Style.NOTES, color = 0xFFA855F7),
        Preset("raw", "Brut", Style.RAW, removeFillers = false, color = 0xFF6B7280),
    )

    fun appRules(): List<AppRule> = listOf(
        AppRule("com.google.android.gm", "email", "Gmail"),
        AppRule("com.microsoft.office.outlook", "email", "Outlook"),
        AppRule("com.samsung.android.email.provider", "email", "Samsung Email"),
        AppRule("com.whatsapp", "message", "WhatsApp"),
        AppRule("com.google.android.apps.messaging", "message", "Messages"),
        AppRule("com.samsung.android.messaging", "message", "Samsung Messages"),
        AppRule("com.facebook.orca", "message", "Messenger"),
        AppRule("org.telegram.messenger", "message", "Telegram"),
        AppRule("org.thoughtcrime.securesms", "message", "Signal"),
        AppRule("com.microsoft.teams", "message", "Teams"),
        AppRule("com.Slack", "message", "Slack"),
        AppRule("com.google.android.keep", "notes", "Keep"),
        AppRule("com.samsung.android.app.notes", "notes", "Samsung Notes"),
        AppRule("com.microsoft.office.onenote", "notes", "OneNote"),
        AppRule("md.obsidian", "notes", "Obsidian"),
    )
}
