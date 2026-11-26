package kz.epharm.posm

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.mockk.every
import io.mockk.mockk
import kz.epharm.catalog.entity.ProductEntity
import kz.epharm.catalog.repository.ProductRepository
import kz.epharm.catalog.service.AccCatalogTaxonomy
import kz.epharm.posm.dto.CartItemDto
import kz.epharm.posm.service.RulesEngineService
import kz.epharm.promo.repository.PromoRepository
import kz.epharm.rules.entity.RuleEntity
import kz.epharm.rules.entity.RuleStatus
import kz.epharm.rules.entity.RuleTrigger
import kz.epharm.rules.repository.RuleRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/**
 * The products table holds one medicine twice: a website catalogue record (prod_01K..., often with the local
 * Standard-N code 8811000...) and a Daribar record (prod_Daribar..., with the EAN). 26.11.2026: a tip recommended
 * one, the till sold the other, and the sale was not counted.
 */
class RulesEngineDuplicateProductsTest {
    private val products = mockk<ProductRepository>()
    private val rules = mockk<RuleRepository>()
    private val engine = RulesEngineService(rules, products, mockk<PromoRepository>(relaxed = true), mockk<AccCatalogTaxonomy>(relaxed = true))

    private val website = ProductEntity(id = "prod_01KSB3XH9N87V18SR5RF2MY816", name = "Тайлолфен хот 20 г № 12", barcode = "8811000123456")
    private val daribar = ProductEntity(id = "prod_DaribarZDg5MjkxYzgtMmEyNy00YWU1", name = "Тайлолфен хот 20 г № 12", barcode = "4870000000011")
    private val bigPack = ProductEntity(id = "prod_01KSBPACK24", name = "Тайлолфен хот 20 г № 24", barcode = "8811000123999")
    private val other = ProductEntity(id = "prod_01KSBOTHER", name = "Парацетамол 500 мг № 10", barcode = "8811000555000")
    private val catalog = mutableListOf(website, daribar, bigPack, other)

    @BeforeEach
    fun setUp() {
        every { products.findAllByBarcodeIn(any()) } answers {
            val keys = firstArg<Collection<String>>().toSet()
            catalog.filter { product -> product.scanBarcodes().any { it in keys } }
        }
        every { products.findAllByIpartIdIn(any()) } answers {
            val keys = firstArg<Collection<String>>().toSet()
            catalog.filter { it.ipartId in keys }
        }
        every { products.findAllByOrderByNameAsc() } answers { catalog.sortedBy { it.name } }
        every { products.findMatchProductsByIds(any()) } answers {
            val ids = firstArg<Collection<String>>().toSet()
            catalog.filter { it.id in ids }
        }
    }

    @Test
    fun `a name that only duplicates of one product carry is not ambiguous`() {
        // The code on the line is unknown to the catalogue, so only the name can tell the product.
        val line = CartItemDto(barcode = "8811000999999", name = "ТАЙЛОЛФЕН ХОТ 20 г №12")

        val resolved = engine.resolveToProductIds(listOf(line)).single()

        // One of the two records, always the same one: the smallest id of those that carry barcodes.
        assertThat(resolved).isEqualTo(website.id)
        assertThat(engine.resolveToProductIds(listOf(line))).containsExactly(website.id)
    }

    @Test
    fun `the same product sold under either record resolves in every receipt`() {
        val byEan = CartItemDto(barcode = daribar.barcode, name = "Тайлолфен хот 20 г № 12")
        val byLocalCode = CartItemDto(barcode = website.barcode, name = "Тайлолфен хот 20 г № 12")

        assertThat(engine.resolveToProductIds(listOf(byEan))).containsExactly(daribar.id)
        assertThat(engine.resolveToProductIds(listOf(byLocalCode))).containsExactly(website.id)
    }

    @Test
    fun `products with the same name beginning but another pack or dose are still different and not matched`() {
        val prefixOnly = CartItemDto(barcode = "8811000999999", name = "Тайлолфен хот 20 г")
        val otherPack = CartItemDto(barcode = "8811000999998", name = "Тайлолфен хот 20 г № 24")
        val otherDose = CartItemDto(barcode = "8811000999997", name = "Тайлолфен хот 40 г № 12")
        val shortName = CartItemDto(name = "Тайлолфен")

        assertThat(engine.resolveToProductIds(listOf(prefixOnly, otherPack, otherDose, shortName)))
            .containsExactly(null, bigPack.id, null, null)
    }

    @Test
    fun `a line is not named after a product when another line of the receipt already carries it by an exact key`() {
        // Payout checks look for the lines of a product by its id: they must see the lines the exact keys found, no more.
        val byKey = CartItemDto(barcode = website.barcode, name = "Тайлолфен хот 20 г № 12")
        val byNameOnly = CartItemDto(barcode = "8811000999999", name = "Тайлолфен хот 20 г № 12")

        assertThat(engine.resolveToProductIds(listOf(byKey, byNameOnly))).containsExactly(website.id, null)
        // The other record of the pair is the same product, so the name-only line stays as it was either way.
        val byEan = CartItemDto(barcode = daribar.barcode, name = "Тайлолфен хот 20 г № 12")
        assertThat(engine.resolveToProductIds(listOf(byNameOnly, byEan))).containsExactly(null, daribar.id)
    }

    @Test
    fun `an exact key stays final and the strong resolution never uses names`() {
        val unknownCode = CartItemDto(barcode = "8811000999999", name = "Тайлолфен хот 20 г № 12")
        val nameOnly = CartItemDto(name = "Тайлолфен хот 20 г № 12")
        // Two products share a code: no name or other key may decide it.
        catalog += ProductEntity(id = "prod_zzz", name = "Другое", barcode = "SHARED")
        catalog += ProductEntity(id = "prod_zzy", name = "Иное", barcode = "SHARED")
        val shared = CartItemDto(barcode = "SHARED", name = website.name)

        assertThat(engine.resolveStrongProductIds(listOf(unknownCode, nameOnly, shared)))
            .containsExactly(null, null, null)
        assertThat(engine.resolveToProductIds(listOf(shared))).containsExactly(null)
    }

    @Test
    fun `a unique name resolves as before`() {
        assertThat(engine.resolveToProductIds(listOf(CartItemDto(name = "парацетамол 500 мг № 10")))).containsExactly(other.id)
    }

    @Test
    fun `duplicate records are found by name and by a shared barcode with the same dose and pack`() {
        val renamed = ProductEntity(id = "prod_01KSBRENAMED", name = "Диклофенак-Акос 5% гель 100 г", barcode = "4870000001001")
        val reordered = ProductEntity(id = "prod_DaribarRENAMED", name = "Диклофенак Акос гель 5% 100 г", barcodes = listOf("4870000001001"))
        val differentDose = ProductEntity(id = "prod_01KSBDOSE", name = "Диклофенак-Акос 1% гель 100 г", barcodes = listOf("4870000001001"))
        catalog += listOf(renamed, reordered, differentDose)

        val groups = engine.canonicalProductIds(
            listOf(website.id, daribar.id, bigPack.id, other.id, renamed.id, reordered.id, differentDose.id, "unknown"),
        )

        // Same normalized name: one group, named by its smallest id.
        assertThat(groups[daribar.id]).isEqualTo(website.id)
        assertThat(groups[website.id]).isEqualTo(website.id)
        // A shared barcode with the same numbers and units: duplicates, although the names are not written alike.
        assertThat(groups[reordered.id]).isEqualTo(renamed.id)
        // A shared barcode with another dose is a data error, not a duplicate.
        assertThat(groups[differentDose.id]).isEqualTo(differentDose.id)
        // Different packs and different medicines stay apart; an unknown id stands for itself.
        assertThat(groups[bigPack.id]).isEqualTo(bigPack.id)
        assertThat(groups[other.id]).isEqualTo(other.id)
        assertThat(groups["unknown"]).isEqualTo("unknown")
    }

    @Test
    fun `a blank name is never a duplicate of another blank name`() {
        val a = ProductEntity(id = "prod_a", name = "", barcode = "1")
        val b = ProductEntity(id = "prod_b", name = " . ", barcode = "2")
        catalog += listOf(a, b)

        assertThat(engine.canonicalProductIds(listOf(a.id, b.id))).containsEntry(a.id, a.id).containsEntry(b.id, b.id)
    }

    @Test
    fun `hundreds of duplicate names do not flood the log`() {
        repeat(300) { index ->
            catalog += ProductEntity(id = "prod_01KSBDUP$index", name = "Товар $index 10 мг № 30", barcode = "881100100$index")
            catalog += ProductEntity(id = "prod_DaribarDUP$index", name = "Товар $index 10 мг № 30", barcode = "487100100$index")
        }
        val warnings = captureWarnings {
            repeat(3) { engine.resolveToProductIds(listOf(CartItemDto(barcode = "unknown", name = "Товар 7 10 мг № 30"))) }
        }

        assertThat(warnings).isEmpty()
    }

    @Test
    fun `showing a tip keeps a name carried by several products unmatched and says so in one line`() {
        // Rule triggers: three duplicated pairs. The display path stays strict, and the log gets one line per scan.
        val active = mutableListOf<RuleEntity>()
        repeat(3) { index ->
            val a = ProductEntity(id = "prod_01KSBTRIG$index", name = "Триггер $index 5 мг № 20", barcode = "881200$index")
            val b = ProductEntity(id = "prod_DaribarTRIG$index", name = "Триггер $index 5 мг № 20", barcode = "487200$index")
            val offer = ProductEntity(id = "offer$index", name = "Предложение $index 5 мг № 20", barcode = "offer$index")
            catalog += listOf(a, b, offer)
            for (trigger in listOf(a, b)) {
                active += RuleEntity(id = "rule-${trigger.id}", recommend = offer.id, trigger = RuleTrigger("product", trigger.id))
                    .also { it.status = RuleStatus.active }
            }
        }
        every { rules.findActiveMatchReferences() } answers { active.map { it.matchReference() } }
        every { rules.findMatchCandidatesByIds(any()) } answers {
            val ids = firstArg<Collection<String>>().toSet()
            active.filter { it.id in ids }
        }
        every { products.findMatchProductsByMnns(any()) } returns emptyList()
        val cart = listOf(CartItemDto(barcode = "unknown", name = "ТРИГГЕР 1 5 мг № 20"))

        val warnings = captureWarnings { assertThat(engine.match(cart).matches).isEmpty() }

        assertThat(warnings).hasSize(1)
        assertThat(warnings.single()).contains("имён с несколькими товарами 3,")
    }

    private fun captureWarnings(action: () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger(RulesEngineService::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        return try {
            action()
            appender.list.filter { it.level.isGreaterOrEqual(Level.WARN) }.map { it.formattedMessage }
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }
}
