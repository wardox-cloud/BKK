package app.murmur.core

import app.murmur.core.MeetingStore.Meeting
import app.murmur.core.MeetingStore.Status

/**
 * Writes down a meeting's parts in order, then its notes. It's safe to run
 * again at any time: it picks up from whatever is still waiting.
 */
class MeetingPipeline(private val store: MeetingStore, private val dictation: Dictation) {
    /**
     * Writes down the parts recorded so far. Once [recordingOver] (no more
     * parts are coming), it also writes the notes after the last part. On the
     * first failure (no internet, no free requests left) it stops and says why
     * in the meeting's message; nothing is lost, and running it again carries on.
     */
    fun run(id: String, settings: DictationSettings, recordingOver: Boolean, onChange: (Meeting) -> Unit = {}): Meeting {
        var meeting = store.update(id) { if (recordingOver) it.copy(status = Status.TRANSCRIBING, message = null) else it.copy(message = null) }
        onChange(meeting)
        try {
            for (part in store.waitingParts(id)) {
                val wav = store.partAudio(id, part).readBytes()
                val transcript = store.transcript(id)
                val text = if (wav.size < 44 + Wav.SAMPLE_RATE) {
                    "" // under half a second
                } else {
                    try {
                        dictation.meetingPart(wav, settings, part, speakers(transcript), tail(transcript))
                    } catch (e: DictationException) {
                        if (e.message != Dictation.NO_SPEECH) throw e
                        ""
                    }
                }
                meeting = store.finishPart(id, part, text).also(onChange)
            }
            if (!recordingOver) return meeting

            val transcript = store.transcript(id)
            if (transcript.isBlank()) {
                meeting = store.update(id) { it.copy(status = Status.DONE, message = "Nothing was said in this recording.") }
            } else {
                if (store.notes(id) == null) {
                    val (title, notes) = splitTitle(dictation.notes(transcript, settings))
                    store.saveNotes(id, notes)
                    if (title != null) store.update(id) { if (it.renamed) it else it.copy(title = title) }
                }
                meeting = store.update(id) { it.copy(status = Status.DONE, message = null) }
            }
        } catch (e: Exception) {
            val reason = (e as? DictationException)?.message ?: "Something went wrong (${e.message})."
            meeting = store.update(id) {
                if (recordingOver) {
                    it.copy(status = Status.WAITING, message = reason)
                } else {
                    // More parts are coming; the waiting ones get another go with the next.
                    it.copy(message = "$reason Murmur tries again with the next part.")
                }
            }
        }
        onChange(meeting)
        return meeting
    }

    companion object {
        private val LABEL = Regex("""^((?:Speaker \d+)|(?:\p{Lu}[\p{L}.'\-]*(?: \p{Lu}[\p{L}.'\-]*){0,2})):\s""")

        /** The speaker labels used so far, in order of first appearance ("Ali", "Speaker 2"). */
        fun speakers(transcript: String): List<String> =
            transcript.lineSequence().mapNotNull { LABEL.find(it.trim())?.groupValues?.get(1) }.distinct().take(12).toList()

        /** The end of the transcript so far, from the start of a line, to carry context into the next part. */
        fun tail(transcript: String, length: Int = 600): String {
            if (transcript.length <= length) return transcript
            val cut = transcript.length - length
            val lineStart = transcript.indexOf('\n', cut)
            return transcript.substring(if (lineStart in cut until transcript.length - 1) lineStart + 1 else cut).trim()
        }

        /** Takes the "Title: ..." line off the top of the notes. */
        fun splitTitle(notes: String): Pair<String?, String> {
            val lines = notes.trim().lines()
            val first = lines.indexOfFirst { it.isNotBlank() }
            if (first < 0) return null to ""
            val match = Regex("""^\W*title\W*:\W*(.+?)\W*$""", RegexOption.IGNORE_CASE).find(lines[first].trim())
                ?: return null to notes.trim()
            val title = match.groupValues[1].replace("*", "").trim().ifEmpty { null }
            return title to lines.drop(first + 1).joinToString("\n").trim()
        }
    }
}
