package com.industrial.horn.ui

import com.industrial.horn.ui.theme.StartupTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Frozen timeline contract (docs/spec/03 §11.1). Every milestone curve is a pure function of t;
 * these assertions pin the endpoints and the monotonicity that the animation and the handoff
 * latches depend on, so a future "small tweak" cannot silently move a milestone.
 */
class StartupTimelineTest {
    @Test
    fun wakeRampEndpoints() {
        assertEquals(0f, StartupTimeline.wakeAlpha(0f), 0.0001f)
        assertEquals(1f, StartupTimeline.wakeAlpha(StartupTimeline.WAKE_END_MS), 0.0001f)
        assertEquals(0.62f, StartupTimeline.orbScale(0f), 0.0001f)
        assertEquals(0.72f, StartupTimeline.orbScale(StartupTimeline.WAKE_END_MS), 0.0001f)
    }

    @Test
    fun reticleDrawsInDuringWake() {
        assertEquals(0f, StartupTimeline.reticleProgress(0f), 0.0001f)
        assertEquals(1f, StartupTimeline.reticleProgress(220f), 0.0001f)
        assertTrue(monotonic(0f, 220f) { StartupTimeline.reticleProgress(it) })
    }

    @Test
    fun bootLogLinesAreStaged() {
        assertEquals(0f, StartupTimeline.bootLineAlpha(StartupTimeline.BOOT_LINE_START_MS, 0), 0.0001f)
        assertEquals(1f, StartupTimeline.bootLineAlpha(320f, 0), 0.0001f)
        // The third line must still be invisible while the first two are up.
        assertEquals(0f, StartupTimeline.bootLineAlpha(600f, 2), 0.0001f)
        assertEquals(1f, StartupTimeline.bootLineAlpha(940f, 2), 0.0001f)
    }

    @Test
    fun wordmarkWipeCompletesAtTheRevealLatch() {
        assertEquals(0f, StartupTimeline.wordmarkReveal(540f), 0.0001f)
        assertEquals(1f, StartupTimeline.wordmarkReveal(StartupTimeline.REVEAL_LATCH_MS), 0.0001f)
        assertTrue(monotonic(540f, StartupTimeline.REVEAL_LATCH_MS) { StartupTimeline.wordmarkReveal(it) })
    }

    @Test
    fun syncProgressRunsZeroToOne() {
        assertEquals(0f, StartupTimeline.progress(0f), 0.0001f)
        assertEquals(1f, StartupTimeline.progress(StartupTimeline.TOTAL_MS), 0.0001f)
        assertEquals(0.5f, StartupTimeline.progress(StartupTimeline.TOTAL_MS / 2f), 0.0001f)
    }

    @Test
    fun shuttersCloseOnlyAfterTheRevealLatch() {
        assertEquals(0f, StartupTimeline.shutterProgress(StartupTimeline.REVEAL_LATCH_MS), 0.0001f)
        assertEquals(1f, StartupTimeline.shutterProgress(StartupTimeline.SHUTTER_END_MS), 0.0001f)
        assertTrue(
            "shutters must stay shut until the overlay leaves",
            StartupTimeline.shutterProgress(StartupTimeline.TOTAL_MS) >= 1f,
        )
        assertTrue(
            monotonic(StartupTimeline.REVEAL_LATCH_MS, StartupTimeline.SHUTTER_END_MS) {
                StartupTimeline.shutterProgress(it)
            },
        )
    }

    @Test
    fun flashIsASinglePulse() {
        assertEquals(0f, StartupTimeline.flashAlpha(StartupTimeline.FLASH_START_MS), 0.0001f)
        assertEquals(1f, StartupTimeline.flashAlpha(StartupTimeline.FLASH_PEAK_MS), 0.0001f)
        assertEquals(0f, StartupTimeline.flashAlpha(StartupTimeline.TOTAL_MS), 0.0001f)
    }

    @Test
    fun overlayFadesOutOnlyAtTheVeryEnd() {
        assertEquals(1f, StartupTimeline.overlayAlpha(StartupTimeline.FADE_START_MS), 0.0001f)
        assertEquals(0f, StartupTimeline.overlayAlpha(StartupTimeline.TOTAL_MS), 0.0001f)
        // The shutters do the handoff, so the layer must still be opaque while they close.
        assertTrue(StartupTimeline.overlayAlpha(StartupTimeline.FADE_START_MS - 1f) > 0.99f)
    }

    @Test
    fun centerLineShrinksToNothing() {
        assertEquals(1f, StartupTimeline.centerLineWidth(StartupTimeline.SHUTTER_END_MS), 0.0001f)
        assertEquals(0f, StartupTimeline.centerLineWidth(StartupTimeline.TOTAL_MS), 0.0001f)
    }

    private fun monotonic(
        from: Float,
        to: Float,
        value: (Float) -> Float,
    ): Boolean {
        var previous = value(from)
        var t = from
        while (t <= to) {
            val current = value(t)
            if (current + 0.0001f < previous) return false
            previous = current
            t += 8f
        }
        return true
    }
}
