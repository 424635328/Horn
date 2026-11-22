package com.industrial.horn.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Read-only safety surface consumed by presentation. Android implementation lives alongside. */
interface AudioSafetyMonitor {
    val state: StateFlow<AudioSafetyState>

    fun start()

    fun stop()

    fun setVolumeTo60Percent()

    fun refresh()
}

/** Test/preview implementation: full volume, no headphones, no side effects. */
class NoOpAudioSafetyMonitor(
    initialState: AudioSafetyState = AudioSafetyState(mediaVolume = 15, maxMediaVolume = 15),
) : AudioSafetyMonitor {
    private val _state = MutableStateFlow(initialState)
    override val state: StateFlow<AudioSafetyState> = _state

    override fun start() = Unit

    override fun stop() = Unit

    override fun setVolumeTo60Percent() {
        _state.value = AudioSafetyPolicy.evaluate(9, 15, _state.value.headphonesConnected)
    }

    override fun refresh() = Unit
}
