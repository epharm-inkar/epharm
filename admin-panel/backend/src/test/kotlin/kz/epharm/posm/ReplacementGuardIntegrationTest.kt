package kz.epharm.posm

import kz.epharm.catalog.entity.ProductEntity
import kz.epharm.catalog.repository.ProductRepository
import kz.epharm.rules.entity.RuleEntity
import kz.epharm.rules.entity.RuleStatus
import kz.epharm.rules.entity.RuleTrigger
import kz.epharm.rules.entity.RuleType
import kz.epharm.rules.repository.RuleRepository
import kz.epharm.posm.guard.ChainPromoUpload
import kz.epharm.posm.guard.MarginMode
import kz.epharm.posm.guard.MarginUpload
import kz.epharm.posm.guard.ReplacementGuardService
import kz.epharm.posm.guard.ReplacementGuardSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Smart replacements on real PostgreSQL (V115): settings, margins, network promotions and the overview. Synthetic data. */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class ReplacementGuardIntegrationTest {
    companion object {
        @Container @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine").apply { start() }
        @JvmStatic @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
        }
    }

    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var guards: ReplacementGuardService
    @Autowired private lateinit var productRepository: ProductRepository
    @Autowired private lateinit var ruleRepository: RuleRepository

    private val chosen = "rg-chosen"
    private val promoted = "rg-promoted"
    private val today: LocalDate = LocalDate.now(ZoneId.of("Asia/Almaty"))

    @BeforeEach fun setup() {
        jdbc.update("DELETE FROM rules WHERE id LIKE 'rg-%'")
        jdbc.update("DELETE FROM product_trade_margins")
        jdbc.update("DELETE FROM chain_promo_barcodes")
        jdbc.update("UPDATE replacement_guard_settings SET chain_promo_enabled = true, margin_mode = 'vs_promo', margin_tolerance_kzt = 0")
        jdbc.update("DELETE FROM products WHERE id LIKE 'rg-%'")
        listOf(chosen to "4600000000017", promoted to "4600000000024").forEach { (id, barcode) ->
            productRepository.saveAndFlush(ProductEntity(id = id, name = "Synthetic $id", price = 1000).also { it.barcode = barcode })
        }
        ruleRepository.saveAndFlush(
            RuleEntity(id = "rg-rule", recommend = promoted, bonus = 100, trigger = RuleTrigger(kind = "product", value = chosen)).also {
                it.type = RuleType.substitution
                it.status = RuleStatus.paused
            },
        )
        guards.invalidate()
    }

    @Test fun `defaults are on for network promotions and for the comparison with the promoted product`() {
        val settings = guards.settings()
        assertEquals(true, settings.chainPromoEnabled)
        assertEquals(MarginMode.vs_promo, settings.marginMode)
        assertEquals(0, settings.marginToleranceKzt)
    }

    @Test fun `a chosen product that earns more is kept, and the overview says why`() {
        guards.uploadMargins(listOf(
            MarginUpload(chosen, BigDecimal(2000), BigDecimal(1100), 5),
            MarginUpload(promoted, BigDecimal(1500), BigDecimal(1200), 5),
        ))
        val overview = guards.overview()
        val row = overview.rules.single { it.ruleId == "rg-rule" }
        assertEquals("margin", row.reason)
        assertEquals(0, BigDecimal(900).compareTo(row.triggerMarginKzt))
        assertEquals(1, overview.blockedByReason["margin"])

        guards.updateSettings(ReplacementGuardSettings(chainPromoEnabled = true, marginMode = MarginMode.vs_promo, marginToleranceKzt = 1000), "test")
        assertNull(guards.overview().rules.single { it.ruleId == "rg-rule" }.reason)
    }

    @Test fun `a promotion counts only between its dates, and a source is replaced whole`() {
        guards.replaceChainPromos("site", listOf(
            ChainPromoUpload("4600000000017", "superceny", today.minusDays(1), today.plusDays(1)),
            ChainPromoUpload("4600000000024", "ended", today.minusDays(10), today.minusDays(1)),
        ))
        assertEquals("chain_promo:superceny", guards.overview().rules.single { it.ruleId == "rg-rule" }.reason)
        assertEquals(1, guards.snapshot()!!.promotionByBarcode.size)

        guards.replaceChainPromos("site", emptyList())
        assertNull(guards.overview().rules.single { it.ruleId == "rg-rule" }.reason)
    }

    @Test fun `an upload never overwrites a margin measured in Standard-N`() {
        guards.storeMeasuredMargins(listOf(MarginUpload(chosen, BigDecimal(1000), BigDecimal(800), 40)), Instant.now())
        guards.uploadMargins(listOf(MarginUpload(chosen, BigDecimal(5000), BigDecimal(100), 1)))
        val row = jdbc.queryForMap("SELECT margin_kzt, source FROM product_trade_margins WHERE product_id = ?", chosen)
        assertEquals(0, BigDecimal(200).compareTo(row["margin_kzt"] as BigDecimal))
        assertEquals("standardn", row["source"])
    }
}
