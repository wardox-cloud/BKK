package app.murmur

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle

/**
 * Starts or stops a meeting recording without showing anything, for the
 * Quick Settings tile and the home-screen widget. Android only lets an app
 * start the microphone from something on screen, which this briefly is.
 */
class RecordActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when {
            Meetings.recording != null -> MeetingService.stop(this)
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
                // The main screen asks for the mic, then starts recording.
                startActivity(MainActivity.intent(this).putExtra(MainActivity.EXTRA_RECORD, true))
            else -> MeetingService.record(this)
        }
        finish()
    }

    companion object {
        fun intent(activity: android.content.Context) =
            Intent(activity, RecordActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}
