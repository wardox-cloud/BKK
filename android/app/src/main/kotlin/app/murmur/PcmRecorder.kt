package app.murmur

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import app.murmur.core.Wav
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

/** Records 16 kHz mono 16-bit PCM, reporting the input level as it goes. */
class PcmRecorder {
    private val pcm = ByteArrayOutputStream()
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    /** Loudest sample so far; real microphones never give a perfectly flat zero. */
    @Volatile
    private var peak = 0

    /** Callers check the RECORD_AUDIO permission first. [onLevel] runs on the recording thread. */
    @SuppressLint("MissingPermission")
    fun start(onLevel: (Float) -> Unit) {
        val minimum = AudioRecord.getMinBufferSize(Wav.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val audio = AudioRecord(
            // Tuned for speech recognition: no aggressive processing.
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            Wav.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minimum, Wav.SAMPLE_RATE),
        )
        if (audio.state != AudioRecord.STATE_INITIALIZED) {
            audio.release()
            error("the microphone is busy or unavailable")
        }
        synchronized(pcm) { pcm.reset() }
        peak = 0
        running = true
        audio.startRecording()
        record = audio
        thread = Thread {
            val buffer = ByteArray(3_200) // 100 ms
            while (running) {
                val read = audio.read(buffer, 0, buffer.size)
                if (read > 0) {
                    synchronized(pcm) { pcm.write(buffer, 0, read) }
                    peak = maxOf(peak, peak(buffer, read))
                    onLevel(level(buffer, read))
                }
            }
        }.apply { start() }
    }

    /** Stops and returns everything recorded (empty if nothing was). */
    fun stop(): ByteArray {
        running = false
        thread?.join(1_000)
        thread = null
        record?.run {
            runCatching { stop() }
            release()
        }
        record = null
        return synchronized(pcm) { pcm.toByteArray().also { pcm.reset() } }
    }

    /**
     * True when Android is feeding silence because this app isn't allowed the
     * microphone right now (it does that quietly instead of failing).
     */
    fun isSilenced(): Boolean {
        val audio = record ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && audio.activeRecordingConfiguration?.isClientSilenced == true) {
            return true
        }
        return synchronized(pcm) { pcm.size() } >= Wav.SAMPLE_RATE && peak == 0 // half a second of exact zeros
    }

    private fun peak(buffer: ByteArray, length: Int): Int {
        var loudest = 0
        for (i in 0 until length / 2) {
            val sample = (buffer[2 * i].toInt() and 0xFF) or (buffer[2 * i + 1].toInt() shl 8)
            loudest = maxOf(loudest, kotlin.math.abs(sample))
        }
        return loudest
    }

    private fun level(buffer: ByteArray, length: Int): Float {
        var sum = 0.0
        val samples = length / 2
        for (i in 0 until samples) {
            val sample = (buffer[2 * i].toInt() and 0xFF) or (buffer[2 * i + 1].toInt() shl 8)
            val normalized = sample / 32768.0
            sum += normalized * normalized
        }
        // RMS of normal speech sits around 0.02-0.2; stretch it to 0-1.
        return if (samples == 0) 0f else minOf(1.0, sqrt(sum / samples) * 6).toFloat()
    }
}
