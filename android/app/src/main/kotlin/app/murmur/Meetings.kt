package app.murmur

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import app.murmur.core.Dictation
import app.murmur.core.HistoryStore
import app.murmur.core.MeetingStore
import app.murmur.core.Prompts
import app.murmur.core.Splitter
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit

/**
 * What the screens and [MeetingService] share: where meetings and dictation
 * history are kept, which meeting is being recorded, and who wants to hear
 * about changes.
 */
object Meetings {
    /** The meeting being recorded right now. */
    data class Recording(val id: String, val elapsedMs: Long, val paused: Boolean)

    @Volatile
    var recording: Recording? = null
        internal set

    /** The meeting whose parts are being written down right now. */
    @Volatile
    var working: String? = null
        internal set

    /** True while [MeetingService] is running, so anything left half-done is known to be stuck. */
    @Volatile
    var serviceRunning = false
        internal set

    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun store(context: Context) = MeetingStore(File(context.filesDir, "meetings"))

    fun history(context: Context) = HistoryStore(File(context.filesDir, "history.jsonl"))

    /** Calls [listener] on the main thread whenever a meeting or the recording changes. */
    fun listen(listener: () -> Unit) = listeners.add(listener)

    fun unlisten(listener: () -> Unit) = listeners.remove(listener)

    fun changed() {
        main.post { listeners.forEach { it() } }
    }

    private val realDictation: (Context) -> Dictation = { context ->
        Dictation(
            Prompts.load(),
            // A few minutes of audio takes longer than a dictation.
            OkHttpClient.Builder().callTimeout(4, TimeUnit.MINUTES).build(),
            quota = Prefs(context).geminiQuota,
        )
    }
    private val realAudioSource: (Context, File) -> AudioSource = { context, file -> AudioFile(context, Uri.fromFile(file)) }

    // Replaceable in tests, which have no microphone, codecs or internet.
    internal var dictation = realDictation
    internal var microphone: () -> Microphone = { MeetingMic() }
    internal var audioWriter: (File) -> AudioWriter = { AacWriter(it) }
    internal var splitter: () -> Splitter = { Splitter() }
    internal var audioSource = realAudioSource

    /** For tests: back to a fresh start. */
    internal fun reset() {
        recording = null
        working = null
        serviceRunning = false
        listeners.clear()
        dictation = realDictation
        microphone = { MeetingMic() }
        audioWriter = { AacWriter(it) }
        splitter = { Splitter() }
        audioSource = realAudioSource
    }

    /**
     * If Android closed Murmur partway through a meeting, marks it as waiting,
     * so Continue can finish it. Everything recorded up to then is kept.
     */
    fun recover(context: Context) {
        if (serviceRunning) return
        val store = store(context)
        for (meeting in store.list()) {
            if (meeting.status == MeetingStore.Status.RECORDING || meeting.status == MeetingStore.Status.TRANSCRIBING) {
                store.update(meeting.id) {
                    it.copy(status = MeetingStore.Status.WAITING, message = context.getString(R.string.meeting_interrupted))
                }
            }
        }
    }

    /** "4:05" or "1:02:30". */
    fun clock(ms: Long): String {
        val seconds = ms / 1000
        return if (seconds >= 3600) {
            "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
        } else {
            "%d:%02d".format(seconds / 60, seconds % 60)
        }
    }

    /** "45 min" or "1 h 20 min". */
    fun length(ms: Long): String {
        val minutes = ((ms + 30_000) / 60_000).coerceAtLeast(if (ms > 0) 1 else 0)
        return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
    }
}
