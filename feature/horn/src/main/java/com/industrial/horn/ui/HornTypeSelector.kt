package com.industrial.horn.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.industrial.horn.audio.HornType
import com.industrial.horn.feature.R
import com.industrial.horn.ui.theme.GlassSegmentedControl
import com.industrial.horn.ui.theme.HornCream
import java.util.Locale

/**
 * Five-way voice selector built on the Apple-style glass segmented control. Radio-button semantics
 * and per-item test tags stay identical to the pre-glass contract so TalkBack and UI tests keep
 * working unchanged.
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

        // Accessibility names resolve from resources, so they are computed in composable scope
        // and passed down as plain strings.
        val descriptions =
            HornType.entries.associateWith { it.accessibilityName() }
        GlassSegmentedControl(
            items = HornType.entries,
            selected = selected,
            onSelect = onSelect,
            accent = accent,
            enabled = enabled,
            itemTag = { "horn_type_${it.name.lowercase(Locale.ROOT)}" },
            itemDescription = { descriptions.getValue(it) },
            modifier =
                Modifier
                    .widthIn(max = 420.dp)
                    .fillMaxWidth(),
        ) { type, isSelected ->
            val style = type.visualStyle()
            val itemAccent by animateColorAsState(
                targetValue = if (isSelected) accent else SelectorMuted,
                animationSpec = tween(durationMillis = 260),
                label = "voiceAccent_${type.name}",
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    painter = painterResource(style.iconRes),
                    contentDescription = null,
                    tint = itemAccent,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = type.displayName(),
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

private val SelectorMuted = Color(0xFFA8B0B6)
