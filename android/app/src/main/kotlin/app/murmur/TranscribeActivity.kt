package app.murmur

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import app.murmur.core.MeetingStore.Status
import app.murmur.core.SttProvider
import java.io.File
import java.io.InputStream

/**
 * Takes a recording shared from another app, such as Voice Recorder or a
 * WhatsApp voice note, and adds it to your meetings, written down and with
 * notes like one recorded in Murmur.
 */
class TranscribeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = sharedUri(intent)
        when {
            uri == null -> Toast.makeText(this, R.string.transcribe_nothing, Toast.LENGTH_LONG).show()
            Prefs(this).key(SttProvider.GEMINI).isBlank() -> {
                Toast.makeText(this, R.string.transcribe_no_key, Toast.LENGTH_LONG).show()
                startActivity(Intent(this, SetupActivity::class.java))
            }
            else -> import(uri)
        }
        finish()
    }

    private fun import(uri: Uri) {
        // Open it now: the app that shared it may take back access once this screen closes.
        val input = try {
            contentResolver.openInputStream(uri) ?: error("nothing to read")
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.transcribe_cant_open, e.message), Toast.LENGTH_LONG).show()
            return
        }
        val store = Meetings.store(this)
        val meeting = store.create(audio = "audio." + extension(uri), needsSplitting = true)
        nameFor(uri)?.let { name -> store.update(meeting.id) { it.copy(title = name) } }
        startActivity(MeetingActivity.intent(this, meeting.id))
        val app = applicationContext
        Thread {
            try {
                copy(input, File(store.folder(meeting.id), meeting.audio!!))
                MeetingService.process(app, meeting.id)
            } catch (e: Exception) {
                store.update(meeting.id) { it.copy(status = Status.WAITING, message = app.getString(R.string.transcribe_cant_open, e.message)) }
                Meetings.changed()
            }
        }.start()
    }

    private fun copy(input: InputStream, file: File) {
        input.use { source -> file.outputStream().use { source.copyTo(it) } }
    }

    /** The shared file's name without its extension, as a starting title. */
    private fun nameFor(uri: Uri): String? = displayName(uri)?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }

    private fun extension(uri: Uri): String =
        displayName(uri)?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.length in 2..4 && it.all(Char::isLetterOrDigit) }
            ?: when (contentResolver.getType(uri)) {
                "audio/ogg", "application/ogg", "audio/opus" -> "ogg"
                "audio/mpeg" -> "mp3"
                "audio/wav", "audio/x-wav" -> "wav"
                else -> "m4a"
            }

    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

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
}
