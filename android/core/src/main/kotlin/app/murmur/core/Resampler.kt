package app.murmur.core

import kotlin.math.roundToInt

/**
 * Turns decoded audio of any sample rate and channel count into 16 kHz mono
 * samples, a chunk at a time, so a long recording never has to sit in memory.
 */
class Resampler(inputRate: Int, private val channels: Int, outputRate: Int = Wav.SAMPLE_RATE) {
    private val step = inputRate.toDouble() / outputRate
    private var frames = 0L
    private var out = ShortArray(4096)
    private var size = 0

    // Downsampling averages the input frames that fall in each output sample, so
    // pitches above the new limit are damped instead of folding down into noise.
    private var boundary = step
    private var sum = 0.0
    private var count = 0

    // Upsampling draws a straight line between neighbouring input frames.
    private var position = 0.0
    private var previous = 0.0

    init {
        require(inputRate > 0 && channels > 0) { "Bad audio format: $inputRate Hz, $channels channels" }
    }

    /** Takes interleaved samples ([length] counts samples, not frames) and returns the 16 kHz mono samples they make. */
    fun feed(samples: ShortArray, length: Int = samples.size): ShortArray {
        size = 0
        var i = 0
        while (i + channels <= length) {
            var mixed = 0.0
            for (c in 0 until channels) mixed += samples[i + c]
            take(mixed / channels)
            i += channels
        }
        return out.copyOf(size)
    }

    /** Returns the last, partly filled sample, if there is one. */
    fun finish(): ShortArray {
        size = 0
        if (count > 0) {
            emit(sum / count)
            sum = 0.0
            count = 0
        }
        return out.copyOf(size)
    }

    private fun take(value: Double) {
        if (step >= 1) {
            sum += value
            count++
            frames++
            if (frames >= boundary) {
                emit(sum / count)
                sum = 0.0
                count = 0
                boundary += step
            }
        } else {
            while (position <= frames) {
                emit(previous + (value - previous) * (position - (frames - 1)))
                position += step
            }
            previous = value
            frames++
        }
    }

    private fun emit(value: Double) {
        if (size == out.size) out = out.copyOf(size * 2)
        out[size++] = value.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
}
