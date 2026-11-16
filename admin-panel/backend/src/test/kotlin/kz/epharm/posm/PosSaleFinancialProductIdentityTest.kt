package kz.epharm.posm

import io.mockk.*
import java.time.Instant
import java.util.Optional
import kz.epharm.posm.dto.PosSaleItemDto
import kz.epharm.posm.dto.PosSaleRequest
import kz.epharm.posm.entity.PosSaleEntity
import kz.epharm.posm.repository.PosSaleRepository
import kz.epharm.posm.service.*
import kz.epharm.receipts.dto.LogSaleInput
import kz.epharm.receipts.service.ReconcileService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/** Storage remains diagnostic; only strong scan identity may cross into legacy financial confirmation. */
class PosSaleFinancialProductIdentityTest {
    private val repository = mockk<PosSaleRepository>()
    private val reconcile = mockk<ReconcileService>(relaxed = true)
    private val rules = mockk<RulesEngineService>()
    private val attribution = mockk<RecommendationAttributionService>(relaxed = true)
    private val bonuses = mockk<RecommendationSaleBonusService>(relaxed = true)
    private val rewards = mockk<RecommendationRewardService>(relaxed = true)
    private val service = PosSaleService(repository, reconcile, rules, attribution, "ofd-api", bonuses, rewards)
    private val saved = slot<PosSaleEntity>()
    private fun request(items: List<PosSaleItemDto>) = PosSaleRequest(
        saleId = "synthetic-sale", pharmacyId = "synthetic-pharmacy", pharmacistId = "synthetic-person",
        sessionId = "synthetic-session", sourceDocumentId = 42, captureSource = "standardn-print-log",
        fiscalId = "synthetic-fiscal", printedAt = Instant.parse("2026-11-16T10:00:00Z"),
        totalAmount = items.sumOf { it.total }, items = items,
    )
    private fun identity(source: PharmacistIdentitySource) =
        PosmPharmacistIdentity("synthetic-person", "Synthetic Person", source, "17", "Synthetic Person")
    private fun prepare(general: List<String?>, strong: List<String?>) {
        every { repository.findById("synthetic-sale") } returns Optional.empty()
        every { repository.save(capture(saved)) } answers { firstArg() }
        every { rules.resolveToProductIds(any()) } returns general
        every { rules.resolveStrongProductIds(any()) } returns strong
    }

    @ParameterizedTest
    @EnumSource(value = PharmacistIdentitySource::class, names = ["POSM_INTERNAL", "STANDARDN_EXPLICIT_MAPPING"])
    fun `name fallback unknown EAN and unverified raw SKU never confirm a legacy pending`(source: PharmacistIdentitySource) {
        val items = listOf(
            PosSaleItemDto(name = "Synthetic name-only match", qty = 1.0, price = 1000, total = 1000),
            PosSaleItemDto(sku = "17", barcode = "unknown-ean", name = "Synthetic fallback", qty = 1.0, price = 1000, total = 1000),
            PosSaleItemDto(sku = "looks-like-canonical-id", qty = 1.0, price = 1000, total = 1000),
        )
        prepare(listOf("diagnostic-product", "diagnostic-product", null), listOf(null, null, null))
        assertTrue(service.record(request(items), identity(source), "synthetic-device"))
        assertEquals(listOf("diagnostic-product", "diagnostic-product", null), saved.captured.items.map { it.productId })
        assertEquals(items.map { it.sku.orEmpty() }, saved.captured.items.map { it.sku })
        verify(exactly = 1) { attribution.attributeSale(saved.captured, setOf("diagnostic-product")) }
        verify { reconcile wasNot Called; rewards wasNot Called }
    }

    @ParameterizedTest
    @EnumSource(value = PharmacistIdentitySource::class, names = ["POSM_INTERNAL", "STANDARDN_EXPLICIT_MAPPING"])
    fun `exact local SKU and EAN keep their financial lines but weak neighbours stay analytics only`(source: PharmacistIdentitySource) {
        val items = listOf(
            PosSaleItemDto(sku = "local-17", qty = 2.0, price = 100, total = 200),
            PosSaleItemDto(barcode = "synthetic-known-ean", qty = 3.0, price = 200, total = 600),
            PosSaleItemDto(name = "Synthetic weak neighbour", qty = 1.0, price = 500, total = 500),
        )
        prepare(listOf("exact-local", "exact-ean", "diagnostic-product"), listOf("exact-local", "exact-ean", null))
        val log = slot<LogSaleInput>()
        every { reconcile.ingestLogSale(capture(log)) } returns Unit
        val request = request(items)
        assertTrue(service.record(request, identity(source), "synthetic-device"))
        assertEquals(listOf("exact-local", "exact-ean"), log.captured.items.map { it.sku })
        assertEquals(listOf(2.0, 3.0), log.captured.items.map { it.qty })
        assertEquals(listOf(100L, 200L), log.captured.items.map { it.price })
        assertEquals(listOf(200L, 600L), log.captured.items.map { it.total })
        assertEquals(request.pharmacyId, log.captured.pharmacyId)
        assertEquals(request.pharmacistId, log.captured.pharmacistId)
        assertEquals(request.fiscalId, log.captured.fiscalId)
        assertEquals(request.printedAt, log.captured.soldAt)
        assertEquals(3, saved.captured.items.size)
        every { repository.findById(request.saleId) } returns Optional.of(saved.captured)
        assertFalse(service.record(request, identity(source), "synthetic-device"))
        verify(exactly = 1) { reconcile.ingestLogSale(any()) }
        verify(exactly = 1) { rules.resolveStrongProductIds(any()) }
    }
}
