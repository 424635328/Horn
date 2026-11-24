package com.industrial.horn.ui

import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.industrial.horn.ui.theme.HornTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Frozen-clock frames, one per act of the film, × dark/light themes = 12 baselines. Every frame is
 * a pure function f(t) of the single clock, so the deterministic injected `elapsedProvider` freezes
 * each frame byte-identically (docs/spec/03 §11.1 / §11.7 / §11.8).
 *
 * Frames are chosen to prove the *latter half* of the storyboard exists, which is exactly what the
 * previous revision was missing:
 *
 *  - `pulse`    0.7 s  — phase-loop rings + vents
 *  - `identity` 2.4 s  — dial locked on, wordmark wiped, boot log complete
 *  - `drawing`  5.6 s  — contour fragments mid-flight, left edge settled, bell still in the air
 *  - `colorize` 7.4 s  — line art cross-fading into the titanium/amber mark
 *  - `portrait` 8.6 s  — colourised mark, captions, particles, light sweep done
 *  - `shutter`  9.9 s  — scene collapsing onto the optical axis, seam beam pinching. The band the
 *    collapsing scene uncovers is the bare test window here; in the app the host interface shows
 *    there, which is the handoff this act exists for.
 *
 * Roborazzi isolation from the existing 80 main-UI baselines: those live under the
 * `{voice}_{theme}_{locale}_{font}_{layout}` namespace and are naturally isolated — the
 * [StartupOverlay] is mounted only at the MainActivity layer, so HornScreen screenshots never pass
 * through it. This suite keeps the overlay standalone and uses its own `startup_wake_*` namespace.
 *
 * Record (Linux renderer only, see .github/workflows/update-screenshots.yml):
 *   ./gradlew recordRoborazziDebug
 * Verify: ./gradlew :app:testDebugUnitTest --tests '*StartupOverlay*'
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StartupOverlaySnapshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun startupFilmFrozenFrameMatrix() {
        var dark by mutableStateOf(true)
        var elapsedMs by mutableIntStateOf(0)
        // 开屏素材直接注入：定格帧不能等异步解码（否则每帧抓到什么都没保证）。
        val hero = heroFixture()
        val heroImage = heroImageFixture()

        // Frozen clock: disable auto-advance so nothing but the injected clock can move.
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            HornTheme(darkTheme = dark) {
                StartupOverlay(
                    onSkip = {},
                    onRevealLatched = {},
                    onFinished = {},
                    exiting = false,
                    elapsedProvider = { elapsedMs.toFloat() },
                    heroOverride = hero,
                    heroImageOverride = heroImage,
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

    /** 从仓库源文件读素材与参考图：不依赖资源合并顺序，定格可复现。 */
    private fun heroFixture(): StartupHero {
        val file = File("../feature/horn/src/main/assets/startup/hero_contours.bin")
        return StartupHero.parse(file.readBytes())
    }

    /**
     * 参考图直接注入：定格帧不能等异步解码（否则每帧抓到什么都没保证）。
     * Robolectric 的 decodeFile 返回 null，但 decodeByteArray 走真实解码器，像素与设备一致。
     */
    private fun heroImageFixture(): ImageBitmap {
        val drawable = File("../feature/horn/src/main/res/drawable-nodpi/startup_hero.png")
        check(drawable.isFile) { "startup_hero.png is missing: " + drawable.absolutePath }
        val bytes = drawable.readBytes()
        val bitmap =
            checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) {
                "cannot decode startup_hero.png (" + bytes.size + " bytes)"
            }
        return bitmap.asImageBitmap()
    }

    private companion object {
        /**
         * One frozen frame per act. Each is a pure f(t), so the injected clock alone decides the
         * pixels; the values sit inside their act, not on a boundary.
         */
        val FRAMES =
            listOf(
                "pulse" to 700,
                "identity" to 2_400,
                "drawing" to 5_600,
                "colorize" to 7_400,
                "portrait" to 8_600,
                "shutter" to 9_900,
            )
    }
}
