package kz.epharm.posm

import io.mockk.every
import io.mockk.mockk
import kz.epharm.catalog.entity.ProductEntity
import kz.epharm.catalog.repository.ProductRepository
import kz.epharm.catalog.service.AccCatalogTaxonomy
import kz.epharm.posm.dto.CartItemDto
import kz.epharm.posm.guard.MarginMode
import kz.epharm.posm.guard.ProductMarginSyncService
import kz.epharm.posm.guard.ReplacementGuard
import kz.epharm.posm.guard.ReplacementGuardSettings
import kz.epharm.posm.guard.ReplacementGuardSnapshot
import kz.epharm.posm.service.RulesEngineService
import kz.epharm.promo.repository.PromoRepository
import kz.epharm.rules.entity.RuleEntity
import kz.epharm.rules.entity.RuleStatus
import kz.epharm.rules.entity.RuleTrigger
import kz.epharm.rules.entity.RuleType
import kz.epharm.rules.repository.RuleRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/** Smart replacements: the decision itself, the median, and how the rules engine applies it. No database. */
class ReplacementGuardDecisionTest {

    private fun snapshot(
        mode: MarginMode = MarginMode.vs_promo,
        tolerance: Int = 0,
        chainPromo: Boolean = true,
        margins: Map<String, Int> = mapOf("chosen" to 900, "promoted" to 400),
        promotions: Map<String, String> = emptyMap(),
    ) = ReplacementGuardSnapshot(
        ReplacementGuardSettings(chainPromoEnabled = chainPromo, marginMode = mode, marginToleranceKzt = tolerance),
        margins.mapValues { BigDecimal(it.value) },
        promotions,
        Instant.EPOCH,
    )

    @Test fun `a product on a network promotion is never replaced`() {
        val s = snapshot(margins = emptyMap(), promotions = mapOf("4600000000017" to "superceny"))
        assertEquals("chain_promo:superceny", s.decide("chosen", listOf("4600000000017"), "promoted"))
        assertNull(snapshot(chainPromo = false, margins = emptyMap(), promotions = mapOf("4600000000017" to "superceny"))
            .decide("chosen", listOf("4600000000017"), "promoted"))
    }

    @Test fun `a product that earns the pharmacy no less than the promoted one is not replaced`() {
        assertEquals("margin", snapshot().decide("chosen", emptyList(), "promoted"))
        assertEquals("margin", snapshot(margins = mapOf("chosen" to 400, "promoted" to 400)).decide("chosen", emptyList(), "promoted"))
        assertNull(snapshot(margins = mapOf("chosen" to 399, "promoted" to 400)).decide("chosen", emptyList(), "promoted"))
    }

    @Test fun `tolerance and mode change the margin rule, unknown margins never block`() {
        // Keep the chosen one only when it earns at least 600 tenge more.
        assertNull(snapshot(tolerance = 600).decide("chosen", emptyList(), "promoted"))
        assertEquals("margin", snapshot(tolerance = 500).decide("chosen", emptyList(), "promoted"))
        assertNull(snapshot(mode = MarginMode.off).decide("chosen", emptyList(), "promoted"))
        assertNull(snapshot(margins = mapOf("chosen" to 900)).decide("chosen", emptyList(), "promoted"))
        assertNull(snapshot().decide(null, emptyList(), "promoted"))
    }

    @Test fun `margin is the median retail minus the median purchase`() {
        val parts = listOf(1000 to 700, 1100 to 760, 1050 to 720, 990 to 650).map { BigDecimal(it.first) to BigDecimal(it.second) }
        val measured = ProductMarginSyncService.measure("p", parts)!!
        assertEquals(BigDecimal("1025.00"), measured.priceKzt)
        assertEquals(BigDecimal("710.00"), measured.purchaseKzt)
        assertEquals(4, measured.samples)
        assertNull(ProductMarginSyncService.measure("p", emptyList()))
        assertEquals(BigDecimal("5.00"), ProductMarginSyncService.median(listOf(BigDecimal(9), BigDecimal(5), BigDecimal(1))))
    }

    // ── the rules engine applies the guard to replacements only ──────────────────────────────────────────────────

    private val ruleRepo = mockk<RuleRepository>()
    private val productRepo = mockk<ProductRepository>()
    private val promoRepo = mockk<PromoRepository>(relaxed = true)
    private val accCatalog = mockk<AccCatalogTaxonomy>(relaxed = true)

    private fun product(id: String) = ProductEntity(id = id, name = "Товар $id", price = 100).also { it.barcode = "460000000$id" }

    private fun rule(id: String, type: RuleType, trigger: String, recommend: String) =
        RuleEntity(id = id, recommend = recommend, bonus = 100, trigger = RuleTrigger(kind = "product", value = trigger)).also {
            it.type = type
            it.status = RuleStatus.active
        }

    private fun engine(guard: ReplacementGuard?, rules: List<RuleEntity>, all: List<ProductEntity>): RulesEngineService {
        every { ruleRepo.findActiveMatchReferences() } returns rules.map { it.matchReference() }
        every { ruleRepo.findMatchCandidatesByIds(any()) } answers {
            val ids = firstArg<Collection<String>>().toSet()
            rules.filter { it.id in ids }
        }
        every { productRepo.findAllByBarcodeIn(any()) } answers {
            val wanted = firstArg<Collection<String>>().toSet()
            all.filter { p -> p.scanBarcodes().any { it in wanted } }
        }
        every { productRepo.findMatchProductsByIds(any()) } answers {
            val ids = firstArg<Collection<String>>().toSet()
            all.filter { it.id in ids }
        }
        return RulesEngineService(ruleRepo, productRepo, promoRepo, accCatalog, guard)
    }

    @Test fun `the engine drops a blocked replacement and keeps a cross-sell of the same product`() {
        val chosen = product("101"); val promoted = product("202"); val extra = product("303")
        val rules = listOf(
            rule("replace", RuleType.substitution, chosen.id, promoted.id),
            rule("add", RuleType.crosssell, chosen.id, extra.id),
        )
        val blocking = ReplacementGuard { trigger, _, _ -> if (trigger?.id == chosen.id) "chain_promo:superceny" else null }
        val cart = listOf(CartItemDto(barcode = chosen.barcode))

        val guarded = engine(blocking, rules, listOf(chosen, promoted, extra)).match(cart)
        assertEquals(listOf("add"), guarded.matches.map { it.rule.id })

        val open = engine(null, rules, listOf(chosen, promoted, extra)).match(cart)
        assertEquals(listOf("replace", "add"), open.matches.map { it.rule.id })
    }

    @Test fun `a pilot-only campaign is offered only on a till of the pilot channel`() {
        val chosen = product("121"); val promoted = product("232")
        val promo = kz.epharm.promo.entity.PromoEntity(id = "pilot-promo").also {
            it.status = kz.epharm.promo.entity.PromoStatus.active
            it.pilotOnly = true
        }
        val pilotRule = rule("pilot", RuleType.substitution, chosen.id, promoted.id).also { it.promoId = promo.id }
        every { promoRepo.findAllById(any<Iterable<String>>()) } returns listOf(promo)
        val cart = listOf(CartItemDto(barcode = chosen.barcode))
        val engine = engine(null, listOf(pilotRule), listOf(chosen, promoted))
        assertEquals(emptyList<String>(), engine.match(cart).matches.map { it.rule.id })
        assertEquals(listOf("pilot"), engine.match(cart, pilotTill = true).matches.map { it.rule.id })
        promo.pilotOnly = false
        assertEquals(listOf("pilot"), engine.match(cart).matches.map { it.rule.id })
    }

    @Test fun `the guard sees the scanned barcode and the products of the pair`() {
        val chosen = product("111"); val promoted = product("222")
        var seen: Triple<String?, String?, String>? = null
        val recording = ReplacementGuard { trigger, barcode, recommend -> seen = Triple(trigger?.id, barcode, recommend.id); null }
        val result = engine(recording, listOf(rule("r", RuleType.substitution, chosen.id, promoted.id)), listOf(chosen, promoted))
            .match(listOf(CartItemDto(barcode = chosen.barcode)))
        assertEquals(listOf("r"), result.matches.map { it.rule.id })
        assertEquals(Triple(chosen.id, chosen.barcode, promoted.id), seen)
    }
}
