package app.murmur.core

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeminiQuotaTest {
    private var clock = Instant.parse("2026-10-25T08:00:00Z") // 1 am in California, 1 pm in Pakistan
    private val storage = GeminiQuota.Storage.InMemory()
    private val quota = GeminiQuota(storage) { clock }
    private val json = ObjectMapper()

    @Test
    fun `ranks flash models best first and leaves out the rest`() {
        val names = listOf(
            "gemini-3.6-flash", "gemini-3.5-flash-lite", "gemini-2.5-flash", "gemini-3.8-flash", "gemini-3.6-pro",
            "gemini-3.6-flash-preview-09-2026", "gemini-flash-latest", "gemini-3.1-flash-lite", "gemini-3.10-flash",
            "gemini-embedding-001",
        )
        assertEquals(
            listOf("gemini-3.10-flash", "gemini-3.8-flash", "gemini-3.6-flash", "gemini-2.5-flash", "gemini-3.5-flash-lite", "gemini-3.1-flash-lite"),
            GeminiQuota.rank(names),
        )
    }

    @Test
    fun `daily allowances come back at midnight in California`() {
        assertEquals(Instant.parse("2026-10-26T07:00:00Z"), quota.nextReset())
        assertContains(quota.nextResetLocal(ZoneId.of("Asia/Karachi")), "12:00")
        // After the clocks change in November, it's 8 am UTC.
        clock = Instant.parse("2026-11-10T12:00:00Z")
        assertEquals(Instant.parse("2026-11-11T08:00:00Z"), quota.nextReset())
    }

    @Test
    fun `a used-up daily allowance rests the model until the reset`() {
        val rest = quota.rest("gemini-3.6-flash", reply("GenerateRequestsPerDayPerProjectPerModel-FreeTier", "20", "30s"))
        assertTrue(rest.daily)
        assertEquals(20, rest.perDay)
        assertEquals(quota.nextReset(), quota.resting("gemini-3.6-flash")!!.until)

        clock = quota.nextReset().plusSeconds(1)
        assertNull(quota.resting("gemini-3.6-flash"))
    }

    @Test
    fun `a per-minute limit rests the model for the delay Gemini gives`() {
        quota.rest("gemini-3.6-flash", reply("GenerateRequestsPerMinutePerProjectPerModel-FreeTier", "10", "7.5s"))
        assertEquals(clock.plusMillis(7_500), quota.resting("gemini-3.6-flash")!!.until)
        // With nothing to go on, a minute.
        quota.rest("gemini-3.5-flash", null)
        assertEquals(clock.plusSeconds(60), quota.resting("gemini-3.5-flash")!!.until)
    }

    @Test
    fun `what one part of Murmur learns, the others see`() {
        quota.rest("gemini-3.6-flash", reply("GenerateRequestsPerDayPerProjectPerModel-FreeTier", "20", "1s"))
        quota.rememberModels(listOf("gemini-3.6-flash", "gemini-3.5-flash"))
        val other = GeminiQuota(storage) { clock }
        assertEquals(listOf("gemini-3.6-flash"), other.rests().map { it.model })
        assertEquals(listOf("gemini-3.6-flash", "gemini-3.5-flash"), other.models())
    }

    @Test
    fun `the model list is looked up again the next day`() {
        quota.rememberModels(listOf("gemini-3.6-flash"))
        clock = quota.nextReset().plusSeconds(1)
        assertNull(quota.models())
    }

    @Test
    fun `bad saved data is ignored`() {
        storage.write("not json")
        assertEquals(emptyList(), quota.rests())
        assertNull(quota.models())
    }

    private fun reply(quotaId: String, value: String, retry: String) = json.readTree(
        """{"error":{"code":429,"details":[{"violations":[{"quotaId":"$quotaId","quotaValue":"$value"}]},{"retryDelay":"$retry"}]}}""",
    )
}
