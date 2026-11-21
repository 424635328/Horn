package com.industrial.autohorn.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import com.industrial.autohorn.audio.HornType
import com.industrial.autohorn.presentation.HornUiState
import com.industrial.autohorn.ui.theme.AutoHornTheme
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 5 voices x 2 themes x 2 locales x 2 font scales x 2 layouts = 80 baselines.
 *
 * Record: ./gradlew :app:testDebugUnitTest --tests '*ScreenshotMatrixTest' \
 *   "-Proborazzi.test.record=true"
 * Verify: ./gradlew :app:testDebugUnitTest
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotMatrixTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = "w360dp-h800dp-xhdpi")
    fun compactMatrix() {
        captureMatrix(expanded = false)
    }

    @Test
    @Config(qualifiers = "w840dp-h800dp-xhdpi")
    fun expandedMatrix() {
        captureMatrix(expanded = true)
    }

    private fun captureMatrix(expanded: Boolean) {
        var type by mutableStateOf(HornType.CAR)
        var dark by mutableStateOf(true)
        var locale by mutableStateOf(Locale.US)
        var fontScale by mutableFloatStateOf(1f)

        composeRule.setContent {
            val base = LocalConfiguration.current
            val configuration = remember(locale) {
                Configuration(base).apply { setLocale(locale) }
            }
            val context = LocalContext.current
            val localizedContext = remember(locale, configuration) {
                context.createConfigurationContext(configuration)
            }
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides configuration,
                LocalDensity provides Density(density.density, fontScale),
            ) {
                AutoHornTheme(darkTheme = dark) {
                    HornScreen(
                        state = HornUiState(
                            selectedHorn = type,
                            preferencesLoaded = true,
                        ),
                        onPressStart = {},
                        onPressEnd = {},
                        onPressCancel = {},
                        onSelectHorn = {},
                        modifier = Modifier.fillMaxSize(),
                        windowWidthSizeClass = if (expanded) {
                            WindowWidthSizeClass.Expanded
                        } else {
                            WindowWidthSizeClass.Compact
                        },
                    )
                }
            }
        }

        for (voice in HornType.entries) {
            for (isDark in listOf(true, false)) {
                for (language in listOf(Locale.US, Locale.SIMPLIFIED_CHINESE)) {
                    for (scale in listOf(1f, 1.35f)) {
                        composeRule.runOnIdle {
                            type = voice
                            dark = isDark
                            locale = language
                            fontScale = scale
                        }
                        composeRule.waitForIdle()
                        val name = listOf(
                            voice.name.lowercase(Locale.ROOT),
                            if (isDark) "dark" else "light",
                            if (language == Locale.US) "en" else "zh",
                            "font${scale.toString().replace('.', '_')}",
                            if (expanded) "expanded" else "compact",
                        ).joinToString("_")
                        composeRule.onRoot().captureRoboImage(
                            "src/test/screenshots/$name.png",
                        )
                    }
                }
            }
        }
    }
}
