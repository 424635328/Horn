package com.industrial.autohorn.audio

/** Immutable acoustic calibration data for one voice. */
data class HornVoiceProfile(
    val type: HornType,
    val attackMillis: Float,
    val releaseMillis: Float,
    val makeupGain: Float,
)

/**
 * Strategy factory: each [HornType] gets a physically different DSP implementation, not merely
 * a different oscillator frequency.
 */
object HornSynthesizerFactory {
    fun create(
        type: HornType,
        sampleRate: Int,
        customProfile: CustomHornProfile = CustomHornProfile.DEFAULT,
    ): HornSynthesizer =
        when (type) {
            HornType.CAR -> DualToneHornSynth(sampleRate)
            HornType.TRAIN -> TrainHornSynth(sampleRate)
            HornType.EBIKE -> EBikeHornSynth(sampleRate)
            HornType.MOTORCYCLE -> MotorcycleHornSynth(sampleRate)
            HornType.CUSTOM -> CustomHornSynth(sampleRate, customProfile)
        }
}

/**
 * Per-voice output calibration.
 *
 * The gains are deliberately not equal: a dense three-chime train chord has far more spectral
 * energy than a single disc horn, while the 2.7 kHz e-bike chirp is much more sensitive to human
 * hearing. These values bring the voices into a similar perceived loudness after the shared
 * soft limiter and keep rapid type switching from producing a volume jump.
 */
object HornVoiceCatalog {
    private val fixedProfiles =
        mapOf(
            HornType.CAR to
                HornVoiceProfile(
                    type = HornType.CAR,
                    attackMillis = 12f,
                    releaseMillis = 14f,
                    makeupGain = 0.47f,
                ),
            HornType.TRAIN to
                HornVoiceProfile(
                    type = HornType.TRAIN,
                    attackMillis = 65f,
                    releaseMillis = 185f,
                    makeupGain = 0.52f,
                ),
            HornType.EBIKE to
                HornVoiceProfile(
                    type = HornType.EBIKE,
                    attackMillis = 4f,
                    releaseMillis = 6f,
                    makeupGain = 0.18f,
                ),
            HornType.MOTORCYCLE to
                HornVoiceProfile(
                    type = HornType.MOTORCYCLE,
                    attackMillis = 8f,
                    releaseMillis = 10f,
                    makeupGain = 0.30f,
                ),
        )

    fun profile(
        type: HornType,
        customProfile: CustomHornProfile = CustomHornProfile.DEFAULT,
    ): HornVoiceProfile =
        if (type == HornType.CUSTOM) {
            val custom = customProfile.sanitized()
            HornVoiceProfile(
                type = HornType.CUSTOM,
                attackMillis = custom.attackMillis,
                releaseMillis = custom.releaseMillis,
                makeupGain = 0.22f,
            )
        } else {
            checkNotNull(fixedProfiles[type])
        }

    fun createVoice(
        type: HornType,
        sampleRate: Int,
        customProfile: CustomHornProfile = CustomHornProfile.DEFAULT,
    ): HornVoice {
        val profile = profile(type, customProfile)
        return HornVoice(
            profile = profile,
            synthesizer = HornSynthesizerFactory.create(type, sampleRate, customProfile),
            envelope =
                AttackReleaseEnvelope(
                    sampleRate = sampleRate,
                    attackMillis = profile.attackMillis,
                    releaseMillis = profile.releaseMillis,
                ),
        )
    }
}

/** One playable voice: waveform strategy + transient envelope + calibrated gain. */
class HornVoice(
    val profile: HornVoiceProfile,
    val synthesizer: HornSynthesizer,
    val envelope: AttackReleaseEnvelope,
) {
    val type: HornType
        get() = profile.type

    val makeupGain: Float
        get() = profile.makeupGain

    fun reset() {
        synthesizer.reset()
        envelope.reset()
    }
}
