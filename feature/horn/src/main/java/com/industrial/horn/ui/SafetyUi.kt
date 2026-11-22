package com.industrial.horn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.industrial.horn.audio.AcousticSpace
import com.industrial.horn.feature.R
import com.industrial.horn.ui.theme.AppleGlassSurface
import com.industrial.horn.ui.theme.AppleRadii
import com.industrial.horn.ui.theme.GlassSegmentedControl
import com.industrial.horn.ui.theme.HornCream
import java.util.Locale

/**
 * Three-way acoustic space selector on the shared glass segmented control. Tags and radio
 * semantics match the established UI test contract.
 */
@Composable
fun AcousticSpaceSelector(
    selected: AcousticSpace,
    accent: Color,
    onSelect: (AcousticSpace) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Accessibility names resolve from resources, so they are computed here and passed as data.
    val descriptions = AcousticSpace.entries.associateWith { it.accessibilityName() }
    GlassSegmentedControl(
        items = AcousticSpace.entries,
        selected = selected,
        onSelect = onSelect,
        accent = accent,
        itemTag = { "space_${it.name.lowercase(Locale.ROOT)}" },
        itemDescription = { descriptions.getValue(it) },
        modifier = modifier,
    ) { space, isSelected ->
        Text(
            text = space.displayName(),
            color = if (isSelected) HornCream else SelectorMuted,
            fontSize = 10.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/**
 * Glass toggle for the physical volume-key trigger. Off means the system volume keys behave
 * completely normally.
 */
@Composable
fun VolumeTriggerChip(
    enabled: Boolean,
    accent: Color,
    onChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.volume_trigger_description)
    Box(
        modifier =
            modifier
                .semantics {
                    contentDescription = description
                    toggleableState = ToggleableState(enabled)
                }.testTag("volume_trigger_chip"),
    ) {
        AppleGlassSurface(
            shape = RoundedCornerShape(AppleRadii.Capsule),
            rimAccent = if (enabled) accent else Color.White,
            frostAlpha = if (enabled) 0.16f else 0.08f,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(6.dp)
                            .background(
                                color = if (enabled) accent else SelectorMuted,
                                shape = CircleShape,
                            ),
                )
                Text(
                    text = stringResource(R.string.volume_trigger_label),
                    color = if (enabled) HornCream else SelectorMuted,
                    fontSize = 10.sp,
                    letterSpacing = 0.5.sp,
                )
            }
        }
        // Full-area click target above the visual pill.
        Box(
            modifier =
                Modifier
                    .matchParentSize()
                    .clickable(role = Role.Switch) { onChanged(!enabled) },
        )
    }
}

/**
 * Ear-protection micro badge. Restyled as a small glass pill; shown only while the -6dB limiter
 * is engaged.
 */
@Composable
fun EarProtectionBadge(
    visible: Boolean,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    if (!visible) return
    AppleGlassSurface(
        modifier = modifier.testTag("ear_protection_badge"),
        shape = RoundedCornerShape(AppleRadii.Capsule),
        rimAccent = accent,
        frostAlpha = 0.12f,
        contentPadding = PaddingValues(horizontal = 11.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier =
                    Modifier
                        .size(5.dp)
                        .background(accent, CircleShape),
            )
            Spacer(modifier = Modifier.size(7.dp))
            Text(
                text = stringResource(R.string.ear_protection_badge),
                color = accent,
                fontSize = 9.sp,
                letterSpacing = 0.8.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * Zero-volume rescue capsule. The dynamic-island banner hosts it; the one-tap action restores
 * media volume to 60% and the prompt fades away.
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
    Row(
        modifier =
            modifier
                .widthIn(max = 420.dp)
                .testTag("zero_volume_prompt"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(6.dp)
                    .background(accent, CircleShape),
        )
        Text(
            text = stringResource(R.string.zero_volume_title),
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        TextButton(onClick = onSetVolume) {
            Text(
                text = stringResource(R.string.zero_volume_action),
                color = accent,
                fontWeight = FontWeight.Bold,
            )
        }
        TextButton(
            onClick = onDismiss,
            contentPadding = PaddingValues(6.dp),
        ) {
            Text(
                text = stringResource(R.string.zero_volume_dismiss_symbol),
                color = SelectorMuted,
                fontSize = 18.sp,
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
        },
    )

@Composable
fun AcousticSpace.accessibilityName(): String =
    stringResource(
        when (this) {
            AcousticSpace.STREET -> R.string.acoustic_space_street_description
            AcousticSpace.TUNNEL -> R.string.acoustic_space_tunnel_description
            AcousticSpace.OPEN_ROAD -> R.string.acoustic_space_open_road_description
        },
    )

private val SelectorMuted = Color(0xFFA8B0B6)
