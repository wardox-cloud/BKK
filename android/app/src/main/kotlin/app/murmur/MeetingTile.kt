package app.murmur

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** A Quick Settings tile: one tap to start recording a meeting, another to stop. */
class MeetingTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val recording = Meetings.recording
        tile.state = if (recording != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(if (recording != null) R.string.tile_stop else R.string.tile_record)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (recording != null) getString(R.string.tile_recording) else null
        }
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        if (Meetings.recording != null) {
            MeetingService.stop(this)
            return
        }
        // Starting the mic needs something on screen, so open the invisible starter.
        val intent = RecordActivity.intent(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        fun refresh(context: Context) {
            runCatching { requestListeningState(context, ComponentName(context, MeetingTile::class.java)) }
        }
    }
}
