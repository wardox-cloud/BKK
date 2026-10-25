package app.murmur.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals

class WavTest {
    @Test
    fun `wraps pcm in a 16 kHz mono 16-bit header`() {
        val pcm = ByteArray(32_000) { (it % 7).toByte() } // one second
        val wav = Wav.encode(pcm)
        val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals(36 + pcm.size, header.getInt(4))
        assertEquals("WAVEfmt ", String(wav, 8, 8))
        assertEquals(1, header.getShort(20).toInt()) // PCM
        assertEquals(1, header.getShort(22).toInt()) // mono
        assertEquals(16_000, header.getInt(24))
        assertEquals(32_000, header.getInt(28))
        assertEquals(16, header.getShort(34).toInt())
        assertEquals("data", String(wav, 36, 4))
        assertEquals(pcm.size, header.getInt(40))
        assertEquals(pcm.toList(), wav.copyOfRange(44, wav.size).toList())
    }

    @Test
    fun `turns samples into little-endian pcm`() {
        val pcm = Wav.pcm(shortArrayOf(1, -2, 0x1234, Short.MIN_VALUE), 1, 4)
        assertEquals(listOf<Byte>(-2, -1, 0x34, 0x12, 0, -128), pcm.toList())
    }
}
