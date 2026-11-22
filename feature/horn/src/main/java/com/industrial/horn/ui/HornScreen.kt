package com.industrial.horn.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.industrial.horn.audio.AcousticSpace
import com.industrial.horn.audio.AudioTelemetry
import com.industrial.horn.audio.CustomHornProfile
import com.industrial.horn.audio.HornType
import com.industrial.horn.feature.R
import com.industrial.horn.presentation.HornFailure
import com.industrial.horn.presentation.HornStatus
import com.industrial.horn.presentation.HornUiState
import com.industrial.horn.ui.theme.AmbientMeshBackground
import com.industrial.horn.ui.theme.AmbientPalettes
import com.industrial.horn.ui.theme.AppleGlassSurface
import com.industrial.horn.ui.theme.AppleRadii
import com.industrial.horn.ui.theme.AudioAura
import com.industrial.horn.ui.theme.DynamicPillBanner
import com.industrial.horn.ui.theme.HornTheme
import com.industrial.horn.ui.theme.drawAmbientMesh
import com.industrial.horn.ui.theme.rememberAmbientMeshSpec
import com.industrial.horn.ui.theme.revealStagger
import kotlin.math.min
import com.industrial.horn.designsystem.R as DesignR

/**
 * Main horn screen (v3.5 Apple Glass). The single-button interaction is untouched; the visuals
 * are layered glass over a breathing ambient mesh, and every high-frequency value (RMS envelope,
 * expression drag) reaches the draw layer through deferred reads.
 */
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
    readWaveformStereo: (FloatArray, FloatArray) -> Int = { _, _ -> 0 },
    revealProgress: () -> Float = { 1f },
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

    // Stable callback identities: a parent recomposition (e.g. telemetry ticks) must not
    // invalidate the heavy visual subtree through fresh lambda instances. Every wrapper reads
    // its target through rememberUpdatedState, so behavior stays live while identity stays put.
    val currentState by rememberUpdatedState(state)
    val currentOnPressStart by rememberUpdatedState(onPressStart)
    val currentOnPressEnd by rememberUpdatedState(onPressEnd)
    val currentOnPressCancel by rememberUpdatedState(onPressCancel)
    val currentOnExpressionChanged by rememberUpdatedState(onExpressionChanged)
    val currentOnSelectHorn by rememberUpdatedState(onSelectHorn)
    val pressStart = remember { { currentOnPressStart() } }
    val pressEnd = remember { { currentOnPressEnd() } }
    val pressCancel = remember { { currentOnPressCancel() } }
    val changeExpression =
        remember {
            { value: Float ->
                expression = value
                currentOnExpressionChanged(value)
            }
        }
    val selectHorn =
        remember<(HornType) -> Unit> {
            { type ->
                expression = 0f
                if (type == HornType.CUSTOM) {
                    if (currentState.preferencesLoaded) {
                        currentOnSelectHorn(type)
                        showLab = true
                    }
                } else {
                    currentOnSelectHorn(type)
                    showLab = false
                }
            }
        }

    // Deferred-read providers: RMS and expression updates redraw only, never recompose.
    val rmsState = rememberUpdatedState(telemetry.rms)
    val expressionState = rememberUpdatedState(expression)
    val rmsProvider = remember { { rmsState.value } }
    val expressionProvider = remember { { expressionState.value } }

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
        val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f

        val meshSpec =
            rememberAmbientMeshSpec(
                palette = AmbientPalettes.of(selectedHorn.ambientKey()),
                dark = dark,
                rmsProvider = rmsProvider,
                honking = isHonking,
            )
        AmbientMeshBackground(
            spec = meshSpec,
            modifier = Modifier.fillMaxSize(),
        )
        // Shared by the glass panels: re-draws the exact mesh layer behind each panel so the
        // RenderEffect blur is a true backdrop blur instead of a self-blur.
        val glassBackdrop: DrawScope.(Offset) -> Unit =
            remember(meshSpec) {
                { origin -> drawAmbientMesh(meshSpec, origin) }
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
                    BrandHeader(
                        accent = accent,
                        showTagline = !lowHeight,
                        modifier =
                            Modifier
                                .revealEntrance(revealProgress, 0)
                                .padding(top = 52.dp, start = 4.dp, end = 4.dp),
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    GlassConsole(
                        state = state,
                        accent = accent,
                        onSelectHorn = selectHorn,
                        onSelectSpace = onSelectSpace,
                        onVolumeTriggerChanged = onVolumeTriggerChanged,
                        backdrop = glassBackdrop,
                        modifier = Modifier.revealEntrance(revealProgress, 1),
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Footer(
                        state = state,
                        compact = false,
                        modifier =
                            Modifier
                                .revealEntrance(revealProgress, 2)
                                .padding(bottom = 24.dp),
                    )
                }
                HornHero(
                    state = state,
                    accent = accent,
                    visualStyle = visualStyle,
                    rmsProvider = rmsProvider,
                    expressionProvider = expressionProvider,
                    buttonDescription = buttonDescription,
                    buttonStateDescription = buttonStateDescription,
                    buttonLabel = buttonLabel,
                    onPressStart = pressStart,
                    onPressEnd = pressEnd,
                    onPressCancel = pressCancel,
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
                BrandHeader(
                    accent = accent,
                    showTagline = !lowHeight,
                    modifier =
                        Modifier
                            .revealEntrance(revealProgress, 0)
                            .padding(
                                top = if (lowHeight) 48.dp else 56.dp,
                                start = 24.dp,
                                end = 24.dp,
                            ),
                )
                HornHero(
                    state = state,
                    accent = accent,
                    visualStyle = visualStyle,
                    rmsProvider = rmsProvider,
                    expressionProvider = expressionProvider,
                    buttonDescription = buttonDescription,
                    buttonStateDescription = buttonStateDescription,
                    buttonLabel = buttonLabel,
                    onPressStart = pressStart,
                    onPressEnd = pressEnd,
                    onPressCancel = pressCancel,
                    onExpressionChanged = changeExpression,
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                )
                GlassConsole(
                    state = state,
                    accent = accent,
                    onSelectHorn = selectHorn,
                    onSelectSpace = onSelectSpace,
                    onVolumeTriggerChanged = onVolumeTriggerChanged,
                    backdrop = glassBackdrop,
                    modifier =
                        Modifier
                            .revealEntrance(revealProgress, 1)
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                )
                Footer(
                    state = state,
                    compact = lowHeight,
                    modifier =
                        Modifier
                            .revealEntrance(revealProgress, 2)
                            .padding(bottom = 10.dp, start = 28.dp, end = 28.dp),
                )
            }
        }

        // Dynamic-island banner: zero-volume rescue > ear protection > engine/space status.
        DynamicPillBanner(
            visible = true,
            accent = accent,
            backdrop = glassBackdrop,
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp),
        ) {
            when {
                state.showZeroVolumePrompt -> {
                    VolumeWarningCapsule(
                        visible = true,
                        accent = accent,
                        onSetVolume = onVolumePromptClicked,
                        onDismiss = onVolumePromptDismissed,
                    )
                }

                state.audioSafety.earProtectionActive -> {
                    EarProtectionBadge(
                        visible = true,
                        accent = accent,
                    )
                }

                else -> {
                    Box(
                        modifier =
                            Modifier
                                .size(6.dp)
                                .background(accent, CircleShape),
                    )
                    Text(
                        text = state.status.bannerLabel(),
                        color =
                            if (state.status is HornStatus.Error) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onBackground
                            },
                        fontSize = 10.sp,
                        letterSpacing = 1.2.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = state.acousticSpace.displayName(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        letterSpacing = 1.2.sp,
                    )
                }
            }
        }

        DebugAudioOverlayHost(
            accent = accent,
            telemetry = telemetry,
            readWaveform = readWaveform,
            readWaveformStereo = readWaveformStereo,
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 6.dp, end = 10.dp),
        )

        if (showLab) {
            CustomLabSheet(
                profile = state.customProfile,
                accent = accent,
                onProfileChange = onCustomProfileChanged,
                onPreviewStart = pressStart,
                onPreviewEnd = pressEnd,
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

/**
 * Staggered MERGE-handoff entrance for one main-UI island. Deferred read inside graphicsLayer:
 * startup frames redraw without recomposition, and a default reveal provider of 1 keeps the
 * screen pixel-identical outside the wake animation.
 */
private fun Modifier.revealEntrance(
    reveal: () -> Float,
    index: Int,
): Modifier =
    this.graphicsLayer {
        val progress = revealStagger(reveal(), index)
        alpha = progress
        translationY = (1f - progress) * 24f * density
    }

@Composable
private fun HornStatus.bannerLabel(): String =
    stringResource(
        when (this) {
            is HornStatus.Error -> R.string.status_error
            HornStatus.Honking -> R.string.status_honking
            HornStatus.Idle -> R.string.status_idle
        },
    )

/**
 * Floating glass console: voice selector, acoustic space and the volume-key trigger, grouped in
 * one frosted island with concentric radii.
 */
@Composable
private fun GlassConsole(
    state: HornUiState,
    accent: Color,
    onSelectHorn: (HornType) -> Unit,
    onSelectSpace: (AcousticSpace) -> Unit,
    onVolumeTriggerChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    backdrop: (DrawScope.(Offset) -> Unit)? = null,
) {
    AppleGlassSurface(
        modifier = modifier.widthIn(max = 460.dp),
        shape = RoundedCornerShape(AppleRadii.Panel),
        rimAccent = accent,
        frostAlpha = 0.08f,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        backdrop = backdrop,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            HornTypeSelector(
                selected = state.selectedHorn,
                enabled = state.preferencesLoaded,
                accent = accent,
                onSelect = onSelectHorn,
                showLabel = false,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
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
    }
}

@Composable
private fun HornHero(
    state: HornUiState,
    accent: Color,
    visualStyle: HornVisualStyle,
    rmsProvider: () -> Float,
    expressionProvider: () -> Float,
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
            rmsProvider = rmsProvider,
            active = state.isHonking,
            modifier = Modifier.size(diameter * 1.34f),
        )
        SoundWaves(
            active = state.isHonking,
            rmsProvider = rmsProvider,
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
            rmsProvider = rmsProvider,
            expressionProvider = expressionProvider,
            onPressStart = onPressStart,
            onPressEnd = onPressEnd,
            onPressCancel = onPressCancel,
            onExpressionChanged = onExpressionChanged,
        )
    }
}

@Composable
private fun SoundWaves(
    active: Boolean,
    rmsProvider: () -> Float,
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

    Canvas(modifier = modifier) {
        val rms = rmsProvider().coerceIn(0f, 1f)
        val pressure = 0.35f + rms * 0.9f
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
            Icon(
                painter = painterResource(DesignR.drawable.ic_brand_mark),
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(15.dp),
            )
            Spacer(modifier = Modifier.size(8.dp))
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
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val hint =
        stringResource(
            when {
                state.status is HornStatus.Error -> R.string.horn_hint_error
                state.isHonking -> R.string.horn_hint_honking
                else -> R.string.horn_hint
            },
        )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!compact) {
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
    HornTheme(darkTheme = true) {
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
    HornTheme(darkTheme = false) {
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
