package app.murmur.core

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SplitterTest {
    private val rate = Wav.SAMPLE_RATE

    // Seconds instead of minutes keeps the tests quick; the logic is the same.
    private fun splitter() = Splitter(shortest = 4 * rate, longest = 6 * rate)

    @Test
    fun `a short recording comes out whole at the end`() {
        val audio = speech(5.5, pausesAt = emptyList())
        val splitter = splitter()
        assertEquals(emptyList(), splitter.add(audio))
        assertEquals(Wav.pcm(audio).toList(), splitter.finish()!!.toList())
        assertNull(splitter.finish())
    }

    @Test
    fun `long recordings are cut in pauses and nothing is lost`() {
        val pauses = listOf(4.6, 9.3, 14.2, 19.9)
        val audio = speech(23.0, pauses)
        val splitter = splitter()
        val parts = mutableListOf<ByteArray>()
        // Feed it the way a decoder does, in uneven chunks.
        var at = 0
        var size = 1000
        while (at < audio.size) {
            val end = minOf(at + size, audio.size)
            parts += splitter.add(audio.copyOfRange(at, end))
            at = end
            size = size * 7 % 9000 + 500
        }
        parts += listOfNotNull(splitter.finish())

        assertEquals(Wav.pcm(audio).toList(), parts.flatMap { it.toList() })
        var cut = 0.0
        for (part in parts.dropLast(1)) {
            val seconds = part.size / 2.0 / rate
            assertTrue(seconds in 4.0..6.0, "part of $seconds s")
            cut += seconds
            assertTrue(pauses.any { cut in it..it + PAUSE }, "cut at $cut s, outside every pause")
        }
        assertEquals(5, parts.size)
    }

    @Test
    fun `without any pause, parts still stay under the limit`() {
        val audio = speech(20.0, pausesAt = emptyList())
        val splitter = splitter()
        val parts = splitter.add(audio) + listOfNotNull(splitter.finish())
        assertTrue(parts.all { it.size <= 6 * rate * 2 })
        assertEquals(audio.size * 2, parts.sumOf { it.size })
    }

    /** A loud warbling tone with near-silent pauses of [PAUSE] seconds. */
    private fun speech(seconds: Double, pausesAt: List<Double>) = ShortArray((seconds * rate).toInt()) { i ->
        val t = i.toDouble() / rate
        val quiet = pausesAt.any { t in it..it + PAUSE }
        val loud = 8000 * sin(2 * PI * (200 + 50 * sin(2 * PI * 3 * t)) * t)
        (if (quiet) loud / 200 else loud).toInt().toShort()
    }

    private companion object {
        const val PAUSE = 0.5
    }
}
