package kz.epharm.posm

import io.mockk.*
import java.time.Instant
import kz.epharm.posm.entity.PosSaleEntity
import kz.epharm.posm.entity.RecommendationEventEntity
import kz.epharm.posm.entity.RecommendationOutcome
import kz.epharm.posm.repository.RecommendationEventRepository
import kz.epharm.posm.service.RecommendationAttributionService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RecommendationAttributionServiceTest {
    private val events = mockk<RecommendationEventRepository>()
    private val service = RecommendationAttributionService(events)
    private val at = Instant.parse("2026-11-16T08:00:00Z")
    private fun event(id: String = "event", pharmacy: String = "ph", person: String = "person") =
        RecommendationEventEntity(id = id, sessionId = "session", pharmacyId = pharmacy,
            pharmacistId = person, recommendSku = "sku", shownAt = at)
    private fun sale(person: String = "person", pharmacy: String = "ph", time: Instant = at.plusSeconds(60)) =
        PosSaleEntity(id = "sale", sessionId = "session", pharmacyId = pharmacy,
            pharmacistId = person, printedAt = time)
    private fun candidates(vararg rows: RecommendationEventEntity) {
        every { events.findBySessionIdAndSoldAtIsNull("session") } returns rows.toList()
        every { events.save(any()) } answers { firstArg() }
    }

    @Test fun `same session and SKU cannot cross pharmacy or known seller boundaries`() {
        val own = event()
        val otherPharmacy = event("other-ph", "other")
        val otherPerson = event("other-person", person = "another")
        otherPharmacy.outcome = RecommendationOutcome.accepted
        otherPerson.outcome = RecommendationOutcome.accepted
        candidates(own, otherPharmacy, otherPerson)
        service.attributeSale(sale(), setOf("sku"))
        assertEquals("sale", own.saleId)
        assertNull(otherPharmacy.saleId)
        assertNull(otherPerson.saleId)
        verify(exactly = 1) { events.save(own) }
    }

    @Test fun `blank pharmacy cannot correlate anonymous records`() {
        val row = event(pharmacy = "")
        candidates(row)
        service.attributeSale(sale(pharmacy = ""), setOf("sku"))
        assertNull(row.saleId)
        verify(exactly = 0) { events.save(any()) }
    }

    @Test fun `anonymous same pharmacy recommendation retains analytical conversion`() {
        val row = event(person = "")
        candidates(row)
        service.attributeSale(sale(), setOf("sku"))
        assertEquals("sale", row.saleId)
        assertEquals(60, row.secondsToSale)
    }

    @Test fun `negative clock skew retains conversion but not fabricated zero duration`() {
        val row = event()
        row.displayedAt = at.plusSeconds(90)
        candidates(row)
        service.attributeSale(sale(), setOf("sku"))
        assertEquals("sale", row.saleId)
        assertEquals(at.plusSeconds(60), row.soldAt)
        assertNull(row.secondsToSale)
    }

    @Test fun `real zero duration stays measurable`() {
        val row = event()
        candidates(row)
        service.attributeSale(sale(time = at), setOf("sku"))
        assertEquals(0, row.secondsToSale)
    }

    @Test fun `duration overflow cannot wrap into a plausible measurement`() {
        val row = event()
        candidates(row)
        service.attributeSale(sale(time = at.plusSeconds(Int.MAX_VALUE.toLong() + 1)), setOf("sku"))
        assertEquals("sale", row.saleId)
        assertNull(row.secondsToSale)
    }

    @Test fun `accepted event wins once among same scope duplicates`() {
        val accepted = event("accepted").also { it.outcome = RecommendationOutcome.accepted }
        val recent = event("recent").also { it.shownAt = at.plusSeconds(10) }
        candidates(accepted, recent)
        service.attributeSale(sale(), setOf("sku"))
        assertEquals("sale", accepted.saleId)
        assertNull(recent.saleId)
        verify(exactly = 1) { events.save(any()) }
    }
}
