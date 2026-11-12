package com.hugocodings.voicetyper.core

import kotlinx.serialization.Serializable

/** Dictation languages (like the desktop app: French and English only, or auto = phone language). */
@Serializable
enum class Lang(val tag: String) {
    FR("fr"), EN("en");

    companion object {
        fun fromTag(tag: String?): Lang? = entries.firstOrNull { tag != null && tag.lowercase().startsWith(it.tag) }
    }
}
