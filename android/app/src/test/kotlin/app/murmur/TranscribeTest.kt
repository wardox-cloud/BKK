package app.murmur

import android.app.Notification
import android.app.NotificationManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import app.murmur.core.Dictation
import app.murmur.core.DictationException
import app.murmur.core.GeminiQuota
import app.murmur.core.Prompts
import app.murmur.core.SttProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.ByteArrayInputStream
import java.io.File
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** Sharing a recording to Murmur from another app, short or long. */
@RunWith(RobolectricTestRunner::class)
class TranscribeTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val server = MockWebServer()

    @Before
    fun fresh() = Transcription.reset()

    @After
    fun cleanUp() {
        Transcription.reset()
        server.shutdown()
    }

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
        assertEquals(app.getString(R.string.transcribe_nothing), status(activity))
    }

    @Test
    fun `without a key, it asks for one`() {
        val activity = Robolectric.buildActivity(TranscribeActivity::class.java, shared()).setup().get()
        assertEquals(app.getString(R.string.transcribe_no_key), status(activity))
    }

    @Test
    fun `a long recording is written down part by part, in the background`() {
        Prefs(app).edit { putString("key_gemini", "AIza-test") }
        reply("Acha suno, woh proposal abhi tak finalize nahi hua.")
        reply("I'll follow up with Sarah tomorrow.")
        val opened = mutableListOf<File>()
        // Two parts, then a scrap too short to send.
        fakeRecording(opened, part(30), part(30), part(0.2))

        val activity = Robolectric.buildActivity(TranscribeActivity::class.java, shared()).setup().get()
        runService(nextService())
        waitFor { Transcription.state.phase == Transcription.Phase.DONE }

        val text = "Acha suno, woh proposal abhi tak finalize nahi hua.\n\nI'll follow up with Sarah tomorrow."
        assertEquals(text, activity.findViewById<TextView>(R.id.transcribe_result).text.toString())
        assertEquals(app.getString(R.string.transcribe_done), status(activity))
        assertEquals(text, clipboard())
        assertEquals(listOf(Transcription.recording(app)), opened)
        assertFalse("the copy is deleted once done", Transcription.recording(app).exists())
        assertEquals(2, server.requestCount)
        assertTrue(server.takeRequest().body.readUtf8().contains("audio/wav"))
        waitFor { notificationTitles().isNotEmpty() }
        assertEquals(listOf(app.getString(R.string.transcript_ready)), notificationTitles())
    }

    @Test
    fun `when the free quota runs out, Continue picks up where it stopped`() {
        Prefs(app).edit { putString("key_gemini", "AIza-test") }
        reply("Part one.")
        // The only model has used up its free requests for the day.
        server.enqueue(
            MockResponse().setResponseCode(429).setBody(
                """{"error":{"code":429,"details":[{"violations":[{"quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier","quotaValue":"20"}]}]}}""",
            ),
        )
        fakeRecording(mutableListOf(), part(30), part(30))

        val activity = Robolectric.buildActivity(TranscribeActivity::class.java, shared()).setup().get()
        runService(nextService())
        waitFor { Transcription.state.phase == Transcription.Phase.STOPPED }
        assertEquals("Part one.", activity.findViewById<TextView>(R.id.transcribe_result).text.toString())
        assertTrue(status(activity), status(activity).contains("free Gemini requests"))
        val resume = activity.findViewById<Button>(R.id.transcribe_resume)
        assertEquals(View.VISIBLE, resume.visibility)
        assertEquals(app.getString(R.string.transcribe_continue), resume.text.toString())
        waitFor { notificationTitles().isNotEmpty() }
        assertEquals(listOf(app.getString(R.string.transcription_stopped)), notificationTitles())

        reply("Part two.")
        resume.performClick()
        val next = nextService()
        assertTrue(next.getBooleanExtra(TranscribeService.EXTRA_RESUME, false))
        runService(next)
        waitFor { Transcription.state.phase == Transcription.Phase.DONE }
        assertEquals("Part one.\n\nPart two.", activity.findViewById<TextView>(R.id.transcribe_result).text.toString())
        assertEquals(3, server.requestCount) // part one wasn't sent again
    }

    @Test
    fun `Stop keeps what's done so far`() {
        Prefs(app).edit { putString("key_gemini", "AIza-test") }
        reply("Part one.")
        reply("Part two.")
        fakeRecording(mutableListOf(), part(30), part(30), beforePart = { index -> if (index == 1) Transcription.cancel() })

        val activity = Robolectric.buildActivity(TranscribeActivity::class.java, shared()).setup().get()
        runService(nextService())
        waitFor { Transcription.state.phase == Transcription.Phase.STOPPED }
        assertEquals("Part one.", activity.findViewById<TextView>(R.id.transcribe_result).text.toString())
        assertTrue(status(activity), status(activity).startsWith("Stopped."))
    }

    @Test
    fun `a file it can't read gets a clear message`() {
        Prefs(app).edit { putString("key_gemini", "AIza-test") }
        Transcription.open = { _, _ -> throw DictationException("That file has no audio in it.") }
        shadowOf(app.contentResolver).registerInputStream(RECORDING, ByteArrayInputStream(ByteArray(10)))

        val activity = Robolectric.buildActivity(TranscribeActivity::class.java, shared()).setup().get()
        runService(nextService())
        waitFor { status(activity).startsWith("That file has no audio in it.") }
        assertFalse(activity.findViewById<Button>(R.id.transcribe_copy).isEnabled)
    }

    private fun reply(text: String) {
        server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"$text"}]}}]}"""))
    }

    private fun part(seconds: Double) = ByteArray((seconds * 2 * 16_000).toInt())

    private fun part(seconds: Int) = part(seconds.toDouble())

    /** Shares a fake recording made of [pieces], and points Murmur at the fake server. */
    private fun fakeRecording(opened: MutableList<File>, vararg pieces: ByteArray, beforePart: (Int) -> Unit = {}) {
        shadowOf(app.contentResolver).registerInputStream(RECORDING, ByteArrayInputStream("m4a bytes".toByteArray()))
        // One known model, so the fake server isn't asked for the list.
        val quota = GeminiQuota().apply { rememberModels(listOf("gemini-3.6-flash")) }
        Transcription.dictation = Dictation(
            Prompts.load(),
            baseUrls = SttProvider.entries.associateWith { server.url("/fake").toString() },
            quota = quota,
        )
        Transcription.open = { _, file ->
            opened += file
            assertEquals("m4a bytes", file.readText())
            object : Transcription.Source {
                override val durationMs = pieces.sumOf { it.size } / 32L
                override fun parts(onPart: (ByteArray) -> Unit) = pieces.forEachIndexed { index, part ->
                    beforePart(index)
                    onPart(part)
                }
            }
        }
    }

    private fun shared() = Intent(Intent.ACTION_SEND).setType("audio/mp4").putExtra(Intent.EXTRA_STREAM, RECORDING)

    private fun status(activity: TranscribeActivity) = activity.findViewById<TextView>(R.id.transcribe_status).text.toString()

    private fun clipboard() =
        (app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip!!.getItemAt(0).text.toString()

    private fun notificationTitles() =
        shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications
            .map { it.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() }
            .filter { it != app.getString(R.string.transcribing_title) }

    /** Waits for the copy to finish and the screen to start the service. */
    private fun nextService(): Intent {
        var intent: Intent? = null
        waitFor { shadowOf(app).nextStartedService?.also { intent = it } != null }
        return intent!!
    }

    private fun runService(intent: Intent) {
        Robolectric.buildService(TranscribeService::class.java, intent).create().startCommand(0, 1)
    }

    /** Runs the main thread until [done] holds, for up to ten seconds. */
    private fun waitFor(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        // Checked once per round: nextService's check takes the intent it finds.
        var ok = done()
        while (!ok && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            ok = done()
        }
        assertTrue("timed out; state was ${Transcription.state.copy(text = Transcription.state.text.take(80))}", ok)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private companion object {
        val RECORDING: Uri = Uri.parse("content://com.sec.android.app.voicenote/recording.m4a")
    }
}
