package com.industrial.horn.ui.theme

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

/** Foreground color for glass-layer captions; keeps text legible on both themes. */
val LocalGlassContentColor = compositionLocalOf { Color(0xFFF4EEE4) }

/**
 * Fluid sliding glass capsule selector. The floating indicator travels with
 * [AppleMotion.CapsuleSpring], stretches slightly with its own velocity (deferred read inside
 * graphicsLayer), and the caller is notified once per confirmed selection so it can fire a fine
 * CLOCK_TICK haptic.
 */
@Composable
fun <T> GlassSegmentedControl(
    items: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    accent: Color,
    itemTag: (T) -> String,
    itemDescription: (T) -> String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onTick: () -> Unit = {},
    itemContent: @Composable (T, Boolean) -> Unit,
) {
    BoxWithConstraints(
        modifier =
            modifier
                .selectableGroup()
                .clip(RoundedCornerShape(AppleRadii.Capsule)),
    ) {
        val itemWidthPx = constraints.maxWidth.toFloat() / items.size.coerceAtLeast(1)
        val selectedIndex = items.indexOf(selected).coerceAtLeast(0)
        val travel = remember { Animatable(selectedIndex * itemWidthPx) }
        val itemFraction = 1f / items.size.coerceAtLeast(1)

        LaunchedEffect(selected, itemWidthPx) {
            travel.animateTo(
                targetValue = items.indexOf(selected).coerceAtLeast(0) * itemWidthPx,
                animationSpec = AppleMotion.CapsuleSpring,
            )
        }

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(46.dp),
        ) {
            GlassIndicatorPill(
                accent = accent,
                travelProvider = { travel.value },
                velocityProvider = { travel.velocity },
                modifier =
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(itemFraction),
            )
            Row(modifier = Modifier.fillMaxSize()) {
                items.forEach { item ->
                    val isSelected = item == selected
                    Box(
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .selectable(
                                    selected = isSelected,
                                    enabled = enabled,
                                    role = Role.RadioButton,
                                    onClick = {
                                        onSelect(item)
                                        onTick()
                                    },
                                ).semantics(mergeDescendants = true) {
                                    contentDescription = itemDescription(item)
                                    role = Role.RadioButton
                                    this.selected = isSelected
                                }.testTag(itemTag(item)),
                        contentAlignment = Alignment.Center,
                    ) {
                        itemContent(item, isSelected)
                    }
                }
            }
        }
    }
}

@Composable
private fun GlassIndicatorPill(
    accent: Color,
    travelProvider: () -> Float,
    velocityProvider: () -> Float,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .offset { IntOffset(travelProvider().roundToInt(), 0) }
                .padding(4.dp)
                .graphicsLayer {
                    val velocity = velocityProvider()
                    val stretch = (abs(velocity) / 5200f).coerceIn(0f, 0.14f)
                    scaleX = 1f + stretch
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                }.clip(RoundedCornerShape(AppleRadii.Capsule)),
    ) {
        AppleGlassSurface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(AppleRadii.Capsule),
            rimAccent = accent,
            frostAlpha = 0.16f,
        ) {}
    }
}

/**
 * iOS-control-center style capsule slider: a translucent glass trough with a smooth fill bar and
 * rubber-band resistance at both ends. Values are shown with tabular figures (tnum).
 */
@Composable
fun GlassCapsuleSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    accent: Color,
    label: String,
    valueText: String,
    modifier: Modifier = Modifier,
) {
    val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(0.0001f)
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    var widthPx by remember { mutableFloatStateOf(1f) }
    var overshoot by remember { mutableFloatStateOf(0f) }
    val fillStretch by animateFloatAsState(
        targetValue = 1f + abs(AppleMotion.rubberBand(overshoot, limit = 0.18f)) * 0.9f,
        animationSpec = AppleMotion.SnappySpring,
        label = "capsuleFillStretch",
    )
    val contentColor = LocalGlassContentColor.current

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                color = contentColor,
                fontSize = 12.sp,
            )
            Text(
                text = valueText,
                style =
                    TextStyle(
                        color = accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFeatureSettings = AppleNumbers.TABULAR,
                    ),
            )
        }
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .onSizeChanged { widthPx = it.width.toFloat() }
                    .pointerInput(valueRange) {
                        detectTapGestures { position ->
                            val raw = (position.x / widthPx).coerceIn(0f, 1f)
                            onValueChange(valueRange.start + raw * span)
                        }
                    }.pointerInput(valueRange) {
                        detectHorizontalDragGestures(
                            onDragEnd = { overshoot = 0f },
                            onDragCancel = { overshoot = 0f },
                        ) { change, dragAmount ->
                            change.consume()
                            val current = (value - valueRange.start) / span
                            val next = current + dragAmount / widthPx
                            overshoot =
                                when {
                                    next < 0f -> next
                                    next > 1f -> next - 1f
                                    else -> 0f
                                }
                            val clamped = next.coerceIn(0f, 1f)
                            onValueChange(valueRange.start + clamped * span)
                        }
                    },
        ) {
            AppleGlassSurface(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(AppleRadii.Capsule),
                rimAccent = accent,
                frostAlpha = 0.08f,
            ) {}
            Box(
                modifier =
                    Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxHeight()
                        .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                        .graphicsLayer {
                            scaleX = fillStretch
                            transformOrigin = TransformOrigin(0f, 0.5f)
                        }.padding(3.dp),
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(AppleRadii.Capsule))
                            .background(
                                brush =
                                    Brush.horizontalGradient(
                                        colors =
                                            listOf(
                                                accent.copy(alpha = 0.55f),
                                                accent.copy(alpha = 0.95f),
                                            ),
                                    ),
                            ),
                )
            }
        }
    }
}

/**
 * Dynamic-island style floating pill banner. The host picks content by priority and the pill
 * morphs between states with a spring size animation instead of disjoint popups.
 */
@Composable
fun DynamicPillBanner(
    visible: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    backdrop: (DrawScope.(Offset) -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    if (!visible) return
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        AppleGlassSurface(
            modifier =
                Modifier
                    .animateContentSize(animationSpec = tween(240))
                    .padding(horizontal = 22.dp),
            shape = RoundedCornerShape(AppleRadii.Capsule),
            rimAccent = accent,
            frostAlpha = 0.12f,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            backdrop = backdrop,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                content = content,
            )
        }
    }
}
