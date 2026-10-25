package app.murmur

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import app.murmur.core.Adts
import app.murmur.core.Wav
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteOrder

/** Keeps a meeting's audio on the phone. */
interface AudioWriter {
    fun write(samples: ShortArray)

    fun close()
}

/**
 * Saves 16 kHz mono audio as AAC (.aac, about 15 MB an hour). Each frame has
 * its own header, so the file plays even if the phone dies mid-meeting.
 */
class AacWriter(file: File) : AudioWriter {
    private val output = FileOutputStream(file)
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val info = MediaCodec.BufferInfo()
    private var samplesIn = 0L
    private var frame = ByteArray(0)

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, Wav.SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 32_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    override fun write(samples: ShortArray) {
        var offset = 0
        while (offset < samples.size) {
            val index = codec.dequeueInputBuffer(10_000)
            if (index < 0) {
                drain(untilEnd = false)
                continue
            }
            val buffer = codec.getInputBuffer(index)!!
            buffer.clear()
            val count = minOf(samples.size - offset, buffer.remaining() / 2)
            buffer.order(ByteOrder.nativeOrder()).asShortBuffer().put(samples, offset, count)
            codec.queueInputBuffer(index, 0, count * 2, samplesIn * 1_000_000 / Wav.SAMPLE_RATE, 0)
            samplesIn += count
            offset += count
            drain(untilEnd = false)
        }
    }

    override fun close() {
        try {
            var index = codec.dequeueInputBuffer(10_000)
            var tries = 0
            while (index < 0 && tries++ < 100) {
                drain(untilEnd = false)
                index = codec.dequeueInputBuffer(10_000)
            }
            if (index >= 0) codec.queueInputBuffer(index, 0, 0, samplesIn * 1_000_000 / Wav.SAMPLE_RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(untilEnd = true)
        } finally {
            runCatching { codec.stop() }
            codec.release()
            output.close()
        }
    }

    private fun drain(untilEnd: Boolean) {
        var waits = 0
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (untilEnd) 10_000 else 0)
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!untilEnd || ++waits > 200) return
                continue
            }
            if (index < 0) continue // a format change; ADTS headers carry the format themselves
            val ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            if (info.size > 0 && !config) {
                val buffer = codec.getOutputBuffer(index)!!
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                if (frame.size < info.size) frame = ByteArray(info.size)
                buffer.get(frame, 0, info.size)
                output.write(Adts.header(info.size))
                output.write(frame, 0, info.size)
            }
            codec.releaseOutputBuffer(index, false)
            if (ended) return
        }
    }
}
