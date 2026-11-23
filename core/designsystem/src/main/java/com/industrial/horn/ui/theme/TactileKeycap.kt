package com.industrial.horn.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * A milled keycap: flat plate, engraved hairline, 1 dp bottom shadow seam and a press travel of
 * 3 % scale that stops instantly. Selection is carried by three independent signals (accent
 * border, accent label, 2 dp accent index bar) so it survives colour-vision deficiency and both
 * palettes.
 *
 * The keycap owns no content decisions: callers supply the icon/label column, which keeps the
 * voice strip and the scene row on one interaction contract.
 */
@Composable
fun TactileKeycap(
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    role: Role = Role.RadioButton,
    contentDescription: String? = null,
    tag: String? = null,
    minHeight: Int = 56,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalHornColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = HornMotion.KeySpring,
        label = "keycapTravel",
    )
    val fill by animateColorAsState(
        targetValue =
            when {
                !enabled -> colors.panelSunken
                selected -> accent.copy(alpha = if (colors.isDark) 0.14f else 0.09f)
                else -> colors.panel
            },
        animationSpec = tween(durationMillis = 160),
        label = "keycapFill",
    )
    val stroke by animateColorAsState(
        targetValue = if (selected) accent else colors.hairline,
        animationSpec = tween(durationMillis = 160),
        label = "keycapStroke",
    )
    val shape = RoundedCornerShape(HornRadii.Key)
    val interactive =
        Modifier
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = role,
                onClick = onClick,
            ).semantics(mergeDescendants = true) {
                if (contentDescription != null) this.contentDescription = contentDescription
                this.role = role
                this.selected = selected
            }.then(if (tag != null) Modifier.testTag(tag) else Modifier)

    Box(
        modifier =
            modifier
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                }.clip(shape)
                .background(fill)
                .border(width = 1.dp, color = stroke, shape = shape)
                .drawWithCache {
                    val bottomInset = size.height - 1f
                    val highlight = colors.plateHighlight.copy(alpha = if (colors.isDark) 0.5f else 0.9f)
                    val shadow = colors.groove.copy(alpha = 0.45f)
                    val barWidth = size.width * 0.42f
                    onDrawBehind {
                        drawLine(
                            color = highlight,
                            start = Offset(6.dp.toPx(), 1f),
                            end = Offset(size.width - 6.dp.toPx(), 1f),
                            strokeWidth = 1f,
                        )
                        drawLine(
                            color = shadow,
                            start = Offset(6.dp.toPx(), bottomInset),
                            end = Offset(size.width - 6.dp.toPx(), bottomInset),
                            strokeWidth = 1f,
                        )
                        if (selected) {
                            drawLine(
                                color = accent,
                                start = Offset((size.width - barWidth) / 2f, bottomInset - 2.dp.toPx()),
                                end = Offset((size.width + barWidth) / 2f, bottomInset - 2.dp.toPx()),
                                strokeWidth = 2.dp.toPx(),
                            )
                        }
                    }
                }.then(interactive)
                .heightIn(min = minHeight.dp)
                .padding(horizontal = 4.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            content()
        }
    }
}

/** Resolved caption colour for a keycap state; use instead of hard-coding a text colour. */
@Composable
fun keycapLabelColor(
    selected: Boolean,
    enabled: Boolean,
    accent: Color,
): Color {
    val colors = LocalHornColors.current
    return when {
        !enabled -> colors.textTertiary
        selected -> accent
        else -> colors.textSecondary
    }
}

/**
 * Machined toggle for the physical volume-key trigger. Off is the safe default: the system
 * volume keys behave exactly as the platform intends.
 */
@Composable
fun InstrumentToggle(
    checked: Boolean,
    accent: Color,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    tag: String? = null,
) {
    val colors = LocalHornColors.current
    val knobOffset by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = HornMotion.KeySpring,
        label = "toggleKnob",
    )
    val trackColor by animateColorAsState(
        targetValue = if (checked) accent.copy(alpha = 0.22f) else colors.panelSunken,
        animationSpec = tween(durationMillis = 150),
        label = "toggleTrack",
    )
    val knobColor by animateColorAsState(
        targetValue = if (checked) accent else colors.textTertiary,
        animationSpec = tween(durationMillis = 150),
        label = "toggleKnobColor",
    )
    Row(
        modifier =
            modifier
                .semantics(mergeDescendants = true) {
                    if (description != null) contentDescription = description
                    toggleableState = ToggleableState(checked)
                    stateDescription = if (checked) "On" else "Off"
                }.then(if (tag != null) Modifier.testTag(tag) else Modifier)
                .clickable(role = Role.Switch) { onCheckedChange(!checked) }
                .padding(horizontal = 2.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text(
            text = label,
            color = if (checked) colors.textPrimary else colors.textSecondary,
            style = HornText.PlateLabel,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        ToggleTrack(
            checked = checked,
            accent = accent,
            knobOffset = knobOffset,
            trackColor = trackColor,
            knobColor = knobColor,
            colors = colors,
        )
    }
}

@Composable
private fun ToggleTrack(
    checked: Boolean,
    accent: Color,
    knobOffset: Float,
    trackColor: Color,
    knobColor: Color,
    colors: HornColors,
) {
    val travelPx = with(LocalDensity.current) { 14.dp.toPx() }
    Box(
        modifier =
            Modifier
                .width(34.dp)
                .height(18.dp)
                .clip(RoundedCornerShape(HornRadii.Well))
                .background(trackColor)
                .border(
                    width = 1.dp,
                    color = if (checked) accent else colors.hairlineStrong,
                    shape = RoundedCornerShape(HornRadii.Well),
                ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier =
                Modifier
                    .padding(horizontal = 3.dp)
                    .offset { IntOffset((travelPx * knobOffset).roundToInt(), 0) }
                    .size(width = 14.dp, height = 12.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(knobColor),
        )
    }
}
