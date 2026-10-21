package dev.flcrom.magnet

import android.os.SystemClock
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile: re-zero the ferrofluid wallpaper's magnetometer baseline.
 *  Use with the magnet AWAY from the phone: one tap re-measures the ambient field. */
class ZeroTileService : TileService() {
    override fun onStartListening() {
        qsTile?.let { it.state = Tile.STATE_ACTIVE; it.subtitle = "tap with magnet away"; it.updateTile() }
    }
    override fun onClick() {
        FerrofluidWallpaper.zeroRequestedAt = SystemClock.elapsedRealtime()
        try { java.io.File(filesDir, "zero_request").writeText("1") } catch (_: Throwable) {}
        qsTile?.let { it.subtitle = "zeroed"; it.updateTile() }
    }
}
