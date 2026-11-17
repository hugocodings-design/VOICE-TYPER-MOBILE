package com.hugocodings.voicetyper.core

/**
 * Automatic stop when tap-to-stop is off. Fed one level every [stepMs] (bubble scale ≈ -2…10, 1 unit ≈ 4 dB).
 * The silence threshold follows the room: the noise floor drops at once to any quieter level and rises slowly,
 * so office / car noise above a fixed threshold no longer keeps the microphone open forever.
 * Speech = ≥ 3 units (~12 dB) above the floor. Stops after [quietAfterSpeechMs] of non-speech once speech was heard,
 * or after [noSpeechMs] if nothing was ever said.
 */
class SilenceDetector(
    private val stepMs: Int = 100,
    private val quietAfterSpeechMs: Int = 1600,
    private val noSpeechMs: Int = 10_000,
) {
    private var floor = Float.NaN
    private var spoke = false
    private var quietMs = 0
    private var elapsedMs = 0

    /** True when the recording should stop now. */
    fun feed(level: Float): Boolean {
        elapsedMs += stepMs
        floor = when {
            floor.isNaN() || level < floor -> level
            else -> floor + RISE_PER_STEP
        }
        val speech = level > maxOf(floor + SPEECH_ABOVE_FLOOR, MIN_SPEECH_LEVEL)
        if (speech) { spoke = true; quietMs = 0 } else quietMs += stepMs
        return if (spoke) quietMs >= quietAfterSpeechMs else elapsedMs >= noSpeechMs
    }

    companion object {
        private const val SPEECH_ABOVE_FLOOR = 3f
        private const val MIN_SPEECH_LEVEL = 1f
        private const val RISE_PER_STEP = 0.02f // 0.2 unit per second
    }
}
