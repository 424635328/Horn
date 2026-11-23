package com.industrial.horn.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.industrial.horn.ui.theme.HornTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Frozen-clock calibration frames: t = 120 (WAKE) / 300 (FOCUS) / 820 (REVEAL) / 920 (SHUTTER) ×
 * dark/light themes = 8 baselines. Every frame is a pure function f(t) of the single wake clock,
 * so the deterministic injected `elapsedProvider` freezes each frame byte-identically
 * (docs/spec/03 §11.1 / §11.7 / §11.8).
 *
 * Roborazzi isolation from the existing 80 main-UI baselines: those live under the
 * `{voice}_{theme}_{locale}_{font}_{layout}` namespace and are naturally isolated — the
 * [StartupOverlay] is mounted only at the MainActivity layer (app/src/main/java/com/industrial/
 * horn/MainActivity.kt), so HornScreen screenshots never pass through it. This suite keeps the
 * overlay rendered standalone (no HornScreen) and uses its own `startup_wake_*` file namespace,
 * which locks that isolation in: no shared files, no shared composition.
 *
 * Record: ./gradlew :app:testDebugUnitTest --tests '*StartupOverlaySnapshotTest' \
 *   "-Proborazzi.test.record=true"
 * Verify: ./gradlew :app:testDebugUnitTest --tests '*StartupOverlay*'
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StartupOverlaySnapshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun startupWakeFrozenFrameMatrix() {
        var dark by mutableStateOf(true)
        var elapsedMs by mutableIntStateOf(0)

        // Frozen clock: disable auto-advance so nothing but the injected elapsed value can move.
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            HornTheme(darkTheme = dark) {
                StartupOverlay(
                    onSkip = {},
                    onRevealLatched = {},
                    onFinished = {},
                    exiting = false,
                    elapsedProvider = { elapsedMs.toFloat() },
                )
            }
        }

        for ((frame, t) in FRAMES) {
            for ((themeName, isDark) in listOf("dark" to true, "light" to false)) {
                composeRule.runOnIdle {
                    dark = isDark
                    elapsedMs = t
                }
                // With autoAdvance off, a state write needs explicit frames before the
                // recomposition (theme!) lands; the draw layer alone would keep the old colors.
                repeat(3) { composeRule.mainClock.advanceTimeByFrame() }
                composeRule.waitForIdle()
                composeRule.onRoot().captureRoboImage(
                    "src/test/screenshots/startup_wake_${frame}_$themeName.png",
                )
            }
        }
    }

    private companion object {
        /**
         * One frozen frame per act: WAKE (reticle + scanlines), FOCUS (graduations + sweep),
         * BLOOM (pressure arcs + wordmark wipe), SHUTTER (vertical masks + centre line).
         * Each is a pure f(t), so the injected clock alone decides the pixels.
         */
        val FRAMES =
            listOf(
                "wake" to 120,
                "focus" to 300,
                "reveal" to 820,
                "shutter" to 920,
            )
    }
}
