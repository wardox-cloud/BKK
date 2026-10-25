package app.murmur.core

import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** History, styles, usage and the new prompts. */
class ExtrasTest {
    private val prompts = Prompts.load()

    @Test
    fun `history keeps dictations newest first and searches them`() {
        val file = File(Files.createTempDirectory("history").toFile(), "history.jsonl")
        val history = HistoryStore(file, limit = 4)
        for (i in 1..7) history.add(HistoryStore.Entry(time = i.toLong(), text = "dictation number $i", app = "com.whatsapp"))
        history.add(HistoryStore.Entry(time = 8, text = "  "))

        val entries = history.entries()
        assertEquals(listOf(7L, 6L, 5L, 4L), entries.map { it.time })
        assertEquals(listOf(3L), history.entries("NUMBER 3").map { it.time })
        assertEquals("com.whatsapp", entries.first().app)
        history.remove(entries.first())
        assertEquals(6L, history.entries().first().time)
        history.clear()
        assertTrue(history.entries().isEmpty())
    }

    @Test
    fun `your own styles join the built-in ones`() {
        val styles = Styles(prompts, "LinkedIn: Write it as a LinkedIn post\nno colon here\nTweet:   Under 280 characters\n: empty name")
        assertEquals(listOf("clean", "message", "email", "notes", "raw", "custom:linkedin", "custom:tweet"), styles.all.map { it.id })
        assertEquals("Under 280 characters", styles.find("custom:tweet")!!.instruction)
    }

    @Test
    fun `each app gets your choice, then a sensible default, then your usual style`() {
        val styles = Styles(prompts, "LinkedIn: Write it as a LinkedIn post")
        val chosen = mapOf("com.linkedin.android" to "custom:linkedin", "com.whatsapp" to "clean")
        assertEquals("custom:linkedin", styles.forApp("com.linkedin.android", chosen, "clean").id)
        assertEquals("clean", styles.forApp("com.whatsapp", chosen, "notes").id)
        assertEquals("email", styles.forApp("com.google.android.gm", chosen, "clean").id)
        assertEquals("notes", styles.forApp("com.example.other", chosen, "notes").id)
        assertEquals("notes", styles.forApp(null, chosen, "notes").id)
        // A style you deleted falls back to your usual one.
        assertEquals("clean", Styles(prompts, "").forApp("com.linkedin.android", chosen, "clean").id)
    }

    @Test
    fun `a custom style's wording replaces the built-in one`() {
        val prompt = prompts.cleanupPrompt("roman", "custom:linkedin", emptyList(), fromAudio = true, styleInstruction = "Write it as a LinkedIn post")
        assertContains(prompt, "Write it as a LinkedIn post")
        assertFalse(prompt.contains("Output plain text in the speaker's own voice."))
    }

    @Test
    fun `meeting, notes and command prompts are filled in`() {
        val first = prompts.meetingInstruction("roman", listOf("Axiom Talent"), part = 1, speakers = emptyList(), tail = "")
        assertTrue(first.startsWith("This is part 1 of a recording of an in-person meeting"))
        assertContains(first, "Roman Urdu")
        assertContains(first, "Spell these names and terms exactly as written: Axiom Talent.")
        assertFalse(first.contains("{"), first)
        assertFalse(first.contains("previous part"))

        val next = prompts.meetingInstruction("english", emptyList(), part = 2, speakers = listOf("Ali"), tail = "Ali: see you\n")
        assertContains(next, "The meeting so far has these speakers: Ali. The previous part ended with:\nAli: see you\nContinue from there")

        val notes = prompts.notesPrompt(emptyList())
        assertTrue(notes.startsWith("You write meeting notes"))
        assertContains(notes, "Title: a short title")
        assertContains(notes, "## Action items")
        assertFalse(notes.contains("{"))

        val command = prompts.commandPrompt(listOf("Shmookh"))
        assertContains(command, "<selected>")
        assertContains(command, "Shmookh")
        assertEquals("<selected>\nhi there\n</selected>", Prompts.selectedMessage("hi there"))
    }

    @Test
    fun `usage counts answered requests per model for the day`() {
        var clock = Instant.parse("2026-10-25T08:00:00Z")
        val quota = GeminiQuota(now = { clock })
        repeat(3) { quota.used("gemini-3.6-flash") }
        quota.used("gemini-3.5-flash")
        assertEquals(mapOf("gemini-3.6-flash" to 3, "gemini-3.5-flash" to 1), quota.usage())
        clock = quota.nextReset().plusSeconds(1)
        assertEquals(emptyMap(), quota.usage())
        quota.used("gemini-3.5-flash")
        assertEquals(mapOf("gemini-3.5-flash" to 1), quota.usage())
    }

    @Test
    fun `adts headers describe 16 kHz mono AAC frames`() {
        val header = Adts.header(frameLength = 300)
        assertEquals(listOf(0xFF, 0xF1, 0x60, 0x40, 0x26, 0x7F, 0xFC), header.map { it.toInt() and 0xFF })
        // The frame length (header included) sits across bytes 3 to 5.
        val length = ((header[3].toInt() and 3) shl 11) or ((header[4].toInt() and 0xFF) shl 3) or ((header[5].toInt() and 0xFF) shr 5)
        assertEquals(307, length)
    }
}
