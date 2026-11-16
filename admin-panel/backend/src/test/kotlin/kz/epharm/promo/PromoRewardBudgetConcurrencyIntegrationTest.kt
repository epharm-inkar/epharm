package kz.epharm.promo

import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kz.epharm.posm.entity.PosSaleEntity
import kz.epharm.posm.entity.PosSaleItem
import kz.epharm.posm.repository.PosSaleRepository
import kz.epharm.promo.dto.UpdatePromoRequest
import kz.epharm.promo.entity.PromoEntity
import kz.epharm.promo.repository.PromoRepository
import kz.epharm.promo.service.PromoService
import kz.epharm.receipts.entity.PendingBonusEntity
import kz.epharm.receipts.repository.PendingBonusRepository
import kz.epharm.receipts.service.ReconcileService
import kz.epharm.shared.error.AppException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class PromoRewardBudgetConcurrencyIntegrationTest {
    companion object {
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("promo_reward_budget_test").withUsername("epharm").withPassword("test-only").apply { start() }
        private val sequence = AtomicLong(880_000_000_000)
        @JvmStatic @DynamicPropertySource fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
        }
    }
    @Autowired lateinit var promos: PromoService
    @Autowired lateinit var promoRepository: PromoRepository
    @Autowired lateinit var reconcile: ReconcileService
    @Autowired lateinit var pending: PendingBonusRepository
    @Autowired lateinit var sales: PosSaleRepository
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var transactions: PlatformTransactionManager

    @Test fun `a stale campaign title patch preserves a concurrent real reward budget reservation`() {
        val f = fixture()
        val loaded = CountDownLatch(1)
        val credited = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val patch = pool.submit(Callable {
                TransactionTemplate(transactions).executeWithoutResult {
                    assertThat(promoRepository.findById(f.promo).orElseThrow().spent).isZero()
                    loaded.countDown()
                    check(credited.await(10, TimeUnit.SECONDS))
                    promos.update(f.promo, UpdatePromoRequest(title = "Changed title"))
                }
            })
            val credit = pool.submit(Callable {
                check(loaded.await(10, TimeUnit.SECONDS))
                reconcile.ingestVerifiedRecommendationReward(f.pending, f.sale, 1200, true)
                credited.countDown()
            })
            credit.get(20, TimeUnit.SECONDS)
            patch.get(20, TimeUnit.SECONDS)
        } finally { pool.shutdownNow() }
        assertThat(promoRepository.findById(f.promo).orElseThrow().title).isEqualTo("Changed title")
        assertThat(spent(f)).isEqualTo(600)
        assertThat(budget(f)).isEqualTo(1000)
        assertThat(balance(f)).isEqualTo(600)
    }

    @Test fun `a stale budget snapshot cannot lower the current budget below a concurrent credit`() {
        val f = fixture()
        val loaded = CountDownLatch(1)
        val credited = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val patch = pool.submit(Callable {
                runCatching {
                    TransactionTemplate(transactions).executeWithoutResult {
                        assertThat(promoRepository.findById(f.promo).orElseThrow().spent).isZero()
                        loaded.countDown()
                        check(credited.await(10, TimeUnit.SECONDS))
                        promos.update(f.promo, UpdatePromoRequest(title = "Must roll back", budget = 500))
                    }
                }
            })
            val credit = pool.submit(Callable {
                check(loaded.await(10, TimeUnit.SECONDS))
                reconcile.ingestVerifiedRecommendationReward(f.pending, f.sale, 1200, true)
                credited.countDown()
            })
            credit.get(20, TimeUnit.SECONDS)
            val failure = patch.get(20, TimeUnit.SECONDS).exceptionOrNull()
            assertThat(failure).isInstanceOf(AppException::class.java)
            assertThat((failure as AppException).status.value()).isEqualTo(409)
        } finally { pool.shutdownNow() }
        assertThat(promoRepository.findById(f.promo).orElseThrow().title).isEqualTo("TEST")
        assertThat(budget(f)).isEqualTo(1000)
        assertThat(spent(f)).isEqualTo(600)
        assertThat(balance(f)).isEqualTo(600)
    }

    @Test fun `parallel budget reductions and credits always commit a budget that covers actual spending`() {
        repeat(6) {
            val f = fixture()
            val gate = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val patch = pool.submit(Callable {
                    check(gate.await(10, TimeUnit.SECONDS))
                    runCatching { promos.update(f.promo, UpdatePromoRequest(budget = 400)) }
                })
                val credit = pool.submit(Callable {
                    check(gate.await(10, TimeUnit.SECONDS))
                    reconcile.ingestVerifiedRecommendationReward(f.pending, f.sale, 1200, true)
                })
                gate.countDown()
                credit.get(20, TimeUnit.SECONDS)
                val failure = patch.get(20, TimeUnit.SECONDS).exceptionOrNull()
                if (failure != null) assertThat(failure).isInstanceOf(AppException::class.java)
            } finally { pool.shutdownNow() }
            assertThat(spent(f)).isLessThanOrEqualTo(budget(f))
            assertThat(spent(f)).isEqualTo(balance(f))
            assertThat(balance(f)).isIn(0L, 600L)
            assertThat(budget(f)).isIn(400L, 1000L)
        }
    }

    @Test fun `budget equal to spend or increased budget is accepted without changing credited money`() {
        val f = fixture()
        reconcile.ingestVerifiedRecommendationReward(f.pending, f.sale, 1200, true)
        assertThatThrownBy { promos.update(f.promo, UpdatePromoRequest(budget = 599)) }.isInstanceOf(AppException::class.java)
        promos.update(f.promo, UpdatePromoRequest(budget = 600))
        assertThat(budget(f)).isEqualTo(600)
        promos.update(f.promo, UpdatePromoRequest(budget = 1200))
        assertThat(budget(f)).isEqualTo(1200)
        assertThat(spent(f)).isEqualTo(600)
        assertThat(balance(f)).isEqualTo(600)
        assertThat(number("SELECT count(*) FROM pharmacist_wallet_entries WHERE source_id=?", f.pending)).isEqualTo(1)
    }

    @Test fun `explicit unlimited mode permits concurrent credits past zero while preserving replay and spending totals`() {
        val first = fixture()
        val second = fixture(first.promo)
        promos.update(first.promo, UpdatePromoRequest(budget = 0, budgetUnlimited = true))
        val gate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = listOf(first, second).map { f -> pool.submit(Callable {
                check(gate.await(10, TimeUnit.SECONDS))
                reconcile.ingestVerifiedRecommendationReward(f.pending, f.sale, 1200, true)
            }) }
            gate.countDown()
            futures.forEach { it.get(20, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
        assertThat(spent(first)).isEqualTo(1200)
        assertThat(budget(first)).isZero()
        assertThat(balance(first)).isEqualTo(600)
        assertThat(balance(second)).isEqualTo(600)
        listOf(first, second).forEach { f ->
            reconcile.ingestVerifiedRecommendationReward(f.pending, f.sale, 1200, true)
            assertThat(number("SELECT count(*) FROM pharmacist_wallet_entries WHERE source_id=?", f.pending)).isEqualTo(1)
        }
        assertThat(spent(first)).isEqualTo(1200)
    }

    @Test fun `unlimited to limited transition checks new committed spend despite stale preloaded campaign`() {
        val f = fixture()
        promos.update(f.promo, UpdatePromoRequest(budget = 0, budgetUnlimited = true))
        val loaded = CountDownLatch(1)
        val credited = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val change = pool.submit(Callable {
                runCatching {
                    TransactionTemplate(transactions).executeWithoutResult {
                        val stale = promoRepository.findById(f.promo).orElseThrow()
                        assertThat(stale.spent).isZero()
                        assertThat(stale.budgetUnlimited).isTrue()
                        loaded.countDown()
                        check(credited.await(10, TimeUnit.SECONDS))
                        promos.update(f.promo, UpdatePromoRequest(budget = 500, budgetUnlimited = false))
                    }
                }
            })
            val credit = pool.submit(Callable {
                check(loaded.await(10, TimeUnit.SECONDS))
                try { reconcile.ingestVerifiedRecommendationReward(f.pending, f.sale, 1200, true) }
                finally { credited.countDown() }
            })
            credit.get(20, TimeUnit.SECONDS)
            assertThat(change.get(20, TimeUnit.SECONDS).exceptionOrNull()).isInstanceOf(AppException::class.java)
        } finally { pool.shutdownNow() }
        assertThat(spent(f)).isEqualTo(600)
        assertThat(promoRepository.findById(f.promo).orElseThrow().budgetUnlimited).isTrue()
        promos.update(f.promo, UpdatePromoRequest(budget = 600, budgetUnlimited = false))
        assertThat(promoRepository.findById(f.promo).orElseThrow().budgetUnlimited).isFalse()
        assertThat(spent(f)).isEqualTo(600)
        assertThat(balance(f)).isEqualTo(600)
    }

    @Test fun `older editor budget-only patch preserves concurrent unlimited enablement`() {
        val f = fixture()
        val loaded = CountDownLatch(1)
        val enabled = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val oldEditor = pool.submit(Callable {
                TransactionTemplate(transactions).executeWithoutResult {
                    assertThat(promoRepository.findById(f.promo).orElseThrow().budgetUnlimited).isFalse()
                    loaded.countDown()
                    check(enabled.await(10, TimeUnit.SECONDS))
                    promos.update(f.promo, UpdatePromoRequest(title = "Old editor", budget = 0))
                }
            })
            val enable = pool.submit(Callable {
                check(loaded.await(10, TimeUnit.SECONDS))
                try { promos.update(f.promo, UpdatePromoRequest(budget = 0, budgetUnlimited = true)) }
                finally { enabled.countDown() }
            })
            enable.get(20, TimeUnit.SECONDS)
            oldEditor.get(20, TimeUnit.SECONDS)
        } finally { pool.shutdownNow() }
        assertThat(promoRepository.findById(f.promo).orElseThrow().budgetUnlimited).isTrue()
        reconcile.ingestVerifiedRecommendationReward(f.pending, f.sale, 1200, true)
        assertThat(spent(f)).isEqualTo(600)
        assertThat(budget(f)).isZero()
    }

    private data class Fixture(val promo: String, val person: String, val pending: String, val sale: String)
    private fun fixture(sharedPromo: String? = null): Fixture {
        val id = UUID.randomUUID().toString().replace("-", "")
        val pharmacy = "rx_$id"
        val person = "person_$id"
        val promo = sharedPromo ?: "promo_$id"
        val sale = "sale_$id"
        val bonus = "bonus_$id"
        val canonical = "canon_$id"
        val number = sequence.incrementAndGet().toString()
        jdbc.update("INSERT INTO chains(id,name,\"group\") VALUES (?,'TEST','pilot')", pharmacy)
        jdbc.update("INSERT INTO pharmacies(id,name,chain_id,chain_name,city,\"group\") VALUES (?,'TEST',?,'TEST','TEST','pilot')", pharmacy, pharmacy)
        jdbc.update("INSERT INTO pharmacists(id,name,iin,phone,pharmacy_id,pharmacy_name,tier,status) VALUES (?,'TEST',?,?,?,'TEST','Silver','active')",
            person, number, "+7${number.takeLast(10)}", pharmacy)
        if (sharedPromo == null) promoRepository.saveAndFlush(PromoEntity(id = promo, title = "TEST", brand = "TEST", budget = 1000))
        sales.saveAndFlush(PosSaleEntity(id = sale, pharmacistId = person, pharmacyId = pharmacy, totalAmount = 1200,
            printedAt = Instant.parse("2026-11-09T10:00:00Z"), fiscalId = "F-$id",
            items = listOf(PosSaleItem(productId = "product", qty = 3.0, price = 400, total = 1200))))
        pending.saveAndFlush(PendingBonusEntity(id = bonus, pharmacistId = person, pharmacistName = "TEST", pharmacyId = pharmacy,
            pharmacyName = "TEST", sku = "product", productName = "TEST", bonus = 200, expectedAmount = 400, sourceSaleId = sale))
        jdbc.update("INSERT INTO recommendation_reward_receipts(id,pharmacy_id,source_sale_id,payload_hash) VALUES (?,?,?,?)", canonical, pharmacy, sale, "b".repeat(64))
        jdbc.update("""INSERT INTO recommendation_sale_rewards(id,canonical_receipt_id,pharmacy_id,pharmacist_id,source_sale_id,
            product_id,promo_id,state,reason_code,unit_bonus,eligible_packages,total_bonus,pending_bonus_id)
            VALUES (?,?,?,?,?,'product',?,'ready','eligible',200,3,600,?)""", "reward_$id", canonical, pharmacy, person, sale, promo, bonus)
        return Fixture(promo, person, bonus, sale)
    }
    private fun balance(f: Fixture) = number("SELECT balance FROM pharmacists WHERE id=?", f.person)
    private fun spent(f: Fixture) = number("SELECT spent FROM promos WHERE id=?", f.promo)
    private fun budget(f: Fixture) = number("SELECT budget FROM promos WHERE id=?", f.promo)
    private fun number(sql: String, id: String) = jdbc.queryForObject(sql, Long::class.java, id)!!
}
