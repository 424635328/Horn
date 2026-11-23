package com.industrial.horn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.industrial.horn.audio.AcousticSpace
import com.industrial.horn.feature.R
import com.industrial.horn.ui.theme.HornRadii
import com.industrial.horn.ui.theme.HornText
import com.industrial.horn.ui.theme.InstrumentToggle
import com.industrial.horn.ui.theme.LocalHornColors
import com.industrial.horn.ui.theme.TactileKeycap
import com.industrial.horn.ui.theme.keycapLabelColor
import java.util.Locale

/**
 * Four-way acoustic space row on the shared keycap contract. One row of four, never a carousel:
 * the whole point of the row is that all four rooms are visible at once.
 */
@Composable
fun AcousticSpaceSelector(
    selected: AcousticSpace,
    accent: Color,
    onSelect: (AcousticSpace) -> Unit,
    modifier: Modifier = Modifier,
) {
    val descriptions = AcousticSpace.entries.associateWith { it.accessibilityName() }
    val colors = LocalHornColors.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AcousticSpace.entries.forEach { space ->
            val isSelected = space == selected
            TactileKeycap(
                selected = isSelected,
                accent = accent,
                onClick = { onSelect(space) },
                role = Role.RadioButton,
                contentDescription = descriptions.getValue(space),
                tag = "space_${space.name.lowercase(Locale.ROOT)}",
                minHeight = 34,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = space.displayName(),
                    color = keycapLabelColor(selected = isSelected, enabled = true, accent = accent),
                    style = HornText.KeyLabel,
                    maxLines = 1,
                )
                if (isSelected) {
                    Box(
                        modifier =
                            Modifier
                                .size(width = 14.dp, height = 2.dp)
                                .background(accent),
                    )
                } else {
                    Spacer(modifier = Modifier.size(width = 14.dp, height = 2.dp))
                }
            }
        }
    }
}

/**
 * Machined toggle for the physical volume-key trigger. Off means the system volume keys behave
 * completely normally.
 */
@Composable
fun VolumeTriggerChip(
    enabled: Boolean,
    accent: Color,
    onChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    InstrumentToggle(
        checked = enabled,
        accent = accent,
        onCheckedChange = onChanged,
        label = stringResource(R.string.volume_trigger_label),
        description = stringResource(R.string.volume_trigger_description),
        tag = "volume_trigger_chip",
        modifier = modifier,
    )
}

/**
 * Ear-protection micro badge: a machined tag, shown only while the −6 dB limiter is engaged.
 */
@Composable
fun EarProtectionBadge(
    visible: Boolean,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    if (!visible) return
    val colors = LocalHornColors.current
    Row(
        modifier =
            modifier
                .testTag("ear_protection_badge")
                .background(colors.panel, RoundedCornerShape(HornRadii.Chip))
                .border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(HornRadii.Chip))
                .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(5.dp)
                    .background(accent),
        )
        Text(
            text = stringResource(R.string.ear_protection_badge),
            color = accent,
            style = HornText.Data,
        )
    }
}

/**
 * Zero-volume rescue strip. The status rail hosts it; the one-tap action restores media volume to
 * 60% and the prompt leaves composition.
 */
@Composable
fun VolumeWarningCapsule(
    visible: Boolean,
    accent: Color,
    onSetVolume: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    val colors = LocalHornColors.current
    Row(
        modifier =
            modifier
                .widthIn(max = 420.dp)
                .testTag("zero_volume_prompt"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(5.dp)
                    .background(colors.fault),
        )
        Text(
            text = stringResource(R.string.zero_volume_title),
            color = colors.textPrimary,
            style = HornText.Data,
            modifier = Modifier.padding(start = 6.dp),
        )
        TextButton(
            onClick = onSetVolume,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Text(
                text = stringResource(R.string.zero_volume_action),
                color = accent,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        TextButton(
            onClick = onDismiss,
            contentPadding = PaddingValues(6.dp),
        ) {
            Text(
                text = stringResource(R.string.zero_volume_dismiss_symbol),
                color = colors.textTertiary,
                fontSize = 15.sp,
            )
        }
    }
}

@Composable
fun AcousticSpace.displayName(): String =
    stringResource(
        when (this) {
            AcousticSpace.STREET -> R.string.acoustic_space_street
            AcousticSpace.TUNNEL -> R.string.acoustic_space_tunnel
            AcousticSpace.OPEN_ROAD -> R.string.acoustic_space_open_road
            AcousticSpace.GARAGE -> R.string.acoustic_space_garage
        },
    )

@Composable
fun AcousticSpace.accessibilityName(): String =
    stringResource(
        when (this) {
            AcousticSpace.STREET -> R.string.acoustic_space_street_description
            AcousticSpace.TUNNEL -> R.string.acoustic_space_tunnel_description
            AcousticSpace.OPEN_ROAD -> R.string.acoustic_space_open_road_description
            AcousticSpace.GARAGE -> R.string.acoustic_space_garage_description
        },
    )
