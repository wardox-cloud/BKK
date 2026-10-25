package app.murmur

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import app.murmur.Transcription.Phase
import java.util.concurrent.Executors

/**
 * Transcribes a recording shared from another app, such as Voice Recorder or a
 * WhatsApp voice note, with the same settings as the mic. Long recordings go
 * a few minutes at a time in [TranscribeService], so you can leave this screen.
 */
class TranscribeActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var progress: View
    private lateinit var copy: Button
    private lateinit var share: Button
    private lateinit var stop: Button
    private lateinit var resume: Button
    private val worker = Executors.newSingleThreadExecutor()

    /** A message about this screen rather than the transcription, such as a missing key. */
    private var note: String? = null
    private var copied: String? = null
    private val listener: (Transcription.State) -> Unit = {
        note = null
        render(it)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transcribe)
        status = findViewById(R.id.transcribe_status)
        result = findViewById(R.id.transcribe_result)
        progress = findViewById(R.id.transcribe_progress)
        copy = findViewById(R.id.transcribe_copy)
        share = findViewById(R.id.transcribe_share)
        stop = findViewById(R.id.transcribe_stop)
        resume = findViewById(R.id.transcribe_resume)
        copy.setOnClickListener { copyToClipboard(result.text.toString()) }
        share.setOnClickListener {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, result.text.toString())
            startActivity(Intent.createChooser(send, null))
        }
        stop.setOnClickListener { Transcription.cancel() }
        resume.setOnClickListener {
            Transcription.proceed()
            startService(resume = true)
        }

        Transcription.restore(this)
        // What's already on screen was copied when it finished.
        copied = Transcription.state.text.takeIf { Transcription.state.phase == Phase.DONE }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    override fun onStart() {
        super.onStart()
        Transcription.listen(listener)
        render(Transcription.state)
    }

    override fun onStop() {
        Transcription.unlisten(listener)
        super.onStop()
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    private fun handle(intent: Intent) {
        val uri = sharedUri(intent)
        val prefs = Prefs(this)
        note = when {
            // Opened from the notification: show how it's going.
            uri == null -> null
            prefs.key(prefs.provider).isBlank() -> getString(R.string.transcribe_no_key)
            Transcription.state.phase == Phase.WORKING -> getString(R.string.transcribe_busy)
            else -> {
                start(uri)
                null
            }
        }
        render(Transcription.state)
    }

    private fun start(uri: Uri) {
        Transcription.begin()
        copied = null
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATIONS_REQUEST)
        }
        // Copy it first: the app that shared it may take back access when this screen closes.
        worker.execute {
            try {
                val input = contentResolver.openInputStream(uri) ?: throw IllegalStateException("nothing to read")
                input.use { Transcription.recording(this).outputStream().use { output -> it.copyTo(output) } }
                runOnUiThread { startService(resume = false) }
            } catch (e: Exception) {
                Transcription.fail("Couldn't open that file (${e.message}).")
            }
        }
    }

    private fun startService(resume: Boolean) {
        try {
            startForegroundService(Intent(this, TranscribeService::class.java).putExtra(TranscribeService.EXTRA_RESUME, resume))
        } catch (e: Exception) {
            // Android only lets it start while Murmur is on screen.
            Transcription.fail(getString(R.string.transcribe_not_started))
        }
    }

    private fun render(state: Transcription.State) {
        val working = state.phase == Phase.WORKING
        result.text = state.text
        progress.visibility = if (working) View.VISIBLE else View.GONE
        copy.isEnabled = state.text.isNotBlank()
        share.isEnabled = state.text.isNotBlank()
        stop.visibility = if (working) View.VISIBLE else View.GONE
        val canResume = state.phase == Phase.STOPPED && Transcription.recording(this).exists()
        resume.visibility = if (canResume) View.VISIBLE else View.GONE
        resume.setText(if (state.partsDone > 0) R.string.transcribe_continue else R.string.transcribe_retry)
        if (state.phase == Phase.DONE && copied != state.text) {
            copyToClipboard(state.text)
            copied = state.text
        }
        status.text = note ?: when (state.phase) {
            Phase.IDLE -> getString(R.string.transcribe_nothing)
            Phase.WORKING -> when {
                state.totalMs > 0 && state.doneMs > 0 -> getString(
                    R.string.transcribe_working,
                    Transcription.clock(state.doneMs),
                    Transcription.clock(state.totalMs),
                )
                state.totalMs > 0 -> getString(R.string.transcribe_starting, Transcription.clock(state.totalMs))
                else -> getString(R.string.transcribe_reading)
            }
            Phase.DONE -> getString(R.string.transcribe_done)
            Phase.STOPPED -> listOfNotNull(state.message, getString(R.string.transcribe_so_far).takeIf { state.text.isNotBlank() })
                .joinToString(" ")
        }
    }

    private fun copyToClipboard(text: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Murmur", text))
    }

    private fun sharedUri(intent: Intent): Uri? {
        if (intent.action != Intent.ACTION_SEND) return null
        val stream = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        }
        return stream ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
    }

    private companion object {
        const val NOTIFICATIONS_REQUEST = 1
    }
}
