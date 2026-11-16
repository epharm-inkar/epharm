package kz.epharm.dashboard

import io.mockk.every
import io.mockk.mockk
import kz.epharm.catalog.repository.ProductRepository
import kz.epharm.dashboard.service.RecommendationAnalyticsService
import kz.epharm.pharmacies.repository.PharmacyRepository
import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.posm.entity.RecommendationEventEntity
import kz.epharm.posm.repository.RecommendationEventRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

class RecommendationAnalyticsServiceTest {
    private val events = mockk<RecommendationEventRepository>()
    private val service = RecommendationAnalyticsService(
        events, mockk<PharmacyRepository>(), mockk<PharmacistRepository>(), mockk<ProductRepository>(), 90,
    )

    private fun analytics(intervals: List<Int?>) = run {
        val sold = Instant.parse("2026-01-01T12:00:00Z")
        every { events.findByShownAtGreaterThanEqualOrderByShownAtDesc(any()) } returns
            intervals.mapIndexed { index, seconds ->
                RecommendationEventEntity(id = "event_$index", soldAt = sold, secondsToSale = seconds)
            }
        every { events.countJournalRowsSince(any()) } returns 0L
        every { events.findJournalRowsSince(any(), any(), any()) } returns emptyList()
        service.analytics(page = 0, size = 50)
    }

    @Test
    fun `even sample median averages the two middle values without integer overflow`() {
        assertEquals(20, analytics(listOf(10, 30)).medianSecondsToSale)
        assertEquals(21, analytics(listOf(10, 31)).medianSecondsToSale)
        assertEquals(Int.MAX_VALUE, analytics(listOf(Int.MAX_VALUE, Int.MAX_VALUE)).medianSecondsToSale)
    }

    @Test
    fun `odd sample median uses sorted middle and missing durations remain unknown`() {
        assertEquals(30, analytics(listOf(90, 10, 30, null)).medianSecondsToSale)
        val unknown = analytics(listOf(null, null))
        assertEquals(2, unknown.converted)
        assertNull(unknown.medianSecondsToSale)
        assertNull(unknown.avgSecondsToSale)
        assertEquals(0, unknown.convertedUnder2m)
    }

    @Test
    fun `negative legacy durations do not inflate fast sales or corrupt time statistics`() {
        val result = analytics(listOf(-30, 30, 120, 121, null))
        assertEquals(5, result.converted)
        assertEquals(2, result.convertedUnder2m)
        assertEquals(90, result.avgSecondsToSale)
        assertEquals(120, result.medianSecondsToSale)
        assertEquals(listOf(0, 2, 1, 0), result.buckets.map { it.count })
    }
}
