package com.industrial.autohorn.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.industrial.autohorn.audio.AcousticSpace
import com.industrial.autohorn.audio.AudioTelemetry
import com.industrial.autohorn.audio.CustomHornProfile
import com.industrial.autohorn.audio.HornType
import com.industrial.autohorn.feature.R
import com.industrial.autohorn.presentation.HornFailure
import com.industrial.autohorn.presentation.HornStatus
import com.industrial.autohorn.presentation.HornUiState
import com.industrial.autohorn.ui.theme.AudioAura
import com.industrial.autohorn.ui.theme.AutoHornTheme
import kotlin.math.min

@Composable
fun HornScreen(
    state: HornUiState,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
    onPressCancel: () -> Unit,
    onSelectHorn: (HornType) -> Unit,
    modifier: Modifier = Modifier,
    telemetry: AudioTelemetry = AudioTelemetry(),
    windowWidthSizeClass: WindowWidthSizeClass = WindowWidthSizeClass.Compact,
    onExpressionChanged: (Float) -> Unit = {},
    onSelectSpace: (AcousticSpace) -> Unit = {},
    onVolumePromptClicked: () -> Unit = {},
    onVolumePromptDismissed: () -> Unit = {},
    onVolumeTriggerChanged: (Boolean) -> Unit = {},
    onCustomProfileChanged: (CustomHornProfile) -> Unit = {},
    onCustomProfileSaved: () -> Unit = {},
    onCustomProfileReset: () -> Unit = {},
    readWaveform: (FloatArray) -> Int = { 0 },
) {
    val isHonking = state.isHonking
    val selectedHorn = state.selectedHorn
    val visualStyle = selectedHorn.visualStyle()
    val accent by animateColorAsState(
        targetValue = visualStyle.accent,
        animationSpec = tween(durationMillis = 420),
        label = "selectedHornAccent",
    )
    val typeName = selectedHorn.accessibilityName()
    val buttonDescription =
        stringResource(
            if (isHonking) {
                R.string.horn_button_description_honking
            } else {
                R.string.horn_button_description
            },
            typeName,
        )
    val buttonStateDescription =
        stringResource(
            when (state.status) {
                HornStatus.Honking -> R.string.horn_state_honking
                HornStatus.Idle -> R.string.horn_state_idle
                is HornStatus.Error -> R.string.horn_state_error
            },
        )
    val buttonLabel =
        stringResource(
            if (isHonking) R.string.horn_button_label_active else R.string.horn_button_label,
        )

    var expression by remember { mutableFloatStateOf(0f) }
    var showLab by rememberSaveable { mutableStateOf(false) }

    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .testTag("horn_screen"),
    ) {
        val expanded = windowWidthSizeClass != WindowWidthSizeClass.Compact
        val lowHeight = maxHeight < 620.dp

        AmbientBackdrop(accent)

        val selectHorn: (HornType) -> Unit = { type ->
            expression = 0f
            if (type == HornType.CUSTOM) {
                if (state.preferencesLoaded) {
                    onSelectHorn(type)
                    showLab = true
                }
            } else {
                onSelectHorn(type)
                showLab = false
            }
        }

        val changeExpression: (Float) -> Unit = { value ->
            expression = value
            onExpressionChanged(value)
        }

        if (expanded) {
            Row(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .testTag("expanded_layout"),
            ) {
                Column(
                    modifier =
                        Modifier
                            .weight(0.43f)
                            .fillMaxHeight()
                            .padding(start = 28.dp, end = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ControlsColumn(
                        state = state,
                        accent = accent,
                        compact = lowHeight,
                        onSelectHorn = selectHorn,
                        onSelectSpace = onSelectSpace,
                        onVolumeTriggerChanged = onVolumeTriggerChanged,
                        onVolumePromptClicked = onVolumePromptClicked,
                        onVolumePromptDismissed = onVolumePromptDismissed,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Footer(
                        state = state,
                        accent = accent,
                        compact = false,
                        modifier = Modifier.padding(bottom = 24.dp),
                    )
                }
                HornHero(
                    state = state,
                    accent = accent,
                    visualStyle = visualStyle,
                    expression = expression,
                    rms = telemetry.rms,
                    buttonDescription = buttonDescription,
                    buttonStateDescription = buttonStateDescription,
                    buttonLabel = buttonLabel,
                    onPressStart = onPressStart,
                    onPressEnd = onPressEnd,
                    onPressCancel = onPressCancel,
                    onExpressionChanged = changeExpression,
                    modifier =
                        Modifier
                            .weight(0.57f)
                            .fillMaxHeight()
                            .padding(end = 28.dp),
                )
            }
        } else {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .testTag("compact_layout"),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ControlsColumn(
                    state = state,
                    accent = accent,
                    compact = lowHeight,
                    onSelectHorn = selectHorn,
                    onSelectSpace = onSelectSpace,
                    onVolumeTriggerChanged = onVolumeTriggerChanged,
                    onVolumePromptClicked = onVolumePromptClicked,
                    onVolumePromptDismissed = onVolumePromptDismissed,
                )
                HornHero(
                    state = state,
                    accent = accent,
                    visualStyle = visualStyle,
                    expression = expression,
                    rms = telemetry.rms,
                    buttonDescription = buttonDescription,
                    buttonStateDescription = buttonStateDescription,
                    buttonLabel = buttonLabel,
                    onPressStart = onPressStart,
                    onPressEnd = onPressEnd,
                    onPressCancel = onPressCancel,
                    onExpressionChanged = changeExpression,
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                )
                Footer(
                    state = state,
                    accent = accent,
                    compact = lowHeight,
                    modifier = Modifier.padding(bottom = 10.dp, start = 28.dp, end = 28.dp),
                )
            }
        }

        DebugAudioOverlayHost(
            accent = accent,
            telemetry = telemetry,
            readWaveform = readWaveform,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 6.dp, end = 10.dp),
        )

        if (showLab) {
            CustomLabSheet(
                profile = state.customProfile,
                accent = accent,
                onProfileChange = onCustomProfileChanged,
                onPreviewStart = onPressStart,
                onPreviewEnd = onPressEnd,
                onSave = onCustomProfileSaved,
                onReset = onCustomProfileReset,
                onDismiss = {
                    if (state.isHonking) onPressCancel()
                    showLab = false
                },
            )
        }
    }
}

@Composable
private fun ControlsColumn(
    state: HornUiState,
    accent: Color,
    compact: Boolean,
    onSelectHorn: (HornType) -> Unit,
    onSelectSpace: (AcousticSpace) -> Unit,
    onVolumeTriggerChanged: (Boolean) -> Unit,
    onVolumePromptClicked: () -> Unit,
    onVolumePromptDismissed: () -> Unit,
) {
    BrandHeader(
        accent = accent,
        showTagline = !compact,
        modifier = Modifier.padding(top = if (compact) 10.dp else 18.dp, start = 24.dp, end = 24.dp),
    )
    EarProtectionBadge(
        visible = state.audioSafety.earProtectionActive,
        modifier = Modifier.padding(top = 8.dp),
    )
    if (state.showZeroVolumePrompt) {
        Spacer(modifier = Modifier.height(8.dp))
        VolumeWarningCapsule(
            visible = true,
            accent = accent,
            onSetVolume = onVolumePromptClicked,
            onDismiss = onVolumePromptDismissed,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
    }
    Spacer(modifier = Modifier.height(if (compact) 8.dp else 14.dp))
    HornTypeSelector(
        selected = state.selectedHorn,
        enabled = state.preferencesLoaded,
        accent = accent,
        onSelect = onSelectHorn,
        showLabel = !compact,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
    )
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AcousticSpaceSelector(
            selected = state.acousticSpace,
            accent = accent,
            onSelect = onSelectSpace,
            modifier = Modifier.weight(1f),
        )
        VolumeTriggerChip(
            enabled = state.volumeTriggerEnabled,
            accent = accent,
            onChanged = onVolumeTriggerChanged,
        )
    }
}

@Composable
private fun HornHero(
    state: HornUiState,
    accent: Color,
    visualStyle: HornVisualStyle,
    expression: Float,
    rms: Float,
    buttonDescription: String,
    buttonStateDescription: String,
    buttonLabel: String,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
    onPressCancel: () -> Unit,
    onExpressionChanged: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        val diameter = minOf(maxWidth * 0.70f, maxHeight * 0.82f, 340.dp)
        val waveDiameter = diameter * 1.55f
        AudioAura(
            accent = accent,
            rms = rms,
            active = state.isHonking,
            modifier = Modifier.size(diameter * 1.34f),
        )
        SoundWaves(
            active = state.isHonking,
            rms = rms,
            accent = accent,
            style = visualStyle.wave,
            modifier = Modifier.size(waveDiameter),
        )
        HornButton(
            isHonking = state.isHonking,
            contentDescription = buttonDescription,
            stateDescription = buttonStateDescription,
            label = buttonLabel,
            diameter = diameter,
            accent = accent,
            iconRes = visualStyle.iconRes,
            rms = rms,
            expression = expression,
            onPressStart = onPressStart,
            onPressEnd = onPressEnd,
            onPressCancel = onPressCancel,
            onExpressionChanged = onExpressionChanged,
        )
    }
}

@Composable
private fun AmbientBackdrop(accent: Color) {
    val background = MaterialTheme.colorScheme.background
    val surface = MaterialTheme.colorScheme.surface
    val outline = MaterialTheme.colorScheme.outline

    Canvas(modifier = Modifier.fillMaxSize()) {
        drawRect(
            brush =
                Brush.verticalGradient(
                    colors = listOf(background, surface.copy(alpha = 0.72f), background),
                ),
        )

        val center = Offset(size.width / 2f, size.height * 0.49f)
        val maxDimension = maxOf(size.width, size.height)
        drawCircle(
            brush =
                Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = 0.10f), Color.Transparent),
                    center = center,
                    radius = maxDimension * 0.52f,
                ),
            radius = maxDimension * 0.52f,
            center = center,
        )

        val gridStep = 56.dp.toPx()
        var x = 0f
        while (x < size.width) {
            drawLine(outline.copy(alpha = 0.045f), Offset(x, 0f), Offset(x, size.height), 1f)
            x += gridStep
        }
        var y = 0f
        while (y < size.height) {
            drawLine(outline.copy(alpha = 0.04f), Offset(0f, y), Offset(size.width, y), 1f)
            y += gridStep
        }

        val ringBase = min(size.width, size.height) * 0.22f
        repeat(4) { index ->
            drawCircle(
                color = outline.copy(alpha = 0.055f - index * 0.008f),
                radius = ringBase * (index + 1),
                center = center,
                style = Stroke(width = 1.dp.toPx()),
            )
        }

        drawCircle(
            brush =
                Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = 0.045f), Color.Transparent),
                    center = Offset(size.width / 2f, size.height * 1.02f),
                    radius = size.width * 0.7f,
                ),
            radius = size.width * 0.7f,
            center = Offset(size.width / 2f, size.height * 1.02f),
        )
    }
}

@Composable
private fun SoundWaves(
    active: Boolean,
    rms: Float,
    accent: Color,
    style: WaveStyle,
    modifier: Modifier = Modifier,
) {
    // Idle screens must stay frame-idle; inspection mode keeps previews and UI tests deterministic.
    if (!active || LocalInspectionMode.current) return

    val transition = rememberInfiniteTransition(label = "hornWaves")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = style.durationMillis, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
        label = "hornWaveProgress",
    )
    val pressure = 0.35f + rms.coerceIn(0f, 1f) * 0.9f

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val baseRadius = min(size.width, size.height) * 0.315f
        val ringCount = style.ringCount.coerceAtLeast(1)

        repeat(ringCount) { index ->
            val phase = (progress + index / ringCount.toFloat()) % 1f
            val radius = baseRadius * (1f + phase * style.expansion) * (0.94f + rms * 0.10f)
            val stroke = (style.strokeWidthDp - phase * 0.65f).coerceAtLeast(0.35f)
            drawCircle(
                color = accent.copy(alpha = (1f - phase) * style.baseAlpha * pressure),
                radius = radius,
                center = center,
                style = Stroke(width = stroke.dp.toPx()),
            )
        }

        drawCircle(
            color = accent.copy(alpha = style.haloAlpha * pressure),
            radius = baseRadius * 1.12f,
            center = center,
        )
    }
}

@Composable
private fun BrandHeader(
    accent: Color,
    showTagline: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(6.dp)
                        .background(accent, CircleShape),
            )
            Spacer(modifier = Modifier.size(9.dp))
            Text(
                text = stringResource(R.string.brand_name),
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.labelLarge,
                letterSpacing = 4.sp,
            )
        }
        if (showTagline) {
            Spacer(modifier = Modifier.height(7.dp))
            Text(
                text = stringResource(R.string.brand_tagline),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 1.6.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun Footer(
    state: HornUiState,
    accent: Color,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val isError = state.status is HornStatus.Error
    val statusColor =
        when {
            isError -> MaterialTheme.colorScheme.error
            state.isHonking -> accent
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    val statusText =
        stringResource(
            when {
                isError -> R.string.status_error
                state.isHonking -> R.string.status_honking
                else -> R.string.status_idle
            },
        )
    val hint =
        stringResource(
            when {
                isError -> R.string.horn_hint_error
                state.isHonking -> R.string.horn_hint_honking
                else -> R.string.horn_hint
            },
        )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier =
                Modifier
                    .background(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
                        shape = RoundedCornerShape(50),
                    ).border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f),
                        shape = RoundedCornerShape(50),
                    ).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(6.dp)
                        .background(statusColor, CircleShape),
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = statusText,
                color = statusColor,
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 1.5.sp,
            )
        }

        if (!compact) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = hint,
                modifier = Modifier.widthIn(max = 380.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }

        if (state.status is HornStatus.Error) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = state.status.failure.message(),
                modifier = Modifier.widthIn(max = 380.dp),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun HornFailure.message(): String =
    stringResource(
        when (this) {
            HornFailure.AUDIO_FOCUS_DENIED -> R.string.horn_error_focus
            HornFailure.AUDIO_ENGINE_UNAVAILABLE -> R.string.horn_error_audio
            HornFailure.INTERNAL -> R.string.horn_error_internal
        },
    )

@Suppress("UnusedPrivateMember")
@Preview(name = "Dark Car", showBackground = true, backgroundColor = 0xFF0B0E11, widthDp = 360, heightDp = 800)
@Composable
private fun HornScreenDarkPreview() {
    AutoHornTheme(darkTheme = true) {
        HornScreen(
            state =
                HornUiState(
                    selectedHorn = HornType.CAR,
                    preferencesLoaded = true,
                ),
            onPressStart = {},
            onPressEnd = {},
            onPressCancel = {},
            onSelectHorn = {},
        )
    }
}

@Suppress("UnusedPrivateMember")
@Preview(name = "Light Train", showBackground = true, backgroundColor = 0xFFF3EDE3, widthDp = 360, heightDp = 800)
@Composable
private fun HornScreenLightPreview() {
    AutoHornTheme(darkTheme = false) {
        HornScreen(
            state =
                HornUiState(
                    selectedHorn = HornType.TRAIN,
                    status = HornStatus.Honking,
                    preferencesLoaded = true,
                ),
            onPressStart = {},
            onPressEnd = {},
            onPressCancel = {},
            onSelectHorn = {},
        )
    }
}
