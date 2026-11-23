package com.industrial.horn

import android.annotation.SuppressLint
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.industrial.horn.data.ThemeMode
import com.industrial.horn.data.WearCommand
import com.industrial.horn.data.WearStatus
import com.industrial.horn.presentation.HornViewModel
import com.industrial.horn.presentation.StartupCoordinator
import com.industrial.horn.presentation.StartupState
import com.industrial.horn.ui.HornScreen
import com.industrial.horn.ui.StartupOverlay
import com.industrial.horn.ui.theme.HornTheme
import com.industrial.horn.ui.theme.LocalReducedMotion
import com.industrial.horn.ui.theme.StartupTimeline
import com.industrial.horn.wear.WearCommandBus
import com.industrial.horn.wear.WearStatusSender
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Single-activity host.
 *
 * Two independent lifecycle guards stop the horn on foreground loss:
 * Activity.onPause (covers lock screen, calls, recents, config changes) and Activity.onStop.
 * The ViewModel additionally reacts to audio-focus loss and noisy route broadcasts.
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
class MainActivity : ComponentActivity() {
    private val appContainer
        get() = (application as HornApplication).container

    private val viewModel: HornViewModel by viewModels {
        HornViewModel.Factory(
            session = appContainer.session,
            preferencesRepository = appContainer.preferencesRepository,
            safetyMonitor = appContainer.safetyMonitor,
        )
    }

    /** Flipped by the first composed frame; the system splash waits on it and nothing else. */
    private val firstFrameReady = mutableStateOf(false)

    private companion object {
        /** Quick-action launches (widget / tile / deep link) bypass the wake animation. */
        const val EXTRA_QUICK_ACTION = "com.industrial.horn.QUICK_ACTION"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Route hardware volume keys to the same media stream used by the AudioTrack.
        volumeControlStream = AudioManager.STREAM_MUSIC

        // Optical Wake playback policy: cold starts only, never for quick-action launches and
        // never when the user asked for reduced motion.
        val reducedMotion =
            Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        val startupCoordinator =
            StartupCoordinator(
                coldStart = savedInstanceState == null,
                startedFromQuickAction = intent?.getBooleanExtra(EXTRA_QUICK_ACTION, false) == true,
                reducedMotion = reducedMotion,
            )
        // The system splash hands over exactly when the first Compose frame is ready — no
        // "system splash → blink → wake animation" seam.
        splashScreen.setKeepOnScreenCondition { !firstFrameReady.value }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                WearCommandBus.commands.collect { command ->
                    when (command) {
                        WearCommand.START -> viewModel.onPressStart()
                        WearCommand.STOP -> viewModel.onPressEnd()
                    }
                }
            }
        }

        setContent {
            val startupState by startupCoordinator.state.collectAsStateWithLifecycle()
            var startupExiting by remember { mutableStateOf(false) }
            var startupDone by remember { mutableStateOf(false) }
            val wakeClock = remember { mutableFloatStateOf(0f) }
            if (!startupDone && (startupState == StartupState.Playing || startupExiting)) {
                LaunchedEffect(Unit) {
                    val origin = withFrameNanos { it }
                    while (isActive) {
                        withFrameNanos { now ->
                            wakeClock.floatValue = ((now - origin) / 1_000_000f).coerceAtMost(1_040f)
                        }
                    }
                }
            }
            CompositionLocalProvider(LocalReducedMotion provides reducedMotion) {
                Box(modifier = Modifier.fillMaxSize()) {
                    LaunchedEffect(Unit) {
                        withFrameNanos { }
                        firstFrameReady.value = true
                    }
                    val state = viewModel.uiState.collectAsStateWithLifecycle()
                    val telemetry = viewModel.telemetry.collectAsStateWithLifecycle()
                    val windowSizeClass = calculateWindowSizeClass(this@MainActivity)
                    val context = LocalContext.current

                    // Resolved theme: SYSTEM follows the platform, DAY/NIGHT pin the palette.
                    val systemDark = isSystemInDarkTheme()
                    val useDarkTheme =
                        when (state.value.themeMode) {
                            ThemeMode.SYSTEM -> systemDark
                            ThemeMode.LIGHT -> false
                            ThemeMode.DARK -> true
                        }

                    // Edge-to-edge means the bars are transparent over our canvas, so their icon
                    // polarity must follow the resolved palette instead of the system default.
                    DisposableEffect(useDarkTheme) {
                        val controller = WindowCompat.getInsetsController(window, window.decorView)
                        controller.isAppearanceLightStatusBars = !useDarkTheme
                        controller.isAppearanceLightNavigationBars = !useDarkTheme
                        onDispose {}
                    }

                    LaunchedEffect(
                        state.value.preferencesLoaded,
                        state.value.status,
                        state.value.selectedHorn,
                    ) {
                        if (state.value.preferencesLoaded) {
                            WearStatusSender.send(
                                context,
                                WearStatus(
                                    isHonking = state.value.isHonking,
                                    voice = state.value.selectedHorn,
                                ),
                            )
                        }
                    }

                    HornTheme(darkTheme = useDarkTheme) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.background),
                        ) {
                            HornScreen(
                                state = state.value,
                                telemetry = telemetry.value,
                                windowWidthSizeClass = windowSizeClass.widthSizeClass,
                                themeMode = state.value.themeMode,
                                onCycleThemeMode = viewModel::onCycleThemeMode,
                                onSelectThemeMode = viewModel::onSelectThemeMode,
                                onPressStart = viewModel::onPressStart,
                                onPressEnd = viewModel::onPressEnd,
                                onPressCancel = viewModel::onPressCancel,
                                onExpressionChanged = viewModel::onExpressionChanged,
                                onSelectHorn = viewModel::onSelectHorn,
                                onSelectSpace = viewModel::onAcousticSpaceSelected,
                                onVolumePromptClicked = viewModel::onVolumePromptClicked,
                                onVolumePromptDismissed = viewModel::onVolumePromptDismissed,
                                onVolumeTriggerChanged = viewModel::onVolumeTriggerChanged,
                                onCustomProfileChanged = viewModel::onCustomProfileChanged,
                                onCustomProfileSaved = viewModel::onCustomProfileSaved,
                                onCustomProfileReset = viewModel::onCustomProfileReset,
                                readWaveform = viewModel::readWaveform,
                                readWaveformStereo = viewModel::readWaveformStereo,
                                revealProgress = {
                                    if (startupDone) {
                                        1f
                                    } else {
                                        StartupTimeline.mergeProgress(wakeClock.floatValue)
                                    }
                                },
                            )
                            if (!startupDone && (startupState == StartupState.Playing || startupExiting)) {
                                StartupOverlay(
                                    onSkip = {
                                        startupExiting = true
                                        startupCoordinator.skip()
                                    },
                                    onRevealLatched = {},
                                    onFinished = {
                                        startupDone = true
                                        startupCoordinator.complete()
                                    },
                                    exiting = startupExiting,
                                    elapsedProvider = { wakeClock.floatValue },
                                    onBloomHaptic = ::lightHapticTick,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /** One restrained tick at the bloom milestone; silent when the device or setting says no. */
    @Suppress("DEPRECATION")
    private fun lightHapticTick() {
        val vibrator =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Vibrator::class.java)
            }
        if (vibrator?.hasVibrator() != true) return
        if (Settings.System.getInt(contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 0) == 0) return
        runCatching {
            @Suppress("DEPRECATION")
            vibrator.vibrate(VibrationEffect.createOneShot(12, 48))
        }
    }

    /**
     * Optional physical volume-key trigger. When enabled, only the initial key-down starts the
     * horn and key-up stops it; repeated key-downs are consumed but do not retrigger. When
     * disabled, the event falls through unchanged so normal volume control is preserved.
     */
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!viewModel.uiState.value.volumeTriggerEnabled) {
            return super.dispatchKeyEvent(event)
        }
        val isVolumeKey =
            event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (!isVolumeKey) return super.dispatchKeyEvent(event)

        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) viewModel.onPressStart()
                true
            }

            KeyEvent.ACTION_UP -> {
                viewModel.onPressEnd()
                true
            }

            else -> {
                super.dispatchKeyEvent(event)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onHostResume()
    }

    override fun onPause() {
        viewModel.onHostPause()
        super.onPause()
    }

    override fun onStop() {
        viewModel.onHostStop()
        super.onStop()
    }
}
