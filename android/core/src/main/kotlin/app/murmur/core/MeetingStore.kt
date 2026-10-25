package app.murmur.core

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * Meetings, kept on the phone in one folder each:
 *
 *     meta.json         title, times and status
 *     audio.m4a         the recording (or the shared file, under its own name)
 *     parts/0001.wav    a few minutes of audio still to be written down
 *     parts/0001.txt    what was said in it; its wav is deleted once this exists
 *     notes.md          summary, decisions and action items
 */
class MeetingStore(
    private val root: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    enum class Status {
        /** Still recording; parts are written down as they come in. */
        RECORDING,

        /** Recording over; the last parts or the notes are being written. */
        TRANSCRIBING,

        /** Stopped partway, for the reason in [Meeting.message]; Continue picks it up. */
        WAITING,

        DONE,
    }

    data class Meeting(
        val id: String = "",
        val title: String = "",
        val startedAt: Long = 0,
        val durationMs: Long = 0,
        val status: Status = Status.RECORDING,
        /** Why writing it down stopped, or a note about it. */
        val message: String? = null,
        /** The recording's file name in the meeting's folder. */
        val audio: String? = null,
        /** How much of the audio has been written down. */
        val doneMs: Long = 0,
        /** A shared recording that still has to be split into parts. */
        val needsSplitting: Boolean = false,
        /** You renamed it, so the notes don't replace the title. */
        val renamed: Boolean = false,
    )

    // Every field has a default, so Kotlin gives Meeting the no-argument constructor Jackson needs.
    private val json = ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun create(audio: String? = null, needsSplitting: Boolean = false): Meeting {
        val started = now()
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(zone).format(Instant.ofEpochMilli(started))
        val meeting = Meeting(
            id = "$stamp-${UUID.randomUUID().toString().take(4)}",
            title = "Meeting, " + DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.ENGLISH).withZone(zone).format(Instant.ofEpochMilli(started)),
            startedAt = started,
            status = if (needsSplitting) Status.TRANSCRIBING else Status.RECORDING,
            audio = audio,
            needsSplitting = needsSplitting,
        )
        File(folder(meeting.id), "parts").mkdirs()
        save(meeting)
        return meeting
    }

    fun folder(id: String) = File(root, id)

    @Synchronized
    fun get(id: String): Meeting? = read(File(folder(id), "meta.json"))

    /** Newest first. */
    @Synchronized
    fun list(): List<Meeting> =
        root.listFiles().orEmpty().mapNotNull { read(File(it, "meta.json")) }.sortedByDescending { it.startedAt }

    /** Meetings whose title, transcript or notes contain [query], newest first. */
    fun search(query: String): List<Meeting> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return list()
        return list().filter { meeting ->
            val text = listOf(meeting.title, transcript(meeting.id), notes(meeting.id).orEmpty()).joinToString("\n").lowercase()
            words.all { it in text }
        }
    }

    @Synchronized
    fun update(id: String, change: (Meeting) -> Meeting): Meeting {
        val updated = change(requireNotNull(get(id)) { "No meeting $id" })
        save(updated)
        return updated
    }

    /** Saves a few minutes of 16 kHz mono PCM to be written down, and returns its part number. */
    @Synchronized
    fun addPart(id: String, pcm: ByteArray): Int {
        val parts = File(folder(id), "parts").apply { mkdirs() }
        val number = (parts.list().orEmpty().mapNotNull { it.substringBefore('.').toIntOrNull() }.maxOrNull() ?: 0) + 1
        writeAtomically(File(parts, "%04d.wav".format(number)), Wav.encode(pcm))
        update(id) { it.copy(durationMs = it.durationMs + millis(pcm.size)) }
        return number
    }

    /** Parts saved so far, written down or not. */
    @Synchronized
    fun partCount(id: String): Int =
        File(folder(id), "parts").list().orEmpty().mapNotNull { it.substringBefore('.').toIntOrNull() }.distinct().size

    /** Parts recorded but not written down yet, in order. */
    @Synchronized
    fun waitingParts(id: String): List<Int> =
        File(folder(id), "parts").listFiles().orEmpty()
            .filter { it.name.endsWith(".wav") && !File(it.parentFile, it.name.replace(".wav", ".txt")).exists() }
            .mapNotNull { it.name.substringBefore('.').toIntOrNull() }
            .sorted()

    fun partAudio(id: String, part: Int) = File(File(folder(id), "parts"), "%04d.wav".format(part))

    /** Keeps what was said in [part] and deletes its audio, which the full recording still has. */
    @Synchronized
    fun finishPart(id: String, part: Int, text: String): Meeting {
        val audio = partAudio(id, part)
        val ms = if (audio.exists()) millis((audio.length() - 44).toInt()) else 0
        writeAtomically(File(audio.parentFile, "%04d.txt".format(part)), text.trim().toByteArray())
        audio.delete()
        return update(id) { it.copy(doneMs = it.doneMs + ms) }
    }

    /** Everything written down so far, part after part. */
    fun transcript(id: String): String =
        File(folder(id), "parts").listFiles().orEmpty()
            .filter { it.name.endsWith(".txt") }
            .sortedBy { it.name }
            .map { it.readText().trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")

    fun notes(id: String): String? = File(folder(id), "notes.md").takeIf { it.exists() }?.readText()

    fun saveNotes(id: String, notes: String) = writeAtomically(File(folder(id), "notes.md"), notes.toByteArray())

    @Synchronized
    fun delete(id: String) {
        folder(id).deleteRecursively()
    }

    @Synchronized
    private fun save(meeting: Meeting) = writeAtomically(File(folder(meeting.id), "meta.json"), json.writeValueAsBytes(meeting))

    private fun read(file: File): Meeting? = runCatching { json.readValue(file, Meeting::class.java) }.getOrNull()

    /** Writes to a temporary file first, so a crash never leaves half a file. */
    private fun writeAtomically(file: File, bytes: ByteArray) {
        file.parentFile.mkdirs()
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeBytes(bytes)
        if (!temporary.renameTo(file)) {
            file.delete()
            temporary.renameTo(file)
        }
    }

    private fun millis(pcmBytes: Int) = pcmBytes / 2 * 1000L / Wav.SAMPLE_RATE
}
