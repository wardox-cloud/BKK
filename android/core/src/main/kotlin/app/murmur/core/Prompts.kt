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
        val parts = mutableListOf(root["languages"][language]["primer"].asText())
        if (terms.isNotEmpty()) parts += fill(root["transcribe"]["primerVocabulary"].asText(), mapOf("terms" to terms))
        return parts.joinToString(" ")
    }

    /** System prompt for cleanup. With [fromAudio] the model gets the recording and does both steps. */
    fun cleanupPrompt(language: String, style: String, vocabulary: List<String>, fromAudio: Boolean = false): String {
        val cleanup = root["cleanup"]
        val terms = vocabulary.joinToString(", ")
        val parts = mutableListOf(
            lines(cleanup["base"]),
            lines(cleanup["accent"]),
            root["languages"][language]["cleanup"].asText(),
            root["styles"][style]["cleanup"]?.asText().orEmpty(),
        )
        if (terms.isNotEmpty()) parts += fill(cleanup["vocabulary"].asText(), mapOf("terms" to terms))
        if (fromAudio) parts += fill(cleanup["oneStep"].asText(), mapOf("speaker" to speaker))
        parts += cleanup["finish"].asText()
        return parts.joinToString("\n\n")
    }

    companion object {
        fun load(): Prompts {
            val stream = requireNotNull(Prompts::class.java.getResourceAsStream("/prompts.json")) { "prompts.json missing" }
            return Prompts(stream.use { it.readBytes().decodeToString() })
        }

        fun transcriptMessage(raw: String) = "<transcript>\n$raw\n</transcript>"

        private val placeholder = Regex("""\{(\w+)\}""")

        private fun fill(text: String, values: Map<String, String>) =
            placeholder.replace(text) { values[it.groupValues[1]].orEmpty() }

        private fun lines(node: JsonNode) =
            if (node.isArray) node.joinToString("\n") { it.asText() } else node.asText()

        private fun labels(node: JsonNode) =
            node.properties().associate { (id, value) -> id to value["label"].asText() }
    }
}
