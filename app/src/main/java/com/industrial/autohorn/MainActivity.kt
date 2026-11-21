package com.industrial.autohorn

import android.annotation.SuppressLint
import android.media.AudioManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.industrial.autohorn.data.WearCommand
import com.industrial.autohorn.data.WearStatus
import com.industrial.autohorn.presentation.HornViewModel
import com.industrial.autohorn.ui.HornScreen
import com.industrial.autohorn.ui.theme.AutoHornTheme
import com.industrial.autohorn.wear.WearCommandBus
import com.industrial.autohorn.wear.WearStatusSender
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
        get() = (application as AutoHornApplication).container

    private val viewModel: HornViewModel by viewModels {
        HornViewModel.Factory(
            session = appContainer.session,
            preferencesRepository = appContainer.preferencesRepository,
            safetyMonitor = appContainer.safetyMonitor,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Route hardware volume keys to the same media stream used by the AudioTrack.
        volumeControlStream = AudioManager.STREAM_MUSIC

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
            AutoHornTheme {
                val state = viewModel.uiState.collectAsStateWithLifecycle()
                val telemetry = viewModel.telemetry.collectAsStateWithLifecycle()
                val windowSizeClass = calculateWindowSizeClass(this@MainActivity)
                val context = LocalContext.current
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
                HornScreen(
                    state = state.value,
                    telemetry = telemetry.value,
                    windowWidthSizeClass = windowSizeClass.widthSizeClass,
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
                )
            }
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
