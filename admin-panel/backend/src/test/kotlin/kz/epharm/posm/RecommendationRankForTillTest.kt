package kz.epharm.posm

import kz.epharm.catalog.entity.ProductEntity
import kz.epharm.posm.service.RecommendationService
import kz.epharm.posm.service.RuleMatch
import kz.epharm.rules.entity.RuleEntity
import kz.epharm.rules.entity.RuleStatus
import kz.epharm.rules.entity.RuleTrigger
import kz.epharm.rules.entity.RuleType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** One popup tab per basket product (POSM 1.0.95): the scanned product first, five offers per kind per product. */
class RecommendationRankForTillTest {

    private fun match(id: String, type: RuleType, barcode: String) = RuleMatch(
        rule = RuleEntity(id = id, recommend = "rec-$id", bonus = 100, trigger = RuleTrigger(kind = "product", value = "p-$barcode"))
            .also { it.type = type; it.status = RuleStatus.active },
        triggerSku = "p-$barcode",
        triggerName = "Товар $barcode",
        triggerProduct = null,
        triggerIpartId = null,
        triggerBarcode = barcode,
        recommend = ProductEntity(id = "rec-$id", name = "Предложение $id", price = 1000),
    )

    @Test
    fun `the scanned product comes first even when the engine ranked an earlier product higher`() {
        val earlier = (1..6).map { match("a$it", RuleType.substitution, "4600000000017") }
        val scanned = (1..2).map { match("b$it", RuleType.substitution, "4600000000024") }
        val ranked = RecommendationService.rankForTill(earlier + scanned, "4600000000024")
        assertEquals(listOf("b1", "b2", "a1", "a2", "a3", "a4", "a5"), ranked.map { it.rule.id })
    }

    @Test
    fun `each product keeps five per kind and a response carries at most fifteen per kind`() {
        val products = (1..4).flatMap { p -> (1..6).map { match("p$p-$it", RuleType.substitution, "460000000001$p") } }
        val crossSells = (1..3).map { match("c$it", RuleType.crosssell, "4600000000011") }
        val ranked = RecommendationService.rankForTill(products + crossSells, null)
        val substitutions = ranked.filter { it.rule.type == RuleType.substitution }
        assertEquals(15, substitutions.size)
        assertEquals(setOf(5), substitutions.groupBy { it.triggerBarcode }.values.map { it.size }.toSet())
        assertEquals(listOf("c1", "c2", "c3"), ranked.filter { it.rule.type == RuleType.crosssell }.map { it.rule.id })
    }

    @Test
    fun `without a scanned barcode the engine order stays`() {
        val ranked = RecommendationService.rankForTill(listOf(match("x", RuleType.substitution, "1"), match("y", RuleType.substitution, "2")), "  ")
        assertEquals(listOf("x", "y"), ranked.map { it.rule.id })
    }
}
