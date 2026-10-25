package app.murmur

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import app.murmur.core.Wav

/**
 * The phone's own speech recognizer, used for dictation when there's no
 * internet. It's English only and does no cleanup, but it never leaves the
 * phone. Needs Android 13 or newer, which can feed it a recording.
 */
object OfflineRecognizer {
    /** Replaceable in tests, which have no recognizer. */
    internal var recognize: (Context, ByteArray, (String?) -> Unit) -> Unit = ::recognizeOnPhone

    internal var available: (Context) -> Boolean = { context ->
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    }

    /** Writes down [pcm] (16 kHz mono); [done] gets the text, or null if it couldn't. Call on the main thread. */
    fun transcribe(context: Context, pcm: ByteArray, done: (String?) -> Unit) {
        if (!available(context)) return done(null)
        recognize(context, pcm, done)
    }

    private fun recognizeOnPhone(context: Context, pcm: ByteArray, done: (String?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return done(null)
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        val pipe = ParcelFileDescriptor.createPipe()
        val segments = mutableListOf<String>()
        var finished = false
        fun finish(text: String?) {
            if (finished) return
            finished = true
            recognizer.destroy()
            runCatching { pipe[0].close() }
            done(text?.trim()?.ifEmpty { null })
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onSegmentResults(results: Bundle) {
                results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { segments += it }
            }

            override fun onEndOfSegmentedSession() = finish(segments.joinToString(" "))

            override fun onResults(results: Bundle) =
                finish((segments + results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()).joinToString(" "))

            override fun onError(error: Int) = finish(segments.joinToString(" ").ifEmpty { null })

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, Wav.SAMPLE_RATE)
            // Keep going past pauses until the recording runs out.
            .putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        recognizer.startListening(intent)
        Thread {
            runCatching { ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(pcm) } }
        }.start()
    }
}
