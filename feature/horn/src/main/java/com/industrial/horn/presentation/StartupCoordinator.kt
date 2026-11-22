package com.industrial.horn.presentation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

sealed interface StartupState {
    data object Playing : StartupState

    data object Completed : StartupState

    data object Skipped : StartupState

    data object Bypassed : StartupState
}

enum class StartupBypassReason {
    QUICK_ACTION,
    WARM_START,
    REDUCED_MOTION,
}

/**
 * Process-level once-only guard: the Optical Wake animation plays at most once per process, so
 * rotation, theme changes, fold transitions and warm returns never replay it.
 */
object StartupPlaybackGuard {
    private val claimed = AtomicBoolean(false)

    fun claim(): Boolean = claimed.compareAndSet(false, true)
}

/**
 * Startup state machine (see docs/spec/03 §"Optical Wake"). Transitions:
 *
 *  - cold start + no quick action + motion allowed → [StartupState.Playing]
 *  - [skip] (any tap) → [StartupState.Skipped], idempotent
 *  - timeline end → [StartupState.Completed], idempotent
 *  - anything else → [StartupState.Bypassed] with a reason
 *
 * Bypass precedence: QUICK_ACTION > WARM_START > REDUCED_MOTION.
 */
class StartupCoordinator(
    coldStart: Boolean,
    startedFromQuickAction: Boolean,
    reducedMotion: Boolean,
    claimOncePerProcess: () -> Boolean = { StartupPlaybackGuard.claim() },
) {
    val bypassReason: StartupBypassReason? =
        when {
            startedFromQuickAction -> StartupBypassReason.QUICK_ACTION
            !coldStart -> StartupBypassReason.WARM_START
            reducedMotion -> StartupBypassReason.REDUCED_MOTION
            else -> null
        }

    private val allowed =
        bypassReason == null &&
            playbackAllowed(
                coldStart = coldStart,
                quickAction = startedFromQuickAction,
                reducedMotion = reducedMotion,
            ) &&
            claimOncePerProcess()

    private val _state =
        MutableStateFlow<StartupState>(
            if (allowed) {
                StartupState.Playing
            } else {
                StartupState.Bypassed
            },
        )
    val state: StateFlow<StartupState> = _state.asStateFlow()

    val isPlaying: Boolean
        get() = _state.value == StartupState.Playing

    /** Tap-to-skip. Only a live playback may be skipped; repeat calls are no-ops. */
    fun skip() {
        if (_state.value == StartupState.Playing) {
            _state.value = StartupState.Skipped
        }
    }

    /** Timeline reached its end. Idempotent. */
    fun complete() {
        if (_state.value == StartupState.Playing) {
            _state.value = StartupState.Completed
        }
    }

    companion object {
        fun playbackAllowed(
            coldStart: Boolean,
            quickAction: Boolean,
            reducedMotion: Boolean,
        ): Boolean = coldStart && !quickAction && !reducedMotion
    }
}
