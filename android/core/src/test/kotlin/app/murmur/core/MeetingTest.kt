package app.murmur.core

import app.murmur.core.MeetingStore.Status
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Meetings: kept on the phone part by part, written down in order, then turned into notes. */
class MeetingTest {
    private val json = ObjectMapper()
    private val root: File = Files.createTempDirectory("meetings").toFile()
    private var clock = 1_793_000_000_000L // a Monday lunchtime in Karachi
    private val store = MeetingStore(root, { clock }, ZoneId.of("Asia/Karachi"))
    private val server = MockWebServer()
    private val systems = mutableListOf<String>()
    private val texts = mutableListOf<String>()
    var outOfQuota = false

    private val settings = DictationSettings(
        provider = SttProvider.GEMINI,
        sttKey = "AIza-test",
        sttModel = "gemini-3.6-flash",
        geminiKey = "AIza-test",
        geminiModel = "gemini-3.6-flash",
        cleanup = CleanupEngine.GEMINI,
        anthropicKey = "",
        claudeModel = "claude-opus-5-5",
        vocabulary = listOf("Axiom Talent"),
    )

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (!request.path.orEmpty().contains(":generateContent")) return MockResponse().setResponseCode(404)
                val body = json.readTree(request.body.readUtf8())
                val system = body["systemInstruction"]["parts"][0]["text"].asText()
                systems += system
                texts += body["contents"][0]["parts"].filter { it.has("text") }.joinToString(" | ") { it["text"].asText() }
                if (outOfQuota) {
                    return MockResponse().setResponseCode(429).setBody(
                        """{"error":{"code":429,"details":[{"violations":[{"quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier","quotaValue":"20"}]}]}}""",
                    )
                }
                val reply = when {
                    system.startsWith("This is part 1 ") -> "Ali: Acha suno, pricing pe baat karni hai.\n\nSpeaker 2: Theek hai, the numbers don't add up."
                    system.startsWith("This is part") -> "Ali: Let's move Sarah's candidate to the interview stage."
                    system.startsWith("Someone selected text") -> "Can we meet tomorrow?"
                    system.startsWith("You write meeting notes") ->
                        "**Title:** Pricing and hiring review\n\n## Summary\nThey agreed to revisit pricing.\n\n## Action items\n- [ ] Ali: send the new numbers by Friday."
                    else -> "?"
                }
                return MockResponse().setBody(json.writeValueAsString(mapOf("candidates" to listOf(mapOf("content" to mapOf("parts" to listOf(mapOf("text" to reply))))))))
            }
        }
        server.start()
    }

    @AfterTest
    fun cleanUp() {
        server.shutdown()
        root.deleteRecursively()
    }

    private fun pipeline() = MeetingPipeline(
        store,
        Dictation(baseUrls = SttProvider.entries.associateWith { server.url("/gemini").toString() }),
    )

    private fun minutes(n: Double) = ByteArray((n * 60 * 2 * Wav.SAMPLE_RATE).toInt())

    @Test
    fun `a new meeting gets a folder and a dated title`() {
        val meeting = store.create()
        assertEquals("Meeting, Mon 26 Oct, 12:33 PM", meeting.title)
        assertEquals(Status.RECORDING, meeting.status)
        assertTrue(File(store.folder(meeting.id), "meta.json").exists())
        assertEquals(listOf(meeting), store.list())
    }

    @Test
    fun `parts are written down in order while recording, then the notes`() {
        val id = store.create().id
        store.addPart(id, minutes(5.0))
        assertEquals(listOf(1), store.waitingParts(id))

        pipeline().run(id, settings, recordingOver = false)
        assertEquals(emptyList(), store.waitingParts(id))
        assertFalse(store.partAudio(id, 1).exists(), "the part's audio is deleted once written down")
        assertEquals(Status.RECORDING, store.get(id)!!.status)
        assertEquals(5 * 60_000L, store.get(id)!!.doneMs)

        store.addPart(id, minutes(4.5))
        val meeting = pipeline().run(id, settings, recordingOver = true)

        assertEquals(Status.DONE, meeting.status)
        assertEquals("Pricing and hiring review", meeting.title)
        assertEquals(570_000L, meeting.durationMs)
        assertEquals(
            "Ali: Acha suno, pricing pe baat karni hai.\n\nSpeaker 2: Theek hai, the numbers don't add up.\n\nAli: Let's move Sarah's candidate to the interview stage.",
            store.transcript(id),
        )
        assertEquals("## Summary\nThey agreed to revisit pricing.\n\n## Action items\n- [ ] Ali: send the new numbers by Friday.", store.notes(id))

        // The second part knows who has spoken and how the first part ended.
        assertContains(systems[1], "The meeting so far has these speakers: Ali, Speaker 2.")
        assertContains(systems[1], "Speaker 2: Theek hai, the numbers don't add up.")
        assertContains(systems[1], "Axiom Talent")
        assertContains(systems[2], "You write meeting notes")
        assertContains(texts[2], "<transcript>")
    }

    @Test
    fun `when the free requests run out it waits, keeps what's done, and carries on later`() {
        val id = store.create().id
        store.addPart(id, minutes(5.0))
        pipeline().run(id, settings, recordingOver = false)
        store.addPart(id, minutes(5.0))

        outOfQuota = true
        val stopped = pipeline().run(id, settings, recordingOver = true)
        assertEquals(Status.WAITING, stopped.status)
        assertContains(stopped.message!!, "free Gemini requests")
        assertEquals(listOf(2), store.waitingParts(id))

        outOfQuota = false
        val requestsBefore = systems.size
        val done = pipeline().run(id, settings, recordingOver = true)
        assertEquals(Status.DONE, done.status)
        assertTrue(systems.drop(requestsBefore).none { it.startsWith("This is part 1 ") }, "part 1 isn't sent again")
    }

    @Test
    fun `a failure while recording keeps recording`() {
        val id = store.create().id
        store.addPart(id, minutes(5.0))
        outOfQuota = true
        val meeting = pipeline().run(id, settings, recordingOver = false)
        assertEquals(Status.RECORDING, meeting.status)
        assertContains(meeting.message!!, "tries again with the next part")
    }

    @Test
    fun `a recording with nothing in it says so`() {
        val id = store.create().id
        store.addPart(id, ByteArray(1000))
        val meeting = pipeline().run(id, settings, recordingOver = true)
        assertEquals(Status.DONE, meeting.status)
        assertEquals("Nothing was said in this recording.", meeting.message)
        assertNull(store.notes(id))
        assertTrue(systems.isEmpty(), "nothing to send")
    }

    @Test
    fun `a title you set is kept`() {
        val id = store.create().id
        store.update(id) { it.copy(title = "Client visit", renamed = true) }
        store.addPart(id, minutes(1.0))
        assertEquals("Client visit", pipeline().run(id, settings, recordingOver = true).title)
    }

    @Test
    fun `search looks in titles, transcripts and notes`() {
        val first = store.create().id
        store.addPart(first, minutes(1.0))
        pipeline().run(first, settings, recordingOver = true)
        clock += 3_600_000
        val second = store.create().id

        assertEquals(listOf(second, first), store.list().map { it.id })
        assertEquals(listOf(first), store.search("numbers ADD").map { it.id })
        assertEquals(listOf(first), store.search("PRICING").map { it.id })
        assertEquals(emptyList(), store.search("budget"))
        store.delete(first)
        assertEquals(listOf(second), store.list().map { it.id })
    }

    @Test
    fun `a spoken instruction rewrites the selected text`() {
        val dictation = Dictation(baseUrls = SttProvider.entries.associateWith { server.url("/gemini").toString() })
        val result = dictation.command(Wav.encode(ByteArray(32_000)), "Hey, would it be possible for us to perhaps meet tomorrow?", settings)
        assertEquals("Can we meet tomorrow?", result)
        assertContains(systems.single(), "apply it to the selected text")
        assertEquals("<selected>\nHey, would it be possible for us to perhaps meet tomorrow?\n</selected>", texts.single())
    }

    @Test
    fun `speakers, tail and title are read from the text`() {
        assertEquals(
            listOf("Ali", "Speaker 2", "Matt Doyle"),
            MeetingPipeline.speakers("Ali: hi\nSpeaker 2: hello\nAli: again\nMatt Doyle: yes\nnot a label: no"),
        )
        assertEquals("line three", MeetingPipeline.tail("line one\nline two\nline three", length = 14))
        assertEquals("Budget" to "## Summary\nx", MeetingPipeline.splitTitle("Title: Budget\n\n## Summary\nx"))
        assertEquals(null to "## Summary\nx", MeetingPipeline.splitTitle("## Summary\nx"))
    }
}
