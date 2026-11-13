package com.hugocodings.voicetyper.core

import kotlinx.serialization.Serializable

/** Which speech-to-text is used: the phone's service, Whisper on the phone, or Whisper on the user's PC (its Whisper server, via Tailscale). */
@Serializable
enum class Engine { ANDROID, WHISPER, PC }

/** A Whisper model (whisper.cpp ggml format) the app can download, like the desktop model manager. */
data class WhisperModel(
    val id: String,
    val name: String,
    val file: String,
    val sizeMb: Int,
    /** 1 (slow) … 5 (fast) on a recent phone. */
    val speed: Int,
    /** 1 (rough) … 5 (best). */
    val accuracy: Int,
    val note: String,
) {
    val url: String get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$file"
}

object ModelCatalog {
    val MODELS: List<WhisperModel> = listOf(
        WhisperModel("tiny", "Tiny", "ggml-tiny-q5_1.bin", 31, 5, 1, "Instantané, beaucoup d'erreurs en français"),
        WhisperModel("base", "Base", "ggml-base-q5_1.bin", 57, 4, 2, "Rapide, correct pour des messages courts"),
        WhisperModel("small", "Small", "ggml-small-q5_1.bin", 181, 3, 4, "Recommandé : bon français, quelques secondes"),
        WhisperModel("turbo", "Large v3 Turbo", "ggml-large-v3-turbo-q5_0.bin", 547, 1, 5, "Le plus précis, mais très lent sur téléphone (≈ 1 min pour 5 s de parole)"),
    )
    const val RECOMMENDED = "small"

    fun byId(id: String?): WhisperModel? = MODELS.firstOrNull { it.id == id }
}
