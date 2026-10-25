package app.murmur.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResamplerTest {
    @Test
    fun `16 kHz mono passes through unchanged`() {
        val input = ShortArray(1000) { (it * 37 % 2000 - 1000).toShort() }
        assertEquals(input.toList(), resample(input, 16_000, 1).toList())
    }

    @Test
    fun `48 kHz stereo becomes 16 kHz mono and keeps the tone`() {
        val second = tone(440.0, 48_000, 1.0)
        val stereo = ShortArray(second.size * 2) { second[it / 2] }
        val output = resample(stereo, 48_000, 2)

        assertEquals(16_000, output.size)
        // Each output sample averages three input samples centred one sample later.
        val shift = 2 * PI * 440 / 48_000
        for (i in listOf(100, 5_000, 12_345)) {
            val centred = 10_000 * sin(2 * PI * 440 * i / 16_000 + shift)
            assertTrue(abs(output[i] - centred) < 30, "sample $i: ${output[i]} vs $centred")
        }
    }

    @Test
    fun `channels are averaged`() {
        val stereo = ShortArray(9600) { if (it % 2 == 0) 1000 else -1000 }
        assertTrue(resample(stereo, 48_000, 2).all { it == 0.toShort() })
    }

    @Test
    fun `44_1 kHz comes out at the right length, however it is chunked`() {
        val input = tone(300.0, 44_100, 2.0)
        val whole = resample(input, 44_100, 1)
        assertTrue(abs(whole.size - 32_000) <= 1, "${whole.size} samples")

        val chunked = Resampler(44_100, 1)
        val output = mutableListOf<Short>()
        var at = 0
        for (size in generateSequence(7) { it * 3 % 4096 + 1 }) {
            if (at >= input.size) break
            val end = minOf(at + size, input.size)
            output += chunked.feed(input.copyOfRange(at, end)).toList()
            at = end
        }
        output += chunked.finish().toList()
        assertEquals(whole.toList(), output)
    }

    @Test
    fun `a sample split across chunks is not lost`() {
        val stereo = ShortArray(12) { 500 }
        val resampler = Resampler(48_000, 2)
        val first = resampler.feed(stereo.copyOfRange(0, 5)) // two frames and half of the third
        val second = resampler.feed(stereo.copyOfRange(4, 12))
        assertEquals(List(2) { 500.toShort() }, (first + second + resampler.finish()).toList())
    }

    @Test
    fun `8 kHz is doubled with in-between samples`() {
        val output = resample(shortArrayOf(0, 100, 200, 300), 8_000, 1)
        assertEquals(listOf<Short>(0, 50, 100, 150, 200, 250, 300), output.toList())
    }

    @Test
    fun `high pitches above the 8 kHz limit are damped`() {
        val output = resample(tone(14_000.0, 48_000, 1.0), 48_000, 1)
        assertTrue(rms(output) < 0.35 * rms(tone(14_000.0, 48_000, 1.0)))
    }

    private fun resample(input: ShortArray, rate: Int, channels: Int) =
        Resampler(rate, channels).let { it.feed(input) + it.finish() }

    private fun tone(hz: Double, rate: Int, seconds: Double) =
        ShortArray((rate * seconds).toInt()) { (10_000 * sin(2 * PI * hz * it / rate)).toInt().toShort() }

    private fun rms(samples: ShortArray) = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)
}
