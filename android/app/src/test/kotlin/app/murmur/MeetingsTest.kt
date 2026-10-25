package app.murmur

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import app.murmur.core.Dictation
import app.murmur.core.GeminiQuota
import app.murmur.core.HistoryStore
import app.murmur.core.MeetingStore
import app.murmur.core.MeetingStore.Status
import app.murmur.core.Prompts
import app.murmur.core.Splitter
import app.murmur.core.SttProvider
import app.murmur.core.Wav
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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
import org.robolectric.android.controller.ServiceController

/** Meetings in a simulated phone: recorded or shared, written down by a fake Gemini, then listed and searched. */
@RunWith(RobolectricTestRunner::class)
class MeetingsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val server = MockWebServer()
    private val json = ObjectMapper()
    private val systems = mutableListOf<String>()

    @Volatile
    private var outOfQuota = false
    private val mic = FakeMic()
    private val written = mutableListOf<Int>()
    private var closed = false

    @Before
    fun setUp() {
        Meetings.reset()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        Prefs(app).edit { putString("key_gemini", "AIza-test") }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (!request.path.orEmpty().contains(":generateContent")) return MockResponse().setResponseCode(404)
                val system = json.readTree(request.body.readUtf8())["systemInstruction"]["parts"][0]["text"].asText()
                synchronized(systems) { systems += system }
                if (outOfQuota) {
                    return MockResponse().setResponseCode(429).setBody(
                        """{"error":{"code":429,"details":[{"violations":[{"quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier","quotaValue":"20"}]}]}}""",
                    )
                }
                val part = Regex("This is part (\\d+)").find(system)?.groupValues?.get(1)
                val reply = when {
                    part != null -> "Ali: Point number $part."
                    system.startsWith("You write meeting notes") -> "Title: Weekly hiring sync\n\n## Summary\nThey went through the pipeline.\n\n## Action items\n- [ ] Ali: call Sarah"
                    else -> "?"
                }
                return MockResponse().setBody(json.writeValueAsString(mapOf("candidates" to listOf(mapOf("content" to mapOf("parts" to listOf(mapOf("text" to reply))))))))
            }
        }
        server.start()
        // One known model, so the fake server isn't asked for the list.
        val quota = GeminiQuota().apply { rememberModels(listOf("gemini-3.6-flash")) }
        Meetings.dictation = { Dictation(Prompts.load(), baseUrls = SttProvider.entries.associateWith { server.url("/gemini").toString() }, quota = quota) }
        Meetings.microphone = { mic }
        // Parts of one to two seconds rather than minutes, so the test is quick.
        Meetings.splitter = { Splitter(shortest = Wav.SAMPLE_RATE, longest = 2 * Wav.SAMPLE_RATE) }
        Meetings.audioWriter = {
            object : AudioWriter {
                override fun write(samples: ShortArray) {
                    written += samples.size
                }

                override fun close() {
                    closed = true
                }
            }
        }
    }

    @After
    fun tearDown() {
        Meetings.reset()
        server.shutdown()
    }

    @Test
    fun `a meeting is written down while it's recorded, then gets notes`() {
        val service = startService(MeetingService.ACTION_RECORD)
        val id = Meetings.recording!!.id
        assertEquals(Status.RECORDING, Meetings.store(app).get(id)!!.status)

        // Five seconds of steady talking: three parts of 1.15 s go off while recording carries on.
        mic.talk(seconds = 5.0)
        waitFor { Meetings.store(app).transcript(id).contains("Point number 2.") }
        assertEquals(Status.RECORDING, Meetings.store(app).get(id)!!.status)

        service.get().onStartCommand(Intent(app, MeetingService::class.java).setAction(MeetingService.ACTION_STOP), 0, 2)
        waitFor { Meetings.store(app).get(id)!!.status == Status.DONE }

        val store = Meetings.store(app)
        val meeting = store.get(id)!!
        assertEquals("Weekly hiring sync", meeting.title)
        assertEquals(5_000L, meeting.durationMs)
        // The last 1.55 s becomes part 4 when you stop.
        assertEquals((1..4).joinToString("\n\n") { "Ali: Point number $it." }, store.transcript(id))
        assertTrue(store.notes(id)!!.startsWith("## Summary"))
        assertEquals(null, Meetings.recording)
        assertTrue("the audio is kept on the phone", closed && written.sum() == 5 * Wav.SAMPLE_RATE)
        assertTrue(mic.stopped)
        // Part 2 knew what part 1 said.
        assertTrue(systems.first { it.startsWith("This is part 2") }.contains("Ali: Point number 1."))
        waitFor { notificationTitles().contains(app.getString(R.string.meeting_ready)) }
    }

    @Test
    fun `pausing skips what's said until you resume`() {
        val service = startService(MeetingService.ACTION_RECORD)
        val id = Meetings.recording!!.id
        mic.talk(seconds = 0.5)
        service.get().onStartCommand(Intent(app, MeetingService::class.java).setAction(MeetingService.ACTION_PAUSE), 0, 2)
        assertTrue(Meetings.recording!!.paused)
        mic.talk(seconds = 3.0)
        service.get().onStartCommand(Intent(app, MeetingService::class.java).setAction(MeetingService.ACTION_RESUME), 0, 3)
        mic.talk(seconds = 0.5)
        service.get().onStartCommand(Intent(app, MeetingService::class.java).setAction(MeetingService.ACTION_STOP), 0, 4)
        waitFor { Meetings.store(app).get(id)!!.status == Status.DONE }
        assertEquals(1_000L, Meetings.store(app).get(id)!!.durationMs)
    }

    @Test
    fun `a shared recording becomes a meeting with notes`() {
        val recording = Uri.parse("content://com.sec.android.app.voicenote/Voice%20001.m4a")
        shadowOf(app.contentResolver).registerInputStream(recording, ByteArrayInputStream("m4a bytes".toByteArray()))
        Meetings.audioSource = { _, file ->
            assertEquals("m4a bytes", file.readText())
            fakeSource(3)
        }
        val share = Intent(Intent.ACTION_SEND).setType("audio/mp4").putExtra(Intent.EXTRA_STREAM, recording)
        Robolectric.buildActivity(TranscribeActivity::class.java, share).setup()

        val opened = shadowOf(app).nextStartedActivity
        assertEquals(MeetingActivity::class.java.name, opened.component!!.className)
        val id = opened.getStringExtra(MeetingActivity.EXTRA_ID)!!
        runService(nextService())
        waitFor { Meetings.store(app).get(id)!!.status == Status.DONE }

        val meeting = Meetings.store(app).get(id)!!
        assertEquals("Weekly hiring sync", meeting.title)
        assertTrue(File(Meetings.store(app).folder(id), meeting.audio!!).exists())
        assertEquals(3, Regex("Point number").findAll(Meetings.store(app).transcript(id)).count())

        val screen = Robolectric.buildActivity(MeetingActivity::class.java, MeetingActivity.intent(app, id)).setup().get()
        assertEquals("Weekly hiring sync", screen.findViewById<TextView>(R.id.meeting_title).text.toString())
        assertTrue(screen.findViewById<TextView>(R.id.meeting_notes).text.toString().contains("☐ Ali: call Sarah"))
        assertTrue(screen.findViewById<TextView>(R.id.meeting_transcript).text.toString().contains("Ali: Point number 3."))
    }

    @Test
    fun `when the free requests run out, Continue finishes it later`() {
        val store = Meetings.store(app)
        val id = store.create(audio = "audio.m4a", needsSplitting = true).id
        File(store.folder(id), "audio.m4a").writeText("m4a bytes")
        Meetings.audioSource = { _, _ -> fakeSource(2) }
        outOfQuota = true
        runService(Intent(app, MeetingService::class.java).setAction(MeetingService.ACTION_PROCESS).putExtra(MeetingService.EXTRA_ID, id))
        waitFor { store.get(id)!!.status == Status.WAITING }
        assertTrue(store.get(id)!!.message!!.contains("free Gemini requests"))

        val screen = Robolectric.buildActivity(MeetingActivity::class.java, MeetingActivity.intent(app, id)).setup().get()
        val resume = screen.findViewById<Button>(R.id.meeting_resume)
        assertEquals(View.VISIBLE, resume.visibility)

        outOfQuota = false
        resume.performClick()
        runService(nextService())
        waitFor { store.get(id)!!.status == Status.DONE }
        assertEquals("Ali: Point number 1.\n\nAli: Point number 2.", store.transcript(id))
    }

    @Test
    fun `the home screen lists meetings, newest first, and searches them`() {
        val store = Meetings.store(app)
        val older = store.create().id
        store.addPart(older, ByteArray(64_000))
        store.finishPart(older, 1, "Ali: we talked about the Lahore office.")
        store.update(older) { it.copy(title = "Office move", status = Status.DONE, startedAt = 1_000) }
        val newer = store.create().id
        store.update(newer) { it.copy(title = "Pricing", status = Status.DONE, startedAt = 2_000) }

        val home = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val list = home.findViewById<ListView>(R.id.meetings)
        assertEquals(2, list.adapter.count)
        assertEquals("Pricing", (list.adapter.getItem(0) as MeetingStore.Meeting).title)

        home.findViewById<EditText>(R.id.search).setText("lahore")
        assertEquals(1, list.adapter.count)
        assertEquals("Office move", (list.adapter.getItem(0) as MeetingStore.Meeting).title)
    }

    @Test
    fun `a meeting cut off when Murmur was closed can be continued`() {
        val store = Meetings.store(app)
        val id = store.create().id
        Robolectric.buildActivity(MainActivity::class.java).setup()
        assertEquals(Status.WAITING, store.get(id)!!.status)
        assertEquals(app.getString(R.string.meeting_interrupted), store.get(id)!!.message)
    }

    @Test
    fun `the record button starts a recording`() {
        val home = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        home.findViewById<Button>(R.id.record).performClick()
        assertEquals(MeetingService.ACTION_RECORD, nextService().action)
    }

    @Test
    fun `history lists dictations and copies them`() {
        val history = Meetings.history(app)
        history.add(HistoryStore.Entry(1, "first one", "com.whatsapp"))
        history.add(HistoryStore.Entry(2, "second one", null))
        val screen = Robolectric.buildActivity(HistoryActivity::class.java).setup().get()
        val list = screen.findViewById<ListView>(R.id.history_list)
        assertEquals(2, list.adapter.count)
        shadowOf(list).performItemClick(1)
        val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals("first one", clipboard.primaryClip!!.getItemAt(0).text.toString())
    }

    @Test
    fun `Murmur is offered when sharing recordings and voice notes`() {
        for (type in listOf("audio/mp4", "audio/x-m4a", "audio/ogg", "audio/mpeg", "application/ogg")) {
            val share = Intent(Intent.ACTION_SEND).setType(type).setPackage(app.packageName)
            val targets = app.packageManager.queryIntentActivities(share, 0).map { it.activityInfo.name }
            assertEquals(type, listOf(TranscribeActivity::class.java.name), targets)
        }
    }

    private class FakeMic : Microphone {
        private var onSamples: ((ShortArray) -> Unit)? = null

        @Volatile
        var stopped = false

        override fun start(onSamples: (ShortArray) -> Unit) {
            this.onSamples = onSamples
        }

        override fun stop() {
            stopped = true
        }

        override fun isSilenced() = false

        /** Feeds [seconds] of loud audio, 100 ms at a time, like the real mic. */
        fun talk(seconds: Double) {
            repeat((seconds * 10).toInt()) { onSamples?.invoke(ShortArray(Wav.SAMPLE_RATE / 10) { 4000 }) }
        }
    }

    private fun fakeSource(parts: Int) = object : AudioSource {
        override val durationMs = parts * 60_000L
        override fun parts(onPart: (ByteArray) -> Unit) = repeat(parts) { onPart(ByteArray(2 * Wav.SAMPLE_RATE)) }
    }

    private fun startService(action: String): ServiceController<MeetingService> =
        Robolectric.buildService(MeetingService::class.java, Intent(app, MeetingService::class.java).setAction(action)).create().startCommand(0, 1)

    private fun runService(intent: Intent) {
        Robolectric.buildService(MeetingService::class.java, intent).create().startCommand(0, 1)
    }

    private fun nextService(): Intent {
        var intent: Intent? = null
        waitFor { shadowOf(app).nextStartedService?.also { intent = it } != null }
        return intent!!
    }

    private fun notificationTitles() =
        shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications
            .mapNotNull { it.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() }

    /** Runs the main thread until [done] holds, for up to ten seconds; checks once per round. */
    private fun waitFor(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        var ok = done()
        while (!ok && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            ok = done()
        }
        assertTrue("timed out", ok)
        shadowOf(Looper.getMainLooper()).idle()
    }
}
