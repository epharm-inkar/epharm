package kz.epharm.receipts

import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kz.epharm.finance.service.PharmacistWalletService
import kz.epharm.finance.service.WalletCreditSource
import kz.epharm.posm.entity.PosSaleEntity
import kz.epharm.posm.entity.PosSaleItem
import kz.epharm.posm.repository.PosSaleRepository
import kz.epharm.receipts.dto.ExcelRowInput
import kz.epharm.receipts.entity.PendingBonusEntity
import kz.epharm.receipts.entity.PendingBonusStatus
import kz.epharm.receipts.entity.ReceiptEntity
import kz.epharm.receipts.entity.ReceiptStatus
import kz.epharm.receipts.repository.PendingBonusRepository
import kz.epharm.receipts.repository.ReceiptRepository
import kz.epharm.receipts.service.ReconcileService
import kz.epharm.receipts.service.ExcelPharmacyMappingProperties
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
class RecommendationRewardCreditIntegrationTest {
    companion object {
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("reward_credit_test").withUsername("epharm").withPassword("test-only").apply { start() }
        private val sequence = AtomicLong(870_000_000_000)
        @JvmStatic @DynamicPropertySource fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
        }
    }
    @Autowired lateinit var reconcile: ReconcileService
    @Autowired lateinit var excelPharmacies: ExcelPharmacyMappingProperties
    @Autowired lateinit var pending: PendingBonusRepository
    @Autowired lateinit var receipts: ReceiptRepository
    @Autowired lateinit var sales: PosSaleRepository
    @Autowired lateinit var wallet: PharmacistWalletService
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var transactions: PlatformTransactionManager

    @Test fun `paid packages credit their total once through the shared wallet without inventing Excel proof`() {
        val f = fixture()
        val id = reconcile.ingestVerifiedRecommendationReward(f.pending, f.sale, 1200, true)
        val receipt = receipts.findById(id).orElseThrow()
        assertThat(receipt.status).isEqualTo(ReceiptStatus.approved)
        assertThat(receipt.autoApproved).isTrue()
        assertThat(receipt.confirmedByLog).isTrue()
        assertThat(receipt.confirmedByExcel).isFalse()
        assertThat(receipt.parsedAmount).isEqualTo(1200)
        assertThat(receipt.bonusCredited).isEqualTo(600)
        assertThat(pending.findById(f.pending).orElseThrow().bonus).isEqualTo(200)
        assertThat(pending.findById(f.pending).orElseThrow().status).isEqualTo(PendingBonusStatus.matched)
        assertThat(balance(f)).isEqualTo(600)
        assertThat(number("SELECT earned_30d FROM pharmacists WHERE id=?", f.person)).isEqualTo(600)
        assertThat(spent(f)).isEqualTo(600)
        assertThat(state(f)).isEqualTo("credited")
        assertThat(number("SELECT count(*) FROM pharmacist_wallet_entries WHERE source='receipt_bonus' AND source_id=?", f.pending)).isEqualTo(1)
    }

    @Test fun `simultaneous sale retries keep one receipt one budget reservation and one wallet entry`() {
        val f = fixture()
        val ids = parallel((1..6).map { { reconcile.ingestVerifiedRecommendationReward(f.pending, f.sale, 1200, true) } })
        assertThat(ids.distinct()).hasSize(1)
        assertThat(number("SELECT count(*) FROM receipts WHERE pending_bonus_id=?", f.pending)).isEqualTo(1)
        assertThat(number("SELECT count(*) FROM pharmacist_wallet_entries WHERE source_id=?", f.pending)).isEqualTo(1)
        assertThat(balance(f)).isEqualTo(600)
        assertThat(spent(f)).isEqualTo(600)
    }

    @Test fun `legacy manual approval racing automatic reward preserves both recipient amounts without deadlock`() {
        val f = fixture()
        val legacy = pending.saveAndFlush(PendingBonusEntity(id="legacy_${UUID.randomUUID()}", pharmacistId=f.person,
            pharmacistName="TEST", pharmacyId=f.pharmacy, pharmacyName="TEST", sku="legacy-product", bonus=250, expectedAmount=1000))
        val oldReceipt = receipts.saveAndFlush(ReceiptEntity(id="legacy_${UUID.randomUUID()}", pharmacistId=f.person,
            pharmacistName="TEST", pharmacyId=f.pharmacy, pharmacyName="TEST", pendingBonusId=legacy.id,
            parsedSku="legacy-product", parsedAmount=1000).also { it.status=ReceiptStatus.moderation_required })
        parallel(listOf(
            { reconcile.approve(oldReceipt.id,"HQ-test").id },
            { reconcile.ingestVerifiedRecommendationReward(f.pending,f.sale,1200,true) },
        ))
        assertThat(receipts.findById(oldReceipt.id).orElseThrow().bonusCredited).isEqualTo(250)
        assertThat(pending.findById(legacy.id).orElseThrow().status).isEqualTo(PendingBonusStatus.matched)
        assertThat(balance(f)).isEqualTo(850)
        assertThat(number("SELECT earned_30d FROM pharmacists WHERE id=?",f.person)).isEqualTo(850)
        assertThat(spent(f)).isEqualTo(600)
        assertThat(number("SELECT count(*) FROM pharmacist_wallet_entries WHERE pharmacist_id=?",f.person)).isEqualTo(2)
        reconcile.approve(oldReceipt.id,"HQ-retry")
        reconcile.ingestVerifiedRecommendationReward(f.pending,f.sale,1200,true)
        assertThat(balance(f)).isEqualTo(850)
        assertThat(spent(f)).isEqualTo(600)
    }

    @Test fun `two independent cases racing the final campaign budget credit only the affordable case`() {
        val first = fixture(budget = 1000)
        val second = fixture(promo = first.promo)
        val ids = parallel(listOf(
            { reconcile.ingestVerifiedRecommendationReward(first.pending, first.sale, 1200, true) },
            { reconcile.ingestVerifiedRecommendationReward(second.pending, second.sale, 1200, true) },
        ))
        val decisions = ids.map { receipts.findById(it).orElseThrow() }
        assertThat(decisions.count { it.status == ReceiptStatus.approved }).isEqualTo(1)
        assertThat(decisions.count { it.status == ReceiptStatus.moderation_required }).isEqualTo(1)
        assertThat(decisions.single { it.status == ReceiptStatus.moderation_required }.flagReason).isEqualTo("reward_budget_exhausted")
        assertThat(balance(first) + balance(second)).isEqualTo(600)
        assertThat(spent(first)).isEqualTo(600)
        assertThat(listOf(state(first), state(second))).containsExactlyInAnyOrder("credited", "review_required")
        assertThat(sales.existsById(first.sale) && sales.existsById(second.sale)).isTrue()
    }

    @Test fun `zero campaign budget preserves the paid sale for HQ but never means unlimited money`() {
        val f = fixture(budget = 0)
        val id = reconcile.ingestVerifiedRecommendationReward(f.pending, f.sale, 1200, true)
        val receipt = receipts.findById(id).orElseThrow()
        assertThat(receipt.status).isEqualTo(ReceiptStatus.moderation_required)
        assertThat(receipt.flagReason).isEqualTo("reward_budget_invalid")
        assertThat(receipt.confirmedByLog).isTrue()
        assertThat(receipt.confirmedByExcel).isFalse()
        assertThat(state(f)).isEqualTo("review_required")
        assertThatThrownBy { reconcile.approve(id, "HQ-test") }.isInstanceOf(AppException::class.java)
        assertThat(balance(f)).isZero()
        assertThat(spent(f)).isZero()
        assertThat(sales.existsById(f.sale)).isTrue()
    }

    @Test fun `manual approval uses the same quantity and budget policy and cannot approve a review case`() {
        val valid = fixture()
        excelPharmacies.pharmacyCodes = mapOf("pilot-branch" to valid.pharmacy)
        val id = reconcile.ingestVerifiedRecommendationReward(valid.pending, valid.sale, 1200, false)
        assertThat(balance(valid)).isZero()
        assertThat(reconcile.ingestExcelRow(ExcelRowInput(
            receipts.findById(id).orElseThrow().fiscalId, "pilot-branch", null, "product", "TEST", java.math.BigDecimal.valueOf(3), 1200, Instant.now(),
        ))).isFalse()
        assertThat(balance(valid)).isZero()
        reconcile.approve(id, "HQ-test")
        assertThat(balance(valid)).isEqualTo(600)
        assertThat(spent(valid)).isEqualTo(600)
        val invalid = fixture(state = "review_required")
        val held = reconcile.ingestVerifiedRecommendationReward(invalid.pending, invalid.sale, 1200, false)
        jdbc.update("UPDATE receipts SET confirmed_by_excel=true WHERE id=?", held)
        assertThatThrownBy { reconcile.approve(held, "HQ-test") }.isInstanceOf(AppException::class.java)
        assertThat(balance(invalid)).isZero()
        assertThat(spent(invalid)).isZero()
    }

    @Test fun `a changed beneficiary or falsified paid amount cannot move funds or consume budget`() {
        for (change in listOf("status='blocked'", "pharmacy_id=NULL")) {
            val f = fixture()
            jdbc.update("UPDATE pharmacists SET $change WHERE id=?", f.person)
            assertThatThrownBy { reconcile.ingestVerifiedRecommendationReward(f.pending, f.sale, 1200, true) }
                .isInstanceOf(AppException::class.java)
            assertThat(balance(f)).isZero()
            assertThat(spent(f)).isZero()
        }
        val amount = fixture()
        assertThatThrownBy { reconcile.ingestVerifiedRecommendationReward(amount.pending, amount.sale, 1199, true) }
            .isInstanceOf(AppException::class.java)
        assertThat(balance(amount)).isZero()
        assertThat(spent(amount)).isZero()
    }

    @Test fun `payment hours after the already verified addition is not rejected by the old thirty minute deadline`() {
        val f = fixture()
        jdbc.update("UPDATE pending_bonuses SET created_at=now()-interval '3 hours' WHERE id=?", f.pending)
        reconcile.ingestVerifiedRecommendationReward(f.pending, f.sale, 1200, true)
        assertThat(balance(f)).isEqualTo(600)
    }

    @Test fun `stale pending state in a manual decision cannot resurrect an expired reservation`() {
        val f = fixture()
        val id = reconcile.ingestVerifiedRecommendationReward(f.pending, f.sale, 1200, false)
        val loaded = CountDownLatch(1)
        val expired = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val approval = pool.submit(Callable {
                runCatching {
                    TransactionTemplate(transactions).executeWithoutResult {
                        assertThat(pending.findById(f.pending).orElseThrow().status).isEqualTo(PendingBonusStatus.awaiting_receipt)
                        loaded.countDown()
                        check(expired.await(10, TimeUnit.SECONDS))
                        reconcile.approve(id, "HQ-test")
                    }
                }
            })
            val expiry = pool.submit(Callable {
                check(loaded.await(10, TimeUnit.SECONDS))
                jdbc.update("UPDATE pending_bonuses SET status='expired' WHERE id=?", f.pending)
                expired.countDown()
            })
            expiry.get(20, TimeUnit.SECONDS)
            assertThat(approval.get(20, TimeUnit.SECONDS).exceptionOrNull()).isInstanceOf(AppException::class.java)
        } finally { pool.shutdownNow() }
        assertThat(pending.findById(f.pending).orElseThrow().status).isEqualTo(PendingBonusStatus.expired)
        assertThat(balance(f)).isZero()
        assertThat(spent(f)).isZero()
    }

    @Test fun `a wallet source conflict rolls back the campaign reservation and receipt atomically`() {
        val f = fixture()
        wallet.credit(WalletCreditSource.RECEIPT_BONUS, f.pending, f.person, 200, "receipt:unrelated-test")
        assertThatThrownBy { reconcile.ingestVerifiedRecommendationReward(f.pending, f.sale, 1200, true) }
            .isInstanceOf(AppException::class.java)
        assertThat(balance(f)).isEqualTo(200)
        assertThat(spent(f)).isZero()
        assertThat(state(f)).isEqualTo("ready")
        assertThat(number("SELECT count(*) FROM receipts WHERE pending_bonus_id=?", f.pending)).isZero()
        assertThat(jdbc.queryForObject("SELECT budget_reserved FROM recommendation_sale_rewards WHERE id=?", Boolean::class.java, f.reward)).isFalse()
    }

    private data class Fixture(val person: String, val pharmacy: String, val promo: String, val pending: String, val sale: String, val reward: String)
    private fun fixture(budget: Long = 1000, promo: String? = null, state: String = "ready"): Fixture {
        val id = UUID.randomUUID().toString().replace("-", "")
        val pharmacy = "rx_$id"
        val person = "person_$id"
        val campaign = promo ?: "promo_$id"
        val sale = "sale_$id"
        val bonus = "bonus_$id"
        val canonical = "canon_$id"
        val reward = "reward_$id"
        val number = sequence.incrementAndGet().toString()
        jdbc.update("INSERT INTO chains(id,name,\"group\") VALUES (?,'TEST','pilot')", pharmacy)
        jdbc.update("INSERT INTO pharmacies(id,name,chain_id,chain_name,city,\"group\") VALUES (?,'TEST',?,'TEST','TEST','pilot')", pharmacy, pharmacy)
        jdbc.update("INSERT INTO pharmacists(id,name,iin,phone,pharmacy_id,pharmacy_name,tier,status) VALUES (?,'TEST',?,?,?,'TEST','Silver','active')",
            person, number, "+7${number.takeLast(10)}", pharmacy)
        if (promo == null) jdbc.update("INSERT INTO promos(id,title,status,brand,budget) VALUES (?,'TEST','active','TEST',?)", campaign, budget)
        sales.saveAndFlush(PosSaleEntity(id = sale, pharmacistId = person, pharmacyId = pharmacy,
            totalAmount = 1200, printedAt = Instant.parse("2026-11-09T10:00:00Z"), fiscalId = "F-$id",
            items = listOf(PosSaleItem(productId = "product", qty = 3.0, price = 400, total = 1200))))
        pending.saveAndFlush(PendingBonusEntity(id = bonus, pharmacistId = person, pharmacistName = "TEST", pharmacyId = pharmacy,
            pharmacyName = "TEST", sku = "product", productName = "TEST", bonus = 200, expectedAmount = 400, sourceSaleId = sale))
        jdbc.update("INSERT INTO recommendation_reward_receipts(id,pharmacy_id,source_sale_id,payload_hash) VALUES (?,?,?,?)", canonical, pharmacy, sale, "a".repeat(64))
        jdbc.update("""INSERT INTO recommendation_sale_rewards(id,canonical_receipt_id,pharmacy_id,pharmacist_id,source_sale_id,
            product_id,promo_id,state,reason_code,unit_bonus,eligible_packages,total_bonus,pending_bonus_id)
            VALUES (?,?,?,?,?,'product',?,?,'eligible',200,3,600,?)""", reward, canonical, pharmacy, person, sale, campaign, state, bonus)
        return Fixture(person, pharmacy, campaign, bonus, sale, reward)
    }
    private fun balance(f: Fixture) = number("SELECT balance FROM pharmacists WHERE id=?", f.person)
    private fun spent(f: Fixture) = number("SELECT spent FROM promos WHERE id=?", f.promo)
    private fun state(f: Fixture) = jdbc.queryForObject("SELECT state FROM recommendation_sale_rewards WHERE id=?", String::class.java, f.reward)!!
    private fun number(sql: String, id: String) = jdbc.queryForObject(sql, Long::class.java, id)!!
    private fun <T> parallel(actions: List<() -> T>): List<T> {
        val gate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(actions.size)
        return try {
            val tasks = actions.map { action -> pool.submit(Callable { check(gate.await(10, TimeUnit.SECONDS)); action() }) }
            gate.countDown()
            tasks.map { it.get(30, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
    }
}
