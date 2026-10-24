package app.murmur.core

import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * Turns decoded audio of any sample rate and channel count into the 16 kHz mono
 * 16-bit PCM that [Wav.encode] wraps. It takes the audio a chunk at a time, so a
 * long recording never sits in memory at its original size.
 */
class Resampler(inputRate: Int, private val channels: Int, private val outputRate: Int = Wav.SAMPLE_RATE) {
    private val out = ByteArrayOutputStream()
    private val step = inputRate.toDouble() / outputRate
    private var frames = 0L

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

    val outputSamples get() = out.size() / 2
    val durationMs get() = outputSamples * 1000L / outputRate

    /** Adds interleaved samples; [size] counts samples, not frames. */
    fun feed(samples: ShortArray, size: Int = samples.size) {
        var i = 0
        while (i + channels <= size) {
            var mixed = 0.0
            for (c in 0 until channels) mixed += samples[i + c]
            take(mixed / channels)
            i += channels
        }
    }

    /** The PCM so far, ready for [Wav.encode]. */
    fun finish(): ByteArray {
        if (count > 0) {
            emit(sum / count)
            sum = 0.0
            count = 0
        }
        return out.toByteArray()
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
        val sample = value.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        out.write(sample and 0xff)
        out.write(sample shr 8 and 0xff)
    }
}
