package app.murmur

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import app.murmur.core.Dictation
import app.murmur.core.DictationException
import app.murmur.core.Prompts
import app.murmur.core.Wav
import okhttp3.OkHttpClient
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Transcribes a recording shared from another app, such as Voice Recorder or a
 * WhatsApp voice note, with the same settings as the mic.
 */
class TranscribeActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var progress: View
    private lateinit var copy: Button
    private lateinit var share: Button
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    /** Settable so tests can point it at a fake server. */
    internal var dictation: Dictation? = null

    /** Settable so tests can skip decoding a real audio file. */
    internal var decode: (Uri) -> AudioFile.Decoded = { AudioFile.decode(this, it, MAX_AUDIO_MS) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transcribe)
        status = findViewById(R.id.transcribe_status)
        result = findViewById(R.id.transcribe_result)
        progress = findViewById(R.id.transcribe_progress)
        copy = findViewById(R.id.transcribe_copy)
        share = findViewById(R.id.transcribe_share)
        copy.setOnClickListener { copyToClipboard() }
        share.setOnClickListener {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, result.text.toString())
            startActivity(Intent.createChooser(send, null))
        }

        val uri = sharedUri(intent)
        val prefs = Prefs(this)
        when {
            uri == null -> showStatus(getString(R.string.transcribe_nothing))
            prefs.key(prefs.provider).isBlank() -> showStatus(getString(R.string.transcribe_no_key))
            else -> transcribe(uri, prefs)
        }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun transcribe(uri: Uri, prefs: Prefs) {
        status.setText(R.string.transcribe_reading)
        val settings = prefs.dictationSettings()
        val dictation = dictation ?: Dictation(
            Prompts.load(),
            // A few minutes of audio takes longer than a dictation.
            OkHttpClient.Builder().callTimeout(4, TimeUnit.MINUTES).build(),
        )
        worker.execute {
            try {
                val audio = decode(uri)
                if (audio.pcm.size < Wav.SAMPLE_RATE) throw DictationException(Dictation.NO_SPEECH)
                val seconds = audio.pcm.size / (2 * Wav.SAMPLE_RATE)
                main.post { status.text = getString(R.string.transcribe_working, seconds / 60, seconds % 60) }
                val outcome = dictation.run(Wav.encode(audio.pcm), settings)
                val notes = listOfNotNull(outcome.note, getString(R.string.transcribe_trimmed).takeIf { audio.trimmed })
                main.post { show(outcome.text, notes) }
            } catch (e: Exception) {
                val message = (e as? DictationException)?.message ?: "Something went wrong (${e.message})."
                main.post { showStatus(message) }
            }
        }
    }

    private fun show(text: String, notes: List<String>) {
        result.text = text
        copy.isEnabled = true
        share.isEnabled = true
        copyToClipboard()
        showStatus((listOf(getString(R.string.transcribe_done)) + notes).joinToString(" "))
    }

    private fun showStatus(message: String) {
        progress.visibility = View.GONE
        status.text = message
    }

    private fun copyToClipboard() {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Murmur", result.text))
    }

    private fun sharedUri(intent: Intent): Uri? {
        val stream = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        }
        return stream ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
    }

    internal companion object {
        // Gemini takes up to 20 MB per request: about 7 minutes of 16 kHz audio.
        const val MAX_AUDIO_MS = 7 * 60 * 1000L
    }
}
