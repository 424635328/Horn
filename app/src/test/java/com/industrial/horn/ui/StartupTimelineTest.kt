package com.industrial.horn.ui

import com.industrial.horn.ui.theme.StartupStage
import com.industrial.horn.ui.theme.StartupTimeline
import com.industrial.horn.ui.theme.heartbeat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Frozen six-act timeline contract (docs/spec/03 §11.1). Every milestone curve is a pure function
 * of t; these assertions pin the act boundaries, the skill's pacing laws and the latch
 * relationships that the animation and the handoff depend on, so a future "small tweak" cannot
 * silently move a milestone.
 */
class StartupTimelineTest {
    @Test
    fun storyboardIsSixActsOnOneTenPointFourSecondClock() {
        assertEquals(10_400f, StartupTimeline.TOTAL_MS, 0.0001f)
        assertEquals(StartupStage.PULSE, StartupTimeline.stageOf(0f))
        assertEquals(StartupStage.PULSE, StartupTimeline.stageOf(1_149f))
        assertEquals(StartupStage.IDENTITY, StartupTimeline.stageOf(1_150f))
        assertEquals(StartupStage.IDENTITY, StartupTimeline.stageOf(4_199f))
        assertEquals(StartupStage.DRAWING, StartupTimeline.stageOf(4_200f))
        // The skill's own acceptance example: "5.5 s is in drawing".
        assertEquals(StartupStage.DRAWING, StartupTimeline.stageOf(5_500f))
        assertEquals(StartupStage.DRAWING, StartupTimeline.stageOf(7_199f))
        assertEquals(StartupStage.COLORIZE, StartupTimeline.stageOf(7_200f))
        assertEquals(StartupStage.PORTRAIT, StartupTimeline.stageOf(7_700f))
        assertEquals(StartupStage.PORTRAIT, StartupTimeline.stageOf(9_599f))
        assertEquals(StartupStage.SHUTTER, StartupTimeline.stageOf(9_600f))
        assertEquals(StartupStage.SHUTTER, StartupTimeline.stageOf(StartupTimeline.TOTAL_MS))
    }

    @Test
    fun perceivedProgressFollowsTheMilestoneCurve() {
        assertEquals(0f, StartupTimeline.progress(0f), 0.0001f)
        // The curve is deliberately not linear: 35% of the clock is 41% of the bar.
        assertEquals(0.41f, StartupTimeline.progress(0.35f * StartupTimeline.TOTAL_MS), 0.002f)
        assertEquals(0.76f, StartupTimeline.progress(0.56f * StartupTimeline.TOTAL_MS), 0.002f)
        assertEquals(1f, StartupTimeline.progress(0.77f * StartupTimeline.TOTAL_MS), 0.0001f)
        assertEquals(1f, StartupTimeline.progress(StartupTimeline.TOTAL_MS), 0.0001f)
        assertTrue(monotonic(0f, StartupTimeline.TOTAL_MS) { StartupTimeline.progress(it) })
    }

    @Test
    fun pulseRingsAreStaggeredOnTheSkillPhaseLaw() {
        val first = StartupTimeline.pulseRingPhase(0f, 0)
        val second = StartupTimeline.pulseRingPhase(0f, 1)
        val third = StartupTimeline.pulseRingPhase(0f, 2)
        assertEquals(0f, first, 0.0001f)
        assertEquals(0.33f, second, 0.0001f)
        assertEquals(0.66f, third, 0.0001f)
        // Rings expand monotonically inside one cycle and are gone before IDENTITY takes over.
        val inner = 10f
        val outer = 40f
        assertTrue(
            StartupTimeline.pulseRingRadius(100f, 0, inner, outer) >
                StartupTimeline.pulseRingRadius(40f, 0, inner, outer),
        )
        assertEquals(0f, StartupTimeline.pulseRingAlpha(StartupTimeline.PULSE_END_MS, 0), 0.0001f)
    }

    @Test
    fun identityBeatsStayInStoryboardOrder() {
        assertEquals(0f, StartupTimeline.identityAlpha(StartupTimeline.IDENTITY_START_MS), 0.0001f)
        assertEquals(1f, StartupTimeline.identityAlpha(1_650f), 0.0001f)

        assertEquals(0f, StartupTimeline.titleReveal(StartupTimeline.TITLE_START_MS), 0.0001f)
        assertEquals(1f, StartupTimeline.titleReveal(2_400f), 0.0001f)
        assertTrue(monotonic(StartupTimeline.TITLE_START_MS, 2_400f) { StartupTimeline.titleReveal(it) })

        // Boot log: three lines, 400 ms apart, each fading in over 140 ms.
        assertEquals(0f, StartupTimeline.bootLineAlpha(1_200f, 0), 0.0001f)
        assertEquals(1f, StartupTimeline.bootLineAlpha(1_340f, 0), 0.0001f)
        assertEquals(0f, StartupTimeline.bootLineAlpha(2_000f, 2), 0.0001f)
        assertEquals(1f, StartupTimeline.bootLineAlpha(2_140f, 2), 0.0001f)
    }

    @Test
    fun drawingAssemblesTheMarkLeftToRight() {
        assertEquals(0f, StartupTimeline.drawingProgress(StartupTimeline.DRAWING_START_MS), 0.0001f)
        assertEquals(1f, StartupTimeline.drawingProgress(StartupTimeline.DRAWING_END_MS), 0.0001f)

        // At 5.6 s the left edge is settled while the bell (right edge) has not started.
        val mid = 5_600f
        assertEquals(1f, StartupTimeline.fragmentArrival(mid, 0.10f), 0.0001f)
        assertEquals(0f, StartupTimeline.fragmentArrival(mid, 0.90f), 0.0001f)

        // Nothing arrived before the act, everything arrived after it.
        assertEquals(0f, StartupTimeline.fragmentArrival(StartupTimeline.DRAWING_START_MS, 0f), 0.0001f)
        assertEquals(1f, StartupTimeline.fragmentArrival(StartupTimeline.DRAWING_END_MS, 1f), 0.0001f)

        // Arrival is monotonically non-increasing in target x at any instant.
        var previous = StartupTimeline.fragmentArrival(5_000f, 0f)
        var x = 0f
        while (x <= 1f) {
            val current = StartupTimeline.fragmentArrival(5_000f, x)
            assertTrue("arrival must not increase with target x", current <= previous + 0.0001f)
            previous = current
            x += 0.02f
        }
    }

    @Test
    fun colorizeCrossfadesInsideItsOwnWindow() {
        assertEquals(0f, StartupTimeline.colorizeMix(StartupTimeline.COLORIZE_START_MS), 0.0001f)
        assertEquals(1f, StartupTimeline.colorizeMix(StartupTimeline.COLORIZE_END_MS), 0.0001f)
        assertTrue(
            monotonic(StartupTimeline.COLORIZE_START_MS, StartupTimeline.COLORIZE_END_MS) {
                StartupTimeline.colorizeMix(it)
            },
        )
        // After the cross-fade the line art is fully replaced by the colour mark.
        assertEquals(1f, StartupTimeline.colorizeMix(StartupTimeline.PORTRAIT_END_MS), 0.0001f)
    }

    @Test
    fun portraitBeatsRunOnceAndBreathe() {
        assertEquals(0f, StartupTimeline.captionAlpha(7_800f, 0), 0.0001f)
        assertEquals(1f, StartupTimeline.captionAlpha(7_940f, 0), 0.0001f)
        assertEquals(0f, StartupTimeline.captionAlpha(8_400f, 1), 0.0001f)
        assertEquals(1f, StartupTimeline.captionAlpha(8_540f, 1), 0.0001f)

        // One light sweep, and it is over before the shutter.
        assertEquals(0f, StartupTimeline.lightSweep(StartupTimeline.SWEEP_START_MS), 0.0001f)
        assertEquals(1f, StartupTimeline.lightSweep(8_800f), 0.0001f)

        // Particles do not exist before the portrait act and are gone before the handoff.
        assertEquals(0f, StartupTimeline.particleAlpha(StartupTimeline.COLORIZE_END_MS, 0), 0.0001f)
        assertEquals(0f, StartupTimeline.particleAlpha(StartupTimeline.PORTRAIT_END_MS, 0), 0.0001f)
        // Heartbeat is the breathing primitive: period-accurate, non-negative.
        assertEquals(0f, heartbeat(0f), 0.0001f)
        assertEquals(1f, heartbeat(540f), 0.0001f)
        assertTrue(heartbeat(900f) >= 0f)
    }

    @Test
    fun shutterCollapsesTheSceneAndRevealsTheHost() {
        assertEquals(0f, StartupTimeline.shutterCollapse(StartupTimeline.SHUTTER_START_MS), 0.0001f)
        assertEquals(1f, StartupTimeline.shutterCollapse(StartupTimeline.TOTAL_MS), 0.0001f)
        assertTrue(
            monotonic(StartupTimeline.SHUTTER_START_MS, StartupTimeline.TOTAL_MS) {
                StartupTimeline.shutterCollapse(it)
            },
        )

        // The boot surface dissolves first, so the real UI shows between the closing halves.
        assertEquals(1f, StartupTimeline.backgroundAlpha(StartupTimeline.SHUTTER_START_MS), 0.0001f)
        assertEquals(0f, StartupTimeline.backgroundAlpha(StartupTimeline.TOTAL_MS), 0.0001f)

        // The residual layer fades last and is gone exactly at the end of the clock.
        assertTrue(StartupTimeline.overlayAlpha(StartupTimeline.FADE_START_MS - 1f) > 0.99f)
        assertEquals(0f, StartupTimeline.overlayAlpha(StartupTimeline.TOTAL_MS), 0.0001f)

        // One-shot flash, single peak.
        assertEquals(1f, StartupTimeline.flashAlpha(StartupTimeline.FLASH_PEAK_MS), 0.0001f)
        assertEquals(0f, StartupTimeline.flashAlpha(StartupTimeline.TOTAL_MS), 0.0001f)
    }

    @Test
    fun revealLatchAndHapticSitOnFrozenMilestones() {
        // Reveal fires on the first frame of the shutter, hence the collapse starts at 0 there.
        assertEquals(StartupTimeline.SHUTTER_START_MS, StartupTimeline.REVEAL_LATCH_MS, 0.0001f)
        assertEquals(0f, StartupTimeline.revealProgress(StartupTimeline.REVEAL_LATCH_MS - 1f), 0.0001f)
        assertTrue(StartupTimeline.revealProgress(StartupTimeline.REVEAL_LATCH_MS + 1f) >= 0f)
        assertEquals(1f, StartupTimeline.revealProgress(StartupTimeline.TOTAL_MS), 0.0001f)

        // Exactly one haptic in the whole film, where the wordmark wipe completes.
        assertEquals(2_400f, StartupTimeline.HAPTIC_MS, 0.0001f)
        assertEquals(1f, StartupTimeline.titleReveal(StartupTimeline.HAPTIC_MS), 0.0001f)

        // Tap-to-skip collapse stays a hard 120 ms upper bound.
        assertEquals(120f, StartupTimeline.SKIP_COLLAPSE_MS, 0.0001f)
    }

    @Test
    fun reducedMotionFrameIsAFinishedColourMark() {
        val frame = StartupTimeline.REDUCED_MOTION_FRAME_MS
        assertEquals(StartupStage.PORTRAIT, StartupTimeline.stageOf(frame))
        // Colorised, captions up, shutter not started: a legible still, not a black screen.
        assertEquals(1f, StartupTimeline.colorizeMix(frame), 0.0001f)
        assertEquals(1f, StartupTimeline.captionAlpha(frame, 0), 0.0001f)
        assertEquals(0f, StartupTimeline.shutterCollapse(frame), 0.0001f)
        assertTrue(frame + StartupTimeline.REDUCED_MOTION_HOLD_MS < StartupTimeline.TOTAL_MS)
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
