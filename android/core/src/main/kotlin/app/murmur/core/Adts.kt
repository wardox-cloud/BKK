package app.murmur.core

/**
 * ADTS framing for AAC audio: a 7-byte header before each frame. A file of
 * framed AAC plays even if recording stops abruptly, unlike .m4a, which is
 * unreadable until it's properly closed.
 */
object Adts {
    private const val LC = 2
    private val RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

    fun header(frameLength: Int, sampleRate: Int = Wav.SAMPLE_RATE, channels: Int = 1): ByteArray {
        val rate = RATES.indexOf(sampleRate).also { require(it >= 0) { "No ADTS rate for $sampleRate Hz" } }
        val length = frameLength + 7
        return byteArrayOf(
            0xFF.toByte(),
            0xF1.toByte(), // MPEG-4, layer 0, no checksum
            (((LC - 1) shl 6) or (rate shl 2) or (channels shr 2)).toByte(),
            (((channels and 3) shl 6) or (length shr 11)).toByte(),
            ((length and 0x7FF) shr 3).toByte(),
            (((length and 7) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
        )
    }
}
