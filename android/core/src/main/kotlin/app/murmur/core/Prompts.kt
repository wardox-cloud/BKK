package app.murmur.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Builds prompts from shared/prompts.json. src/prompts.js builds the same
 * strings for the web app; PromptsTest checks the two match.
 */
class Prompts(json: String) {
    private val root: JsonNode = ObjectMapper().readTree(json)
    private val speaker = lines(root["speaker"])

    /** Language mode id to label, in file order. */
    val languages: Map<String, String> = labels(root["languages"])

    /** Style id to label, in file order. */
    val styles: Map<String, String> = labels(root["styles"])

    /** For models that take instructions with the audio (Gemini). */
    fun transcriptionInstruction(language: String, vocabulary: List<String>): String {
        val terms = vocabulary.joinToString(", ")
        val transcribe = root["transcribe"]
        return fill(
            lines(transcribe["instruction"]),
            mapOf(
                "speaker" to speaker,
                "language" to root["languages"][language]["transcribe"].asText(),
                "vocabulary" to if (terms.isEmpty()) "" else fill(transcribe["vocabulary"].asText(), mapOf("terms" to terms)),
            ),
        ).split("\n").filter { it.isNotBlank() }.joinToString("\n")
    }

    /** For Whisper-style models, whose prompt is example text, not instructions. */
    fun transcriptionPrimer(language: String, vocabulary: List<String>): String {
        val terms = vocabulary.joinToString(", ")
        val parts = listOf(root["languages"][language]["primer"].asText()).filter { it.isNotEmpty() }.toMutableList()
        if (terms.isNotEmpty()) parts += fill(root["transcribe"]["primerVocabulary"].asText(), mapOf("terms" to terms))
        return parts.joinToString(" ")
    }

    /**
     * System prompt for cleanup. With [fromAudio] the model gets the recording and does both steps.
     * [styleInstruction] replaces the style's own wording, for styles you write yourself.
     */
    fun cleanupPrompt(
        language: String,
        style: String,
        vocabulary: List<String>,
        fromAudio: Boolean = false,
        styleInstruction: String? = null,
    ): String {
        val cleanup = root["cleanup"]
        val terms = vocabulary.joinToString(", ")
        val parts = mutableListOf(
            lines(cleanup["base"]),
            lines(cleanup["accent"]),
            root["languages"][language]["cleanup"].asText(),
            styleInstruction ?: root["styles"][style]?.get("cleanup")?.asText().orEmpty(),
        )
        if (terms.isNotEmpty()) parts += fill(cleanup["vocabulary"].asText(), mapOf("terms" to terms))
        if (fromAudio) parts += fill(cleanup["oneStep"].asText(), mapOf("speaker" to speaker))
        parts += cleanup["finish"].asText()
        return parts.joinToString("\n\n")
    }

    /**
     * For one part of a meeting recording. [speakers] and [tail] come from the
     * parts before it, so names and speaker labels carry on.
     */
    fun meetingInstruction(language: String, vocabulary: List<String>, part: Int, speakers: List<String>, tail: String): String {
        val meeting = root["meeting"]
        val context = if (tail.isBlank()) "" else fill(
            meeting["context"].asText(),
            mapOf("speakers" to speakers.joinToString(", ").ifEmpty { "not yet known" }, "tail" to tail.trim()),
        )
        return fill(
            lines(meeting["transcribe"]),
            mapOf(
                "part" to "$part",
                "speaker" to speaker,
                "language" to root["languages"][language]["cleanup"].asText(),
                "vocabulary" to vocabularyLine(vocabulary),
                "context" to context,
            ),
        ).dropBlankLines()
    }

    /** System prompt for meeting notes; the transcript goes in [transcriptMessage]. */
    fun notesPrompt(vocabulary: List<String>): String =
        fill(lines(root["meeting"]["notes"]), mapOf("speaker" to speaker, "vocabulary" to vocabularyLine(vocabulary)))
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

    /** For a spoken instruction about selected text; the text goes in [selectedMessage]. */
    fun commandPrompt(vocabulary: List<String>): String =
        fill(lines(root["command"]["instruction"]), mapOf("speaker" to speaker, "vocabulary" to vocabularyLine(vocabulary)))
            .dropBlankLines()

    private fun vocabularyLine(vocabulary: List<String>): String {
        val terms = vocabulary.joinToString(", ")
        return if (terms.isEmpty()) "" else fill(root["cleanup"]["vocabulary"].asText(), mapOf("terms" to terms))
    }

    companion object {
        fun load(): Prompts {
            val stream = requireNotNull(Prompts::class.java.getResourceAsStream("/prompts.json")) { "prompts.json missing" }
            return Prompts(stream.use { it.readBytes().decodeToString() })
        }

        fun transcriptMessage(raw: String) = "<transcript>\n$raw\n</transcript>"

        fun selectedMessage(text: String) = "<selected>\n$text\n</selected>"

        private fun String.dropBlankLines() = split("\n").filter { it.isNotBlank() }.joinToString("\n")

        private val placeholder = Regex("""\{(\w+)\}""")

        private fun fill(text: String, values: Map<String, String>) =
            placeholder.replace(text) { values[it.groupValues[1]].orEmpty() }

        private fun lines(node: JsonNode) =
            if (node.isArray) node.joinToString("\n") { it.asText() } else node.asText()

        private fun labels(node: JsonNode) =
            node.properties().associate { (id, value) -> id to value["label"].asText() }
    }
}
