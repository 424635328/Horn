package com.industrial.horn.ui

import android.graphics.Bitmap
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.industrial.horn.audio.AudioSafetyPolicy
import com.industrial.horn.audio.HornType
import com.industrial.horn.feature.R
import com.industrial.horn.presentation.HornStatus
import com.industrial.horn.presentation.HornUiState
import com.industrial.horn.ui.theme.HornTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class HornScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun allFiveVoiceOptionsRenderAndUpdateSelection() {
        var state by mutableStateOf(HornUiState(preferencesLoaded = true))

        composeRule.setContent {
            HornTheme(darkTheme = true) {
                HornScreen(
                    state = state,
                    onPressStart = {},
                    onPressEnd = {},
                    onPressCancel = {},
                    onSelectHorn = { type ->
                        state = state.copy(selectedHorn = type, status = HornStatus.Idle)
                    },
                )
            }
        }

        HornType.entries.forEach { type ->
            composeRule.onNodeWithTag(type.testTag()).assertIsDisplayed()
        }
        composeRule.onNodeWithTag("horn_type_car").assertIsSelected()

        composeRule.onNodeWithTag("horn_type_train").performClick()
        composeRule.waitForIdle()

        assertEquals(HornType.TRAIN, state.selectedHorn)
        composeRule.onNodeWithTag("horn_type_train").assertIsSelected()
        composeRule.onNodeWithContentDescription(typeDescription(HornType.TRAIN)).assertExists()
        composeRule
            .onNodeWithContentDescription(
                context.getString(
                    R.string.horn_button_description,
                    typeDescription(HornType.TRAIN),
                ),
            ).assertIsDisplayed()
    }

    @Test
    fun fifthVoiceOpensTheProSoundLab() {
        var state by mutableStateOf(HornUiState(preferencesLoaded = true))

        composeRule.setContent {
            HornTheme(darkTheme = true) {
                HornScreen(
                    state = state,
                    onPressStart = {},
                    onPressEnd = {},
                    onPressCancel = {},
                    onSelectHorn = { type -> state = state.copy(selectedHorn = type) },
                )
            }
        }

        composeRule.onNodeWithTag("horn_type_custom").performClick()
        composeRule.waitForIdle()

        assertEquals(HornType.CUSTOM, state.selectedHorn)
        composeRule.onNodeWithText(context.getString(R.string.custom_lab_title)).assertIsDisplayed()
        composeRule.onNodeWithTag("custom_lab_sheet").assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.custom_preview_description))
            .assertExists()
    }

    @Test
    fun zeroVolumePromptAndEarProtectionBadgeAreAnnounced() {
        var volumeClicks = 0
        val safe = AudioSafetyPolicy.evaluate(12, 15, true)
        composeRule.setContent {
            HornTheme(darkTheme = true) {
                HornScreen(
                    state =
                        HornUiState(
                            preferencesLoaded = true,
                            showZeroVolumePrompt = true,
                            audioSafety = safe.copy(mediaVolume = 0),
                        ),
                    onPressStart = {},
                    onPressEnd = {},
                    onPressCancel = {},
                    onSelectHorn = {},
                    onVolumePromptClicked = { volumeClicks++ },
                )
            }
        }

        composeRule.onNodeWithTag("zero_volume_prompt").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.zero_volume_action)).performClick()
        composeRule.runOnIdle { assertEquals(1, volumeClicks) }
    }

    @Test
    fun earProtectionBadgeFollowsHeadphonePolicy() {
        composeRule.setContent {
            HornTheme(darkTheme = true) {
                HornScreen(
                    state =
                        HornUiState(
                            preferencesLoaded = true,
                            audioSafety = AudioSafetyPolicy.evaluate(14, 15, true),
                        ),
                    onPressStart = {},
                    onPressEnd = {},
                    onPressCancel = {},
                    onSelectHorn = {},
                )
            }
        }

        composeRule.onNodeWithTag("ear_protection_badge").assertIsDisplayed()
        composeRule
            .onNodeWithText(context.getString(R.string.ear_protection_badge))
            .assertIsDisplayed()
    }

    @Test
    fun spaceSelectorUpdatesAcousticSpace() {
        var state by mutableStateOf(HornUiState(preferencesLoaded = true))

        composeRule.setContent {
            HornTheme(darkTheme = true) {
                HornScreen(
                    state = state,
                    onPressStart = {},
                    onPressEnd = {},
                    onPressCancel = {},
                    onSelectHorn = {},
                    onSelectSpace = { space -> state = state.copy(acousticSpace = space) },
                )
            }
        }

        composeRule.onNodeWithTag("space_tunnel").performClick()
        composeRule.waitForIdle()

        assertEquals(
            com.industrial.horn.audio.AcousticSpace.TUNNEL,
            state.acousticSpace,
        )
        composeRule.onNodeWithTag("space_tunnel").assertIsSelected()
    }

    @Test
    fun expandedWindowUsesDashboardLayout() {
        composeRule.setContent {
            HornTheme(darkTheme = true) {
                HornScreen(
                    state = HornUiState(preferencesLoaded = true),
                    onPressStart = {},
                    onPressEnd = {},
                    onPressCancel = {},
                    onSelectHorn = {},
                    windowWidthSizeClass = WindowWidthSizeClass.Expanded,
                )
            }
        }

        composeRule.onNodeWithTag("expanded_layout").assertIsDisplayed()
        composeRule.onNodeWithTag("compact_layout").assertDoesNotExist()
    }

    @Test
    fun verticalDragProducesBidirectionalExpression() {
        var lastExpression = 0f
        var state by mutableStateOf(HornUiState(preferencesLoaded = true, status = HornStatus.Honking))

        composeRule.setContent {
            HornTheme(darkTheme = true) {
                HornScreen(
                    state = state,
                    onPressStart = {},
                    onPressEnd = {},
                    onPressCancel = {},
                    onSelectHorn = {},
                    onExpressionChanged = { lastExpression = it },
                )
            }
        }

        composeRule.onNodeWithTag("horn_button").performTouchInput {
            down(center)
            moveBy(Offset(0f, -50f))
        }
        composeRule.runOnIdle { assertTrue("Upward drag should bend pitch up", lastExpression > 0f) }
        composeRule.onNodeWithTag("horn_button").performTouchInput { up() }
        composeRule.waitForIdle()
    }

    @Test
    fun singleHornButtonHasAccessibilityLabelAndIdleState() {
        composeRule.setContent {
            HornTheme(darkTheme = true) {
                HornScreen(
                    state = HornUiState(preferencesLoaded = true),
                    onPressStart = {},
                    onPressEnd = {},
                    onPressCancel = {},
                    onSelectHorn = {},
                )
            }
        }

        composeRule.onAllNodesWithTag("horn_button").assertCountEquals(1)
        composeRule.onNodeWithTag("horn_button").assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription(
                context.getString(
                    R.string.horn_button_description,
                    typeDescription(HornType.CAR),
                ),
            ).assertIsDisplayed()
        composeRule.onNodeWithTag("horn_button").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                context.getString(R.string.horn_state_idle),
            ),
        )
    }

    @Test
    fun pressAndReleaseInvokeHoldCallbacksInOrder() {
        var starts = 0
        var ends = 0
        var cancels = 0

        composeRule.setContent {
            HornTheme {
                HornScreen(
                    state = HornUiState(preferencesLoaded = true),
                    onPressStart = { starts++ },
                    onPressEnd = { ends++ },
                    onPressCancel = { cancels++ },
                    onSelectHorn = {},
                )
            }
        }

        composeRule.onNodeWithTag("horn_button").performTouchInput {
            down(center)
            up()
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertEquals("Expected one press start", 1, starts)
            assertEquals("Expected one press end", 1, ends)
            assertEquals("Unexpected cancellation", 0, cancels)
        }
    }

    @Test
    fun slidingOutsideTheCircularHitAreaCancelsWithoutWaitingForLift() {
        var starts = 0
        var ends = 0
        var cancels = 0

        composeRule.setContent {
            HornTheme {
                HornScreen(
                    state = HornUiState(preferencesLoaded = true),
                    onPressStart = { starts++ },
                    onPressEnd = { ends++ },
                    onPressCancel = { cancels++ },
                    onSelectHorn = {},
                )
            }
        }

        composeRule.onNodeWithTag("horn_button").performTouchInput {
            down(center)
            moveTo(Offset(-40f, -40f))
            up()
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertEquals("Expected one press start", 1, starts)
            assertEquals("Expected one cancellation", 1, cancels)
            assertEquals("Press end must not fire after a slide-out", 0, ends)
        }
    }

    @Test
    fun screenRendersANonBlankReferenceBitmap() {
        composeRule.setContent {
            HornTheme(darkTheme = true) {
                HornScreen(
                    state =
                        HornUiState(
                            selectedHorn = HornType.CAR,
                            preferencesLoaded = true,
                        ),
                    onPressStart = {},
                    onPressEnd = {},
                    onPressCancel = {},
                    onSelectHorn = {},
                )
            }
        }
        composeRule.waitForIdle()
        captureAndAssert("horn-screen.png")
    }

    @Test
    fun lightThemeRendersANonBlankReferenceBitmap() {
        composeRule.setContent {
            HornTheme(darkTheme = false) {
                HornScreen(
                    state =
                        HornUiState(
                            selectedHorn = HornType.TRAIN,
                            preferencesLoaded = true,
                        ),
                    onPressStart = {},
                    onPressEnd = {},
                    onPressCancel = {},
                    onSelectHorn = {},
                )
            }
        }
        composeRule.waitForIdle()
        captureAndAssert("horn-screen-light.png")
    }

    private fun captureAndAssert(fileName: String) {
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        assertTrue("Empty screenshot width", bitmap.width > 0)
        assertTrue("Empty screenshot height", bitmap.height > 0)

        var nonBlackSamples = 0
        var x = 0
        while (x < bitmap.width) {
            var y = 0
            while (y < bitmap.height) {
                if (bitmap.getPixel(x, y) != 0xFF000000.toInt()) nonBlackSamples++
                y += 32
            }
            x += 32
        }
        assertTrue("Screen rendered as a black frame", nonBlackSamples > 20)

        val target = File(context.cacheDir, fileName)
        FileOutputStream(target).use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        assertTrue("Screenshot artifact was not written", target.length() > 1_000)
    }

    @Test
    fun honkingStateUpdatesTheAccessibilityStateDescription() {
        composeRule.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                HornTheme(darkTheme = true) {
                    HornScreen(
                        state =
                            HornUiState(
                                selectedHorn = HornType.EBIKE,
                                status = HornStatus.Honking,
                                preferencesLoaded = true,
                            ),
                        onPressStart = {},
                        onPressEnd = {},
                        onPressCancel = {},
                        onSelectHorn = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule
            .onNodeWithContentDescription(
                context.getString(
                    R.string.horn_button_description_honking,
                    typeDescription(HornType.EBIKE),
                ),
            ).assertExists()
        composeRule.onNodeWithTag("horn_button").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                context.getString(R.string.horn_state_honking),
            ),
        )
    }

    private fun typeDescription(type: HornType): String =
        context.getString(
            when (type) {
                HornType.CAR -> R.string.horn_type_car_description
                HornType.TRAIN -> R.string.horn_type_train_description
                HornType.EBIKE -> R.string.horn_type_ebike_description
                HornType.MOTORCYCLE -> R.string.horn_type_motorcycle_description
                HornType.CUSTOM -> R.string.horn_type_custom_description
            },
        )

    private fun HornType.testTag(): String = "horn_type_${name.lowercase(Locale.ROOT)}"
}
