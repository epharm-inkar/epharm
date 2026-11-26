package kz.epharm.posm

import io.mockk.*
import java.time.Instant
import kz.epharm.catalog.entity.ProductEntity
import kz.epharm.catalog.repository.ProductRepository
import kz.epharm.catalog.service.AccCatalogTaxonomy
import kz.epharm.posm.entity.PosSaleEntity
import kz.epharm.posm.entity.RecommendationEventEntity
import kz.epharm.posm.entity.RecommendationOutcome
import kz.epharm.posm.repository.RecommendationEventRepository
import kz.epharm.posm.service.RecommendationAttributionService
import kz.epharm.posm.service.RulesEngineService
import kz.epharm.promo.repository.PromoRepository
import kz.epharm.rules.repository.RuleRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RecommendationAttributionServiceTest {
    private val events = mockk<RecommendationEventRepository>()
    private val products = mockk<ProductRepository>()
    private val catalog = mutableListOf<ProductEntity>()
    private val service = RecommendationAttributionService(
        events,
        RulesEngineService(mockk<RuleRepository>(), products, mockk<PromoRepository>(relaxed = true), mockk<AccCatalogTaxonomy>(relaxed = true)),
    )
    private val at = Instant.parse("2026-11-16T08:00:00Z")

    init {
        every { products.findMatchProductsByIds(any()) } answers {
            val ids = firstArg<Collection<String>>().toSet()
            catalog.filter { it.id in ids }
        }
    }

    private fun event(id: String = "event", pharmacy: String = "ph", person: String = "person", sku: String = "sku") =
        RecommendationEventEntity(id = id, sessionId = "session", pharmacyId = pharmacy,
            pharmacistId = person, recommendSku = sku, shownAt = at)
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

    // The products table holds one medicine twice (26.11.2026): the website catalogue record with the local
    // Standard-N code and the Daribar record with the EAN. A tip recommends one, the till sells the other.
    private val website = ProductEntity(id = "prod_01KSB3XH9N87V18SR5RF2MY816", name = "Тайлолфен хот 20 г № 12", barcode = "8811000123456")
    private val daribar = ProductEntity(id = "prod_DaribarZDg5MjkxYzgtMmEyNy00YWU1", name = "Тайлолфен хот 20 г № 12", barcode = "4870000000011")
    private val biggerPack = ProductEntity(id = "prod_01KSBPACK24", name = "Тайлолфен хот 20 г № 24", barcode = "8811000123999")

    @Test fun `a tip for one record of a product is closed by the sale of its other record`() {
        catalog += listOf(website, daribar)
        val tip = event(sku = daribar.id)
        candidates(tip)
        service.attributeSale(sale(), setOf(website.id))
        assertEquals("sale", tip.saleId)
        assertEquals(at.plusSeconds(60), tip.soldAt)
        assertEquals(60, tip.secondsToSale)

        val reverse = event("reverse", sku = website.id)
        candidates(reverse)
        service.attributeSale(sale(), setOf(daribar.id))
        assertEquals("sale", reverse.saleId)
    }

    @Test fun `a tip is not closed by a different product with the same beginning of the name`() {
        catalog += listOf(website, daribar, biggerPack)
        val tip = event(sku = daribar.id)
        candidates(tip)
        service.attributeSale(sale(), setOf(biggerPack.id))
        assertNull(tip.saleId)
        assertNull(tip.soldAt)
        verify(exactly = 0) { events.save(any()) }
    }

    @Test fun `two tips for the two records of one product close only one sale line`() {
        catalog += listOf(website, daribar)
        val earlier = event("earlier", sku = website.id)
        val later = event("later", sku = daribar.id).also { it.shownAt = at.plusSeconds(10) }
        candidates(earlier, later)
        service.attributeSale(sale(), setOf(website.id))
        assertNull(earlier.saleId)
        assertEquals("sale", later.saleId)
        verify(exactly = 1) { events.save(any()) }
    }

    @Test fun `duplicates do not cross pharmacy or seller boundaries either`() {
        catalog += listOf(website, daribar)
        val foreign = event("foreign", pharmacy = "other", sku = daribar.id)
        val foreignSeller = event("seller", person = "another", sku = daribar.id)
        candidates(foreign, foreignSeller)
        service.attributeSale(sale(), setOf(website.id))
        assertNull(foreign.saleId)
        assertNull(foreignSeller.saleId)
        verify(exactly = 0) { events.save(any()) }
    }

    @Test fun `a tip for a product that is not in the catalogue is closed only under its own id`() {
        val tip = event(sku = "gone")
        candidates(tip)
        service.attributeSale(sale(), setOf(website.id))
        assertNull(tip.saleId)
        val sameId = event("same", sku = "gone")
        candidates(sameId)
        service.attributeSale(sale(), setOf(website.id, "gone"))
        assertEquals("sale", sameId.saleId)
    }

    @Test fun `the catalogue is read only when a sale has something to reconcile`() {
        candidates(event())
        service.attributeSale(sale(), setOf("sku"))
        verify(exactly = 0) { products.findMatchProductsByIds(any()) }
    }
}
