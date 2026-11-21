package com.industrial.autohorn.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.industrial.autohorn.audio.HornType
import com.industrial.autohorn.feature.R
import com.industrial.autohorn.ui.theme.HornCream
import java.util.Locale

/**
 * Four-way voice selector. It intentionally lives on a graphite capsule in both themes so the
 * bright per-voice accent always keeps accessible contrast. Radio-button semantics expose the
 * selected state to TalkBack without adding a second clickable wrapper.
 */
@Composable
fun HornTypeSelector(
    selected: HornType,
    enabled: Boolean,
    accent: Color,
    onSelect: (HornType) -> Unit,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
) {
    Column(
        modifier = modifier.alpha(if (enabled) 1f else 0.55f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (showLabel) {
            Text(
                text = stringResource(R.string.voice_selector_label),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 2.2.sp,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        Surface(
            modifier =
                Modifier
                    .widthIn(max = 420.dp)
                    .fillMaxWidth(),
            shape = RoundedCornerShape(50),
            color = SelectorSurface,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.09f)),
        ) {
            Row(
                modifier =
                    Modifier
                        .padding(4.dp)
                        .selectableGroup(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HornType.entries.forEach { type ->
                    val isSelected = type == selected
                    val style = type.visualStyle()
                    val typeDescription = type.accessibilityName()
                    val typeLabel = type.displayName()
                    val itemAccent by animateColorAsState(
                        targetValue = if (isSelected) accent else SelectorMuted,
                        animationSpec = tween(durationMillis = 260),
                        label = "voiceAccent_${type.name}",
                    )
                    val selectedAlpha by animateFloatAsState(
                        targetValue = if (isSelected) 0.18f else 0f,
                        animationSpec = tween(durationMillis = 260),
                        label = "voiceBackground_${type.name}",
                    )

                    Column(
                        modifier =
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(50))
                                .background(itemAccent.copy(alpha = selectedAlpha))
                                .selectable(
                                    selected = isSelected,
                                    enabled = enabled,
                                    role = Role.RadioButton,
                                    onClick = { onSelect(type) },
                                ).semantics(mergeDescendants = true) {
                                    contentDescription = typeDescription
                                    role = Role.RadioButton
                                    this.selected = isSelected
                                }.testTag("horn_type_${type.name.lowercase(Locale.ROOT)}")
                                .padding(vertical = 7.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            painter = painterResource(style.iconRes),
                            contentDescription = null,
                            tint = itemAccent,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = typeLabel,
                            color = if (isSelected) HornCream else SelectorMuted,
                            fontSize = 10.sp,
                            lineHeight = 12.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            letterSpacing = 0.3.sp,
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HornType.displayName(): String =
    stringResource(
        when (this) {
            HornType.CAR -> R.string.horn_type_car
            HornType.TRAIN -> R.string.horn_type_train
            HornType.EBIKE -> R.string.horn_type_ebike
            HornType.MOTORCYCLE -> R.string.horn_type_motorcycle
            HornType.CUSTOM -> R.string.horn_type_custom
        },
    )

@Composable
fun HornType.accessibilityName(): String =
    stringResource(
        when (this) {
            HornType.CAR -> R.string.horn_type_car_description
            HornType.TRAIN -> R.string.horn_type_train_description
            HornType.EBIKE -> R.string.horn_type_ebike_description
            HornType.MOTORCYCLE -> R.string.horn_type_motorcycle_description
            HornType.CUSTOM -> R.string.horn_type_custom_description
        },
    )

private val SelectorSurface = Color(0xFF151B20)
private val SelectorMuted = Color(0xFFA8B0B6)
