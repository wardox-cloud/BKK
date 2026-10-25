package app.murmur.core

/**
 * Splits a long recording into parts that fit in one request, cutting each at
 * the quietest moment near its end so no word is cut in half.
 */
class Splitter(
    private val shortest: Int = 4 * 60 * Wav.SAMPLE_RATE,
    // Six minutes of 16 kHz WAV stays well under Gemini's 20 MB request limit.
    private val longest: Int = 6 * 60 * Wav.SAMPLE_RATE,
) {
    private val buffer = ShortArray(longest)
    private var size = 0

    init {
        require(shortest + STRETCH * FRAME <= longest) { "Parts need room to look for a pause" }
    }

    /** Adds 16 kHz mono samples and returns the parts that are complete, as PCM for [Wav.encode]. */
    fun add(samples: ShortArray): List<ByteArray> {
        val parts = mutableListOf<ByteArray>()
        var at = 0
        while (at < samples.size) {
            val n = minOf(samples.size - at, longest - size)
            samples.copyInto(buffer, size, at, at + n)
            size += n
            at += n
            if (size == longest) {
                val cut = quietestPoint()
                parts += Wav.pcm(buffer, 0, cut)
                buffer.copyInto(buffer, 0, cut, size)
                size -= cut
            }
        }
        return parts
    }

    /** The rest of the recording, or null if nothing is left. */
    fun finish(): ByteArray? {
        if (size == 0) return null
        return Wav.pcm(buffer, 0, size).also { size = 0 }
    }

    /** The middle of the quietest 300 ms between [shortest] and [longest]. */
    private fun quietestPoint(): Int {
        val energy = DoubleArray((longest - shortest) / FRAME) { frame ->
            val start = shortest + frame * FRAME
            var sum = 0.0
            for (i in start until start + FRAME) sum += buffer[i].toDouble() * buffer[i]
            sum
        }
        var best = 0
        var bestEnergy = Double.MAX_VALUE
        for (frame in 0..energy.size - STRETCH) {
            var sum = 0.0
            for (k in frame until frame + STRETCH) sum += energy[k]
            if (sum < bestEnergy) {
                bestEnergy = sum
                best = frame
            }
        }
        return shortest + best * FRAME + STRETCH * FRAME / 2
    }

    private companion object {
        const val FRAME = Wav.SAMPLE_RATE / 10 // 100 ms
        const val STRETCH = 3
    }
}
