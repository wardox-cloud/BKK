package app.murmur.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64

/** Google Gemini's generateContent: transcribes from instructions, or tidies text. */
class Gemini(
    private val http: OkHttpClient,
    private val baseUrl: String,
    private val quota: GeminiQuota = GeminiQuota(),
) {
    private val json = ObjectMapper()

    fun generate(apiKey: String, model: String, system: String, parts: List<Map<String, Any>>): String {
        var status = 0
        var reply: JsonNode? = null
        var rest: GeminiQuota.Rest? = null
        for (candidate in candidates(apiKey, model)) {
            val (code, body) = call(apiKey, candidate, system, parts)
            if (code in 200..299) {
                quota.used(candidate)
                return text(body)
            }
            status = code
            reply = body
            when {
                // The free tier caps requests per model, so another model may still have some.
                code == 429 -> rest = quota.rest(candidate, body)
                // Gone, or busy: another model may answer.
                code == 404 || code >= 500 -> Unit
                else -> break
            }
        }
        throw failure(status, reply, rest)
    }

    /**
     * The chosen model, then the other Flash models that still have free
     * requests. The others are looked up (once a day) only when needed.
     */
    private fun candidates(apiKey: String, model: String) = sequence {
        val hasRequests = { name: String -> quota.resting(name) == null }
        val first = hasRequests(model)
        if (first) yield(model)
        val others = (quota.models() ?: lookUpModels(apiKey)).filter { it != model }
        for (other in others) if (hasRequests(other)) yield(other)
        // Everything looks used up, but what Murmur remembers may be out of date.
        if (!first && others.none(hasRequests)) yield(model)
    }

    /** Every Flash model this key can use, best first; just the Flash-Lite alias if the list isn't available. */
    private fun lookUpModels(apiKey: String): List<String> {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}?pageSize=1000")
            .header("x-goog-api-key", apiKey)
            .build()
        val names = try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return listOf(FREE_FALLBACK_MODEL)
                json.readTree(response.body?.string().orEmpty()).path("models")
                    .filter { model -> model.path("supportedGenerationMethods").any { it.asText() == "generateContent" } }
                    .map { it.path("name").asText().removePrefix("models/") }
            }
        } catch (e: Exception) {
            return listOf(FREE_FALLBACK_MODEL)
        }
        val ranked = GeminiQuota.rank(names).ifEmpty { listOf(FREE_FALLBACK_MODEL) }
        quota.rememberModels(ranked)
        return ranked
    }

    private fun failure(status: Int, reply: JsonNode?, rest: GeminiQuota.Rest?): DictationException {
        val keyRejected = status == 401 || status == 403 ||
            reply?.path("error")?.path("details")?.any { it.path("reason").asText() == "API_KEY_INVALID" } == true
        if (keyRejected) return DictationException("Gemini rejected the API key. Check it in Murmur's settings.")
        if (status == 429) {
            return DictationException(
                if (rest?.daily == true) {
                    "You've used today's free Gemini requests on every model Murmur can use. They come back at ${quota.nextResetLocal()}."
                } else {
                    "Gemini's free tier allows only so many requests a minute. Wait a minute, then try again."
                },
            )
        }
        if (status >= 500) return DictationException("Gemini is busy right now. Try again in a moment.")
        val message = reply?.path("error")?.path("message")?.asText().orEmpty().ifEmpty { "$status" }
        return DictationException("Gemini couldn't do that: $message")
    }

    private fun text(reply: JsonNode?): String =
        reply?.path("candidates")?.path(0)?.path("content")?.path("parts")
            ?.filter { it.has("text") && !it.path("thought").asBoolean(false) }
            ?.joinToString("") { it.path("text").asText() }
            .orEmpty()
            .trim()

    private fun call(apiKey: String, model: String, system: String, parts: List<Map<String, Any>>): Pair<Int, JsonNode?> {
        val body = buildMap {
            put("systemInstruction", mapOf("parts" to listOf(mapOf("text" to system))))
            put("contents", listOf(mapOf("role" to "user", "parts" to parts)))
            // Transcribing and tidying text need little reasoning; less is faster.
            when {
                model.startsWith("gemini-3") -> put("generationConfig", mapOf("thinkingConfig" to mapOf("thinkingLevel" to "low")))
                model.startsWith("gemini-2.5-flash") -> put("generationConfig", mapOf("thinkingConfig" to mapOf("thinkingBudget" to 0)))
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
            throw ConnectionException("Couldn't reach Gemini. Check your connection, then try again.")
        }
        return status to runCatching { json.readTree(text) }.getOrNull()
    }

    companion object {
        /** Rolling alias for the newest Flash-Lite, used when the list of models isn't available. */
        const val FREE_FALLBACK_MODEL = "gemini-flash-lite-latest"

        fun audioPart(wav: ByteArray): Map<String, Any> =
            mapOf("inlineData" to mapOf("mimeType" to "audio/wav", "data" to Base64.getEncoder().encodeToString(wav)))

        fun textPart(text: String): Map<String, Any> = mapOf("text" to text)
    }
}
