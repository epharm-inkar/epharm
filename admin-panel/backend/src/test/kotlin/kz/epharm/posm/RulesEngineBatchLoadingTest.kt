package kz.epharm.posm

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.Optional
import kz.epharm.catalog.entity.ProductEntity
import kz.epharm.catalog.repository.ProductRepository
import kz.epharm.catalog.service.AccCatalogClassification
import kz.epharm.catalog.service.AccCatalogTaxonomy
import kz.epharm.posm.dto.CartItemDto
import kz.epharm.posm.service.RulesEngineService
import kz.epharm.promo.entity.PromoEntity
import kz.epharm.promo.entity.PromoStatus
import kz.epharm.promo.repository.PromoRepository
import kz.epharm.rules.entity.RuleCard
import kz.epharm.rules.entity.RuleEntity
import kz.epharm.rules.entity.RuleStatus
import kz.epharm.rules.entity.RuleTrigger
import kz.epharm.rules.entity.RuleTriggerCondition
import kz.epharm.rules.entity.RuleType
import kz.epharm.rules.repository.RuleRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RulesEngineBatchLoadingTest {
    @Test
    fun `981 active rules use two relevant product queries while preserving exact ACC AND and legacy MNN matches`() {
        val products = mockk<ProductRepository>()
        val rules = mockk<RuleRepository>()
        val promos = mockk<PromoRepository>()
        val taxonomy = mockk<AccCatalogTaxonomy>()
        val exact = ProductEntity(id = "trigger", name = "Exact trigger", barcode = "primary", barcodes = listOf("secondary"))
        val legacyA = ProductEntity(id = "legacy-a", name = "Legacy A", barcode = "legacy-a", mnn = "MNN-A")
        val legacyB = ProductEntity(id = "legacy-b", name = "Legacy B", barcode = "legacy-b", mnn = "MNN-B")
        val catalog = mutableListOf(exact, legacyA, legacyB)
        fun rule(id: String, trigger: RuleTrigger, substitution: Boolean = false, rank: Int? = null): RuleEntity {
            val offer = ProductEntity(id = "offer-$id", name = "Offer $id", barcode = "barcode-$id")
            catalog += offer
            return RuleEntity(id = id, trigger = trigger, recommend = offer.id, bonus = if (rank == 0) 0 else 100,
                promoId = "campaign").also {
                it.status = RuleStatus.active
                it.type = if (substitution) RuleType.substitution else RuleType.crosssell
                it.card = rank?.let { value -> RuleCard(offerRank = value) }
            }
        }
        val active = mutableListOf(
            rule("exact-later", RuleTrigger("product", exact.id), substitution = true, rank = 2),
            rule("exact-first", RuleTrigger("product", exact.id), substitution = true, rank = 0),
            rule("any", RuleTrigger("product_any", listOf("missing-product", exact.id))),
            rule("subgroup", RuleTrigger("acc_subgroup", "subgroup")),
            rule("acc-mnn", RuleTrigger("acc_mnn", "mnn")),
            rule("group-and", RuleTrigger("acc_group", "group", conditions = listOf(RuleTriggerCondition("acc_mnn", "mnn")))),
            rule("wrong-and", RuleTrigger("acc_group", "group", conditions = listOf(RuleTriggerCondition("acc_mnn", "other-mnn")))),
            rule("legacy-a", RuleTrigger("mnn", "MNN-A")),
            rule("legacy-b", RuleTrigger("mnn", "MNN-B")),
        )
        repeat(972) { index ->
            val trigger = ProductEntity(id = "unrelated-$index", name = "Unrelated $index", barcode = "unrelated-$index")
            catalog += trigger
            active += rule("unrelated-$index", RuleTrigger("product", trigger.id))
        }
        assertThat(active).hasSize(981)
        every { rules.findAllByStatusRawOrderByUpdatedAtDesc("active") } returns active
        every { promos.findAllById(any<Iterable<String>>()) } returns listOf(PromoEntity(id = "campaign").also { it.status = PromoStatus.active })
        every { products.findAllById(any<Iterable<String>>()) } answers {
            val ids = firstArg<Iterable<String>>().toSet()
            // Deliberately unlike requested order: batching must not alter old candidate ordering.
            catalog.filter { it.id in ids }.reversed()
        }
        every { products.findAllByMnnInOrderByNameAsc(any()) } answers {
            val mnns = firstArg<Collection<String>>().toSet()
            catalog.filter { it.mnn in mnns }.sortedBy { it.name }
        }
        // These stubs let the old implementation reach the call-count assertion.
        every { products.findById(any()) } answers { Optional.ofNullable(catalog.firstOrNull { it.id == firstArg<String>() }) }
        every { products.findAllByMnnOrderByNameAsc(any()) } answers { catalog.filter { it.mnn == firstArg<String>() }.sortedBy { it.name } }
        every { products.findAllByBarcodeIn(any()) } answers {
            val barcodes = firstArg<Collection<String>>().toSet()
            catalog.filter { product -> product.scanBarcodes().any { it in barcodes } }
        }
        every { taxonomy.unambiguousBarcodes(any()) } returns mapOf(
            "secondary" to AccCatalogClassification("ware", "secondary", "group", "subgroup", "mnn"),
        )
        val result = RulesEngineService(rules, products, promos, taxonomy).match(
            listOf(CartItemDto(barcode = "secondary"), CartItemDto(barcode = "legacy-a"), CartItemDto(barcode = "legacy-b")),
            scannedBarcode = "secondary",
        )
        assertThat(result.matches.map { it.rule.id }).containsExactly(
            "exact-first", "exact-later", "any", "subgroup", "acc-mnn", "group-and", "legacy-a", "legacy-b",
        )
        assertThat(result.matches.take(6).map { it.triggerBarcode }).containsOnly("secondary")
        assertThat(result.conflicts).isEmpty()
        verify(exactly = 1) { products.findAllById(any<Iterable<String>>()) }
        verify(exactly = 1) { products.findAllByMnnInOrderByNameAsc(setOf("MNN-A", "MNN-B")) }
        verify(exactly = 0) { products.findById(any()) }
        verify(exactly = 0) { products.findAllByMnnOrderByNameAsc(any()) }
        verify(exactly = 0) { products.findAllByOrderByNameAsc() }
        verify(exactly = 1) { products.findAllByBarcodeIn(any()) }
    }
}
