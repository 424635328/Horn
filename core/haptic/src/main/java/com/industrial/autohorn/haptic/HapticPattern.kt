package com.industrial.autohorn.haptic

import com.industrial.autohorn.audio.HornType

/**
 * Platform-independent vibration waveform.
 *
 * Timings alternate off/on, starting with off. [amplitudes] is used only on devices with
 * amplitude control; [fallbackTimingsMs] gives amplitude-less devices a distinct but softer
 * rhythm instead of an all-or-nothing buzz.
 */
data class HapticPattern(
    val timingsMs: LongArray,
    val amplitudes: IntArray,
    val fallbackTimingsMs: LongArray,
) {
    init {
        require(timingsMs.isNotEmpty()) { "timings must not be empty" }
        require(timingsMs.size == amplitudes.size) { "timings/amplitudes must match" }
        require(timingsMs.all { it > 0L }) { "timings must be positive" }
        require(amplitudes.all { it in 0..255 }) { "amplitudes must be 0..255" }
        require(amplitudes.any { it > 0 }) { "waveform must contain a pulse" }
        require(fallbackTimingsMs.isNotEmpty()) { "fallback timings must not be empty" }
        require(fallbackTimingsMs.all { it > 0L }) { "fallback timings must be positive" }
    }
}

/** Haptic signatures are intentionally as different as the four acoustic voices. */
object HapticSignatures {
    fun forType(type: HornType): HapticPattern =
        when (type) {
            HornType.CAR -> {
                HapticPattern(
                    timingsMs = longArrayOf(1, 24, 80, 12, 50, 9, 55, 11),
                    amplitudes = intArrayOf(0, 190, 0, 90, 0, 70, 0, 50),
                    fallbackTimingsMs = longArrayOf(1, 14, 96, 7, 80, 6),
                )
            }

            HornType.TRAIN -> {
                HapticPattern(
                    timingsMs = longArrayOf(1, 120, 100, 50, 110, 45, 90, 55),
                    amplitudes = intArrayOf(0, 255, 0, 180, 0, 150, 0, 120),
                    fallbackTimingsMs = longArrayOf(1, 90, 170, 45, 160, 40),
                )
            }

            HornType.EBIKE -> {
                HapticPattern(
                    timingsMs = longArrayOf(1, 7, 23, 5, 19, 4, 21, 3),
                    amplitudes = intArrayOf(0, 70, 0, 55, 0, 45, 0, 40),
                    fallbackTimingsMs = longArrayOf(1, 6, 42, 4, 38, 4),
                )
            }

            HornType.MOTORCYCLE -> {
                HapticPattern(
                    timingsMs = longArrayOf(1, 18, 30, 10, 26, 8, 28, 6),
                    amplitudes = intArrayOf(0, 150, 0, 95, 0, 75, 0, 60),
                    fallbackTimingsMs = longArrayOf(1, 12, 54, 7, 48, 6),
                )
            }

            HornType.CUSTOM -> {
                HapticPattern(
                    timingsMs = longArrayOf(1, 10, 28, 6, 20, 6, 18, 5),
                    amplitudes = intArrayOf(0, 125, 0, 80, 0, 65, 0, 50),
                    fallbackTimingsMs = longArrayOf(1, 8, 46, 5, 38, 5),
                )
            }
        }
}
