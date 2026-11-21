package com.industrial.autohorn.audio

/**
 * User-tunable fifth voice. Values are clamped by [sanitized] before reaching the DSP path, so a
 * corrupt DataStore entry can never divide by zero or generate non-finite audio.
 */
data class CustomHornProfile(
    val primaryFrequencyHz: Float = 620f,
    val secondaryFrequencyRatio: Float = 1.5f,
    val dualTone: Boolean = true,
    val harmonicRichness: Float = 0.55f,
    val attackMillis: Float = 20f,
    val releaseMillis: Float = 90f,
    val saturation: Float = 0.35f,
) {
    fun sanitized(): CustomHornProfile =
        copy(
            primaryFrequencyHz =
                safe(primaryFrequencyHz, DEFAULT.primaryFrequencyHz)
                    .coerceIn(MIN_FREQUENCY_HZ, MAX_FREQUENCY_HZ),
            secondaryFrequencyRatio =
                safe(secondaryFrequencyRatio, DEFAULT.secondaryFrequencyRatio)
                    .coerceIn(MIN_RATIO, MAX_RATIO),
            harmonicRichness = safe(harmonicRichness, DEFAULT.harmonicRichness).coerceIn(0f, 1f),
            attackMillis =
                safe(attackMillis, DEFAULT.attackMillis)
                    .coerceIn(MIN_ATTACK_MS, MAX_ATTACK_MS),
            releaseMillis =
                safe(releaseMillis, DEFAULT.releaseMillis)
                    .coerceIn(MIN_RELEASE_MS, MAX_RELEASE_MS),
            saturation = safe(saturation, DEFAULT.saturation).coerceIn(0f, 1f),
        )

    private fun safe(
        value: Float,
        fallback: Float,
    ): Float = if (value.isFinite()) value else fallback

    companion object {
        const val MIN_FREQUENCY_HZ = 150f
        const val MAX_FREQUENCY_HZ = 3_000f
        const val MIN_RATIO = 0.5f
        const val MAX_RATIO = 2f
        const val MIN_ATTACK_MS = 3f
        const val MAX_ATTACK_MS = 120f
        const val MIN_RELEASE_MS = 5f
        const val MAX_RELEASE_MS = 250f

        val DEFAULT = CustomHornProfile()
    }
}
