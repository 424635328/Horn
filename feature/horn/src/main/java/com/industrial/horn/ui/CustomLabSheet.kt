package com.industrial.horn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.industrial.horn.audio.CustomHornProfile
import com.industrial.horn.feature.R
import com.industrial.horn.ui.theme.EngravedRule
import com.industrial.horn.ui.theme.HornRadii
import com.industrial.horn.ui.theme.HornText
import com.industrial.horn.ui.theme.InstrumentActionKey
import com.industrial.horn.ui.theme.InstrumentSlider
import com.industrial.horn.ui.theme.InstrumentToggle
import com.industrial.horn.ui.theme.LocalHornColors

/**
 * Pro Sound Lab. Every slider writes the real profile into the audio engine; Save only persists
 * the profile to DataStore, Reset restores and persists the factory preset.
 *
 * The sheet is a calibration panel: engraved captions, numbered plates, machined sliders and a
 * hold-to-preview key. No glass, no blur — the sheet is the same material as the instrument.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomLabSheet(
    profile: CustomHornProfile,
    accent: Color,
    onProfileChange: (CustomHornProfile) -> Unit,
    onPreviewStart: () -> Unit,
    onPreviewEnd: () -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalHornColors.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.panel,
        contentColor = colors.textPrimary,
        dragHandle = {
            Box(
                modifier =
                    Modifier
                        .padding(top = 10.dp)
                        .background(colors.hairlineStrong, RoundedCornerShape(2.dp))
                        .height(3.dp)
                        .fillMaxWidth(0.14f),
            )
        },
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 26.dp)
                    .testTag("custom_lab_sheet"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.custom_lab_title),
                        color = colors.textPrimary,
                        style =
                            TextStyle(
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = (-0.2).sp,
                            ),
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = stringResource(R.string.custom_lab_subtitle),
                        color = colors.textSecondary,
                        style = HornText.Data,
                        modifier = Modifier.widthIn(max = 280.dp),
                    )
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.custom_close), color = accent)
                }
            }

            EngravedRule(accent = accent)

            LabSlider(
                label = stringResource(R.string.custom_primary_frequency),
                value = profile.primaryFrequencyHz,
                valueRange = CustomHornProfile.MIN_FREQUENCY_HZ..CustomHornProfile.MAX_FREQUENCY_HZ,
                valueText = "${profile.primaryFrequencyHz.toInt()} Hz",
                accent = accent,
                onValueChange = { onProfileChange(profile.copy(primaryFrequencyHz = it)) },
            )
            LabSlider(
                label = stringResource(R.string.custom_secondary_ratio),
                value = profile.secondaryFrequencyRatio,
                valueRange = CustomHornProfile.MIN_RATIO..CustomHornProfile.MAX_RATIO,
                valueText = "× ${"%.2f".format(profile.secondaryFrequencyRatio)}",
                accent = accent,
                onValueChange = { onProfileChange(profile.copy(secondaryFrequencyRatio = it)) },
            )

            InstrumentToggle(
                checked = profile.dualTone,
                accent = accent,
                onCheckedChange = { onProfileChange(profile.copy(dualTone = it)) },
                label = stringResource(R.string.custom_dual_tone),
            )

            LabSlider(
                label = stringResource(R.string.custom_harmonics),
                value = profile.harmonicRichness,
                valueRange = 0f..1f,
                valueText = "${(profile.harmonicRichness * 100).toInt()}%",
                accent = accent,
                onValueChange = { onProfileChange(profile.copy(harmonicRichness = it)) },
            )
            LabSlider(
                label = stringResource(R.string.custom_attack),
                value = profile.attackMillis,
                valueRange = CustomHornProfile.MIN_ATTACK_MS..CustomHornProfile.MAX_ATTACK_MS,
                valueText = "${profile.attackMillis.toInt()} ms",
                accent = accent,
                onValueChange = { onProfileChange(profile.copy(attackMillis = it)) },
            )
            LabSlider(
                label = stringResource(R.string.custom_release),
                value = profile.releaseMillis,
                valueRange = CustomHornProfile.MIN_RELEASE_MS..CustomHornProfile.MAX_RELEASE_MS,
                valueText = "${profile.releaseMillis.toInt()} ms",
                accent = accent,
                onValueChange = { onProfileChange(profile.copy(releaseMillis = it)) },
            )
            LabSlider(
                label = stringResource(R.string.custom_saturation),
                value = profile.saturation,
                valueRange = 0f..1f,
                valueText = "${(profile.saturation * 100).toInt()}%",
                accent = accent,
                onValueChange = { onProfileChange(profile.copy(saturation = it)) },
            )

            Spacer(modifier = Modifier.height(4.dp))
            val previewDescription = stringResource(R.string.custom_preview_description)
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .semantics {
                            role = Role.Button
                            contentDescription = previewDescription
                        }.pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume()
                                onPreviewStart()
                                try {
                                    waitForUpOrCancellation()
                                } finally {
                                    onPreviewEnd()
                                }
                            }
                        },
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(accent.copy(alpha = if (colors.isDark) 0.14f else 0.08f))
                            .border(
                                width = 1.dp,
                                color = accent,
                                shape = RoundedCornerShape(HornRadii.Key),
                            ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.custom_preview),
                        color = accent,
                        style = HornText.PlateLabel,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                InstrumentActionKey(
                    label = stringResource(R.string.custom_reset),
                    accent = accent,
                    onClick = onReset,
                    modifier = Modifier.weight(1f),
                )
                InstrumentActionKey(
                    label = stringResource(R.string.custom_save),
                    accent = accent,
                    onClick = onSave,
                    filled = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun LabSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueText: String,
    accent: Color,
    onValueChange: (Float) -> Unit,
) {
    InstrumentSlider(
        value = value,
        valueRange = valueRange,
        onValueChange = onValueChange,
        accent = accent,
        label = label,
        valueText = valueText,
        modifier = Modifier.fillMaxWidth(),
    )
}
