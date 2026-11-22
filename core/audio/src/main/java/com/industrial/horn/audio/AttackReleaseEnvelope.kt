package com.industrial.horn.audio

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Sample-accurate raised-cosine attack/release envelope.
 *
 * Each voice supplies its own physical transient: the car stays in the classic 10-15 ms
 * anti-pop window, the train uses a slow air-pressure attack and long tail, the e-bike snaps
 * on in a few milliseconds, and the motorcycle sits between them. Retriggering during a release
 * starts from the current level instead of jumping to zero, so rapid taps stay click-free.
 */
class AttackReleaseEnvelope(
    sampleRate: Int,
    val attackMillis: Float = DEFAULT_ATTACK_MS,
    val releaseMillis: Float = DEFAULT_RELEASE_MS,
) {
    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(attackMillis in MIN_ENVELOPE_MS..MAX_ENVELOPE_MS) {
            "attackMillis must be in $MIN_ENVELOPE_MS..$MAX_ENVELOPE_MS"
        }
        require(releaseMillis in MIN_ENVELOPE_MS..MAX_ENVELOPE_MS) {
            "releaseMillis must be in $MIN_ENVELOPE_MS..$MAX_ENVELOPE_MS"
        }
    }

    private val attackSamples = max(1, (sampleRate * attackMillis / 1000f).roundToInt())
    private val releaseSamples = max(1, (sampleRate * releaseMillis / 1000f).roundToInt())

    private var stage = Stage.IDLE
    private var level = 0f
    private var startLevel = 0f
    private var elapsedSamples = 0

    val isSilent: Boolean
        get() = stage == Stage.IDLE

    val isActive: Boolean
        get() = stage != Stage.IDLE

    fun noteOn() {
        if (stage == Stage.ATTACK || stage == Stage.SUSTAIN) return
        startLevel = level
        elapsedSamples = 0
        stage = Stage.ATTACK
    }

    fun noteOff() {
        if (stage == Stage.IDLE || stage == Stage.RELEASE) return
        startLevel = level
        elapsedSamples = 0
        stage = Stage.RELEASE
    }

    fun reset() {
        stage = Stage.IDLE
        level = 0f
        startLevel = 0f
        elapsedSamples = 0
    }

    fun nextSample(): Float {
        when (stage) {
            Stage.IDLE -> {
                return 0f
            }

            Stage.ATTACK -> {
                val progress = (elapsedSamples.toFloat() / attackSamples).coerceIn(0f, 1f)
                level = startLevel + (1f - startLevel) * smoothStep(progress)
                elapsedSamples++
                if (elapsedSamples >= attackSamples) {
                    level = 1f
                    stage = Stage.SUSTAIN
                }
            }

            Stage.SUSTAIN -> {
                level = 1f
            }

            Stage.RELEASE -> {
                val progress = (elapsedSamples.toFloat() / releaseSamples).coerceIn(0f, 1f)
                level = startLevel * (1f - smoothStep(progress))
                elapsedSamples++
                if (elapsedSamples >= releaseSamples) {
                    level = 0f
                    stage = Stage.IDLE
                }
            }
        }
        return level
    }

    private fun smoothStep(value: Float): Float = value * value * (3f - 2f * value)

    private enum class Stage {
        IDLE,
        ATTACK,
        SUSTAIN,
        RELEASE,
    }

    companion object {
        const val DEFAULT_ATTACK_MS = 12f
        const val DEFAULT_RELEASE_MS = 14f
        const val MIN_ENVELOPE_MS = 1f
        const val MAX_ENVELOPE_MS = 250f
    }
}
