package app.murmur.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 16 kHz mono 16-bit WAV: every speech-to-text provider accepts it. */
object Wav {
    const val SAMPLE_RATE = 16_000

    /** Wraps little-endian 16-bit mono PCM in a WAV header. */
    fun encode(pcm: ByteArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + pcm.size)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16) // fmt chunk size
            putShort(1) // PCM
            putShort(1) // mono
            putInt(sampleRate)
            putInt(sampleRate * 2) // bytes per second
            putShort(2) // bytes per frame
            putShort(16) // bits per sample
            put("data".toByteArray())
            putInt(pcm.size)
        }
        return header.array() + pcm
    }

    /** Little-endian 16-bit PCM from samples, for [encode]. */
    fun pcm(samples: ShortArray, from: Int = 0, to: Int = samples.size): ByteArray {
        val bytes = ByteBuffer.allocate((to - from) * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asShortBuffer().put(samples, from, to - from)
        return bytes.array()
    }
}
