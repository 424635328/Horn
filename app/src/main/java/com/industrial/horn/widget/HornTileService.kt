package com.industrial.horn.widget

import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.industrial.horn.R

/** Quick Settings one-tap trigger. Reuses the same foreground-service playback path as the widget. */
class HornTileService : TileService() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        HornWidgetService.start(this)
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            label = getString(R.string.tile_label_active)
        }
        handler.postDelayed({ updateTile() }, 1_400L)
    }

    private fun updateTile() {
        val current = qsTile ?: return
        current.icon = Icon.createWithResource(this, R.drawable.ic_tile_horn)
        current.label = getString(R.string.tile_label)
        current.state =
            if (HornWidgetService.isRunning()) {
                Tile.STATE_ACTIVE
            } else {
                Tile.STATE_INACTIVE
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            current.subtitle = getString(R.string.tile_subtitle)
        }
    }
}
