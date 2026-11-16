package kz.epharm.pharmacies.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import java.time.temporal.ChronoUnit
import kz.epharm.pharmacies.entity.PharmacyEntity
import kz.epharm.pharmacies.repository.PharmacyRepository
import kz.epharm.posm.repository.PharmacyAggRow
import kz.epharm.posm.repository.PharmacyAmountRow
import kz.epharm.posm.repository.PharmacyCountRow
import kz.epharm.posm.repository.PosSaleRepository
import kz.epharm.posm.repository.RecommendationEventRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PharmacyMetricsSchedulerTest {
    private val pharmacies = mockk<PharmacyRepository>()
    private val sales = mockk<PosSaleRepository>()
    private val recommendations = mockk<RecommendationEventRepository>()
    private val scheduler = PharmacyMetricsScheduler(pharmacies, sales, recommendations)

    @Test
    fun `refresh stores exact thirty day receipt amount and accepted recommendation totals`() {
        val now = Instant.parse("2026-11-04T09:00:00Z")
        val pharmacy = PharmacyEntity(
            id = "ph-1",
            name = "Аптека",
            chainId = "chain",
            chainName = "Сеть",
            city = "Алматы",
        ).also {
            it.receipts30d = 1
            it.gmv30d = 1
            it.rulesAccepted = 1
        }
        val count = mockk<PharmacyCountRow> {
            every { pharmacyId } returns pharmacy.id
            every { cnt } returns 2
        }
        val amount = mockk<PharmacyAmountRow> {
            every { pharmacyId } returns pharmacy.id
            every { amount } returns 12_345
        }
        val accepts = mockk<PharmacyAggRow> {
            every { pharmacyId } returns pharmacy.id
            every { impressions } returns 5
            every { accepts } returns 3
        }
        every { sales.countByPharmacySince(now.minus(30, ChronoUnit.DAYS)) } returns listOf(count)
        every { sales.amountByPharmacySince(now.minus(30, ChronoUnit.DAYS)) } returns listOf(amount)
        every { recommendations.aggregateByPharmacySince(Instant.EPOCH) } returns listOf(accepts)
        every { pharmacies.findAllIdsForMetrics() } returns listOf(pharmacy.id)
        every { pharmacies.updateMetricsIfChanged(pharmacy.id, 2, 12_345L, 3) } returns 1

        assertThat(scheduler.refreshNow(now)).isEqualTo(1)
        verify(exactly = 1) { pharmacies.updateMetricsIfChanged(pharmacy.id, 2, 12_345L, 3) }
        verify(exactly = 0) { pharmacies.findAll() }
        verify(exactly = 0) { pharmacies.save(any()) }
    }

    @Test
    fun `refresh resets absent rolling sale metrics to zero`() {
        val now = Instant.parse("2026-11-04T09:00:00Z")
        val pharmacy = PharmacyEntity(
            id = "ph-empty",
            name = "Аптека без продаж",
            chainId = "chain",
            chainName = "Сеть",
            city = "Алматы",
        ).also {
            it.receipts30d = 4
            it.gmv30d = 9_999
        }
        every { sales.countByPharmacySince(any()) } returns emptyList()
        every { sales.amountByPharmacySince(any()) } returns emptyList()
        every { recommendations.aggregateByPharmacySince(Instant.EPOCH) } returns emptyList()
        every { pharmacies.findAllIdsForMetrics() } returns listOf(pharmacy.id)
        every { pharmacies.updateMetricsIfChanged(pharmacy.id, 0, 0L, 0) } returns 1

        assertThat(scheduler.refreshNow(now)).isEqualTo(1)
        verify(exactly = 1) { pharmacies.updateMetricsIfChanged(pharmacy.id, 0, 0L, 0) }
        verify(exactly = 0) { pharmacies.save(any()) }
    }

    @Test
    fun `unchanged or deleted pharmacies do not count as changed`() {
        every { sales.countByPharmacySince(any()) } returns emptyList()
        every { sales.amountByPharmacySince(any()) } returns emptyList()
        every { recommendations.aggregateByPharmacySince(Instant.EPOCH) } returns emptyList()
        every { pharmacies.findAllIdsForMetrics() } returns listOf("unchanged", "deleted")
        every { pharmacies.updateMetricsIfChanged(any(), 0, 0L, 0) } returns 0

        assertThat(scheduler.refreshNow()).isZero()
        verify(exactly = 2) { pharmacies.updateMetricsIfChanged(any(), 0, 0L, 0) }
        verify(exactly = 0) { pharmacies.save(any()) }
    }
}
