package com.industrial.horn.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.industrial.horn.audio.AcousticSpace
import com.industrial.horn.audio.AudioSafetyMonitor
import com.industrial.horn.audio.AudioSafetyPolicy
import com.industrial.horn.audio.AudioSafetyState
import com.industrial.horn.audio.AudioTelemetry
import com.industrial.horn.audio.CustomHornProfile
import com.industrial.horn.audio.HornSession
import com.industrial.horn.audio.HornSessionEvent
import com.industrial.horn.audio.HornStartResult
import com.industrial.horn.audio.HornType
import com.industrial.horn.audio.InterruptionReason
import com.industrial.horn.audio.OutputMode
import com.industrial.horn.data.HornPreferencesRepository
import com.industrial.horn.data.HornSettings
import com.industrial.horn.data.ThemeMode
import com.industrial.horn.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class HornViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun initialStateIsIdleCarWithPreferencesNotLoaded() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = HornViewModel(FakeHornSession(), FakePreferencesRepository())
            assertEquals(HornType.CAR, viewModel.uiState.value.selectedHorn)
            assertEquals(HornStatus.Idle, viewModel.uiState.value.status)
            assertFalse(viewModel.uiState.value.preferencesLoaded)
        }

    @Test
    fun coldStartRestoresPersistedVoice() =
        runTest(mainDispatcherRule.dispatcher) {
            val preferences = FakePreferencesRepository(initial = HornType.TRAIN)
            val viewModel = HornViewModel(FakeHornSession(), preferences)

            runCurrent()

            assertEquals(HornType.TRAIN, viewModel.uiState.value.selectedHorn)
            assertTrue(viewModel.uiState.value.preferencesLoaded)
        }

    @Test
    fun readFailureFallsBackToCarAndUnlocksTheUi() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = HornViewModel(FakeHornSession(), FakePreferencesRepository(failRead = true))

            runCurrent()

            assertEquals(HornType.CAR, viewModel.uiState.value.selectedHorn)
            assertTrue(viewModel.uiState.value.preferencesLoaded)
        }

    @Test
    fun coldStartRestoresPersistedThemeMode() =
        runTest(mainDispatcherRule.dispatcher) {
            val preferences =
                FakePreferencesRepository(
                    initialSettings = HornSettings(themeMode = ThemeMode.DARK),
                )
            val viewModel = HornViewModel(FakeHornSession(), preferences)

            runCurrent()

            assertEquals(ThemeMode.DARK, viewModel.uiState.value.themeMode)
            assertTrue(viewModel.uiState.value.preferencesLoaded)
        }

    @Test
    fun themeCycleWalksAutoDayNightAndPersistsEveryStep() =
        runTest(mainDispatcherRule.dispatcher) {
            val preferences = FakePreferencesRepository()
            val viewModel = HornViewModel(FakeHornSession(), preferences)
            runCurrent()
            assertEquals(ThemeMode.SYSTEM, viewModel.uiState.value.themeMode)

            val walked = mutableListOf<ThemeMode>()
            repeat(4) {
                viewModel.onCycleThemeMode()
                walked += viewModel.uiState.value.themeMode
            }

            assertEquals(
                listOf(ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.SYSTEM, ThemeMode.LIGHT),
                walked,
            )
            runCurrent()
            assertEquals(ThemeMode.LIGHT, preferences.lastThemeMode)
        }

    @Test
    fun themeSelectionIsSynchronousWhilePersistenceIsDeferred() =
        runTest(mainDispatcherRule.dispatcher) {
            val preferences = FakePreferencesRepository()
            val viewModel = HornViewModel(FakeHornSession(), preferences)
            runCurrent()

            viewModel.onSelectThemeMode(ThemeMode.LIGHT)

            // The palette must flip on the tap frame, before the DataStore write is scheduled.
            assertEquals(ThemeMode.LIGHT, viewModel.uiState.value.themeMode)

            runCurrent()
            assertEquals(ThemeMode.LIGHT, preferences.lastThemeMode)
        }

    @Test
    fun selectingTheCurrentThemeModeDoesNotTouchStorage() =
        runTest(mainDispatcherRule.dispatcher) {
            val preferences = FakePreferencesRepository()
            val viewModel = HornViewModel(FakeHornSession(), preferences)
            runCurrent()

            viewModel.onSelectThemeMode(ThemeMode.SYSTEM)
            runCurrent()

            assertEquals(null, preferences.lastThemeMode)
        }

    @Test
    fun pressUsesTheSelectedVoiceAndReleaseStops() =
        runTest(mainDispatcherRule.dispatcher) {
            val session = FakeHornSession()
            val viewModel = HornViewModel(session, FakePreferencesRepository(initial = HornType.TRAIN))
            runCurrent()

            viewModel.onPressStart()
            assertEquals(HornStatus.Honking, viewModel.uiState.value.status)
            assertEquals(listOf(HornType.TRAIN), session.startTypes)

            viewModel.onPressEnd()
            assertEquals(HornStatus.Idle, viewModel.uiState.value.status)
            assertEquals(1, session.stopCalls)
        }

    @Test
    fun repeatedPressDoesNotStartDuplicatePlayback() =
        runTest(mainDispatcherRule.dispatcher) {
            val session = FakeHornSession()
            val viewModel = HornViewModel(session, FakePreferencesRepository())
            runCurrent()

            viewModel.onPressStart()
            viewModel.onPressStart()

            assertEquals(1, session.startCalls)
            assertEquals(HornStatus.Honking, viewModel.uiState.value.status)
        }

    @Test
    fun releaseWhileIdleIsANoOp() =
        runTest(mainDispatcherRule.dispatcher) {
            val session = FakeHornSession()
            val viewModel = HornViewModel(session, FakePreferencesRepository())
            runCurrent()

            viewModel.onPressEnd()

            assertEquals(0, session.stopCalls)
            assertEquals(HornStatus.Idle, viewModel.uiState.value.status)
        }

    @Test
    fun cancelUsesTheSameSafeReleasePath() =
        runTest(mainDispatcherRule.dispatcher) {
            val session = FakeHornSession()
            val viewModel = HornViewModel(session, FakePreferencesRepository())
            runCurrent()

            viewModel.onPressStart()
            viewModel.onPressCancel()

            assertEquals(HornStatus.Idle, viewModel.uiState.value.status)
            assertEquals(1, session.stopCalls)
        }

    @Test
    fun audioFocusDenialBecomesErrorState() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel =
                HornViewModel(
                    FakeHornSession(startResult = HornStartResult.FOCUS_DENIED),
                    FakePreferencesRepository(),
                )
            runCurrent()

            viewModel.onPressStart()

            assertEquals(
                HornStatus.Error(HornFailure.AUDIO_FOCUS_DENIED),
                viewModel.uiState.value.status,
            )
        }

    @Test
    fun unavailableEngineBecomesErrorState() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel =
                HornViewModel(
                    FakeHornSession(startResult = HornStartResult.ENGINE_UNAVAILABLE),
                    FakePreferencesRepository(),
                )
            runCurrent()

            viewModel.onPressStart()

            assertEquals(
                HornStatus.Error(HornFailure.AUDIO_ENGINE_UNAVAILABLE),
                viewModel.uiState.value.status,
            )
        }

    @Test
    fun exceptionInsideSessionIsContainedAndPanicStopped() =
        runTest(mainDispatcherRule.dispatcher) {
            val session = FakeHornSession(startException = IllegalStateException("mock failure"))
            val viewModel = HornViewModel(session, FakePreferencesRepository())
            runCurrent()

            viewModel.onPressStart()

            assertEquals(HornStatus.Error(HornFailure.INTERNAL), viewModel.uiState.value.status)
            assertEquals(1, session.panicCalls)
        }

    @Test
    fun focusInterruptionDropsBackToIdleButKeepsVoice() =
        runTest(mainDispatcherRule.dispatcher) {
            val session = FakeHornSession()
            val viewModel = HornViewModel(session, FakePreferencesRepository(initial = HornType.EBIKE))
            runCurrent()
            viewModel.onPressStart()

            session.emit(HornSessionEvent.Interrupted(InterruptionReason.AUDIO_FOCUS_LOST))
            advanceUntilIdle()

            assertEquals(HornStatus.Idle, viewModel.uiState.value.status)
            assertEquals(HornType.EBIKE, viewModel.uiState.value.selectedHorn)
        }

    @Test
    fun engineFailureBecomesErrorState() =
        runTest(mainDispatcherRule.dispatcher) {
            val session = FakeHornSession()
            val viewModel = HornViewModel(session, FakePreferencesRepository())
            runCurrent()
            viewModel.onPressStart()

            session.emit(HornSessionEvent.EngineFailed(IllegalStateException("write failed")))
            advanceUntilIdle()

            assertEquals(
                HornStatus.Error(HornFailure.AUDIO_ENGINE_UNAVAILABLE),
                viewModel.uiState.value.status,
            )
        }

    @Test
    fun lifecyclePauseForcesPanicStopAndIdleState() =
        runTest(mainDispatcherRule.dispatcher) {
            val session = FakeHornSession()
            val viewModel = HornViewModel(session, FakePreferencesRepository())
            runCurrent()
            viewModel.onPressStart()

            viewModel.onHostPause()

            assertEquals(HornStatus.Idle, viewModel.uiState.value.status)
            assertEquals(1, session.panicCalls)
            assertEquals(0, session.stopCalls)
        }

    @Test
    fun lifecycleStopIsAnIndependentSecondGuard() =
        runTest(mainDispatcherRule.dispatcher) {
            val session = FakeHornSession()
            val viewModel = HornViewModel(session, FakePreferencesRepository())
            runCurrent()
            viewModel.onPressStart()

            viewModel.onHostStop()

            assertEquals(HornStatus.Idle, viewModel.uiState.value.status)
            assertEquals(1, session.panicCalls)
        }

    @Test
    fun errorStateCanBeRetriedByTheNextPress() =
        runTest(mainDispatcherRule.dispatcher) {
            val session = FakeHornSession(startResult = HornStartResult.FOCUS_DENIED)
            val viewModel = HornViewModel(session, FakePreferencesRepository())
            runCurrent()

            viewModel.onPressStart()
            session.startResult = HornStartResult.GRANTED
            viewModel.onPressStart()

            assertEquals(HornStatus.Honking, viewModel.uiState.value.status)
            assertEquals(2, session.startCalls)
        }

    @Test
    fun selectionWhileIdlePersistsAndKeepsIdle() =
        runTest(mainDispatcherRule.dispatcher) {
            val preferences = FakePreferencesRepository()
            val session = FakeHornSession()
            val viewModel = HornViewModel(session, preferences)
            runCurrent()

            viewModel.onSelectHorn(HornType.MOTORCYCLE)
            advanceUntilIdle()

            assertEquals(HornType.MOTORCYCLE, viewModel.uiState.value.selectedHorn)
            assertEquals(HornStatus.Idle, viewModel.uiState.value.status)
            assertEquals(HornType.MOTORCYCLE, preferences.lastWritten)
            assertEquals(0, session.stopCalls)
        }

    @Test
    fun selectionWhileHonkingStopsTheOldVoiceBeforePersisting() =
        runTest(mainDispatcherRule.dispatcher) {
            val preferences = FakePreferencesRepository(initial = HornType.CAR)
            val session = FakeHornSession()
            val viewModel = HornViewModel(session, preferences)
            runCurrent()
            viewModel.onPressStart()

            viewModel.onSelectHorn(HornType.TRAIN)
            advanceUntilIdle()

            assertEquals(HornStatus.Idle, viewModel.uiState.value.status)
            assertEquals(HornType.TRAIN, viewModel.uiState.value.selectedHorn)
            assertEquals(1, session.stopCalls)
            assertEquals(listOf(HornType.CAR), session.startTypes)
            assertEquals(HornType.TRAIN, preferences.lastWritten)
        }

    @Test
    fun selectingTheAlreadySelectedVoiceDoesNotStopOrWrite() =
        runTest(mainDispatcherRule.dispatcher) {
            val preferences = FakePreferencesRepository(initial = HornType.CAR)
            val session = FakeHornSession()
            val viewModel = HornViewModel(session, preferences)
            runCurrent()
            viewModel.onPressStart()

            viewModel.onSelectHorn(HornType.CAR)
            advanceUntilIdle()

            assertEquals(0, session.stopCalls)
            assertEquals(0, preferences.writeCalls)
            assertEquals(HornStatus.Honking, viewModel.uiState.value.status)
        }

    @Test
    fun selectionBeforePreferencesLoadIsIgnoredToAvoidStaleOverwrite() =
        runTest(mainDispatcherRule.dispatcher) {
            val preferences = FakePreferencesRepository(initial = HornType.TRAIN)
            val session = FakeHornSession()
            val viewModel = HornViewModel(session, preferences)

            viewModel.onSelectHorn(HornType.EBIKE)
            runCurrent()

            assertEquals(HornType.TRAIN, viewModel.uiState.value.selectedHorn)
            assertEquals(0, preferences.writeCalls)
        }

    @Test
    fun pressBeforePreferencesLoadIsIgnoredToKeepStateAndAudioInSync() =
        runTest(mainDispatcherRule.dispatcher) {
            val preferences = FakePreferencesRepository(initial = HornType.TRAIN)
            val session = FakeHornSession()
            val viewModel = HornViewModel(session, preferences)

            viewModel.onPressStart()
            assertEquals(0, session.startCalls)
            assertEquals(HornStatus.Idle, viewModel.uiState.value.status)

            runCurrent()
            viewModel.onPressStart()
            assertEquals(listOf(HornType.TRAIN), session.startTypes)
            assertEquals(HornStatus.Honking, viewModel.uiState.value.status)
        }

    @Test
    fun zeroVolumeShowsPromptAndSixtyPercentActionRaisesVolume() =
        runTest(mainDispatcherRule.dispatcher) {
            val safety = FakeSafetyMonitor(AudioSafetyState(mediaVolume = 0, maxMediaVolume = 15))
            val session = FakeHornSession()
            val viewModel = HornViewModel(session, FakePreferencesRepository(), safety)
            runCurrent()

            viewModel.onPressStart()
            assertTrue(viewModel.uiState.value.showZeroVolumePrompt)
            assertEquals(HornStatus.Honking, viewModel.uiState.value.status)

            viewModel.onVolumePromptClicked()
            runCurrent()

            assertFalse(viewModel.uiState.value.showZeroVolumePrompt)
            assertEquals(9, viewModel.uiState.value.audioSafety.mediaVolume)
            viewModel.onPressEnd()
        }

    @Test
    fun headphoneProtectionPolicyIsForwardedToTheEngine() =
        runTest(mainDispatcherRule.dispatcher) {
            val safety = FakeSafetyMonitor(AudioSafetyPolicy.evaluate(12, 15, true))
            val session = FakeHornSession()
            val viewModel = HornViewModel(session, FakePreferencesRepository(), safety)
            runCurrent()

            assertEquals(true, session.lastProtection)
            assertEquals(true, viewModel.uiState.value.audioSafety.earProtectionActive)

            safety.emit(AudioSafetyPolicy.evaluate(3, 15, true))
            runCurrent()

            assertEquals(false, session.lastProtection)
            assertEquals(false, viewModel.uiState.value.audioSafety.earProtectionActive)
        }

    @Test
    fun persistedSpaceCustomProfileAndVolumeTriggerAreRestoredAndWritten() =
        runTest(mainDispatcherRule.dispatcher) {
            val custom =
                CustomHornProfile(
                    primaryFrequencyHz = 1_100f,
                    secondaryFrequencyRatio = 1.3f,
                    harmonicRichness = 0.8f,
                    attackMillis = 20f,
                    releaseMillis = 100f,
                    saturation = 0.4f,
                )
            val initial =
                HornSettings(
                    selectedHorn = HornType.CUSTOM,
                    acousticSpace = AcousticSpace.TUNNEL,
                    customProfile = custom,
                    volumeTriggerEnabled = true,
                )
            val preferences = FakePreferencesRepository(initialSettings = initial)
            val session = FakeHornSession()
            val viewModel = HornViewModel(session, preferences, FakeSafetyMonitor())
            runCurrent()

            assertEquals(AcousticSpace.TUNNEL, viewModel.uiState.value.acousticSpace)
            assertEquals(custom, viewModel.uiState.value.customProfile)
            assertEquals(true, viewModel.uiState.value.volumeTriggerEnabled)
            assertEquals(AcousticSpace.TUNNEL, session.lastSpace)
            assertEquals(custom, session.lastCustomProfile)

            viewModel.onAcousticSpaceSelected(AcousticSpace.STREET)
            viewModel.onCustomProfileChanged(custom.copy(primaryFrequencyHz = 1_250f))
            viewModel.onCustomProfileSaved()
            viewModel.onVolumeTriggerChanged(false)
            advanceUntilIdle()

            assertEquals(AcousticSpace.STREET, preferences.lastSpace)
            assertEquals(1_250f, preferences.lastCustomProfile?.primaryFrequencyHz ?: 0f, 0f)
            assertEquals(false, preferences.lastVolumeTrigger)
        }

    @Test
    fun expressionIsClampedAndOnlySentWhileHonking() =
        runTest(mainDispatcherRule.dispatcher) {
            val session = FakeHornSession()
            val viewModel = HornViewModel(session, FakePreferencesRepository(), FakeSafetyMonitor())
            runCurrent()

            viewModel.onExpressionChanged(1f)
            assertTrue("Idle expression must not reach the engine", session.expressions.isEmpty())

            viewModel.onPressStart()
            viewModel.onExpressionChanged(2f)
            viewModel.onExpressionChanged(-2f)

            assertTrue(session.expressions.contains(1f))
            assertTrue(session.expressions.contains(-1f))

            viewModel.onPressEnd()
            assertEquals(0f, session.expressions.last(), 0f)
        }

    @Test
    fun headphoneDetectionDrivesThePsychoacousticOutputMode() =
        runTest(mainDispatcherRule.dispatcher) {
            val session = FakeHornSession()
            val safety = FakeSafetyMonitor()
            val viewModel = HornViewModel(session, FakePreferencesRepository(), safety)
            runCurrent()

            safety.emit(AudioSafetyState(mediaVolume = 12, maxMediaVolume = 15, headphonesConnected = true))
            runCurrent()
            assertEquals(
                "Headphones must engage the full-range chain",
                OutputMode.HEADPHONE_FULL_RANGE,
                session.lastOutputMode,
            )

            safety.emit(AudioSafetyState(mediaVolume = 12, maxMediaVolume = 15, headphonesConnected = false))
            runCurrent()
            assertEquals(
                "Phone speaker must engage the Virtual Bass System",
                OutputMode.SPEAKER_VBS,
                session.lastOutputMode,
            )
        }

    @Test
    fun telemetryPollingIsTightWhileHonkingAndRelaxedWhileIdle() {
        assertEquals(
            "Honking needs a tight 20 Hz feedback cadence",
            HornViewModel.TELEMETRY_INTERVAL_ACTIVE_MS,
            HornViewModel.telemetryIntervalMs(isHonking = true),
        )
        assertEquals(
            "Idle telemetry is static and must not burn battery",
            HornViewModel.TELEMETRY_INTERVAL_IDLE_MS,
            HornViewModel.telemetryIntervalMs(isHonking = false),
        )
        assertTrue(
            "Idle interval must be at least 5x slower than the active one",
            HornViewModel.telemetryIntervalMs(false) >= 5 * HornViewModel.telemetryIntervalMs(true),
        )
    }

    @Test
    fun clearingTheViewModelStopsAudioButNeverReleasesTheProcessSession() =
        runTest(mainDispatcherRule.dispatcher) {
            val session = FakeHornSession()
            val viewModel = HornViewModel(session, FakePreferencesRepository())
            runCurrent()

            clearViewModel(viewModel)

            assertEquals(
                "session 是 AppContainer 的进程级对象，页面级生命周期不得 release 它",
                0,
                session.releaseCalls,
            )
            assertTrue("onCleared 仍必须把音频立即静音", session.panicCalls >= 1)
        }

    @Test
    fun aSecondLaunchedViewModelCanStillStartTheSameSession() =
        runTest(mainDispatcherRule.dispatcher) {
            val session = FakeHornSession()
            val first = HornViewModel(session, FakePreferencesRepository())
            runCurrent()
            first.onPressStart()
            assertEquals(1, session.startCalls)
            clearViewModel(first)

            // 二次进入：同一个进程级 session 必须仍然可以起音（A1 的 JVM 级不变量）。
            val second = HornViewModel(session, FakePreferencesRepository())
            runCurrent()
            second.onPressStart()

            assertEquals("第二次进入仍必须能触发起音", 2, session.startCalls)
            assertEquals(HornStatus.Honking, second.uiState.value.status)
            assertEquals("整个过程不得 release 进程级 session", 0, session.releaseCalls)
        }

    private fun clearViewModel(viewModel: HornViewModel) {
        val store = ViewModelStore()
        val factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return viewModel as T
                }
            }
        ViewModelProvider(store, factory)[HornViewModel::class.java]
        store.clear()
    }

    private class FakeHornSession(
        var startResult: HornStartResult = HornStartResult.GRANTED,
        var startException: RuntimeException? = null,
    ) : HornSession {
        private val mutableEvents = MutableSharedFlow<HornSessionEvent>(extraBufferCapacity = 16)
        override val events: Flow<HornSessionEvent> = mutableEvents

        var startCalls = 0
        var stopCalls = 0
        var panicCalls = 0
        var releaseCalls = 0
        var telemetryCalls = 0
        val startTypes = mutableListOf<HornType>()
        val expressions = mutableListOf<Float>()
        var lastSpace: AcousticSpace? = null
        var lastProtection: Boolean? = null
        var lastCustomProfile: CustomHornProfile? = null
        var lastOutputMode: OutputMode = OutputMode.SPEAKER_VBS
        var telemetry = AudioTelemetry(engineName = "Fake", rms = 0.4f)

        override fun setOutputMode(mode: OutputMode) {
            lastOutputMode = mode
        }

        override fun start(type: HornType): HornStartResult {
            startCalls++
            startTypes += type
            startException?.let { throw it }
            return startResult
        }

        override fun setExpression(expression: Float) {
            expressions += expression
        }

        override fun setAcousticSpace(space: AcousticSpace) {
            lastSpace = space
        }

        override fun setProtection(enabled: Boolean) {
            lastProtection = enabled
        }

        override fun setCustomProfile(profile: CustomHornProfile) {
            lastCustomProfile = profile
        }

        override fun telemetrySnapshot(): AudioTelemetry {
            telemetryCalls++
            return telemetry
        }

        override fun readWaveform(destination: FloatArray): Int = 0

        override fun stop() {
            stopCalls++
        }

        override fun panicStop() {
            panicCalls++
        }

        override fun release() {
            releaseCalls++
        }

        fun emit(event: HornSessionEvent) {
            check(mutableEvents.tryEmit(event)) { "event buffer full" }
        }
    }

    private class FakePreferencesRepository(
        initial: HornType = HornType.CAR,
        private val failRead: Boolean = false,
        initialSettings: HornSettings = HornSettings(selectedHorn = initial),
    ) : HornPreferencesRepository {
        val settingsState = MutableStateFlow(initialSettings)
        override val settings: Flow<HornSettings> =
            if (failRead) {
                flow { throw IOException("mock read failure") }
            } else {
                settingsState
            }
        override val selectedHorn: Flow<HornType> =
            flow {
                emit(settingsState.value.selectedHorn)
            }

        var lastWritten: HornType? = null
        var writeCalls = 0
        var lastSpace: AcousticSpace? = null
        var lastCustomProfile: CustomHornProfile? = null
        var lastVolumeTrigger: Boolean? = null
        var lastThemeMode: ThemeMode? = null

        override suspend fun setSelectedHorn(type: HornType) {
            writeCalls++
            lastWritten = type
            settingsState.value = settingsState.value.copy(selectedHorn = type)
        }

        override suspend fun setAcousticSpace(space: AcousticSpace) {
            lastSpace = space
            settingsState.value = settingsState.value.copy(acousticSpace = space)
        }

        override suspend fun setCustomProfile(profile: CustomHornProfile) {
            lastCustomProfile = profile
            settingsState.value = settingsState.value.copy(customProfile = profile)
        }

        override suspend fun setVolumeTriggerEnabled(enabled: Boolean) {
            lastVolumeTrigger = enabled
            settingsState.value = settingsState.value.copy(volumeTriggerEnabled = enabled)
        }

        override suspend fun setThemeMode(mode: ThemeMode) {
            lastThemeMode = mode
            settingsState.value = settingsState.value.copy(themeMode = mode)
        }

        override suspend fun resetCustomProfile() {
            lastCustomProfile = CustomHornProfile.DEFAULT
            settingsState.value =
                settingsState.value.copy(customProfile = CustomHornProfile.DEFAULT)
        }
    }

    private class FakeSafetyMonitor(
        initial: AudioSafetyState = AudioSafetyState(mediaVolume = 15, maxMediaVolume = 15),
    ) : AudioSafetyMonitor {
        private val mutableState = MutableStateFlow(initial)
        override val state: StateFlow<AudioSafetyState> = mutableState
        var startCalls = 0
        var stopCalls = 0

        override fun start() {
            startCalls++
        }

        override fun stop() {
            stopCalls++
        }

        override fun setVolumeTo60Percent() {
            mutableState.value =
                AudioSafetyState(mediaVolume = 9, maxMediaVolume = 15, headphonesConnected = false)
        }

        override fun refresh() = Unit

        fun emit(state: AudioSafetyState) {
            mutableState.value = state
        }
    }
}
