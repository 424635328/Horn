package com.industrial.autohorn.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.industrial.autohorn.audio.AcousticSpace
import com.industrial.autohorn.feature.R
import com.industrial.autohorn.ui.theme.HornCream
import java.util.Locale

@Composable
fun AcousticSpaceSelector(
    selected: AcousticSpace,
    accent: Color,
    onSelect: (AcousticSpace) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = SelectorSurface,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
    ) {
        Row(
            modifier =
                Modifier
                    .padding(3.dp)
                    .selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AcousticSpace.entries.forEach { space ->
                val isSelected = space == selected
                val description = space.accessibilityName()
                val label = space.displayName()
                val background by animateFloatAsState(
                    targetValue = if (isSelected) 0.18f else 0f,
                    animationSpec = tween(220),
                    label = "spaceBackground",
                )
                Box(
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(accent.copy(alpha = background))
                            .selectable(
                                selected = isSelected,
                                role = Role.RadioButton,
                                onClick = { onSelect(space) },
                            ).semantics(mergeDescendants = true) {
                                contentDescription = description
                                role = Role.RadioButton
                                this.selected = isSelected
                            }.testTag("space_${space.name.lowercase(Locale.ROOT)}")
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        color = if (isSelected) HornCream else SelectorMuted,
                        fontSize = 10.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
fun VolumeTriggerChip(
    enabled: Boolean,
    accent: Color,
    onChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.volume_trigger_description)
    FilterChip(
        selected = enabled,
        onClick = { onChanged(!enabled) },
        label = {
            Text(
                text = stringResource(R.string.volume_trigger_label),
                fontSize = 10.sp,
                letterSpacing = 0.5.sp,
            )
        },
        modifier =
            modifier
                .semantics { contentDescription = description }
                .testTag("volume_trigger_chip"),
        colors =
            FilterChipDefaults.filterChipColors(
                containerColor = SelectorSurface,
                labelColor = SelectorMuted,
                selectedContainerColor = accent.copy(alpha = 0.20f),
                selectedLabelColor = HornCream,
            ),
        border =
            FilterChipDefaults.filterChipBorder(
                enabled = true,
                selected = enabled,
                borderColor = Color.White.copy(alpha = 0.10f),
                selectedBorderColor = accent.copy(alpha = 0.55f),
            ),
        shape = RoundedCornerShape(50),
    )
}

@Composable
fun EarProtectionBadge(
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    Surface(
        modifier = modifier.testTag("ear_protection_badge"),
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(5.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
            Spacer(modifier = Modifier.size(7.dp))
            Text(
                text = stringResource(R.string.ear_protection_badge),
                color = MaterialTheme.colorScheme.primary,
                fontSize = 9.sp,
                letterSpacing = 0.8.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
fun VolumeWarningCapsule(
    visible: Boolean,
    accent: Color,
    onSetVolume: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    val surfaceColor by animateColorAsState(
        targetValue = accent.copy(alpha = 0.14f),
        animationSpec = tween(200),
        label = "volumeWarningColor",
    )
    Surface(
        modifier =
            modifier
                .widthIn(max = 420.dp)
                .testTag("zero_volume_prompt"),
        shape = RoundedCornerShape(50),
        color = surfaceColor,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
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
                contentPadding =
                    androidx.compose.foundation.layout
                        .PaddingValues(6.dp),
            ) {
                Text(
                    text = stringResource(R.string.zero_volume_dismiss_symbol),
                    color = SelectorMuted,
                    fontSize = 18.sp,
                )
            }
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

private val SelectorSurface = Color(0xFF151B20)
private val SelectorMuted = Color(0xFFA8B0B6)
