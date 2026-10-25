package app.murmur

import android.content.Context
import android.content.SharedPreferences
import app.murmur.core.CleanupEngine
import app.murmur.core.DictationSettings
import app.murmur.core.GeminiQuota
import app.murmur.core.Prompts
import app.murmur.core.SttProvider
import app.murmur.core.Styles
import org.json.JSONObject

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

    /** Show the mic bubble even when no text box is focused. */
    val bubbleAlways get() = prefs.getBoolean("bubble_always", false)

    /** Set once Android has shown it silences the bubble's mic, so the mic panel is used straight away. */
    var micPanel: Boolean
        get() = prefs.getBoolean("mic_panel", false)
        set(value) = edit { putBoolean("mic_panel", value) }

    var bubblePosition: Pair<Int, Int>
        get() = prefs.getInt("bubble_x", -1) to prefs.getInt("bubble_y", -1)
        set(value) = edit { putInt("bubble_x", value.first).putInt("bubble_y", value.second) }

    var style: String
        get() = prefs.getString("style", null) ?: "clean"
        set(value) = edit { putString("style", value) }

    /** Styles you wrote, one per line: "Name: what it should do". */
    var customStyles: String
        get() = prefs.getString("custom_styles", "").orEmpty()
        set(value) = edit { putString("custom_styles", value) }

    /** The style you picked in each app, by package name. */
    val appStyles: Map<String, String>
        get() = runCatching {
            val saved = JSONObject(prefs.getString("app_styles", "{}").orEmpty())
            saved.keys().asSequence().associateWith { saved.getString(it) }
        }.getOrDefault(emptyMap())

    fun setAppStyle(app: String?, style: String) {
        if (app == null) {
            this.style = style
            return
        }
        val saved = JSONObject(appStyles).put(app, style)
        edit { putString("app_styles", saved.toString()) }
    }

    fun clearAppStyles() = edit { remove("app_styles") }

    /** With no internet, let the phone's own recognizer write English dictation. */
    val offlineFallback get() = prefs.getBoolean("offline_fallback", true)

    fun styles(prompts: Prompts) = Styles(prompts, customStyles)

    fun key(provider: SttProvider) = prefs.getString("key_${provider.id}", "").orEmpty()

    fun model(provider: SttProvider) =
        prefs.getString("model_${provider.id}", null)?.takeIf { it.isNotBlank() } ?: provider.defaultModel

    fun edit(block: SharedPreferences.Editor.() -> Unit) = prefs.edit().apply(block).apply()

    /** Which Gemini models have used up their free requests, shared by the mic, the keyboard and recordings. */
    val geminiQuota
        get() = GeminiQuota(object : GeminiQuota.Storage {
            override fun read() = prefs.getString("gemini_quota", null)
            override fun write(value: String) = edit { putString("gemini_quota", value) }
        })

    /** Settings for dictating into [app]: its own style if you picked one there. */
    fun dictationSettings(app: String?, prompts: Prompts): DictationSettings {
        val chosen = styles(prompts).forApp(app, appStyles, style)
        return dictationSettings().copy(style = chosen.id, styleInstruction = chosen.instruction)
    }

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
