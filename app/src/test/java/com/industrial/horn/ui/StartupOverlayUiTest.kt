package com.industrial.horn.ui

import android.content.res.Configuration
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import com.industrial.horn.ui.theme.HornTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Optical Wake overlay semantics and latch behaviour (docs/spec/03 §11.2 / §11.8).
 *
 * The overlay timeline is a pure function of one clock, so every test injects a frozen
 * [StartupOverlay.elapsedProvider] and advances virtual time explicitly: deterministic, no
 * wall-clock sleeps, no hidden scheduling. Latch expectations come straight from
 * [com.industrial.horn.ui.theme.StartupTimeline]: haptic at 560 ms (one-shot), reveal at 820 ms
 * (one-shot), onFinished at 1040 ms (one-shot) or 120 ms after `exiting` flips true.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StartupOverlayUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun overlayExposesSkipHintAccessibilitySemanticsInEnglish() {
        val recorder = Recorder()
        startOverlay(locale = Locale.US, recorder = recorder) { 0f }

        composeRule.onNodeWithTag(OVERLAY_TAG).assertExists(
            "the startup overlay must be tagged \"$OVERLAY_TAG\" so tests and TalkBack can target it",
        )
        val description = skipHintContentDescription()
        assertTrue(
            "the overlay must expose the English skip hint " +
                "(R.string.startup_skip_hint = \"Tap to skip\") as contentDescription, got $description",
            description.any { it.contains("Tap to skip") },
        )
    }

    @Test
    fun overlayExposesSkipHintAccessibilitySemanticsInChinese() {
        val recorder = Recorder()
        startOverlay(locale = Locale.SIMPLIFIED_CHINESE, recorder = recorder) { 0f }

        composeRule.onNodeWithTag(OVERLAY_TAG).assertExists(
            "the startup overlay must be tagged \"$OVERLAY_TAG\" so tests and TalkBack can target it",
        )
        val description = skipHintContentDescription()
        assertTrue(
            "the overlay must expose the Chinese skip hint " +
                "(R.string.startup_skip_hint = \"轻触跳过\") as contentDescription, got $description",
            description.any { it.contains("轻触跳过") },
        )
    }

    @Test
    fun pressAtTheTopLeftCornerSkipsOnTheFirstPressFrame() {
        val recorder = Recorder()
        startOverlay(locale = Locale.US, recorder = recorder) { 0f }

        composeRule.onRoot().performTouchInput {
            down(Offset(8f, 8f))
        }
        composeRule.waitForIdle()

        assertEquals(
            "a press at the top-left corner must skip immediately on the press frame, " +
                "without waiting for the finger to lift",
            1,
            recorder.skips.get(),
        )

        composeRule.onRoot().performTouchInput { up() }
        composeRule.waitForIdle()
        assertEquals(
            "the finger lift must not fire a second skip",
            1,
            recorder.skips.get(),
        )
    }

    @Test
    fun pressAtTheBottomRightCornerSkipsOnTheFirstPressFrame() {
        val recorder = Recorder()
        startOverlay(locale = Locale.US, recorder = recorder) { 0f }

        val size = composeRule.onRoot().fetchSemanticsNode().size
        composeRule.onRoot().performTouchInput {
            down(Offset(size.width - 8f, size.height - 8f))
        }
        composeRule.waitForIdle()

        assertEquals(
            "a press at the bottom-right corner must skip immediately on the press frame, " +
                "without waiting for the finger to lift",
            1,
            recorder.skips.get(),
        )

        composeRule.onRoot().performTouchInput { up() }
        composeRule.waitForIdle()
        assertEquals(
            "the finger lift must not fire a second skip",
            1,
            recorder.skips.get(),
        )
    }

    @Test
    fun revealLatchFiresExactlyOncePastTheRevealMilestone() {
        val recorder = Recorder()
        // t = 900 ms: past REVEAL_LATCH_MS (820), before TOTAL_MS (1040) — the reveal window.
        startOverlay(locale = Locale.US, recorder = recorder) { 900f }

        awaitCondition("onRevealLatched to fire at t=900 >= 820") {
            recorder.reveals.get() >= 1
        }

        // Keep the 8 ms latch polling loop running far past the milestone: one-shot semantics.
        advanceVirtualTimeBy(1_000)
        assertEquals(
            "the reveal latch is one-shot and must never repeat, even past the milestone",
            1,
            recorder.reveals.get(),
        )
    }

    @Test
    fun revealLatchStaysSilentBeforeTheRevealMilestone() {
        val recorder = Recorder()
        // t = 700 ms: past HAPTIC_MS (560) but before REVEAL_LATCH_MS (820) — negative control.
        startOverlay(locale = Locale.US, recorder = recorder) { 700f }

        advanceVirtualTimeBy(1_000)

        assertEquals(
            "before t=820 the reveal latch must stay silent (negative control for the 820 ms latch)",
            0,
            recorder.reveals.get(),
        )
        assertEquals(
            "the bloom haptic latch at t=560 must have fired exactly once at t=700",
            1,
            recorder.haptics.get(),
        )
    }

    @Test
    fun timelineEndFiresOnFinishedExactlyOnce() {
        val recorder = Recorder()
        // t = 2000 ms: past TOTAL_MS (1040) — the completion window.
        startOverlay(locale = Locale.US, recorder = recorder) { 2_000f }

        awaitCondition("onFinished to fire at t=2000 >= 1040") {
            recorder.finishes.get() >= 1
        }

        advanceVirtualTimeBy(1_000)
        assertEquals(
            "onFinished is a one-shot latch and must never repeat",
            1,
            recorder.finishes.get(),
        )
        assertEquals(
            "the reveal latch must have fired exactly once on the way past t=820",
            1,
            recorder.reveals.get(),
        )
    }

    @Test
    fun exitingFiresOnFinishedOnlyAfterTheSkipCollapseWindow() {
        val recorder = Recorder()
        var exiting by mutableStateOf(false)
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            HornTheme(darkTheme = true) {
                StartupOverlay(
                    onSkip = { recorder.skips.incrementAndGet() },
                    onRevealLatched = { recorder.reveals.incrementAndGet() },
                    onFinished = { recorder.finishes.incrementAndGet() },
                    exiting = exiting,
                    // Freeze the timeline at t=0: only the exit-collapse path may finish.
                    elapsedProvider = { 0f },
                    onBloomHaptic = { recorder.haptics.incrementAndGet() },
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle { exiting = true }
        composeRule.waitForIdle()

        // SKIP_COLLAPSE_MS = 120 is a hard upper bound: onFinished must wait for the collapse.
        advanceVirtualTimeBy(100)
        assertEquals(
            "onFinished must not fire before the 120 ms skip-collapse window elapses",
            0,
            recorder.finishes.get(),
        )

        awaitCondition("onFinished to fire after the 120 ms skip-collapse window") {
            recorder.finishes.get() >= 1
        }

        advanceVirtualTimeBy(500)
        assertEquals(
            "onFinished is one-shot across the exit path as well",
            1,
            recorder.finishes.get(),
        )
    }

    private class Recorder {
        val skips = AtomicInteger(0)
        val reveals = AtomicInteger(0)
        val finishes = AtomicInteger(0)
        val haptics = AtomicInteger(0)
    }

    private fun startOverlay(
        locale: Locale,
        recorder: Recorder,
        elapsedProvider: () -> Float,
    ) {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            val base = LocalConfiguration.current
            val configuration =
                remember(locale) {
                    Configuration(base).apply { setLocale(locale) }
                }
            val context = LocalContext.current
            val localizedContext =
                remember(locale, configuration) {
                    context.createConfigurationContext(configuration)
                }
            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides configuration,
            ) {
                HornTheme(darkTheme = true) {
                    StartupOverlay(
                        onSkip = { recorder.skips.incrementAndGet() },
                        onRevealLatched = { recorder.reveals.incrementAndGet() },
                        onFinished = { recorder.finishes.incrementAndGet() },
                        exiting = false,
                        elapsedProvider = elapsedProvider,
                        onBloomHaptic = { recorder.haptics.incrementAndGet() },
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun skipHintContentDescription(): List<String> =
        composeRule
            .onNodeWithTag(OVERLAY_TAG)
            .fetchSemanticsNode()
            .config[SemanticsProperties.ContentDescription]

    /**
     * Advance virtual time in 16 ms slices across BOTH clocks the effects may ride — the Compose
     * test frame clock and the Robolectric main looper scheduler — so resumed `delay` polling
     * loops and latch effects progress deterministically without wall-clock sleeps.
     */
    private fun advanceVirtualTimeBy(ms: Long) {
        var remaining = ms
        while (remaining > 0) {
            val step = minOf(remaining, 16L)
            composeRule.mainClock.advanceTimeBy(step)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(step))
            composeRule.waitForIdle()
            remaining -= step
        }
    }

    /** Poll in bounded virtual time until [condition] holds; fail loudly instead of hanging. */
    private fun awaitCondition(
        what: String,
        timeoutMs: Long = 2_000,
        condition: () -> Boolean,
    ) {
        var waited = 0L
        while (!condition()) {
            if (waited >= timeoutMs) {
                throw AssertionError(
                    "timed out after $waited ms of virtual time waiting for: $what",
                )
            }
            advanceVirtualTimeBy(16)
            waited += 16
        }
    }

    private companion object {
        const val OVERLAY_TAG = "startup_overlay"
    }
}
