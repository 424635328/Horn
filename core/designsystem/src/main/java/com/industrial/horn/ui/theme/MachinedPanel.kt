package com.industrial.horn.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.min

/**
 * A machined plate: extruded edge, engraved hairline, caliper ticks and an optional accent index
 * rule. This is the single container primitive of the cockpit — no translucent layers, no blur,
 * no frosted backdrop. Elevation is expressed with a 1 dp light seam on top and a 1 dp groove on
 * the bottom, the way a real milled plate catches light.
 */
@Composable
fun MachinedPanel(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    shape: Shape = RoundedCornerShape(HornRadii.Instrument),
    raised: Boolean = true,
    sunken: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalHornColors.current
    val top =
        if (sunken) {
            colors.panelSunken
        } else if (raised) {
            colors.panelRaised
        } else {
            colors.panel
        }
    val bottom = if (sunken) colors.panelSunken else colors.panel

    Box(
        modifier =
            modifier
                .clip(shape)
                .background(Brush.verticalGradient(colors = listOf(top, bottom)))
                .border(width = 1.dp, color = colors.hairline, shape = shape)
                .drawWithCache {
                    val inset = 10.dp.toPx()
                    val arm = 6.dp.toPx()
                    val indexWidth = 22.dp.toPx()
                    onDrawBehind {
                        // Top light seam / bottom shadow seam: the extruded edge.
                        if (!sunken) {
                            drawLine(
                                color = colors.plateHighlight.copy(alpha = if (colors.isDark) 0.55f else 0.95f),
                                start = Offset(inset, 1f),
                                end = Offset(size.width - inset, 1f),
                                strokeWidth = 1f,
                            )
                        }
                        drawLine(
                            color = colors.groove.copy(alpha = 0.55f),
                            start = Offset(inset, size.height - 1f),
                            end = Offset(size.width - inset, size.height - 1f),
                            strokeWidth = 1f,
                        )
                        // Accent index rule, flush with the top-left edge.
                        if (accent != null) {
                            drawLine(
                                color = accent,
                                start = Offset(inset, 1.5f),
                                end = Offset(inset + indexWidth, 1.5f),
                                strokeWidth = 2.dp.toPx(),
                            )
                        }
                        drawCornerTicks(colors, inset, arm)
                    }
                }.padding(contentPadding),
    ) {
        Column(content = content)
    }
}

/** Micro caliper marks in all four corners of a plate. */
private fun DrawScope.drawCornerTicks(
    colors: HornColors,
    inset: Float,
    arm: Float,
) {
    val color = colors.hairlineStrong.copy(alpha = if (colors.isDark) 0.8f else 0.9f)
    val width = 1.dp.toPx()
    val bottom = size.height - inset
    val right = size.width - inset
    drawLine(color, Offset(inset, inset), Offset(inset + arm, inset), width)
    drawLine(color, Offset(inset, inset), Offset(inset, inset + arm), width)
    drawLine(color, Offset(right - arm, inset), Offset(right, inset), width)
    drawLine(color, Offset(right, inset), Offset(right, inset + arm), width)
    drawLine(color, Offset(inset, bottom), Offset(inset + arm, bottom), width)
    drawLine(color, Offset(inset, bottom - arm), Offset(inset, bottom), width)
    drawLine(color, Offset(right - arm, bottom), Offset(right, bottom), width)
    drawLine(color, Offset(right, bottom - arm), Offset(right, bottom), width)
}

/**
 * Engraved plate caption: `01 · VOICE`. The index is data, the title is a label — both share one
 * baseline so a row of plates reads as one instrument panel.
 */
@Composable
fun PlateCaption(
    index: String,
    title: String,
    modifier: Modifier = Modifier,
    accent: Color = LocalHornColors.current.textTertiary,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = LocalHornColors.current
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(
            text = index,
            color = accent,
            style = HornText.Data,
        )
        Text(
            text = "·",
            color = colors.hairlineStrong,
            style = HornText.Data,
        )
        Text(
            text = title,
            color = colors.textSecondary,
            style = HornText.PlateLabel,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (trailing != null) {
            Spacer(modifier = Modifier.weight(1f))
            trailing()
        }
    }
}

/**
 * Thin instrument status rail: a milled strip with a state LED, a monospace status token and a
 * secondary context token. Replaces the floating pill banner; it never overlaps the top bar or
 * the pad, and it collapses the previous three disjoint popups into one stable slot.
 */
@Composable
fun CockpitStatusRail(
    statusText: String,
    detailText: String,
    ledColor: Color,
    modifier: Modifier = Modifier,
    statusColor: Color = LocalHornColors.current.textPrimary,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = LocalHornColors.current
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(30.dp)
                .background(colors.panel)
                .drawWithCache {
                    onDrawBehind {
                        drawLine(
                            color = colors.hairline,
                            start = Offset.Zero,
                            end = Offset(size.width, 0f),
                            strokeWidth = 1.dp.toPx(),
                        )
                        drawLine(
                            color = colors.hairline,
                            start = Offset(0f, size.height),
                            end = Offset(size.width, size.height),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }
                }.padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Square LED: a circular dot would read as a consumer notification, a square one as an
        // indicator lamp on an instrument.
        Box(
            modifier =
                Modifier
                    .size(5.dp)
                    .background(ledColor),
        )
        Text(
            text = statusText,
            color = statusColor,
            style = HornText.Data,
            maxLines = 1,
        )
        Box(
            modifier =
                Modifier
                    .width(1.dp)
                    .height(10.dp)
                    .background(colors.hairlineStrong),
        )
        Text(
            text = detailText,
            color = colors.textSecondary,
            style = HornText.Data,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (trailing != null) {
            Spacer(modifier = Modifier.weight(1f))
            trailing()
        }
    }
}

/** Horizontal engraved rule with a 1 dp accent segment at the left. */
@Composable
fun EngravedRule(
    modifier: Modifier = Modifier,
    accent: Color? = null,
) {
    val colors = LocalHornColors.current
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(1.dp)
                .drawWithCache {
                    val segment = min(size.width * 0.18f, 28.dp.toPx())
                    onDrawBehind {
                        drawLine(colors.hairline, Offset.Zero, Offset(size.width, 0f), 1f)
                        if (accent != null) {
                            drawLine(accent, Offset.Zero, Offset(segment, 0f), 1.5.dp.toPx())
                        }
                    }
                },
    )
}
