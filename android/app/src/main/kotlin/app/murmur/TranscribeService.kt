package app.murmur

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.util.concurrent.Executors

/**
 * Keeps a transcription going after you leave the screen, with its progress in
 * a notification, and says when it's done.
 */
class TranscribeService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val notifications get() = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    private var running = 0
    private val listener: (Transcription.State) -> Unit = { state ->
        if (state.phase == Transcription.Phase.WORKING) notifications.notify(PROGRESS_ID, progress(state))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannels()
        val notification = progress(Transcription.state)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(PROGRESS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(PROGRESS_ID, notification)
        }
        Transcription.listen(listener)
        running++
        val resume = intent?.getBooleanExtra(EXTRA_RESUME, false) ?: false
        val settings = Prefs(this).dictationSettings()
        // Keeps the phone from pausing Murmur while the screen is off.
        val wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "murmur:transcribe")
            .apply { acquire(3 * 60 * 60 * 1000L) }
        worker.execute {
            try {
                Transcription.run(this, settings, resume)
            } finally {
                if (wake.isHeld) wake.release()
                main.post { finished() }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Transcription.unlisten(listener)
        worker.shutdown()
        super.onDestroy()
    }

    private fun finished() {
        // Continue may have queued another run while this one was ending.
        if (--running > 0) return
        Transcription.unlisten(listener)
        stopForeground(STOP_FOREGROUND_REMOVE)
        val state = Transcription.state
        val done = state.phase == Transcription.Phase.DONE
        val notification = Notification.Builder(this, DONE_CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(if (done) R.string.transcript_ready else R.string.transcription_stopped))
            .setContentText(if (done) getString(R.string.transcript_ready_text) else state.message)
            .setContentIntent(openScreen())
            .setAutoCancel(true)
            .build()
        notifications.notify(DONE_ID, notification)
        stopSelf()
    }

    private fun progress(state: Transcription.State): Notification {
        val builder = Notification.Builder(this, PROGRESS_CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.transcribing_title))
            .setContentIntent(openScreen())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        val total = (state.totalMs / 1000).toInt()
        if (total > 0) {
            builder.setContentText(getString(R.string.transcribe_progress, Transcription.clock(state.doneMs), Transcription.clock(state.totalMs)))
            builder.setProgress(total, (state.doneMs / 1000).toInt().coerceAtMost(total), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun openScreen(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, TranscribeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun createChannels() {
        notifications.createNotificationChannel(
            NotificationChannel(PROGRESS_CHANNEL, getString(R.string.channel_progress), NotificationManager.IMPORTANCE_LOW),
        )
        notifications.createNotificationChannel(
            NotificationChannel(DONE_CHANNEL, getString(R.string.channel_done), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    internal companion object {
        const val EXTRA_RESUME = "resume"
        const val PROGRESS_ID = 1
        const val DONE_ID = 2
        private const val PROGRESS_CHANNEL = "transcription"
        private const val DONE_CHANNEL = "transcript-ready"
    }
}
