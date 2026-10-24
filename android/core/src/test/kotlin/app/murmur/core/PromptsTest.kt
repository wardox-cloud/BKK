package app.murmur.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals

/** The keyboard must send exactly the prompts the web app sends (fixtures come from src/prompts.js). */
class PromptsTest {
    private val prompts = Prompts.load()
    private val fixtures: JsonNode = ObjectMapper().readTree(javaClass.getResourceAsStream("/prompt-fixtures.json"))

    private fun labels(node: JsonNode) = node.properties().associate { (id, label) -> id to label.asText() }

    @Test
    fun `labels and their order match the web app`() {
        assertEquals(labels(fixtures["languages"]).toList(), prompts.languages.toList())
        assertEquals(labels(fixtures["styles"]).toList(), prompts.styles.toList())
    }

    @Test
    fun `every prompt matches the web app`() {
        for (case in fixtures["cases"]) {
            val language = case["language"].asText()
            val vocabulary = case["vocabulary"].map { it.asText() }
            val actual = when (case["kind"].asText()) {
                "instruction" -> prompts.transcriptionInstruction(language, vocabulary)
                "primer" -> prompts.transcriptionPrimer(language, vocabulary)
                else -> prompts.cleanupPrompt(language, case["style"].asText(), vocabulary, case["fromAudio"].asBoolean())
            }
            assertEquals(case["expected"].asText(), actual, "mismatch for $case")
        }
    }
}
