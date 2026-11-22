package com.industrial.horn.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Optical Wake startup state machine, 100% transition coverage (docs/spec/03 §11.3 / §11.4 /
 * §11.8). Pure JVM: every state-machine test injects its own `claimOncePerProcess` lambda so
 * nothing here depends on the process-level [StartupPlaybackGuard] singleton or on test ordering;
 * the guard itself is covered by a dedicated self-contained test.
 */
class StartupCoordinatorTest {
    @Test
    fun coldStartWithoutQuickActionAndMotionAllowedPlaysTheWake() {
        val coordinator = playingCoordinator()

        assertEquals(
            "cold start + no quick action + motion allowed must play the Optical Wake",
            StartupState.Playing,
            coordinator.state.value,
        )
        assertNull("a Playing coordinator must not carry any bypass reason", coordinator.bypassReason)
        assertTrue("isPlaying must mirror the Playing state", coordinator.isPlaying)
    }

    @Test
    fun completeTransitionsToCompletedAndIsIdempotent() {
        val coordinator = playingCoordinator()

        coordinator.complete()
        assertEquals(
            "the timeline end must land in Completed",
            StartupState.Completed,
            coordinator.state.value,
        )

        coordinator.complete()
        assertEquals(
            "a repeat complete() must be a no-op on the Completed state",
            StartupState.Completed,
            coordinator.state.value,
        )

        coordinator.skip()
        assertEquals(
            "a late skip() must not pull Completed back to Skipped",
            StartupState.Completed,
            coordinator.state.value,
        )
        assertFalse("isPlaying must be false once the wake completed", coordinator.isPlaying)
    }

    @Test
    fun skipTransitionsToSkippedAndIsIdempotent() {
        val coordinator = playingCoordinator()

        coordinator.skip()
        assertEquals(
            "a tap during playback must land in Skipped",
            StartupState.Skipped,
            coordinator.state.value,
        )

        coordinator.skip()
        assertEquals(
            "a repeat skip() must be a no-op on the Skipped state",
            StartupState.Skipped,
            coordinator.state.value,
        )
        assertFalse("isPlaying must be false once the wake was skipped", coordinator.isPlaying)
    }

    @Test
    fun completeAfterSkipDoesNotRollBackTheSkip() {
        val coordinator = playingCoordinator()

        coordinator.skip()
        coordinator.complete()

        assertEquals(
            "complete() after skip() must not roll the state back to Completed",
            StartupState.Skipped,
            coordinator.state.value,
        )
    }

    @Test
    fun terminalBypassedStateIsAbsorbingForSkipAndComplete() {
        val coordinator =
            StartupCoordinator(
                coldStart = true,
                startedFromQuickAction = false,
                reducedMotion = true,
                claimOncePerProcess = { true },
            )

        coordinator.skip()
        assertEquals(
            "skip() must not resurrect a Bypassed coordinator into Skipped",
            StartupState.Bypassed,
            coordinator.state.value,
        )

        coordinator.complete()
        assertEquals(
            "complete() must not resurrect a Bypassed coordinator into Completed",
            StartupState.Bypassed,
            coordinator.state.value,
        )
        assertFalse("isPlaying must stay false for a Bypassed coordinator", coordinator.isPlaying)
    }

    @Test
    fun warmStartBypassesWithTheWarmStartReason() {
        val coordinator =
            StartupCoordinator(
                coldStart = false,
                startedFromQuickAction = false,
                reducedMotion = false,
                claimOncePerProcess = { true },
            )

        assertEquals(
            "a warm start (rotation / theme change / return from background) must bypass playback",
            StartupState.Bypassed,
            coordinator.state.value,
        )
        assertEquals(
            "a warm start must be attributed to WARM_START",
            StartupBypassReason.WARM_START,
            coordinator.bypassReason,
        )
        assertFalse("a warm start must never report isPlaying", coordinator.isPlaying)
    }

    @Test
    fun quickActionBypassesWithTheQuickActionReason() {
        val coordinator =
            StartupCoordinator(
                coldStart = true,
                startedFromQuickAction = true,
                reducedMotion = false,
                claimOncePerProcess = { true },
            )

        assertEquals(
            "a quick-action launch must bypass playback and reach the UI immediately",
            StartupState.Bypassed,
            coordinator.state.value,
        )
        assertEquals(
            "a quick-action launch must be attributed to QUICK_ACTION",
            StartupBypassReason.QUICK_ACTION,
            coordinator.bypassReason,
        )
        assertFalse("a quick-action launch must never report isPlaying", coordinator.isPlaying)
    }

    @Test
    fun reducedMotionBypassesWithTheReducedMotionReason() {
        val coordinator =
            StartupCoordinator(
                coldStart = true,
                startedFromQuickAction = false,
                reducedMotion = true,
                claimOncePerProcess = { true },
            )

        assertEquals(
            "reduced motion must bypass playback entirely",
            StartupState.Bypassed,
            coordinator.state.value,
        )
        assertEquals(
            "reduced motion must be attributed to REDUCED_MOTION",
            StartupBypassReason.REDUCED_MOTION,
            coordinator.bypassReason,
        )
        assertFalse("reduced motion must never report isPlaying", coordinator.isPlaying)
    }

    @Test
    fun quickActionWinsTheBypassPrecedenceOverWarmStartAndReducedMotion() {
        val coordinator =
            StartupCoordinator(
                coldStart = false,
                startedFromQuickAction = true,
                reducedMotion = true,
                claimOncePerProcess = { true },
            )

        assertEquals(
            "quick action + warm start + reduced motion together must still bypass",
            StartupState.Bypassed,
            coordinator.state.value,
        )
        assertEquals(
            "bypass precedence QUICK_ACTION > WARM_START > REDUCED_MOTION must attribute QUICK_ACTION first",
            StartupBypassReason.QUICK_ACTION,
            coordinator.bypassReason,
        )
    }

    @Test
    fun warmStartWinsTheBypassPrecedenceOverReducedMotion() {
        val coordinator =
            StartupCoordinator(
                coldStart = false,
                startedFromQuickAction = false,
                reducedMotion = true,
                claimOncePerProcess = { true },
            )

        assertEquals(
            "with both warm start and reduced motion the higher-priority reason must win",
            StartupBypassReason.WARM_START,
            coordinator.bypassReason,
        )
    }

    @Test
    fun processGuardAlreadyClaimedBypassesToAvoidReplayAfterRebuild() {
        var claims = 0
        val coordinator =
            StartupCoordinator(
                coldStart = true,
                startedFromQuickAction = false,
                reducedMotion = false,
                claimOncePerProcess = {
                    claims++
                    false
                },
            )

        assertEquals(
            "a rotation rebuild must not replay the wake once the process guard is claimed",
            StartupState.Bypassed,
            coordinator.state.value,
        )
        assertNull(
            "a guard denial carries no matrix reason (all three flags were clean)",
            coordinator.bypassReason,
        )
        assertEquals("the claim lambda must be consulted exactly once", 1, claims)
    }

    @Test
    fun processGuardClaimSuccessPlaysAndIsConsultedExactlyOnce() {
        var claims = 0
        val coordinator =
            StartupCoordinator(
                coldStart = true,
                startedFromQuickAction = false,
                reducedMotion = false,
                claimOncePerProcess = {
                    claims++
                    true
                },
            )

        assertEquals(
            "an unclaimed process guard must let the cold-start wake play",
            StartupState.Playing,
            coordinator.state.value,
        )
        assertEquals("the claim lambda must be consulted exactly once", 1, claims)
    }

    @Test
    fun bypassedStartDoesNotConsumeTheOncePerProcessClaim() {
        var claims = 0
        val coordinator =
            StartupCoordinator(
                coldStart = true,
                startedFromQuickAction = true,
                reducedMotion = false,
                claimOncePerProcess = {
                    claims++
                    true
                },
            )

        assertEquals(
            "a bypassed start must not consume the once-per-process claim (that would suppress the next cold start)",
            0,
            claims,
        )
        assertEquals(
            "the quick-action start must still end up Bypassed",
            StartupState.Bypassed,
            coordinator.state.value,
        )
    }

    @Test
    fun startupPlaybackGuardClaimsAtMostOncePerProcess() {
        val first = StartupPlaybackGuard.claim()
        val second = StartupPlaybackGuard.claim()

        assertTrue("the first StartupPlaybackGuard.claim() in a process must win", first)
        assertFalse("every later StartupPlaybackGuard.claim() must be refused", second)
    }

    @Test
    fun playbackAllowedTruthTableCoversAllFlagCombinations() {
        val rows =
            listOf(
                Triple(true, false, false) to true,
                Triple(true, false, true) to false,
                Triple(true, true, false) to false,
                Triple(true, true, true) to false,
                Triple(false, false, false) to false,
                Triple(false, false, true) to false,
                Triple(false, true, false) to false,
                Triple(false, true, true) to false,
            )

        for ((flags, expected) in rows) {
            val (coldStart, quickAction, reducedMotion) = flags
            assertEquals(
                "playbackAllowed(coldStart=$coldStart, quickAction=$quickAction, " +
                    "reducedMotion=$reducedMotion) must be $expected",
                expected,
                StartupCoordinator.playbackAllowed(
                    coldStart = coldStart,
                    quickAction = quickAction,
                    reducedMotion = reducedMotion,
                ),
            )
        }
    }

    private fun playingCoordinator(claimOncePerProcess: () -> Boolean = { true }): StartupCoordinator =
        StartupCoordinator(
            coldStart = true,
            startedFromQuickAction = false,
            reducedMotion = false,
            claimOncePerProcess = claimOncePerProcess,
        )
}
