package com.industrial.autohorn.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.background
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider

/**
 * 1x1 / 2x2 home-screen widget. The tap target is the whole widget; playback is delegated to a
 * short media-playback foreground service so it remains reliable while the app is not visible.
 */
class HornWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(DpSize(56.dp, 56.dp), DpSize(120.dp, 120.dp)),
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { HornWidgetContent() }
    }
}

@Composable
private fun HornWidgetContent() {
    val compact = LocalSize.current.width < 80.dp
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(Color(0xFF101519)))
            .cornerRadius(24.dp)
            .clickable(actionRunCallback<WidgetHonkAction>())
            .padding(10.dp),
        verticalAlignment = Alignment.Vertical.CenterVertically,
        horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
    ) {
        Text(
            text = if (compact) "HONK" else "AUTOHORN",
            style = TextStyle(
                color = ColorProvider(Color(0xFFFFB547)),
                fontSize = if (compact) 14.sp else 16.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            ),
            maxLines = 1,
        )
        if (!compact) {
            Text(
                text = "HOLD THE HORN",
                style = TextStyle(
                    color = ColorProvider(Color(0xFFA8B0B6)),
                    fontSize = 8.sp,
                    textAlign = TextAlign.Center,
                ),
                maxLines = 1,
            )
        }
    }
}

class WidgetHonkAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        HornWidgetService.start(context)
    }
}

class HornWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HornWidget()
}
