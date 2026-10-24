package app.murmur.core

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.messages.BetaFallbacksParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams

/** Claude cleanup. [baseUrl] is only overridden by tests. */
class Claude(private val baseUrl: String? = null) {
    private var client: Pair<String, AnthropicClient>? = null

    /** The polished text, or null when Claude declined or returned nothing. */
    fun polish(raw: String, apiKey: String, model: String, system: String): String? {
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(16_000)
            // Cleanup is light work; low effort keeps the wait after you stop talking short.
            .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
            .system(system)
            .addUserMessage(Prompts.transcriptMessage(raw))
            .apply {
                // If a safety classifier declines, the API retries on Anthropic's
                // recommended fallback model in the same request. Haiku doesn't offer this.
                if (!model.startsWith("claude-haiku")) {
                    addBeta("server-side-fallback-2026-07-01")
                    fallbacks(BetaFallbacksParam.ofDefault())
                }
            }
            .build()

        val message = clientFor(apiKey).beta().messages().create(params)
        if (message.stopReason().orElse(null) == BetaStopReason.REFUSAL) return null
        return message.content()
            .mapNotNull { block -> block.text().orElse(null)?.text() }
            .joinToString("")
            .trim()
            .ifEmpty { null }
    }

    private fun clientFor(apiKey: String): AnthropicClient {
        client?.let { (key, existing) -> if (key == apiKey) return existing }
        val created = AnthropicOkHttpClient.builder()
            .apiKey(apiKey)
            .apply { baseUrl?.let { baseUrl(it) } }
            .build()
        client = apiKey to created
        return created
    }

    companion object {
        fun describe(error: Throwable): String = when (error) {
            is UnauthorizedException -> "Anthropic rejected the API key"
            is PermissionDeniedException -> "this key can't use that model"
            is RateLimitException -> "Anthropic rate limit hit, wait a moment"
            is AnthropicIoException -> "couldn't reach Anthropic"
            is AnthropicServiceException -> error.message ?: "Anthropic error ${error.statusCode()}"
            else -> error.message ?: error.toString()
        }
    }
}
