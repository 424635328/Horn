package com.industrial.horn.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.industrial.horn.designsystem.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Frozen instrument states: `idle` (rms 0, expression 0), `pressure` (rms 0.45, expression 0) and
 * `peak` (rms 0.92, expression +0.62), each in both palettes = 6 baselines.
 *
 * Why this suite exists: the 80-shot main matrix only ever captures the idle screen, so the two
 * visuals that define the instrument while it is held — the 60-tick pressure ring lighting up and
 * the needle deflecting — had no pixel-level regression cover. Everything here is a pure function
 * of two injected providers, so the frames are deterministic without a clock or an audio engine.
 *
 * Record: ./gradlew :app:testDebugUnitTest --tests '*InstrumentDialSnapshotTest' \
 *   "-Proborazzi.test.record=true"
 * Verify: ./gradlew verifyRoborazziDebug
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InstrumentDialSnapshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun dialPressureAndNeedleMatrix() {
        var dark by mutableStateOf(true)
        var rms by mutableFloatStateOf(0f)
        var expression by mutableFloatStateOf(0f)

        composeRule.setContent {
            HornTheme(darkTheme = dark) {
                val colors = LocalHornColors.current
                val accent = HornInstrumentAccents.Car.resolve(dark)
                Box(
                    modifier = Modifier.fillMaxSize().background(colors.canvas),
                    contentAlignment = Alignment.Center,
                ) {
                    InstrumentDial(
                        diameter = 300.dp,
                        accent = accent,
                        active = rms > 0f,
                        pressed = rms > 0f,
                        rmsProvider = { rms },
                        expressionProvider = { expression },
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                painter = painterResource(R.drawable.ic_type_car),
                                contentDescription = null,
                                tint = accent,
                                modifier = Modifier.size(58.dp),
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = if (rms > 0f) "HONK" else "HOLD",
                                color = colors.textPrimary,
                                style = HornText.PlateLabel,
                            )
                            Spacer(modifier = Modifier.height(5.dp))
                            Text(
                                text =
                                    if (rms > 0f) {
                                        "EXP +${(expression * 100).toInt().toString().padStart(2, '0')}"
                                    } else {
                                        "EXP ±00"
                                    },
                                color = if (rms > 0f) accent else colors.textTertiary,
                                style = HornText.Data,
                            )
                        }
                    }
                }
            }
        }

        val states =
            listOf(
                Triple("idle", 0f, 0f),
                Triple("pressure", 0.45f, 0f),
                Triple("peak", 0.92f, 0.62f),
            )
        for (state in states) {
            for ((themeName, isDark) in listOf("dark" to true, "light" to false)) {
                composeRule.runOnIdle {
                    dark = isDark
                    rms = state.second
                    expression = state.third
                }
                composeRule.waitForIdle()
                composeRule.onRoot().captureRoboImage(
                    "src/test/screenshots/dial_${state.first}_$themeName.png",
                )
            }
        }
    }
}
