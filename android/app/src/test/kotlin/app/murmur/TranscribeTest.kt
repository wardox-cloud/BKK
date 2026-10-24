package app.murmur

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import app.murmur.core.Dictation
import app.murmur.core.DictationException
import app.murmur.core.Prompts
import app.murmur.core.SttProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** Sharing a recording to Murmur from another app. */
@RunWith(RobolectricTestRunner::class)
class TranscribeTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test
    fun `Murmur is offered when sharing recordings and voice notes`() {
        for (type in listOf("audio/mp4", "audio/x-m4a", "audio/ogg", "audio/mpeg", "application/ogg")) {
            val share = Intent(Intent.ACTION_SEND).setType(type).setPackage(app.packageName)
            val targets = app.packageManager.queryIntentActivities(share, 0).map { it.activityInfo.name }
            assertEquals(type, listOf(TranscribeActivity::class.java.name), targets)
        }
    }

    @Test
    fun `opened without a recording, it says how to use it`() {
        val activity = Robolectric.buildActivity(TranscribeActivity::class.java).setup().get()
        assertEquals(app.getString(R.string.transcribe_nothing), activity.findViewById<TextView>(R.id.transcribe_status).text.toString())
    }

    @Test
    fun `without a key, it asks for one`() {
        val activity = Robolectric.buildActivity(TranscribeActivity::class.java, shared()).setup().get()
        assertEquals(app.getString(R.string.transcribe_no_key), activity.findViewById<TextView>(R.id.transcribe_status).text.toString())
    }

    @Test
    fun `a shared recording is transcribed, shown and copied`() {
        Prefs(app).edit { putString("key_gemini", "AIza-test") }
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"Acha suno, woh proposal abhi tak finalize nahi hua."}]}}]}"""))
        val controller = Robolectric.buildActivity(TranscribeActivity::class.java, shared())
        val activity = controller.get()
        var decoded: Uri? = null
        activity.decode = { decoded = it; AudioFile.Decoded(ByteArray(2 * 16_000 * 75), trimmed = true) }
        activity.dictation = Dictation(Prompts.load(), baseUrls = SttProvider.entries.associateWith { server.url("/fake").toString() })
        controller.setup()

        val status = activity.findViewById<TextView>(R.id.transcribe_status)
        waitFor(status) { it.startsWith("Done") }
        assertEquals(RECORDING, decoded)
        assertTrue(status.text.toString(), status.text.contains("first 7 minutes"))
        val text = "Acha suno, woh proposal abhi tak finalize nahi hua."
        assertEquals(text, activity.findViewById<TextView>(R.id.transcribe_result).text.toString())
        assertTrue(activity.findViewById<Button>(R.id.transcribe_copy).isEnabled)
        val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(text, clipboard.primaryClip!!.getItemAt(0).text.toString())

        val request = server.takeRequest()
        assertTrue(request.path!!.endsWith("gemini-3.6-flash:generateContent"))
        assertTrue(request.body.readUtf8().contains("audio/wav"))
        server.shutdown()
    }

    @Test
    fun `a file it can't read gets a clear message`() {
        Prefs(app).edit { putString("key_gemini", "AIza-test") }
        val controller = Robolectric.buildActivity(TranscribeActivity::class.java, shared())
        controller.get().decode = { throw DictationException("That file has no audio in it.") }
        val activity = controller.setup().get()

        val status = activity.findViewById<TextView>(R.id.transcribe_status)
        waitFor(status) { it == "That file has no audio in it." }
        assertFalse(activity.findViewById<Button>(R.id.transcribe_copy).isEnabled)
    }

    private fun shared() = Intent(Intent.ACTION_SEND).setType("audio/mp4").putExtra(Intent.EXTRA_STREAM, RECORDING)

    /** Runs the main thread until [done] holds for the status text, for up to ten seconds. */
    private fun waitFor(status: TextView, done: (String) -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!done(status.text.toString()) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        }
        assertTrue("status was: ${status.text}", done(status.text.toString()))
    }

    private companion object {
        val RECORDING: Uri = Uri.parse("content://com.sec.android.app.voicenote/recording.m4a")
    }
}
