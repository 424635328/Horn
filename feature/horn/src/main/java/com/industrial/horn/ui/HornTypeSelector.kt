package com.industrial.horn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.industrial.horn.audio.HornType
import com.industrial.horn.feature.R
import com.industrial.horn.ui.theme.HornText
import com.industrial.horn.ui.theme.LocalHornColors
import com.industrial.horn.ui.theme.TactileKeycap
import com.industrial.horn.ui.theme.keycapLabelColor
import java.util.Locale

/**
 * Five-voice mechanical gear strip: one milled key per voice, equal width, no scrolling and no
 * cramping. Radio semantics and the `horn_type_{voice}` test tags are unchanged from the previous
 * generation so TalkBack and the instrumentation suite keep working without edits.
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
    val colors = LocalHornColors.current
    // Accessibility names resolve from resources, so they are computed in composable scope and
    // passed down as plain strings.
    val descriptions = HornType.entries.associateWith { it.accessibilityName() }
    val dark = colors.isDark
    Column(
        modifier = modifier.alpha(if (enabled) 1f else 0.55f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (showLabel) {
            Text(
                text = stringResource(R.string.voice_selector_label),
                color = colors.textTertiary,
                style = HornText.PlateLabel,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        Row(
            modifier =
                Modifier
                    .widthIn(max = 460.dp)
                    .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            HornType.entries.forEach { type ->
                val style = type.visualStyle(dark)
                val isSelected = type == selected
                TactileKeycap(
                    selected = isSelected,
                    accent = accent,
                    onClick = { onSelect(type) },
                    enabled = enabled,
                    role = Role.RadioButton,
                    contentDescription = descriptions.getValue(type),
                    tag = "horn_type_${type.name.lowercase(Locale.ROOT)}",
                    minHeight = 60,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        painter = painterResource(style.iconRes),
                        contentDescription = null,
                        tint =
                            if (isSelected) {
                                accent
                            } else {
                                colors.textTertiary
                            },
                        modifier = Modifier.size(19.dp),
                    )
                    Text(
                        text = type.displayName(),
                        color = keycapLabelColor(selected = isSelected, enabled = enabled, accent = accent),
                        style = HornText.KeyLabel,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                    )
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
