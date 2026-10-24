package app.murmur.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Runs whole dictations against a local server standing in for Gemini, OpenAI and Claude. */
class DictationTest {
    private val json = ObjectMapper()
    private val server = MockWebServer()
    private val requests = mutableListOf<Pair<RecordedRequest, String>>()

    // What the fake services answer; tests change these.
    var geminiTranscript = "yaar mujhe kal um the report bhejni hai na, so please remind me"
    var geminiStatus = 200
    var geminiOutOfQuota = setOf<String>()
    var claudeStatus = 200
    var claudeStopReason = "end_turn"

    private val wav = Wav.encode(ByteArray(3_200))
    private val settings = DictationSettings(
        provider = SttProvider.GEMINI,
        sttKey = "AIza-test",
        sttModel = "gemini-3.6-flash",
        geminiKey = "AIza-test",
        geminiModel = "gemini-3.6-flash",
        cleanup = CleanupEngine.CLAUDE,
        anthropicKey = "sk-ant-api03-test",
        claudeModel = "claude-opus-5-5",
        vocabulary = listOf("Shmookh"),
    )

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                requests += request to body
                val path = request.path.orEmpty()
                return when {
                    path.contains(":generateContent") -> gemini(body, path)
                    path.contains("/audio/transcriptions") ->
                        MockResponse().setBody("""{"text":"yaar mujhe kal report bhejni hai, so please remind me"}""")
                    path.startsWith("/v1/messages") -> claude()
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @AfterTest
    fun stop() = server.shutdown()

    private fun gemini(body: String, path: String): MockResponse {
        if (geminiOutOfQuota.any { path.contains("/$it:") }) {
            return MockResponse().setResponseCode(429).setBody("""{"error":{"code":429,"status":"RESOURCE_EXHAUSTED","message":"Quota exceeded"}}""")
        }
        if (geminiStatus != 200) {
            return MockResponse().setResponseCode(geminiStatus).setBody(
                """{"error":{"code":400,"message":"API key not valid.","status":"INVALID_ARGUMENT","details":[{"reason":"API_KEY_INVALID"}]}}""",
            )
        }
        val system = json.readTree(body)["systemInstruction"]["parts"][0]["text"].asText()
        val reply = if (system.startsWith("Transcribe")) geminiTranscript else "Yaar, mujhe kal report bhejni hai, so please remind me."
        val parts = listOf(mapOf("text" to "thinking", "thought" to true), mapOf("text" to reply))
        return MockResponse().setBody(json.writeValueAsString(mapOf("candidates" to listOf(mapOf("content" to mapOf("parts" to parts))))))
    }

    private fun claude(): MockResponse {
        if (claudeStatus != 200) {
            return MockResponse().setResponseCode(claudeStatus)
                .setBody("""{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""")
        }
        val content = if (claudeStopReason == "refusal") "[]" else """[{"type":"text","text":"Yaar, mujhe kal report bhejni hai, so please remind me."}]"""
        return MockResponse().setHeader("content-type", "application/json").setBody(
            """{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5","content":$content,"stop_reason":"$claudeStopReason","stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":5}}""",
        )
    }

    private fun dictation() = Dictation(
        baseUrls = mapOf(
            SttProvider.GEMINI to server.url("/gemini").toString(),
            SttProvider.OPENAI to server.url("/openai").toString(),
            SttProvider.GROQ to server.url("/groq").toString(),
        ),
        anthropicBaseUrl = server.url("/").toString().trimEnd('/'),
    )

    private fun bodyOf(index: Int): JsonNode = json.readTree(requests[index].second)

    @Test
    fun `gemini transcribes and claude cleans up in roman urdu`() {
        val stages = mutableListOf<Dictation.Stage>()
        val result = dictation().run(wav, settings) { stages += it }

        assertEquals("Yaar, mujhe kal report bhejni hai, so please remind me.", result.text)
        assertEquals(geminiTranscript, result.raw)
        assertNull(result.note)
        assertEquals(listOf(Dictation.Stage.TRANSCRIBING, Dictation.Stage.POLISHING), stages)

        val (geminiRequest, _) = requests[0]
        assertEquals("/gemini/gemini-3.6-flash:generateContent", geminiRequest.path)
        assertEquals("AIza-test", geminiRequest.getHeader("x-goog-api-key"))
        val gemini = bodyOf(0)
        assertTrue(gemini["systemInstruction"]["parts"][0]["text"].asText().contains("Roman Urdu"))
        assertEquals("low", gemini["generationConfig"]["thinkingConfig"]["thinkingLevel"].asText())
        val audio = gemini["contents"][0]["parts"][0]["inlineData"]
        assertEquals("audio/wav", audio["mimeType"].asText())
        assertEquals("RIFF", String(java.util.Base64.getDecoder().decode(audio["data"].asText()), 0, 4))

        val (claudeRequest, _) = requests[1]
        // The SDK sends the key as x-api-key or, in newer versions, as a bearer token.
        val credential = claudeRequest.getHeader("x-api-key") ?: claudeRequest.getHeader("Authorization")?.removePrefix("Bearer ")
        assertEquals("sk-ant-api03-test", credential)
        assertContains(claudeRequest.getHeader("anthropic-beta").orEmpty(), "server-side-fallback-2026-07-01")
        val claude = bodyOf(1)
        assertEquals("claude-opus-5-5", claude["model"].asText())
        assertEquals("default", claude["fallbacks"].asText())
        assertEquals("low", claude["output_config"]["effort"].asText())
        assertEquals(Prompts.load().cleanupPrompt("roman", "clean", listOf("Shmookh")), claude["system"].asText())
        assertEquals(Prompts.transcriptMessage(geminiTranscript), claude["messages"][0]["content"].asText())
    }

    @Test
    fun `gemini does both steps in one call`() {
        val result = dictation().run(wav, settings.copy(cleanup = CleanupEngine.GEMINI))

        assertEquals(1, requests.size)
        assertTrue(bodyOf(0)["systemInstruction"]["parts"][0]["text"].asText().contains("voice recording itself"))
        assertEquals("Yaar, mujhe kal report bhejni hai, so please remind me.", result.text)
    }

    @Test
    fun `openai gets a roman urdu primer and haiku skips the fallback beta`() {
        val result = dictation().run(
            wav,
            settings.copy(provider = SttProvider.OPENAI, sttKey = "sk-openai", sttModel = "gpt-4o-transcribe", claudeModel = "claude-haiku-5-5"),
        )

        val (sttRequest, form) = requests[0]
        assertEquals("/openai/audio/transcriptions", sttRequest.path)
        assertEquals("Bearer sk-openai", sttRequest.getHeader("Authorization"))
        assertContains(form, "filename=\"dictation.wav\"")
        assertContains(form, "theek hai?")
        assertContains(form, "Names: Shmookh.")

        val (claudeRequest, _) = requests[1]
        assertFalse(claudeRequest.getHeader("anthropic-beta").orEmpty().contains("server-side-fallback"))
        assertFalse(bodyOf(1).has("fallbacks"))
        assertNull(result.note)
    }

    @Test
    fun `a rejected anthropic key falls back to the raw transcript`() {
        claudeStatus = 401
        val result = dictation().run(wav, settings)

        assertEquals(geminiTranscript, result.text)
        assertEquals("Couldn't polish (Anthropic rejected the API key), so this is the raw transcript.", result.note)
    }

    @Test
    fun `a claude refusal keeps the raw transcript`() {
        claudeStopReason = "refusal"
        val result = dictation().run(wav, settings)

        assertEquals(geminiTranscript, result.text)
        assertEquals("Claude sent back nothing, so this is the raw transcript.", result.note)
    }

    @Test
    fun `a rejected gemini key is explained`() {
        geminiStatus = 400
        val error = assertFailsWith<DictationException> { dictation().run(wav, settings) }
        assertEquals("Gemini rejected the API key. Check it in Murmur's settings.", error.message)
    }

    @Test
    fun `silence is reported, not pasted`() {
        geminiTranscript = ""
        val error = assertFailsWith<DictationException> { dictation().run(wav, settings) }
        assertEquals(Dictation.NO_SPEECH, error.message)
    }

    @Test
    fun `raw style and missing cleanup keys skip cleanup`() {
        assertEquals(geminiTranscript, dictation().run(wav, settings.copy(style = "raw")).text)
        assertEquals(geminiTranscript, dictation().run(wav, settings.copy(anthropicKey = "")).text)
        assertEquals(2, requests.size) // two transcriptions, no cleanup calls
    }

    @Test
    fun `when the free quota runs out it falls back to flash-lite`() {
        geminiOutOfQuota = setOf("gemini-3.6-flash")
        val result = dictation().run(wav, settings.copy(cleanup = CleanupEngine.GEMINI))

        assertEquals(
            listOf("/gemini/gemini-3.6-flash:generateContent", "/gemini/gemini-flash-lite-latest:generateContent"),
            requests.map { it.first.path },
        )
        assertFalse(bodyOf(1).has("generationConfig")) // the alias isn't a gemini-3 id
        assertEquals("Yaar, mujhe kal report bhejni hai, so please remind me.", result.text)
    }

    @Test
    fun `when every free quota is used up it says so`() {
        geminiOutOfQuota = setOf("gemini-3.6-flash", "gemini-flash-lite-latest")
        val error = assertFailsWith<DictationException> { dictation().run(wav, settings.copy(cleanup = CleanupEngine.GEMINI)) }
        assertEquals("You've used Gemini's free requests for now. Try again in a little while.", error.message)
    }

    @Test
    fun `gemini is the free default for cleanup`() {
        assertEquals(CleanupEngine.GEMINI, CleanupEngine.of(null))
        assertEquals(CleanupEngine.GEMINI, CleanupEngine.of("unknown"))
    }
}

