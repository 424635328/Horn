package com.industrial.horn.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.industrial.horn.data.WearCommand
import com.industrial.horn.data.WearProtocol
import com.industrial.horn.data.WearStatus
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class WearMainActivity : ComponentActivity() {
    private var phoneStatus by mutableStateOf<WearStatus?>(null)
    private var localHolding by mutableStateOf(false)

    private val statusListener =
        MessageClient.OnMessageReceivedListener { event: MessageEvent ->
            if (event.path == WearProtocol.PATH_STATUS) {
                WearProtocol.decodeStatus(event.data)?.let { status ->
                    runOnUiThread { phoneStatus = status }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                WearHornScreen(
                    isHolding = localHolding,
                    phoneStatus = phoneStatus,
                    onHoldStart = {
                        localHolding = true
                        WearLink.sendCommand(this, WearCommand.START)
                    },
                    onHoldEnd = {
                        localHolding = false
                        WearLink.sendCommand(this, WearCommand.STOP)
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        WearLink.registerStatusListener(this, statusListener)
    }

    override fun onPause() {
        if (localHolding) {
            localHolding = false
            WearLink.sendCommand(this, WearCommand.STOP)
        }
        WearLink.unregisterStatusListener(this, statusListener)
        super.onPause()
    }
}

@Composable
private fun WearHornScreen(
    isHolding: Boolean,
    phoneStatus: WearStatus?,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
) {
    // Carbon palette subset of the phone cockpit: the watch is a remote instrument, so it uses
    // the same machined language at wrist scale instead of a shrunken copy of the phone UI.
    val accent = Color(0xFFFFB547)
    val textPrimary = Color(0xFFF4F6F9)
    val textSecondary = Color(0xFF8C94A3)
    val hairline = Color(0xFF2C313C)
    val secondary =
        if (phoneStatus?.isHonking == true) {
            "PHONE HONKING"
        } else if (phoneStatus != null) {
            "PHONE READY"
        } else {
            "PAIR YOUR PHONE"
        }

    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color(0xFF0D0F12))
                .testTag("wear_horn_screen"),
        contentAlignment = Alignment.Center,
    ) {
        val diameter = min(maxWidth.value, maxHeight.value) * 0.70f
        val pressScale by animateFloatAsState(
            targetValue = if (isHolding) 0.97f else 1f,
            animationSpec = spring(dampingRatio = 0.9f, stiffness = 900f),
            label = "wearPressScale",
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension / 2f
            val outer = radius * 0.94f
            val inner = radius * 0.86f
            // 24 graduations around the dial, lit from 12 o'clock while holding.
            for (index in 0 until 24) {
                val angle = (-90f + index * 15f) * (Math.PI.toFloat() / 180f)
                val major = index % 6 == 0
                val length = if (major) outer - inner else (outer - inner) * 0.55f
                drawLine(
                    color = if (isHolding) accent else textSecondary.copy(alpha = 0.5f),
                    start =
                        Offset(
                            center.x + cos(angle) * (outer - length),
                            center.y + sin(angle) * (outer - length),
                        ),
                    end = Offset(center.x + cos(angle) * outer, center.y + sin(angle) * outer),
                    strokeWidth = if (major) 2f else 1f,
                )
            }
            drawCircle(color = hairline, radius = outer, center = center, style = Stroke(1f))
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "HORN",
                color = textSecondary,
                style = MaterialTheme.typography.labelMedium,
                letterSpacing = 3.sp,
            )
            Surface(
                modifier =
                    Modifier
                        .size(diameter.dp)
                        .graphicsLayer {
                            scaleX = pressScale
                            scaleY = pressScale
                        }.testTag("wear_horn_button")
                        .semantics {
                            role = Role.Button
                            contentDescription = "Hold to honk on the phone"
                        }.pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume()
                                onHoldStart()
                                try {
                                    waitForUpOrCancellation()
                                } finally {
                                    onHoldEnd()
                                }
                            }
                        },
                shape = CircleShape,
                color = Color(0xFF16191F),
                border =
                    androidx.compose.foundation.BorderStroke(
                        width = 1.dp,
                        color = accent.copy(alpha = if (isHolding) 1f else 0.55f),
                    ),
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.radialGradient(
                                    colors =
                                        if (isHolding) {
                                            listOf(accent.copy(alpha = 0.42f), Color(0xFF16191F))
                                        } else {
                                            listOf(Color(0xFF2A3039), Color(0xFF12151A))
                                        },
                                ),
                            ),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (isHolding) "HONK" else "HOLD",
                            color = if (isHolding) Color(0xFF0D0F12) else textPrimary,
                            fontWeight = FontWeight.Black,
                            fontSize = 20.sp,
                            letterSpacing = 2.sp,
                        )
                        Text(
                            text = "2CH · 48k",
                            color = if (isHolding) Color(0xFF0D0F12).copy(alpha = 0.7f) else textSecondary,
                            fontSize = 8.sp,
                            letterSpacing = 0.8.sp,
                        )
                    }
                }
            }
            Text(
                text = secondary,
                color = textSecondary,
                fontSize = 9.sp,
                letterSpacing = 1.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}
