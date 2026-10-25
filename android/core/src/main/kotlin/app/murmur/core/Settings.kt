package app.murmur.core

enum class SttProvider(val id: String, val label: String, val defaultModel: String, val baseUrl: String) {
    GEMINI("gemini", "Google Gemini (best for Urdu + English)", "gemini-3.6-flash", "https://generativelanguage.googleapis.com/v1beta/models"),
    OPENAI("openai", "OpenAI", "gpt-4o-transcribe", "https://api.openai.com/v1"),
    GROQ("groq", "Groq", "whisper-large-v3-turbo", "https://api.groq.com/openai/v1"),
    ;

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: GEMINI
    }
}

enum class CleanupEngine(val id: String, val label: String) {
    GEMINI("gemini", "Gemini (free with your Gemini key)"),
    CLAUDE("claude", "Claude (needs paid Anthropic API credits)"),
    ;

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: GEMINI
    }
}

val CLAUDE_MODELS = linkedMapOf(
    "claude-opus-5-5" to "Claude Opus 5.5 (best quality)",
    "claude-sonnet-5-5" to "Claude Sonnet 5.5",
    "claude-haiku-5-5" to "Claude Haiku 5.5 (fastest, cheapest)",
)

data class DictationSettings(
    val provider: SttProvider,
    /** Key for [provider]. */
    val sttKey: String,
    val sttModel: String,
    /** Used for Gemini cleanup, whichever provider transcribes. */
    val geminiKey: String,
    val geminiModel: String,
    val cleanup: CleanupEngine,
    val anthropicKey: String,
    val claudeModel: String,
    val language: String = "roman",
    val style: String = "clean",
    val vocabulary: List<String> = emptyList(),
    /** The wording of a style you wrote yourself; null for the built-in ones. */
    val styleInstruction: String? = null,
) {
    val cleanupKey get() = if (cleanup == CleanupEngine.GEMINI) geminiKey else anthropicKey
    val oneStep get() = provider == SttProvider.GEMINI && cleanup == CleanupEngine.GEMINI && style != "raw"
}

/** An error worth showing to the person dictating, already in plain words. */
open class DictationException(message: String) : Exception(message)

/** The phone couldn't reach the service at all: no internet, most likely. */
class ConnectionException(message: String) : DictationException(message)
