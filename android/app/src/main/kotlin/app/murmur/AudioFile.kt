package app.murmur

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import app.murmur.core.DictationException
import app.murmur.core.Resampler
import app.murmur.core.Splitter
import java.nio.ByteOrder

/** Audio to write down, a few minutes at a time. */
interface AudioSource {
    /** How long the recording is, or 0 if the file doesn't say. */
    val durationMs: Long

    /** Calls [onPart] with each part of a few minutes, as 16 kHz mono PCM. */
    fun parts(onPart: (ByteArray) -> Unit)
}

/** A recording the phone can play (m4a, mp3, ogg, opus, wav...), decoded a few minutes at a time. */
class AudioFile(private val context: Context, private val uri: Uri) : AudioSource {
    override val durationMs: Long by lazy {
        val extractor = MediaExtractor()
        try {
            val format = open(extractor)
            if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000 else 0
        } finally {
            extractor.release()
        }
    }

    override fun parts(onPart: (ByteArray) -> Unit) {
        val extractor = MediaExtractor()
        try {
            val format = open(extractor)
            val codec = try {
                MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            } catch (e: Exception) {
                throw DictationException("This phone can't play that kind of audio.")
            }
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                drain(extractor, codec, format, onPart)
            } finally {
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    /** Opens the file and selects its audio track. */
    private fun open(extractor: MediaExtractor): MediaFormat {
        try {
            extractor.setDataSource(context, uri, null)
        } catch (e: Exception) {
            throw DictationException("Couldn't open that file (${e.message}).")
        }
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")
        } ?: throw DictationException("That file has no audio in it.")
        extractor.selectTrack(track)
        return extractor.getTrackFormat(track)
    }

    private fun drain(extractor: MediaExtractor, codec: MediaCodec, inputFormat: MediaFormat, onPart: (ByteArray) -> Unit) {
        var format = inputFormat
        var resampler: Resampler? = null
        val splitter = Splitter()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var waits = 0
        while (true) {
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    val size = extractor.readSampleData(codec.getInputBuffer(index)!!, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) format = codec.outputFormat
            if (index < 0) {
                // A decoder that never signals the end shouldn't hang the transcription.
                if (inputDone && ++waits > 300) break
                continue
            }
            waits = 0
            val buffer = codec.getOutputBuffer(index)!!.order(ByteOrder.nativeOrder())
            buffer.position(info.offset)
            buffer.limit(info.offset + info.size)
            val samples = if (isFloat(format)) {
                val floats = buffer.asFloatBuffer()
                ShortArray(floats.remaining()) { (floats.get(it).coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort() }
            } else {
                val shorts = buffer.asShortBuffer()
                ShortArray(shorts.remaining()).also { shorts.get(it) }
            }
            codec.releaseOutputBuffer(index, false)
            val current = resampler
                ?: Resampler(format.getInteger(MediaFormat.KEY_SAMPLE_RATE), format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                    .also { resampler = it }
            splitter.add(current.feed(samples)).forEach(onPart)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
        }
        resampler?.let { splitter.add(it.finish()).forEach(onPart) }
        splitter.finish()?.let(onPart)
    }

    private fun isFloat(format: MediaFormat) =
        format.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
            format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT

    private companion object {
        const val TIMEOUT_US = 10_000L
    }
}
