package app.murmur

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import app.murmur.core.Dictation
import app.murmur.core.DictationException
import app.murmur.core.DictationSettings
import app.murmur.core.Prompts
import app.murmur.core.Wav
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit

/**
 * A shared recording being written down a part at a time. [TranscribeService]
 * runs it, so it keeps going when you leave the screen, and
 * [TranscribeActivity] shows it.
 */
object Transcription {
    interface Source {
        /** How long the recording is, or 0 if the file doesn't say. */
        val durationMs: Long

        /** Calls [onPart] with each part of a few minutes, as 16 kHz mono PCM. */
        fun parts(onPart: (ByteArray) -> Unit)
    }

    enum class Phase { IDLE, WORKING, DONE, STOPPED }

    data class State(
        val phase: Phase = Phase.IDLE,
        val text: String = "",
        val doneMs: Long = 0,
        val totalMs: Long = 0,
        val partsDone: Int = 0,
        /** Why it stopped. */
        val message: String? = null,
    )

    @Volatile
    var state = State()
        private set

    @Volatile
    private var cancelled = false
    private val listeners = CopyOnWriteArraySet<(State) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    /** Settable so tests can point it at a fake server. */
    internal var dictation: Dictation? = null

    /** Settable so tests can skip decoding a real audio file. */
    internal var open: (Context, File) -> Source = { context, file -> AudioFile(context, Uri.fromFile(file)) }

    /** The copy of the shared recording that the service works from. */
    fun recording(context: Context) = File(context.cacheDir, "recording")

    private fun lastText(context: Context) = File(context.filesDir, "last-transcript.txt")

    /** Calls [listener] on the main thread whenever the state changes. */
    fun listen(listener: (State) -> Unit) = listeners.add(listener)

    fun unlisten(listener: (State) -> Unit) = listeners.remove(listener)

    /** Brings back the last transcript after Android has closed Murmur in the background. */
    fun restore(context: Context) {
        val saved = lastText(context)
        if (state.phase == Phase.IDLE && saved.length() > 0) state = State(Phase.DONE, saved.readText())
    }

    /** Marks a new recording as on its way, before it's copied and the service starts. */
    fun begin() {
        cancelled = false
        publish(State(Phase.WORKING))
    }

    /** Picks up a stopped run where it left off; the service then runs it with resume. */
    fun proceed() {
        cancelled = false
        publish(state.copy(phase = Phase.WORKING, message = null))
    }

    fun fail(message: String) = publish(state.copy(phase = Phase.STOPPED, message = message))

    fun cancel() {
        cancelled = true
    }

    /** Runs on a worker thread. With [resume], skips the parts already written down. */
    fun run(context: Context, settings: DictationSettings, resume: Boolean) {
        val start = if (resume) state else State()
        var current = start.copy(phase = Phase.WORKING, message = null)
        publish(current)
        val dictation = dictation ?: Dictation(
            Prompts.load(),
            // A few minutes of audio takes longer than a dictation.
            OkHttpClient.Builder().callTimeout(4, TimeUnit.MINUTES).build(),
        )
        try {
            val source = open(context, recording(context))
            current = current.copy(totalMs = source.durationMs)
            publish(current)
            var index = 0
            source.parts { pcm ->
                if (cancelled) throw Stopped()
                if (index++ < start.partsDone) return@parts
                val text = if (pcm.size < Wav.SAMPLE_RATE) "" else transcribe(dictation, pcm, settings)
                if (cancelled) throw Stopped()
                current = current.copy(
                    text = listOf(current.text, text).filter { it.isNotBlank() }.joinToString("\n\n"),
                    doneMs = current.doneMs + pcm.size / 2 * 1000L / Wav.SAMPLE_RATE,
                    partsDone = index,
                )
                publish(current)
            }
            if (current.text.isBlank()) throw DictationException(Dictation.NO_SPEECH)
            current = current.copy(phase = Phase.DONE, doneMs = maxOf(current.doneMs, current.totalMs))
            recording(context).delete()
        } catch (e: Stopped) {
            current = current.copy(phase = Phase.STOPPED, message = "Stopped.")
        } catch (e: Exception) {
            val reason = (e as? DictationException)?.message ?: "Something went wrong (${e.message})."
            current = current.copy(phase = Phase.STOPPED, message = reason)
        }
        lastText(context).writeText(current.text)
        publish(current)
    }

    /** A part with nothing said in it is fine; any other failure stops the run. */
    private fun transcribe(dictation: Dictation, pcm: ByteArray, settings: DictationSettings): String =
        try {
            dictation.run(Wav.encode(pcm), settings).text
        } catch (e: DictationException) {
            if (e.message == Dictation.NO_SPEECH) "" else throw e
        }

    private fun publish(next: State) {
        state = next
        main.post { listeners.forEach { it(state) } }
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

    /** For tests: back to a fresh start. */
    internal fun reset() {
        state = State()
        cancelled = false
        listeners.clear()
        dictation = null
        open = { context, file -> AudioFile(context, Uri.fromFile(file)) }
    }

    private class Stopped : Exception()
}
