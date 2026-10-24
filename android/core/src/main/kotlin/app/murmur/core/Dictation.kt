package app.murmur.core

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * One dictation, start to finish: recording in, ready-to-paste text out.
 * Mirrors processAudio and polishText in the web app's src/main.js.
 */
class Dictation(
    private val prompts: Prompts = Prompts.load(),
    http: OkHttpClient = OkHttpClient.Builder().callTimeout(90, TimeUnit.SECONDS).build(),
    /** Base URLs by provider; tests point these at a local server. */
    private val baseUrls: Map<SttProvider, String> = SttProvider.entries.associateWith { it.baseUrl },
    anthropicBaseUrl: String? = null,
) {
    enum class Stage { TRANSCRIBING, POLISHING, TRANSCRIBING_AND_POLISHING }

    data class Result(val text: String, val raw: String, val note: String? = null)

    private val gemini = Gemini(http, baseUrls.getValue(SttProvider.GEMINI))
    private val whisper = Whisper(http)
    private val claude = Claude(anthropicBaseUrl)

    fun run(wav: ByteArray, settings: DictationSettings, onStage: (Stage) -> Unit = {}): Result {
        if (settings.oneStep) {
            // Gemini listens and cleans up in a single call: faster and cheaper.
            onStage(Stage.TRANSCRIBING_AND_POLISHING)
            val text = gemini.generate(
                settings.sttKey,
                settings.sttModel,
                prompts.cleanupPrompt(settings.language, settings.style, settings.vocabulary, fromAudio = true),
                listOf(Gemini.audioPart(wav), Gemini.textPart("Here is the recording.")),
            )
            if (text.isEmpty()) throw DictationException(NO_SPEECH)
            return Result(text, raw = text)
        }

        onStage(Stage.TRANSCRIBING)
        val raw = transcribe(wav, settings)
        if (raw.isEmpty()) throw DictationException(NO_SPEECH)
        return polish(raw, settings, onStage)
    }

    /** Cleans up [raw] in the settings' style; also used to redo a dictation in another style. */
    fun polish(raw: String, settings: DictationSettings, onStage: (Stage) -> Unit = {}): Result {
        if (settings.style == "raw" || settings.cleanupKey.isBlank()) return Result(raw, raw)
        onStage(Stage.POLISHING)
        val system = prompts.cleanupPrompt(settings.language, settings.style, settings.vocabulary)
        val usesGemini = settings.cleanup == CleanupEngine.GEMINI
        return try {
            val text = if (usesGemini) {
                gemini.generate(settings.geminiKey, settings.geminiModel, system, listOf(Gemini.textPart(Prompts.transcriptMessage(raw))))
                    .ifEmpty { null }
            } else {
                claude.polish(raw, settings.anthropicKey, settings.claudeModel, system)
            }
            val engine = if (usesGemini) "Gemini" else "Claude"
            if (text != null) Result(text, raw) else Result(raw, raw, "$engine sent back nothing, so this is the raw transcript.")
        } catch (e: Exception) {
            val reason = if (e is DictationException) e.message.orEmpty().removeSuffix(".") else Claude.describe(e)
            Result(raw, raw, "Couldn't polish ($reason), so this is the raw transcript.")
        }
    }

    private fun transcribe(wav: ByteArray, settings: DictationSettings): String =
        if (settings.provider == SttProvider.GEMINI) {
            gemini.generate(
                settings.sttKey,
                settings.sttModel,
                prompts.transcriptionInstruction(settings.language, settings.vocabulary),
                listOf(Gemini.audioPart(wav), Gemini.textPart("Transcribe this recording.")),
            )
        } else {
            whisper.transcribe(
                wav,
                baseUrls.getValue(settings.provider),
                settings.provider.label,
                settings.sttKey,
                settings.sttModel,
                prompts.transcriptionPrimer(settings.language, settings.vocabulary),
            )
        }

    companion object {
        const val NO_SPEECH = "Didn't catch any speech. Try again a little closer to the mic."
    }
}
