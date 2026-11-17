package com.hugocodings.voicetyper.core

/**
 * Automatic stop when tap-to-stop is off. Fed one level every [stepMs] (bubble scale ≈ -2…10, 1 unit ≈ 4 dB).
 * The threshold follows the room: the noise floor drops at once to any quieter level and rises slowly, but only
 * on quiet frames (never while you talk), so office / car noise does not keep the mic open and a soft voice does
 * not close it. Speech starts ≥ 2 units (~8 dB) above the floor and, once talking, continues down to 1 unit (~4 dB)
 * above it, so soft syllables and word endings count as speech. Stops after [pauseMs] without speech once speech
 * was heard, or after [noSpeechMs] if nothing was ever said.
 */
class SilenceDetector(
    private val stepMs: Int = 100,
    private val pauseMs: Int = DEFAULT_PAUSE_MS,
    private val noSpeechMs: Int = 15_000,
) {
    private var floor = Float.NaN
    private var spoke = false
    private var talking = false
    private var quietMs = 0
    private var elapsedMs = 0

    /** True when the recording should stop now. */
    fun feed(level: Float): Boolean {
        elapsedMs += stepMs
        if (floor.isNaN() || level < floor) floor = level
        val threshold = floor + if (talking) KEEP_ABOVE_FLOOR else START_ABOVE_FLOOR
        talking = level > maxOf(threshold, MIN_SPEECH_LEVEL)
        if (talking) {
            spoke = true; quietMs = 0
        } else {
            quietMs += stepMs
            floor += RISE_PER_STEP // only on non-speech frames
        }
        return if (spoke) quietMs >= pauseMs else elapsedMs >= noSpeechMs
    }

    companion object {
        const val DEFAULT_PAUSE_MS = 2500
        private const val START_ABOVE_FLOOR = 2f
        private const val KEEP_ABOVE_FLOOR = 1f
        private const val MIN_SPEECH_LEVEL = 0f
        private const val RISE_PER_STEP = 0.01f // 0.1 unit per second of quiet
    }
}
