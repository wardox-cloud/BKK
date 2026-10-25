package app.murmur

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.widget.Toast
import app.murmur.core.Dictation
import app.murmur.core.DictationException
import app.murmur.core.DictationSettings
import app.murmur.core.MeetingPipeline
import app.murmur.core.MeetingStore
import app.murmur.core.MeetingStore.Status
import app.murmur.core.Splitter
import app.murmur.core.Wav
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Records meetings and writes them down, in the background with the screen
 * off. While recording, every few minutes of audio is written down as soon as
 * it's recorded, so the notes are ready about a minute after you stop. The
 * audio itself stays on the phone.
 */
class MeetingService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val jobs = AtomicInteger(0)
    private val notifications get() = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    private lateinit var store: MeetingStore
    private var dictation: Dictation? = null
    private var recorder: Recorder? = null
    private var wake: PowerManager.WakeLock? = null

    private inner class Recorder(val id: String) {
        val mic = Meetings.microphone()
        val splitter: Splitter = Meetings.splitter()
        val audio: AudioWriter? = runCatching { Meetings.audioWriter(File(store.folder(id), AUDIO)) }.getOrNull()
        val lock = Any()

        @Volatile
        var paused = false

        @Volatile
        var samples = 0L

        val elapsedMs get() = samples * 1000 / Wav.SAMPLE_RATE
    }

    private val tick = object : Runnable {
        override fun run() {
            val current = recorder ?: return
            Meetings.recording = Meetings.Recording(current.id, current.elapsedMs, current.paused)
            Meetings.changed()
            main.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = Meetings.store(this)
        Meetings.serviceRunning = true
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android requires this promptly after startForegroundService, whatever happens next.
        goForeground()
        when (intent?.action) {
            ACTION_RECORD -> startRecording()
            ACTION_PAUSE -> setPaused(true)
            ACTION_RESUME -> setPaused(false)
            ACTION_STOP -> stopRecording()
            ACTION_PROCESS -> intent.getStringExtra(EXTRA_ID)?.let { process(it, final = true) }
        }
        finishIfIdle()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        recorder?.let { runCatching { it.mic.stop() } }
        main.removeCallbacksAndMessages(null)
        worker.shutdown()
        wake?.let { if (it.isHeld) it.release() }
        Meetings.serviceRunning = false
        Meetings.recording = null
        super.onDestroy()
    }

    // ---------- Recording ----------

    private fun startRecording() {
        if (recorder != null) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast(getString(R.string.meeting_needs_mic))
            return
        }
        val meeting = store.create(audio = AUDIO)
        val current = Recorder(meeting.id)
        try {
            current.mic.start { samples -> heard(current, samples) }
        } catch (e: Exception) {
            current.audio?.let { runCatching { it.close() } }
            store.delete(meeting.id)
            toast(getString(R.string.mic_failed, e.message))
            return
        }
        recorder = current
        holdWakeLock()
        goForeground()
        Meetings.recording = Meetings.Recording(meeting.id, 0, false)
        stateChanged()
        main.post(tick)
        // Android can hand an app silence instead of the mic; catch that early rather than after an hour.
        main.postDelayed({ if (recorder === current && current.mic.isSilenced()) blocked(current) }, 2_500)
    }

    /** Runs on the mic's thread. */
    private fun heard(current: Recorder, samples: ShortArray) {
        if (current.paused) return
        synchronized(current.lock) {
            current.samples += samples.size
            current.audio?.let { audio -> runCatching { audio.write(samples) } }
            for (part in current.splitter.add(samples)) {
                store.addPart(current.id, part)
                process(current.id, final = false)
            }
        }
    }

    private fun setPaused(paused: Boolean) {
        val current = recorder ?: return
        current.paused = paused
        Meetings.recording = Meetings.Recording(current.id, current.elapsedMs, paused)
        stateChanged()
    }

    private fun stopRecording() {
        val current = recorder ?: return
        recorder = null
        main.removeCallbacks(tick)
        current.mic.stop()
        synchronized(current.lock) {
            current.splitter.finish()?.let { store.addPart(current.id, it) }
            current.audio?.let { runCatching { it.close() } }
        }
        store.update(current.id) { it.copy(status = Status.TRANSCRIBING) }
        Meetings.recording = null
        stateChanged()
        process(current.id, final = true)
    }

    private fun blocked(current: Recorder) {
        recorder = null
        main.removeCallbacks(tick)
        current.mic.stop()
        current.audio?.let { runCatching { it.close() } }
        store.delete(current.id)
        Meetings.recording = null
        stateChanged()
        toast(getString(R.string.meeting_mic_blocked))
        finishIfIdle()
    }

    // ---------- Writing it down ----------

    /** Writes down whatever is waiting. With [final], no more audio is coming, so it also writes the notes. */
    private fun process(id: String, final: Boolean) {
        jobs.incrementAndGet()
        worker.execute {
            try {
                Meetings.working = id
                main.post { refreshNotification() }
                val settings = Prefs(this).dictationSettings()
                val pipeline = MeetingPipeline(store, dictation ?: Meetings.dictation(this).also { dictation = it })
                val onChange: (MeetingStore.Meeting) -> Unit = {
                    Meetings.changed()
                    main.post { refreshNotification() }
                }
                if (store.get(id)?.needsSplitting == true) split(id, pipeline, settings, onChange)
                val meeting = pipeline.run(id, settings, recordingOver = final, onChange = onChange)
                if (final) main.post { announce(meeting) }
            } catch (e: Exception) {
                val reason = (e as? DictationException)?.message ?: "Something went wrong (${e.message})."
                runCatching { store.update(id) { it.copy(status = Status.WAITING, message = reason) } }
                Meetings.changed()
            } finally {
                Meetings.working = null
                jobs.decrementAndGet()
                main.post { finishIfIdle() }
            }
        }
    }

    /** Splits a shared recording into parts, writing each down as it goes. Picks up where an earlier try stopped. */
    private fun split(
        id: String,
        pipeline: MeetingPipeline,
        settings: DictationSettings,
        onChange: (MeetingStore.Meeting) -> Unit,
    ) {
        val meeting = store.get(id) ?: return
        val source = Meetings.audioSource(this, File(store.folder(id), meeting.audio ?: return))
        val saved = store.partCount(id)
        var index = 0
        var failing = false
        source.parts { pcm ->
            if (index++ < saved) return@parts
            store.addPart(id, pcm)
            // After a failure, keep splitting but leave the writing down for the final run.
            if (!failing) failing = pipeline.run(id, settings, recordingOver = false, onChange = onChange).message != null
        }
        store.update(id) { it.copy(needsSplitting = false, durationMs = maxOf(it.durationMs, source.durationMs)) }
    }

    /** Lets you know the notes are ready, or why they aren't. */
    private fun announce(meeting: MeetingStore.Meeting) {
        val done = meeting.status == Status.DONE
        val notification = Notification.Builder(this, DONE_CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(if (done) R.string.meeting_ready else R.string.meeting_waiting))
            .setContentText(if (done) meeting.title else meeting.message)
            .setContentIntent(MeetingActivity.open(this, meeting.id))
            .setAutoCancel(true)
            .build()
        notifications.notify(meeting.id.hashCode(), notification)
    }

    // ---------- Foreground ----------

    private fun goForeground() {
        val notification = progressNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            if (recorder != null) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            startForeground(PROGRESS_ID, notification, types)
        } else {
            startForeground(PROGRESS_ID, notification)
        }
    }

    private fun finishIfIdle() {
        if (recorder != null || jobs.get() > 0) {
            refreshNotification()
            return
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        wake?.let { if (it.isHeld) it.release() }
        stopSelf()
    }

    private fun holdWakeLock() {
        val lock = wake ?: (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "murmur:meeting")
            .also { wake = it }
        // Long enough for a very long meeting; released as soon as it's done.
        if (!lock.isHeld) lock.acquire(8 * 60 * 60 * 1000L)
    }

    private fun stateChanged() {
        Meetings.changed()
        refreshNotification()
        MeetingTile.refresh(this)
        MeetingWidget.refresh(this)
    }

    private fun refreshNotification() {
        if (recorder != null || jobs.get() > 0) notifications.notify(PROGRESS_ID, progressNotification())
    }

    private fun progressNotification(): Notification {
        val builder = Notification.Builder(this, PROGRESS_CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        val current = recorder
        if (current != null) {
            builder.setContentTitle(getString(if (current.paused) R.string.meeting_paused else R.string.meeting_recording))
                .setContentIntent(MainActivity.open(this))
                .addAction(
                    Notification.Action.Builder(
                        null,
                        getString(if (current.paused) R.string.meeting_resume else R.string.meeting_pause),
                        command(this, if (current.paused) ACTION_RESUME else ACTION_PAUSE),
                    ).build(),
                )
                .addAction(Notification.Action.Builder(null, getString(R.string.meeting_stop), command(this, ACTION_STOP)).build())
            if (current.paused) {
                builder.setContentText(getString(R.string.meeting_paused_at, Meetings.clock(current.elapsedMs)))
            } else {
                // The chronometer counts recorded time, so pauses don't count.
                builder.setUsesChronometer(true).setWhen(System.currentTimeMillis() - current.elapsedMs).setShowWhen(true)
            }
            return builder.build()
        }
        val meeting = Meetings.working?.let { store.get(it) }
        builder.setContentTitle(getString(R.string.meeting_writing))
        if (meeting != null) {
            builder.setContentIntent(MeetingActivity.open(this, meeting.id))
            val total = (meeting.durationMs / 1000).toInt()
            if (total > 0) {
                builder.setContentText(getString(R.string.meeting_progress, Meetings.clock(meeting.doneMs), Meetings.clock(meeting.durationMs)))
                builder.setProgress(total, (meeting.doneMs / 1000).toInt().coerceAtMost(total), false)
            } else {
                builder.setProgress(0, 0, true)
            }
        }
        return builder.build()
    }

    private fun createChannels() {
        notifications.createNotificationChannel(
            NotificationChannel(PROGRESS_CHANNEL, getString(R.string.channel_meeting), NotificationManager.IMPORTANCE_LOW),
        )
        notifications.createNotificationChannel(
            NotificationChannel(DONE_CHANNEL, getString(R.string.channel_meeting_done), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    private fun toast(message: String) = main.post { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }

    companion object {
        const val ACTION_RECORD = "app.murmur.RECORD"
        const val ACTION_PAUSE = "app.murmur.PAUSE"
        const val ACTION_RESUME = "app.murmur.RESUME"
        const val ACTION_STOP = "app.murmur.STOP"
        const val ACTION_PROCESS = "app.murmur.PROCESS"
        const val EXTRA_ID = "id"
        const val AUDIO = "audio.aac"
        private const val PROGRESS_ID = 10
        private const val PROGRESS_CHANNEL = "meeting"
        private const val DONE_CHANNEL = "meeting-ready"

        /** Starts recording a new meeting. Call it from something on screen: Android only lets a visible app start the mic. */
        fun record(context: Context) = context.startForegroundService(Intent(context, MeetingService::class.java).setAction(ACTION_RECORD))

        fun stop(context: Context) = context.startService(Intent(context, MeetingService::class.java).setAction(ACTION_STOP))

        fun pause(context: Context, paused: Boolean) =
            context.startService(Intent(context, MeetingService::class.java).setAction(if (paused) ACTION_PAUSE else ACTION_RESUME))

        /** Writes down a meeting's waiting parts and notes, for Continue and for shared recordings. */
        fun process(context: Context, id: String) = context.startForegroundService(
            Intent(context, MeetingService::class.java).setAction(ACTION_PROCESS).putExtra(EXTRA_ID, id),
        )

        private fun command(context: Context, action: String): PendingIntent = PendingIntent.getService(
            context,
            action.hashCode(),
            Intent(context, MeetingService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
