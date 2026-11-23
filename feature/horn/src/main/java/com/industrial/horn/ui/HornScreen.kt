package com.industrial.horn.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.industrial.horn.audio.AcousticSpace
import com.industrial.horn.audio.AudioTelemetry
import com.industrial.horn.audio.CustomHornProfile
import com.industrial.horn.audio.HornType
import com.industrial.horn.data.ThemeMode
import com.industrial.horn.feature.R
import com.industrial.horn.presentation.HornFailure
import com.industrial.horn.presentation.HornStatus
import com.industrial.horn.presentation.HornUiState
import com.industrial.horn.ui.theme.CockpitCanvas
import com.industrial.horn.ui.theme.CockpitStatusRail
import com.industrial.horn.ui.theme.EngravedRule
import com.industrial.horn.ui.theme.HornRadii
import com.industrial.horn.ui.theme.HornText
import com.industrial.horn.ui.theme.HornTheme
import com.industrial.horn.ui.theme.LocalHornColors
import com.industrial.horn.ui.theme.MachinedPanel
import com.industrial.horn.ui.theme.PlateCaption
import com.industrial.horn.ui.theme.revealStagger
import kotlin.math.min
import com.industrial.horn.designsystem.R as DesignR

/**
 * Main cockpit screen (v4.0 Precision Automotive Cockpit).
 *
 * The single-button interaction is untouched: one instrument pad, hold to honk, slide out to
 * cancel, vertical drag bends expression. What changed is the material language — a machined
 * ceramic/carbon instrument panel instead of frosted glass — and the information architecture:
 *
 *   top bar : brand identity · theme position · Pro Sound Lab
 *   rail    : status token · acoustic space · sample rate
 *   centre  : the instrument pad (60-tick RMS ring + 240° expression bezel)
 *   deck    : 5-voice gear strip, 4-room row, volume-key trigger
 *
 * Every high-frequency value (RMS envelope, expression drag) reaches the draw layer through
 * deferred reads; startup reveal runs through one shared clock.
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
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    onCycleThemeMode: () -> Unit = {},
    onSelectThemeMode: (ThemeMode) -> Unit = {},
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
    val colors = LocalHornColors.current
    val dark = colors.isDark
    val isHonking = state.isHonking
    val selectedHorn = state.selectedHorn
    val visualStyle = selectedHorn.visualStyle(dark)
    val accent by animateColorAsState(
        targetValue = visualStyle.accent,
        animationSpec = tween(durationMillis = 260),
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
                .background(colors.canvas)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .testTag("horn_screen"),
    ) {
        val expanded = windowWidthSizeClass != WindowWidthSizeClass.Compact
        val lowHeight = maxHeight < 640.dp

        CockpitCanvas(
            accent = accent,
            active = isHonking,
            rmsProvider = rmsProvider,
            modifier = Modifier.fillMaxSize(),
        )

        Column(modifier = Modifier.fillMaxSize()) {
            CockpitTopBar(
                accent = accent,
                themeMode = themeMode,
                showTagline = !lowHeight,
                onCycleThemeMode = onCycleThemeMode,
                onSelectThemeMode = onSelectThemeMode,
                onOpenLab = { showLab = true },
                modifier = Modifier.revealEntrance(revealProgress, 0),
            )
            CockpitStatusRail(
                statusText = state.status.bannerLabel(),
                detailText = "${state.acousticSpace.displayName()} · ${selectedHorn.displayName()}",
                ledColor = if (state.status is HornStatus.Error) colors.fault else accent,
                statusColor =
                    if (state.status is HornStatus.Error) {
                        colors.fault
                    } else {
                        colors.textPrimary
                    },
                modifier = Modifier.revealEntrance(revealProgress, 1),
            ) {
                if (state.audioSafety.earProtectionActive) {
                    EarProtectionBadge(visible = true, accent = accent)
                } else {
                    Text(
                        text = "48 kHz · 2CH",
                        color = colors.textTertiary,
                        style = HornText.Data,
                    )
                }
            }
            if (state.showZeroVolumePrompt) {
                VolumeWarningStrip(
                    accent = accent,
                    onSetVolume = onVolumePromptClicked,
                    onDismiss = onVolumePromptDismissed,
                )
            }

            if (expanded) {
                Row(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .testTag("expanded_layout"),
                ) {
                    Column(
                        modifier =
                            Modifier
                                .weight(0.46f)
                                .fillMaxHeight()
                                .padding(start = 24.dp, end = 12.dp, top = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Spacer(modifier = Modifier.weight(1f))
                        CockpitDeck(
                            state = state,
                            accent = accent,
                            onSelectHorn = selectHorn,
                            onSelectSpace = onSelectSpace,
                            onVolumeTriggerChanged = onVolumeTriggerChanged,
                            modifier = Modifier.revealEntrance(revealProgress, 2),
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        TelemetryPlate(
                            telemetry = telemetry,
                            accent = accent,
                            modifier =
                                Modifier
                                    .revealEntrance(revealProgress, 3)
                                    .widthIn(max = 520.dp),
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        CockpitFooter(
                            state = state,
                            compact = false,
                            modifier =
                                Modifier
                                    .revealEntrance(revealProgress, 4)
                                    .padding(bottom = 18.dp),
                        )
                    }
                    InstrumentZone(
                        state = state,
                        accent = accent,
                        visualStyle = visualStyle,
                        rmsProvider = rmsProvider,
                        expressionProvider = expressionProvider,
                        buttonDescription = buttonDescription,
                        buttonStateDescription = buttonStateDescription,
                        buttonLabel = buttonLabel,
                        maxDiameter = 360.dp,
                        onPressStart = pressStart,
                        onPressEnd = pressEnd,
                        onPressCancel = pressCancel,
                        onExpressionChanged = changeExpression,
                        modifier =
                            Modifier
                                .weight(0.54f)
                                .fillMaxHeight()
                                .padding(end = 24.dp, top = 8.dp, bottom = 12.dp),
                    )
                }
            } else {
                Column(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .testTag("compact_layout"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    InstrumentZone(
                        state = state,
                        accent = accent,
                        visualStyle = visualStyle,
                        rmsProvider = rmsProvider,
                        expressionProvider = expressionProvider,
                        buttonDescription = buttonDescription,
                        buttonStateDescription = buttonStateDescription,
                        buttonLabel = buttonLabel,
                        maxDiameter = 320.dp,
                        onPressStart = pressStart,
                        onPressEnd = pressEnd,
                        onPressCancel = pressCancel,
                        onExpressionChanged = changeExpression,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                    CockpitDeck(
                        state = state,
                        accent = accent,
                        onSelectHorn = selectHorn,
                        onSelectSpace = onSelectSpace,
                        onVolumeTriggerChanged = onVolumeTriggerChanged,
                        modifier =
                            Modifier
                                .revealEntrance(revealProgress, 2)
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                    )
                    CockpitFooter(
                        state = state,
                        compact = lowHeight,
                        modifier =
                            Modifier
                                .revealEntrance(revealProgress, 3)
                                .padding(top = 8.dp, bottom = 8.dp, start = 24.dp, end = 24.dp),
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
                    .padding(top = 92.dp, end = 8.dp),
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
 * Staggered MERGE-handoff entrance for one cockpit island. Deferred read inside graphicsLayer:
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
        translationY = (1f - progress) * 18f * density
    }

@Composable
private fun CockpitTopBar(
    accent: Color,
    themeMode: ThemeMode,
    showTagline: Boolean,
    onCycleThemeMode: () -> Unit,
    onSelectThemeMode: (ThemeMode) -> Unit,
    onOpenLab: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalHornColors.current
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(DesignR.drawable.ic_brand_mark),
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(17.dp),
        )
        Spacer(modifier = Modifier.size(9.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.brand_name),
                color = colors.textPrimary,
                style = MaterialTheme.typography.labelLarge,
                letterSpacing = 4.sp,
                maxLines = 1,
            )
            if (showTagline) {
                Text(
                    text = stringResource(R.string.brand_tagline),
                    color = colors.textTertiary,
                    style = HornText.PlateLabel,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        ThemeKey(
            mode = themeMode,
            accent = accent,
            onCycle = onCycleThemeMode,
            onSelect = onSelectThemeMode,
        )
        Spacer(modifier = Modifier.size(8.dp))
        LabKey(
            accent = accent,
            onClick = onOpenLab,
        )
    }
}

/**
 * Theme position key. A single tap advances AUTO → DAY → NIGHT (the frequent action); a long
 * press — or the TalkBack custom action — opens the explicit three-position menu, so the
 * preference is reachable without discovering a gesture.
 */
@Composable
private fun ThemeKey(
    mode: ThemeMode,
    accent: Color,
    onCycle: () -> Unit,
    onSelect: (ThemeMode) -> Unit,
) {
    val colors = LocalHornColors.current
    var menuOpen by remember { mutableStateOf(false) }
    val modeLabel = stringResource(mode.labelRes())
    val description = stringResource(R.string.theme_mode_description, modeLabel)
    val actionLabel = stringResource(R.string.theme_choose_action)
    Box {
        Row(
            modifier =
                Modifier
                    .height(34.dp)
                    .clip(RoundedCornerShape(HornRadii.Key))
                    .background(colors.panel)
                    .border(1.dp, colors.hairlineStrong, RoundedCornerShape(HornRadii.Key))
                    .combinedClickable(
                        onClick = onCycle,
                        onLongClick = { menuOpen = true },
                    ).semantics(mergeDescendants = true) {
                        contentDescription = description
                        role = Role.Button
                        customActions =
                            listOf(
                                CustomAccessibilityAction(actionLabel) {
                                    menuOpen = true
                                    true
                                },
                            )
                    }.testTag("theme_key")
                    .padding(horizontal = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(mode.iconRes()),
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = modeLabel,
                color = colors.textPrimary,
                style = HornText.Data,
            )
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            containerColor = colors.panel,
            shape = RoundedCornerShape(HornRadii.Instrument),
        ) {
            ThemeMode.entries.forEach { candidate ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(candidate.labelRes()),
                            color =
                                if (candidate == mode) {
                                    accent
                                } else {
                                    colors.textPrimary
                                },
                            style = HornText.Data,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(candidate.iconRes()),
                            contentDescription = null,
                            tint =
                                if (candidate == mode) {
                                    accent
                                } else {
                                    colors.textTertiary
                                },
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    onClick = {
                        onSelect(candidate)
                        menuOpen = false
                    },
                )
            }
        }
    }
}

@Composable
private fun LabKey(
    accent: Color,
    onClick: () -> Unit,
) {
    val colors = LocalHornColors.current
    val description = stringResource(R.string.lab_key_description)
    Box(
        modifier =
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(HornRadii.Key))
                .background(colors.panel)
                .border(1.dp, colors.hairlineStrong, RoundedCornerShape(HornRadii.Key))
                .combinedClickable(onClick = onClick)
                .semantics {
                    contentDescription = description
                    role = Role.Button
                }.testTag("lab_key"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(DesignR.drawable.ic_lab_tune),
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun VolumeWarningStrip(
    accent: Color,
    onSetVolume: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalHornColors.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(colors.panelSunken)
                .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VolumeWarningCapsule(
            visible = true,
            accent = accent,
            onSetVolume = onSetVolume,
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun InstrumentZone(
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
    maxDiameter: Dp = 320.dp,
) {
    val colors = LocalHornColors.current
    Column(
        modifier = modifier.padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "02",
                color = accent,
                style = HornText.Data,
            )
            Spacer(modifier = Modifier.width(7.dp))
            Box(
                modifier =
                    Modifier
                        .width(18.dp)
                        .height(1.dp)
                        .background(colors.hairlineStrong),
            )
            Spacer(modifier = Modifier.width(7.dp))
            Text(
                text = stringResource(R.string.plate_instrument),
                color = colors.textSecondary,
                style = HornText.PlateLabel,
            )
        }
        BoxWithConstraints(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            val diameter = minOf(maxWidth * 0.94f, maxHeight * 0.94f, maxDiameter)
            HornInstrumentPad(
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
}

@Composable
private fun TelemetryPlate(
    telemetry: AudioTelemetry,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    MachinedPanel(
        modifier = modifier.fillMaxWidth(),
        sunken = true,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    ) {
        PlateCaption(
            index = "04",
            title = stringResource(R.string.plate_telemetry),
            accent = accent,
        )
        Spacer(modifier = Modifier.height(8.dp))
        TelemetryRow(label = "ENGINE", value = telemetry.engineName.ifBlank { "—" })
        TelemetryRow(label = "OUTPUT", value = telemetry.outputMode.displayName)
        TelemetryRow(label = "LATENCY", value = "${"%.1f".format(telemetry.latencyMs)} ms")
        TelemetryRow(label = "RENDER", value = "${telemetry.renderTimeMicros} µs")
        TelemetryRow(label = "XRUN", value = telemetry.xrunCount.toString())
        TelemetryRow(label = "STEREO ρ", value = "%.2f".format(telemetry.stereoCorrelation))
        TelemetryRow(label = "CHANNELS", value = "2 × 48 kHz")
    }
}

@Composable
private fun TelemetryRow(
    label: String,
    value: String,
) {
    val colors = LocalHornColors.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = colors.textTertiary,
            style = HornText.PlateLabel,
            maxLines = 1,
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = value,
            color = colors.textSecondary,
            style = HornText.Data,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CockpitDeck(
    state: HornUiState,
    accent: Color,
    onSelectHorn: (HornType) -> Unit,
    onSelectSpace: (AcousticSpace) -> Unit,
    onVolumeTriggerChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalHornColors.current
    MachinedPanel(
        modifier = modifier.widthIn(max = 520.dp),
        accent = accent,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 11.dp),
    ) {
        PlateCaption(
            index = "01",
            title = stringResource(R.string.plate_voice),
            accent = accent,
        ) {
            Text(
                text = state.selectedHorn.displayName().uppercase(),
                color = colors.textSecondary,
                style = HornText.Data,
            )
        }
        Spacer(modifier = Modifier.height(9.dp))
        HornTypeSelector(
            selected = state.selectedHorn,
            enabled = state.preferencesLoaded,
            accent = accent,
            onSelect = onSelectHorn,
            showLabel = false,
        )
        Spacer(modifier = Modifier.height(11.dp))
        EngravedRule(accent = accent)
        Spacer(modifier = Modifier.height(9.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlateCaption(
                index = "03",
                title = stringResource(R.string.acoustic_space_label),
                accent = accent,
                modifier = Modifier.weight(1f),
            )
            VolumeTriggerChip(
                enabled = state.volumeTriggerEnabled,
                accent = accent,
                onChanged = onVolumeTriggerChanged,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        AcousticSpaceSelector(
            selected = state.acousticSpace,
            accent = accent,
            onSelect = onSelectSpace,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun CockpitFooter(
    state: HornUiState,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalHornColors.current
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
                color = colors.textSecondary,
                style = HornText.Data,
                textAlign = TextAlign.Center,
            )
        }
        if (state.status is HornStatus.Error) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = state.status.failure.message(),
                modifier = Modifier.widthIn(max = 380.dp),
                color = colors.fault,
                style = HornText.Data,
                textAlign = TextAlign.Center,
            )
        }
    }
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

@Composable
private fun HornFailure.message(): String =
    stringResource(
        when (this) {
            HornFailure.AUDIO_FOCUS_DENIED -> R.string.horn_error_focus
            HornFailure.AUDIO_ENGINE_UNAVAILABLE -> R.string.horn_error_audio
            HornFailure.INTERNAL -> R.string.horn_error_internal
        },
    )

@Composable
private fun ThemeMode.labelRes(): Int =
    when (this) {
        ThemeMode.SYSTEM -> R.string.theme_mode_system
        ThemeMode.LIGHT -> R.string.theme_mode_light
        ThemeMode.DARK -> R.string.theme_mode_dark
    }

@DrawableRes
private fun ThemeMode.iconRes(): Int =
    when (this) {
        ThemeMode.SYSTEM -> DesignR.drawable.ic_theme_auto
        ThemeMode.LIGHT -> DesignR.drawable.ic_theme_light
        ThemeMode.DARK -> DesignR.drawable.ic_theme_dark
    }

@Suppress("UnusedPrivateMember")
@Preview(name = "Carbon Cockpit", showBackground = true, backgroundColor = 0xFF0D0F12, widthDp = 360, heightDp = 800)
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
@Preview(name = "Ceramic Cockpit", showBackground = true, backgroundColor = 0xFFF3F3EE, widthDp = 360, heightDp = 800)
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
