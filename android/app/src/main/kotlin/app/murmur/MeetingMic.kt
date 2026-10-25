package app.murmur

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import app.murmur.core.Wav
import kotlin.math.abs

/** Where meeting audio comes from: the phone's microphone, or a fake one in tests. */
interface Microphone {
    /** Calls [onSamples] with 16 kHz mono samples, on its own thread, until [stop]. */
    fun start(onSamples: (ShortArray) -> Unit)

    fun stop()

    /** True when Android is quietly feeding silence because Murmur isn't allowed the mic. */
    fun isSilenced(): Boolean
}

/** The phone's microphone, set up for a room rather than for talking into it. */
class MeetingMic : Microphone {
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var heard = 0L

    @Volatile
    private var peak = 0

    /** Callers check the RECORD_AUDIO permission first. */
    @SuppressLint("MissingPermission")
    override fun start(onSamples: (ShortArray) -> Unit) {
        val minimum = AudioRecord.getMinBufferSize(Wav.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val audio = AudioRecord(
            // The phone's usual processing, including automatic gain, which helps with voices across a table.
            MediaRecorder.AudioSource.MIC,
            Wav.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            // A few seconds of room, so saving a part never drops audio.
            maxOf(minimum, Wav.SAMPLE_RATE * 2 * 4),
        )
        if (audio.state != AudioRecord.STATE_INITIALIZED) {
            audio.release()
            error("the microphone is busy or unavailable")
        }
        running = true
        audio.startRecording()
        record = audio
        thread = Thread {
            val buffer = ShortArray(Wav.SAMPLE_RATE / 10) // 100 ms
            while (running) {
                val read = audio.read(buffer, 0, buffer.size)
                if (read > 0) {
                    heard += read
                    for (i in 0 until read) peak = maxOf(peak, abs(buffer[i].toInt()))
                    onSamples(buffer.copyOf(read))
                }
            }
        }.apply {
            name = "meeting-mic"
            start()
        }
    }

    override fun stop() {
        running = false
        thread?.join(2_000)
        thread = null
        record?.run {
            runCatching { stop() }
            release()
        }
        record = null
    }

    override fun isSilenced(): Boolean {
        val audio = record ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && audio.activeRecordingConfiguration?.isClientSilenced == true) return true
        return heard >= Wav.SAMPLE_RATE && peak == 0 // a second of exact zeros
    }
}
