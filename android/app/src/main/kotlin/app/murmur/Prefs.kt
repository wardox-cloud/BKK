package app.murmur

import android.content.Context
import android.content.SharedPreferences
import app.murmur.core.CleanupEngine
import app.murmur.core.DictationSettings
import app.murmur.core.SttProvider

/** Settings, stored privately on this phone. */
class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("murmur", Context.MODE_PRIVATE)

    val provider get() = SttProvider.of(prefs.getString("provider", null))
    val cleanup get() = CleanupEngine.of(prefs.getString("cleanup", null))
    val anthropicKey get() = prefs.getString("anthropic_key", "").orEmpty()
    val claudeModel get() = prefs.getString("claude_model", null) ?: "claude-opus-5-5"
    val language get() = prefs.getString("language", null) ?: "roman"
    val vocabulary get() = prefs.getString("vocabulary", "").orEmpty()
    val switchBack get() = prefs.getBoolean("switch_back", false)

    var style: String
        get() = prefs.getString("style", null) ?: "clean"
        set(value) = edit { putString("style", value) }

    fun key(provider: SttProvider) = prefs.getString("key_${provider.id}", "").orEmpty()

    fun model(provider: SttProvider) =
        prefs.getString("model_${provider.id}", null)?.takeIf { it.isNotBlank() } ?: provider.defaultModel

    fun edit(block: SharedPreferences.Editor.() -> Unit) = prefs.edit().apply(block).apply()

    fun dictationSettings() = DictationSettings(
        provider = provider,
        sttKey = key(provider),
        sttModel = model(provider),
        geminiKey = key(SttProvider.GEMINI),
        geminiModel = model(SttProvider.GEMINI),
        cleanup = cleanup,
        anthropicKey = anthropicKey,
        claudeModel = claudeModel,
        language = language,
        style = style,
        vocabulary = vocabulary.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() },
    )
}
