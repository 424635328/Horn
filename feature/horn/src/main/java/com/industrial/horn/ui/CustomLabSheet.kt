package com.industrial.horn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.industrial.horn.audio.CustomHornProfile
import com.industrial.horn.feature.R
import com.industrial.horn.ui.theme.HornCream
import com.industrial.horn.ui.theme.HornInk

/**
 * Pro Sound Lab. All sliders write the real profile into the audio engine; Save only persists the
 * profile to DataStore, Reset restores and persists the factory preset.
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
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = LabSurface,
        contentColor = HornCream,
        dragHandle = {
            Surface(
                modifier =
                    Modifier
                        .padding(top = 10.dp)
                        .background(HornCream.copy(alpha = 0.25f), RoundedCornerShape(50))
                        .height(4.dp)
                        .fillMaxWidth(0.18f),
                color = Color.Transparent,
            ) {}
        },
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp)
                    .padding(bottom = 28.dp)
                    .testTag("custom_lab_sheet"),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.custom_lab_title),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = HornCream,
                    )
                    Text(
                        text = stringResource(R.string.custom_lab_subtitle),
                        color = LabMuted,
                        fontSize = 12.sp,
                    )
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.custom_close), color = accent)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.custom_dual_tone),
                    color = HornCream,
                    fontSize = 14.sp,
                )
                Switch(
                    checked = profile.dualTone,
                    onCheckedChange = { onProfileChange(profile.copy(dualTone = it)) },
                )
            }
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

            Spacer(modifier = Modifier.height(14.dp))
            val previewDescription = stringResource(R.string.custom_preview_description)
            Surface(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(54.dp)
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
                shape = RoundedCornerShape(50),
                color = accent,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = stringResource(R.string.custom_preview),
                        color = HornInk,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.6.sp,
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onReset,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.custom_reset))
                }
                Surface(
                    modifier =
                        Modifier
                            .weight(1f)
                            .height(40.dp),
                    shape = RoundedCornerShape(50),
                    color = accent,
                ) {
                    TextButton(
                        onClick = onSave,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.custom_save),
                            color = HornInk,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
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
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = label, color = LabMuted, fontSize = 12.sp)
            Text(text = valueText, color = accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = value.coerceIn(valueRange),
            onValueChange = onValueChange,
            valueRange = valueRange,
        )
    }
}

private val LabSurface = Color(0xFF101519)
private val LabMuted = Color(0xFFA8B0B6)
