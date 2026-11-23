package com.industrial.horn.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Theme wiring: `HornTheme(darkTheme = …)` must install the matching cockpit token set *and* map
 * the Material 3 scheme onto the same canvas/panel colours, otherwise stock M3 components would
 * paint a different material than the hand-drawn instrument.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CockpitThemeWiringTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun darkThemeInstallsCarbonTokens() {
        var tokens: HornColors? = null
        var schemeBackground = androidx.compose.ui.graphics.Color.Unspecified

        composeRule.setContent {
            HornTheme(darkTheme = true) {
                tokens = LocalHornColors.current
                schemeBackground = MaterialTheme.colorScheme.background
            }
        }
        composeRule.waitForIdle()

        assertEquals(CarbonColors, tokens)
        assertEquals(CarbonColors.canvas, schemeBackground)
    }

    @Test
    fun lightThemeInstallsDaylightTokens() {
        var tokens: HornColors? = null
        var schemeBackground = androidx.compose.ui.graphics.Color.Unspecified

        composeRule.setContent {
            HornTheme(darkTheme = false) {
                tokens = LocalHornColors.current
                schemeBackground = MaterialTheme.colorScheme.background
            }
        }
        composeRule.waitForIdle()

        assertEquals(DaylightColors, tokens)
        assertEquals(DaylightColors.canvas, schemeBackground)
    }
}
