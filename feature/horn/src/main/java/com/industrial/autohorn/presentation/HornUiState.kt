package com.industrial.autohorn.presentation

import com.industrial.autohorn.audio.AcousticSpace
import com.industrial.autohorn.audio.AudioSafetyState
import com.industrial.autohorn.audio.CustomHornProfile
import com.industrial.autohorn.audio.HornType

/**
 * Immutable single source of truth for the screen. The selected voice, acoustic space, custom
 * profile, safety state and playback status travel together so the UI can never advertise a
 * configuration the engine has not received.
 */
data class HornUiState(
    val selectedHorn: HornType = HornType.CAR,
    val acousticSpace: AcousticSpace = AcousticSpace.STREET,
    val customProfile: CustomHornProfile = CustomHornProfile.DEFAULT,
    val volumeTriggerEnabled: Boolean = false,
    val status: HornStatus = HornStatus.Idle,
    val preferencesLoaded: Boolean = false,
    val audioSafety: AudioSafetyState = AudioSafetyState(),
    val showZeroVolumePrompt: Boolean = false,
) {
    val isHonking: Boolean
        get() = status is HornStatus.Honking
}

sealed interface HornStatus {
    data object Idle : HornStatus

    data object Honking : HornStatus

    data class Error(
        val failure: HornFailure,
    ) : HornStatus
}

enum class HornFailure {
    AUDIO_FOCUS_DENIED,
    AUDIO_ENGINE_UNAVAILABLE,
    INTERNAL,
}
