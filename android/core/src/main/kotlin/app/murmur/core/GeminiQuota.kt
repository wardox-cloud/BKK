package app.murmur.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Gemini's free tier gives each model its own allowance of requests per day and
 * per minute. This remembers which models have used theirs up, until it comes
 * back, so Murmur goes straight to a model that still has some.
 *
 * Everything lives in [storage], so the keyboard, the bubble and the
 * transcription service share what they've learned.
 */
class GeminiQuota(
    private val storage: Storage = Storage.InMemory(),
    private val now: () -> Instant = Instant::now,
) {
    interface Storage {
        fun read(): String?
        fun write(value: String)

        class InMemory : Storage {
            private var value: String? = null
            override fun read() = value
            override fun write(value: String) {
                this.value = value
            }
        }
    }

    /** A model that's out of free requests until [until]; [daily] if it's the daily allowance. */
    data class Rest(val model: String, val until: Instant, val daily: Boolean, val perDay: Int?)

    private val json = ObjectMapper()

    /** When daily allowances come back: midnight in California. */
    fun nextReset(): Instant = today().plusDays(1).atStartOfDay(PACIFIC).toInstant()

    /** [nextReset] in the phone's time, such as "12:00 PM". */
    fun nextResetLocal(zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(zone).format(nextReset())

    @Synchronized
    fun resting(model: String): Rest? = rests().firstOrNull { it.model == model }

    /** Models that are out of free requests right now. */
    @Synchronized
    fun rests(): List<Rest> {
        val saved = load().path("rests")
        return saved.fieldNames().asSequence().mapNotNull { model ->
            val rest = saved.path(model)
            val until = Instant.ofEpochMilli(rest.path("until").asLong())
            if (!until.isAfter(now())) return@mapNotNull null
            Rest(model, until, rest.path("daily").asBoolean(), rest.path("perDay").takeIf { it.isInt }?.asInt())
        }.toList()
    }

    /** Reads Gemini's 429 reply: a used-up daily allowance rests the model until the reset, a per-minute one briefly. */
    @Synchronized
    fun rest(model: String, reply: JsonNode?): Rest {
        val details = reply?.path("error")?.path("details")?.toList().orEmpty()
        val daily = details.flatMap { it.path("violations").toList() }
            .firstOrNull { it.path("quotaId").asText().contains("PerDay") }
        val retry = details.firstNotNullOfOrNull { detail ->
            Regex("""([\d.]+)s""").matchEntire(detail.path("retryDelay").asText())?.groupValues?.get(1)?.toDoubleOrNull()
        }
        val rest = Rest(
            model,
            until = if (daily != null) nextReset() else now().plus(Duration.ofMillis(((retry ?: 60.0) * 1000).toLong())),
            daily = daily != null,
            perDay = daily?.path("quotaValue")?.asText()?.toIntOrNull(),
        )
        val saved = load()
        saved.withObjectProperty("rests").putObject(model).put("until", rest.until.toEpochMilli()).put("daily", rest.daily).apply {
            rest.perDay?.let { put("perDay", it) }
        }
        storage.write(json.writeValueAsString(saved))
        return rest
    }

    /** The Flash models this key can use, if they were looked up today. */
    @Synchronized
    fun models(): List<String>? {
        val saved = load()
        if (saved.path("modelsDay").asText() != today().toString()) return null
        return saved.path("models").map { it.asText() }
    }

    @Synchronized
    fun rememberModels(models: List<String>) {
        val saved = load()
        saved.put("modelsDay", today().toString())
        saved.putArray("models").apply { models.forEach { add(it) } }
        storage.write(json.writeValueAsString(saved))
    }

    private fun today(): LocalDate = now().atZone(PACIFIC).toLocalDate()

    private fun load(): ObjectNode =
        runCatching { json.readTree(storage.read()) as? ObjectNode }.getOrNull() ?: json.createObjectNode()

    companion object {
        private val PACIFIC: ZoneId = ZoneId.of("America/Los_Angeles")
        private val NAME = Regex("""gemini-(\d+)\.(\d+)-flash(-lite)?""")
        private const val MOST = 8

        /**
         * The Flash and Flash-Lite models among [names], best first: Flash before
         * Flash-Lite, newer before older. Previews and special versions are left out.
         */
        fun rank(names: List<String>): List<String> = names
            .mapNotNull { name -> NAME.matchEntire(name)?.let { name to it.groupValues } }
            .sortedWith(
                compareBy<Pair<String, List<String>>> { it.second[3].isNotEmpty() }
                    .thenByDescending { it.second[1].toInt() }
                    .thenByDescending { it.second[2].toInt() },
            )
            .map { it.first }
            .take(MOST)
    }
}
