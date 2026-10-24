package app.murmur.core

import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64

/** Google Gemini's generateContent: transcribes from instructions, or tidies text. */
class Gemini(private val http: OkHttpClient, private val baseUrl: String) {
    private val json = ObjectMapper()

    fun generate(apiKey: String, model: String, system: String, parts: List<Map<String, Any>>): String {
        val body = buildMap {
            put("systemInstruction", mapOf("parts" to listOf(mapOf("text" to system))))
            put("contents", listOf(mapOf("role" to "user", "parts" to parts)))
            // Transcribing and tidying text need little reasoning; less is faster.
            if (model.startsWith("gemini-3")) {
                put("generationConfig", mapOf("thinkingConfig" to mapOf("thinkingLevel" to "low")))
            }
        }
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(json.writeValueAsBytes(body).toRequestBody("application/json".toMediaType()))
            .build()

        val (status, text) = try {
            http.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
        } catch (e: IOException) {
            throw DictationException("Couldn't reach Gemini. Check your connection, then try again.")
        }
        val reply = runCatching { json.readTree(text) }.getOrNull()
        if (status !in 200..299) {
            val keyRejected = status == 401 || status == 403 ||
                reply?.path("error")?.path("details")?.any { it.path("reason").asText() == "API_KEY_INVALID" } == true
            if (keyRejected) throw DictationException("Gemini rejected the API key. Check it in Murmur's settings.")
            val message = reply?.path("error")?.path("message")?.asText().orEmpty().ifEmpty { "$status" }
            throw DictationException("Gemini couldn't do that: $message")
        }
        return reply?.path("candidates")?.path(0)?.path("content")?.path("parts")
            ?.filter { it.has("text") && !it.path("thought").asBoolean(false) }
            ?.joinToString("") { it.path("text").asText() }
            .orEmpty()
            .trim()
    }

    companion object {
        fun audioPart(wav: ByteArray): Map<String, Any> =
            mapOf("inlineData" to mapOf("mimeType" to "audio/wav", "data" to Base64.getEncoder().encodeToString(wav)))

        fun textPart(text: String): Map<String, Any> = mapOf("text" to text)
    }
}
