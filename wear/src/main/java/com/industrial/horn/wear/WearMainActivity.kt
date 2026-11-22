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
import kotlin.math.min

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
    val accent = Color(0xFFFFB547)
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
                .background(Color(0xFF07090C))
                .testTag("wear_horn_screen"),
        contentAlignment = Alignment.Center,
    ) {
        val diameter = min(maxWidth.value, maxHeight.value) * 0.68f
        val pressScale by animateFloatAsState(
            targetValue = if (isHolding) 0.93f else 1f,
            animationSpec = spring(dampingRatio = 0.55f, stiffness = 650f),
            label = "wearPressScale",
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            repeat(3) { index ->
                drawCircle(
                    color = accent.copy(alpha = 0.10f - index * 0.02f),
                    radius = size.minDimension * (0.30f + index * 0.10f),
                    center = center,
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
            drawCircle(
                brush =
                    Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = 0.10f), Color.Transparent),
                        center = center,
                        radius = size.minDimension * 0.5f,
                    ),
                radius = size.minDimension * 0.5f,
                center = center,
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = "HORN",
                color = accent,
                style = MaterialTheme.typography.labelMedium,
                letterSpacing = 2.5.sp,
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
                color = if (isHolding) accent else Color(0xFF1B232B),
                border =
                    androidx.compose.foundation.BorderStroke(
                        width = 2.dp,
                        color = accent.copy(alpha = 0.65f),
                    ),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = if (isHolding) "HONK" else "HORN",
                        color = if (isHolding) Color(0xFF17120A) else accent,
                        fontWeight = FontWeight.Black,
                        fontSize = 24.sp,
                        letterSpacing = 1.sp,
                    )
                }
            }
            Text(
                text = secondary,
                color = Color(0xFFA8B0B6),
                fontSize = 10.sp,
                letterSpacing = 0.8.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}
