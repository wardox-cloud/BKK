package app.murmur

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import app.murmur.core.DictationException
import app.murmur.core.Resampler
import java.nio.ByteOrder

/** Decodes any recording the phone can play (m4a, mp3, ogg, opus, wav...) into 16 kHz mono PCM. */
object AudioFile {
    class Decoded(val pcm: ByteArray, val trimmed: Boolean)

    private const val TIMEOUT_US = 10_000L

    /** Stops after [maxMs] of audio and says so in [Decoded.trimmed]. */
    fun decode(context: Context, uri: Uri, maxMs: Long): Decoded {
        val extractor = MediaExtractor()
        try {
            try {
                extractor.setDataSource(context, uri, null)
            } catch (e: Exception) {
                throw DictationException("Couldn't open that file (${e.message}).")
            }
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")
            } ?: throw DictationException("That file has no audio in it.")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val codec = try {
                MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            } catch (e: Exception) {
                throw DictationException("This phone can't play that kind of audio.")
            }
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                return drain(extractor, codec, format, maxMs)
            } finally {
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    private fun drain(extractor: MediaExtractor, codec: MediaCodec, inputFormat: MediaFormat, maxMs: Long): Decoded {
        var format = inputFormat
        var resampler: Resampler? = null
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
                // A decoder that never signals the end shouldn't hang the screen.
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
            current.feed(samples)
            if (current.durationMs >= maxMs) return Decoded(current.finish(), trimmed = true)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
        }
        return Decoded(resampler?.finish() ?: ByteArray(0), trimmed = false)
    }

    private fun isFloat(format: MediaFormat) =
        format.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
            format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
}
