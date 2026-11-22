package com.industrial.horn.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.industrial.horn.audio.AcousticSpace
import com.industrial.horn.audio.AudioSafetyMonitor
import com.industrial.horn.audio.AudioTelemetry
import com.industrial.horn.audio.CustomHornProfile
import com.industrial.horn.audio.HornSession
import com.industrial.horn.audio.HornSessionEvent
import com.industrial.horn.audio.HornStartResult
import com.industrial.horn.audio.HornType
import com.industrial.horn.audio.NoOpAudioSafetyMonitor
import com.industrial.horn.audio.OutputMode
import com.industrial.horn.data.HornPreferencesRepository
import com.industrial.horn.data.HornSettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Single source of truth for horn interaction, voice/space selection, custom lab editing and
 * safety guidance.
 *
 * A voice change during a held honk first calls [HornSession.stop] (a smooth envelope release)
 * and only then commits the new type to state. The new voice is applied by the engine on the
 * next start, so waveform state can never be swapped under a live sample.
 */
class HornViewModel(
    private val session: HornSession,
    private val preferencesRepository: HornPreferencesRepository,
    private val safetyMonitor: AudioSafetyMonitor = NoOpAudioSafetyMonitor(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(HornUiState())
    val uiState: StateFlow<HornUiState> = _uiState.asStateFlow()

    private val _telemetry = MutableStateFlow(AudioTelemetry())
    val telemetry: StateFlow<AudioTelemetry> = _telemetry.asStateFlow()

    private var telemetryJob: Job? = null

    init {
        safetyMonitor.start()

        viewModelScope.launch {
            session.events.collect(::onSessionEvent)
        }
        viewModelScope.launch {
            safetyMonitor.state.collect { safety ->
                _uiState.update {
                    it.copy(
                        audioSafety = safety,
                        showZeroVolumePrompt = it.showZeroVolumePrompt && safety.isZeroVolume,
                    )
                }
                session.setProtection(safety.earProtectionActive)
                // Psychoacoustic stage follows the listening device: headphones get the pure
                // full-range chain, the phone speaker gets the Virtual Bass System.
                session.setOutputMode(
                    if (safety.headphonesConnected) {
                        OutputMode.HEADPHONE_FULL_RANGE
                    } else {
                        OutputMode.SPEAKER_VBS
                    },
                )
            }
        }
        viewModelScope.launch {
            // The persisted value is loaded exactly once so an in-flight user selection can
            // never be overwritten by a stale DataStore emission.
            val settings =
                runCatching { preferencesRepository.settings.first() }
                    .getOrDefault(HornSettings())
            _uiState.update {
                it.copy(
                    selectedHorn = settings.selectedHorn,
                    acousticSpace = settings.acousticSpace,
                    customProfile = settings.customProfile,
                    volumeTriggerEnabled = settings.volumeTriggerEnabled,
                    preferencesLoaded = true,
                )
            }
            session.setAcousticSpace(settings.acousticSpace)
            session.setCustomProfile(settings.customProfile)
            session.setProtection(safetyMonitor.state.value.earProtectionActive)
        }
    }

    /**
     * Telemetry polling runs only while the host is visible; it is not a free-running timer.
     * The interval is adaptive: tight while honking (live RMS/latency feedback) and relaxed
     * while idle (values are static, so frequent wakeups would only waste battery).
     */
    fun onHostResume() {
        if (telemetryJob?.isActive == true) return
        telemetryJob =
            viewModelScope.launch {
                while (isActive) {
                    _telemetry.value =
                        runCatching { session.telemetrySnapshot() }
                            .getOrDefault(AudioTelemetry())
                    delay(telemetryIntervalMs(_uiState.value.isHonking))
                }
            }
    }

    fun onPressStart() {
        val state = _uiState.value
        // Block the first few milliseconds of cold start: starting the default Car voice and
        // then restoring a persisted Train while it sounds would violate the state/audio contract.
        if (!state.preferencesLoaded || state.isHonking) return

        _uiState.update {
            it.copy(showZeroVolumePrompt = it.audioSafety.isZeroVolume)
        }
        session.setExpression(0f)

        val result =
            try {
                session.start(state.selectedHorn)
            } catch (_: RuntimeException) {
                runCatching { session.panicStop() }
                _uiState.update { it.copy(status = HornStatus.Error(HornFailure.INTERNAL)) }
                return
            }

        _uiState.update {
            it.copy(
                status =
                    when (result) {
                        HornStartResult.GRANTED -> {
                            HornStatus.Honking
                        }

                        HornStartResult.FOCUS_DENIED -> {
                            HornStatus.Error(HornFailure.AUDIO_FOCUS_DENIED)
                        }

                        HornStartResult.ENGINE_UNAVAILABLE -> {
                            HornStatus.Error(HornFailure.AUDIO_ENGINE_UNAVAILABLE)
                        }
                    },
            )
        }
    }

    fun onPressEnd() {
        if (!_uiState.value.isHonking) return
        runCatching {
            session.setExpression(0f)
            session.stop()
        }
        _uiState.update {
            it.copy(status = HornStatus.Idle, showZeroVolumePrompt = false)
        }
    }

    fun onPressCancel() {
        onPressEnd()
    }

    /** Continuous vertical-drag expression, called at pointer rate from the main thread. */
    fun onExpressionChanged(expression: Float) {
        if (!_uiState.value.isHonking) return
        session.setExpression(expression.coerceIn(-1f, 1f))
    }

    /**
     * Switching while honking is safe by construction: the active voice gets its release
     * envelope, UI goes Idle immediately, and only then is the new selection sent to DataStore.
     */
    fun onSelectHorn(type: HornType) {
        val current = _uiState.value
        if (!current.preferencesLoaded || current.selectedHorn == type) return

        if (current.isHonking) {
            try {
                session.setExpression(0f)
                session.stop()
            } catch (_: RuntimeException) {
                runCatching { session.panicStop() }
            }
        }

        session.setCustomProfile(current.customProfile)
        _uiState.update {
            it.copy(
                selectedHorn = type,
                status = if (it.isHonking) HornStatus.Idle else it.status,
            )
        }

        viewModelScope.launch {
            runCatching { preferencesRepository.setSelectedHorn(type) }
        }
    }

    fun onAcousticSpaceSelected(space: AcousticSpace) {
        _uiState.update { it.copy(acousticSpace = space) }
        session.setAcousticSpace(space)
        viewModelScope.launch {
            runCatching { preferencesRepository.setAcousticSpace(space) }
        }
    }

    fun onCustomProfileChanged(profile: CustomHornProfile) {
        val safe = profile.sanitized()
        _uiState.update { it.copy(customProfile = safe) }
        session.setCustomProfile(safe)
    }

    fun onCustomProfileSaved() {
        val profile = _uiState.value.customProfile
        viewModelScope.launch {
            runCatching { preferencesRepository.setCustomProfile(profile) }
        }
    }

    fun onCustomProfileReset() {
        val default = CustomHornProfile.DEFAULT
        _uiState.update { it.copy(customProfile = default) }
        session.setCustomProfile(default)
        viewModelScope.launch {
            runCatching { preferencesRepository.resetCustomProfile() }
        }
    }

    fun onVolumeTriggerChanged(enabled: Boolean) {
        _uiState.update { it.copy(volumeTriggerEnabled = enabled) }
        viewModelScope.launch {
            runCatching { preferencesRepository.setVolumeTriggerEnabled(enabled) }
        }
    }

    fun onVolumePromptClicked() {
        safetyMonitor.setVolumeTo60Percent()
        _uiState.update { it.copy(showZeroVolumePrompt = false) }
    }

    fun onVolumePromptDismissed() {
        _uiState.update { it.copy(showZeroVolumePrompt = false) }
    }

    /** Debug overlay bridge; consumes the engine's real telemetry ring, never a synthetic wave. */
    fun readWaveform(destination: FloatArray): Int = runCatching { session.readWaveform(destination) }.getOrDefault(0)

    fun readWaveformStereo(
        left: FloatArray,
        right: FloatArray,
    ): Int = runCatching { session.readWaveformStereo(left, right) }.getOrDefault(0)

    /** Called from Activity.onPause: incoming call, lock screen, config change, recents, etc. */
    fun onHostPause() {
        telemetryJob?.cancel()
        telemetryJob = null
        forceStop()
    }

    /** Called from Activity.onStop as a second, independent safety net. */
    fun onHostStop() {
        telemetryJob?.cancel()
        telemetryJob = null
        forceStop()
    }

    override fun onCleared() {
        telemetryJob?.cancel()
        safetyMonitor.stop()
        runCatching { session.release() }
        super.onCleared()
    }

    private fun forceStop() {
        runCatching {
            session.setExpression(0f)
            session.panicStop()
        }
        if (_uiState.value.isHonking) {
            _uiState.update {
                it.copy(status = HornStatus.Idle, showZeroVolumePrompt = false)
            }
        }
    }

    private fun onSessionEvent(event: HornSessionEvent) {
        when (event) {
            is HornSessionEvent.Interrupted -> {
                if (_uiState.value.isHonking) {
                    _uiState.update { it.copy(status = HornStatus.Idle) }
                }
            }

            is HornSessionEvent.EngineFailed -> {
                _uiState.update {
                    it.copy(status = HornStatus.Error(HornFailure.AUDIO_ENGINE_UNAVAILABLE))
                }
            }
        }
    }

    class Factory(
        private val session: HornSession,
        private val preferencesRepository: HornPreferencesRepository,
        private val safetyMonitor: AudioSafetyMonitor,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(HornViewModel::class.java)) {
                "Unsupported ViewModel type: ${modelClass.name}"
            }
            return HornViewModel(session, preferencesRepository, safetyMonitor) as T
        }
    }

    internal companion object {
        /** Live feedback cadence while honking: 20 updates per second. */
        const val TELEMETRY_INTERVAL_ACTIVE_MS = 50L

        /** Idle cadence: values are static, so poll twice per second at most. */
        const val TELEMETRY_INTERVAL_IDLE_MS = 500L

        fun telemetryIntervalMs(isHonking: Boolean): Long =
            if (isHonking) {
                TELEMETRY_INTERVAL_ACTIVE_MS
            } else {
                TELEMETRY_INTERVAL_IDLE_MS
            }
    }
}
