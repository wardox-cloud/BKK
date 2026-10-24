package app.murmur.core

import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** OpenAI-compatible /audio/transcriptions (OpenAI, Groq). */
class Whisper(private val http: OkHttpClient) {
    private val json = ObjectMapper()

    fun transcribe(wav: ByteArray, baseUrl: String, label: String, apiKey: String, model: String, primer: String): String {
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "dictation.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "json")
            // Example text in the target style steers the script and spelling.
            .apply { if (primer.isNotEmpty()) addFormDataPart("prompt", primer) }
            .build()
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/audio/transcriptions")
            .header("Authorization", "Bearer $apiKey")
            .post(form)
            .build()

        val (status, text) = try {
            http.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
        } catch (e: IOException) {
            throw DictationException("Couldn't reach $label. Check your connection, then try again.")
        }
        val reply = runCatching { json.readTree(text) }.getOrNull()
        if (status == 401) throw DictationException("$label rejected the API key. Check it in Murmur's settings.")
        if (status !in 200..299) {
            val message = reply?.path("error")?.path("message")?.asText().orEmpty().ifEmpty { "$status" }
            throw DictationException("$label couldn't transcribe that: $message")
        }
        return reply?.path("text")?.asText().orEmpty().trim()
    }
}
