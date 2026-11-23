package com.industrial.horn.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Machined slider for the Pro Sound Lab: a recessed trough with printed graduations, a hard-edged
 * fill and a knurled thumb — the control you would find on a calibrated instrument, not a phone
 * media scrubber. Values are supplied pre-formatted and MUST use tabular figures
 * ([HornText.Data] / [HornNumbers.TABULAR]) so digits do not jitter while dragging.
 */
@Composable
fun InstrumentSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    accent: Color,
    label: String,
    valueText: String,
    modifier: Modifier = Modifier,
    graduations: Int = 10,
) {
    val colors = LocalHornColors.current
    val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(0.0001f)
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    var widthPx by remember { mutableFloatStateOf(1f) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = colors.textSecondary,
                style = HornText.PlateLabel,
            )
            Text(
                text = valueText,
                color = accent,
                style = HornText.Data,
            )
        }
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
                    .pointerInput(valueRange) {
                        detectTapGestures { position ->
                            val raw = (position.x / widthPx).coerceIn(0f, 1f)
                            onValueChange(valueRange.start + raw * span)
                        }
                    }.pointerInput(valueRange) {
                        detectHorizontalDragGestures { change, dragAmount ->
                            change.consume()
                            val current = (value - valueRange.start) / span
                            val next = (current + dragAmount / widthPx).coerceIn(0f, 1f)
                            onValueChange(valueRange.start + next * span)
                        }
                    },
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(22.dp)
                        .align(Alignment.CenterStart)
                        .clip(RoundedCornerShape(HornRadii.Well))
                        .background(colors.panelSunken)
                        .border(1.dp, colors.hairlineStrong, RoundedCornerShape(HornRadii.Well))
                        .drawWithCache {
                            val steps = graduations.coerceAtLeast(2)
                            val tickTop = size.height - 6.dp.toPx()
                            val tickBottom = size.height - 2.dp.toPx()
                            onDrawBehind {
                                var index = 0
                                while (index <= steps) {
                                    val x = size.width * index / steps
                                    val major = index % (steps / 2).coerceAtLeast(1) == 0
                                    drawLine(
                                        color =
                                            colors.textTertiary.copy(
                                                alpha = if (major) 0.75f else 0.4f,
                                            ),
                                        start = Offset(x, tickTop),
                                        end = Offset(x, tickBottom),
                                        strokeWidth = 1f,
                                    )
                                    index++
                                }
                            }
                        },
            ) {
                // Fill bar with a hard leading edge (no rounded cap: this is a measured value).
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.CenterStart)
                            .fillMaxWidth(fraction)
                            .height(8.dp)
                            .background(
                                brush =
                                    Brush.horizontalGradient(
                                        colors = listOf(accent.copy(alpha = 0.55f), accent),
                                    ),
                            ),
                )
            }
            SliderThumb(
                fraction = fraction,
                accent = accent,
                colors = colors,
                modifier = Modifier.fillMaxHeight(),
                widthPx = widthPx,
            )
        }
    }
}

@Composable
private fun SliderThumb(
    fraction: Float,
    accent: Color,
    colors: HornColors,
    modifier: Modifier,
    widthPx: Float,
) {
    val thumbWidth = 12.dp
    val thumbPx = with(LocalDensity.current) { thumbWidth.toPx() }
    Box(
        modifier =
            modifier
                .offset {
                    IntOffset(
                        ((widthPx - thumbPx) * fraction).roundToInt(),
                        0,
                    )
                }.size(width = thumbWidth, height = 26.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(colors.metalTop)
                .border(1.dp, accent, RoundedCornerShape(3.dp))
                .drawWithCache {
                    val grip = size.height / 6f
                    onDrawBehind {
                        var index = 1
                        while (index <= 2) {
                            val y = grip * index * 2f
                            drawLine(
                                color = colors.textTertiary.copy(alpha = 0.6f),
                                start = Offset(size.width * 0.28f, y),
                                end = Offset(size.width * 0.72f, y),
                                strokeWidth = 1f,
                            )
                            index++
                        }
                        drawRect(
                            color = colors.plateHighlight.copy(alpha = if (colors.isDark) 0.3f else 0.8f),
                            topLeft = Offset(1f, 1f),
                            size = Size(size.width - 2f, 1f),
                        )
                        drawRect(
                            color = colors.groove.copy(alpha = 0.5f),
                            topLeft = Offset(1f, size.height - 2f),
                            size = Size(size.width - 2f, 1f),
                        )
                    }
                },
    )
}

/** Compact keycap-style action used inside instrument plates (e.g. Lab / Save). */
@Composable
fun InstrumentActionKey(
    label: String,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
) {
    val colors = LocalHornColors.current
    Box(
        modifier =
            modifier
                .height(40.dp)
                .clip(RoundedCornerShape(HornRadii.Key))
                .background(if (filled) accent else colors.panel)
                .border(
                    width = 1.dp,
                    color = if (filled) accent else colors.hairlineStrong,
                    shape = RoundedCornerShape(HornRadii.Key),
                ).clickable(enabled = true, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (filled) colors.canvas else colors.textPrimary,
            style = HornText.PlateLabel,
        )
    }
}
