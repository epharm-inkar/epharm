package kz.epharm.receipts

import io.mockk.*
import java.time.Instant
import java.util.Optional
import kz.epharm.posm.entity.PosSaleEntity
import kz.epharm.posm.entity.PosSaleItem
import kz.epharm.posm.repository.PosSaleRepository
import kz.epharm.receipts.entity.PendingBonusEntity
import kz.epharm.receipts.entity.PendingBonusStatus
import kz.epharm.receipts.entity.ReceiptEntity
import kz.epharm.receipts.service.RecommendationRewardCreditPolicy
import kz.epharm.shared.error.AppException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.jdbc.core.JdbcTemplate

class RecommendationRewardCreditPolicyTest {
    private lateinit var jdbc: JdbcTemplate
    private lateinit var sales: PosSaleRepository
    private lateinit var policy: RecommendationRewardCreditPolicy
    private lateinit var reward: MutableMap<String, Any>
    private lateinit var budget: MutableMap<String, Any>
    private lateinit var person: MutableMap<String, Any>
    private lateinit var pending: PendingBonusEntity
    private lateinit var receipt: ReceiptEntity
    private lateinit var sale: PosSaleEntity
    private val now = Instant.parse("2026-11-09T10:00:00Z")

    @BeforeEach fun setup() {
        jdbc=mockk();sales=mockk();policy=RecommendationRewardCreditPolicy(jdbc,sales)
        reward=mutableMapOf("id" to "reward", "pharmacy_id" to "pharmacy", "pharmacist_id" to "person",
            "source_sale_id" to "sale", "product_id" to "product", "promo_id" to "promo", "state" to "ready",
            "reason_code" to "eligible", "unit_bonus" to 200L, "eligible_packages" to 3L, "total_bonus" to 600L,
            "pending_bonus_id" to "pending", "receipt_id" to "receipt", "budget_reserved" to false)
        budget=mutableMapOf("budget" to 1000L,"spent" to 100L,"budget_unlimited" to false)
        person=mutableMapOf("status" to "active","pharmacy_id" to "pharmacy")
        pending=PendingBonusEntity(id="pending",pharmacistId="person",pharmacyId="pharmacy",sku="product",bonus=200,sourceSaleId="sale")
        receipt=ReceiptEntity(id="receipt",pendingBonusId="pending",pharmacistId="person",pharmacyId="pharmacy",parsedSku="product",parsedAmount=1200,parsedAt=now)
        sale=PosSaleEntity(id="sale",pharmacistId="person",pharmacyId="pharmacy",totalAmount=2000,printedAt=now,
            items=listOf(PosSaleItem(productId="product",qty=3.0,price=400,total=1200)))
        every { jdbc.queryForList(match { it.contains("FROM recommendation_sale_rewards") }, *anyVararg()) } answers { listOf(HashMap(reward)) }
        every { jdbc.queryForList("SELECT status,pharmacy_id FROM pharmacists WHERE id=?", any<String>()) } answers { listOf(HashMap(person)) }
        every { jdbc.queryForList(match { it.startsWith("SELECT budget,") && it.endsWith("FROM promos WHERE id=? FOR NO KEY UPDATE") }, any<String>()) } answers { listOf(HashMap(budget)) }
        every { sales.findById("sale") } answers { Optional.of(sale) }
        every { jdbc.update(any<String>(), *anyVararg()) } answers {
            val statement=firstArg<String>()
            if(statement.contains("SET budget_reserved=true")) reward["budget_reserved"]=true
            if(statement.contains("SET state='credited'")) reward["state"]="credited"
            if(statement.contains("SET state='review_required'")) reward["state"]="review_required"
            1
        }
    }

    @Test fun `legacy pending retains its amount without a campaign budget or sale lookup`() {
        every { jdbc.queryForList(match { it.contains("FROM recommendation_sale_rewards") }, *anyVararg()) } returns emptyList()
        val result=policy.beforeCredit(pending,receipt)
        assertTrue(result.allowed);assertEquals(200L,result.amountKzt)
        policy.afterCredit(pending,receipt,200)
        verify { sales wasNot Called }
        verify(exactly=0) { jdbc.update(any<String>(), *anyVararg()) }
    }

    @ParameterizedTest
    @EnumSource(value = PendingBonusStatus::class, names = ["expired", "matched"])
    fun `legacy credit cannot reopen a closed reservation without a v2 reward case`(status: PendingBonusStatus) {
        every { jdbc.queryForList(match { it.contains("FROM recommendation_sale_rewards") }, *anyVararg()) } returns emptyList()
        pending.sourceSaleId = null
        pending.status = status
        assertEquals(409, assertThrows(AppException::class.java) { policy.beforeCredit(pending, receipt) }.status.value())
        verify { sales wasNot Called }
        verify(exactly = 0) { jdbc.update(any<String>(), *anyVararg()) }
    }

    @Test fun `three paid packages use total bonus while preserving the unit pending snapshot`() {
        val result=policy.beforeCredit(pending,receipt)
        assertTrue(result.allowed);assertEquals(600L,result.amountKzt);assertEquals(200L,pending.bonus)
        verify(exactly=1) { jdbc.update("UPDATE promos SET spent=spent+? WHERE id=? AND spent=?",600L,"promo",100L) }
        policy.afterCredit(pending,receipt,result.amountKzt)
        assertEquals("credited",reward["state"])
    }

    @Test fun `insufficient or invalid budget returns review without spending or creating a credit`() {
        for((limit,spent,reason) in listOf(Triple(500L,100L,"reward_budget_exhausted"),Triple(0L,0L,"reward_budget_invalid"),Triple(1000L,-1L,"reward_budget_invalid"))) {
            reward["state"]="ready";budget["budget"]=limit;budget["spent"]=spent
            val result=policy.beforeCredit(pending,receipt)
            assertFalse(result.allowed);assertEquals(0L,result.amountKzt);assertEquals(reason,result.reasonCode)
            assertEquals("review_required",reward["state"]);assertEquals(false,reward["budget_reserved"])
        }
        verify(exactly=0) { jdbc.update(match { it.startsWith("UPDATE promos") }, *anyVararg()) }
    }

    @Test fun `a prior valid reservation is not charged twice`() {
        reward["budget_reserved"]=true;budget["spent"]=700L
        assertEquals(600L,policy.beforeCredit(pending,receipt).amountKzt)
        verify(exactly=0) { jdbc.update(match { it.startsWith("UPDATE promos") }, *anyVararg()) }
    }

    @Test fun `explicit unlimited budget allows zero limit and still records exact spending`() {
        budget["budget_unlimited"] = true
        budget["budget"] = 0L
        budget["spent"] = 100L
        val result = policy.beforeCredit(pending, receipt)
        assertTrue(result.allowed)
        assertEquals(600L, result.amountKzt)
        verify(exactly = 1) { jdbc.update("UPDATE promos SET spent=spent+? WHERE id=? AND spent=?", 600L, "promo", 100L) }
        assertEquals(true, reward["budget_reserved"])
    }

    @Test fun `unlimited budget ignores numeric cap but never bypasses identity or paid evidence`() {
        budget["budget_unlimited"] = true
        budget["budget"] = 100L
        budget["spent"] = 5000L
        sale.pharmacistId = "another-person"
        assertThrows(AppException::class.java) { policy.beforeCredit(pending, receipt) }
        verify(exactly = 0) { jdbc.update(match { it.startsWith("UPDATE promos") }, *anyVararg()) }
        sale.pharmacistId = "person"
        assertTrue(policy.beforeCredit(pending, receipt).allowed)
    }

    @Test fun `unlimited reserved reward is not charged twice even with spending beyond numeric budget`() {
        budget["budget_unlimited"] = true
        budget["budget"] = 0L
        budget["spent"] = Long.MAX_VALUE
        reward["budget_reserved"] = true
        assertEquals(600L, policy.beforeCredit(pending, receipt).amountKzt)
        verify(exactly = 0) { jdbc.update(match { it.startsWith("UPDATE promos") }, *anyVararg()) }
    }

    @Test fun `unlimited budget fails closed before bigint spending overflow`() {
        budget["budget_unlimited"] = true
        budget["budget"] = 0L
        budget["spent"] = Long.MAX_VALUE - 599L
        val result = policy.beforeCredit(pending, receipt)
        assertFalse(result.allowed)
        assertEquals("reward_budget_overflow", result.reasonCode)
        assertEquals(false, reward["budget_reserved"])
        verify(exactly = 0) { jdbc.update(match { it.startsWith("UPDATE promos") }, *anyVararg()) }
    }

    @Test fun `unlimited budget accepts exact bigint boundary but not negative stored values`() {
        budget["budget_unlimited"] = true
        budget["budget"] = 0L
        budget["spent"] = -1L
        assertEquals("reward_budget_invalid", policy.beforeCredit(pending, receipt).reasonCode)
        reward["state"] = "ready"
        budget["spent"] = Long.MAX_VALUE - 600L
        assertTrue(policy.beforeCredit(pending, receipt).allowed)
        verify(exactly = 1) { jdbc.update("UPDATE promos SET spent=spent+? WHERE id=? AND spent=?", 600L, "promo", Long.MAX_VALUE - 600L) }
    }

    @Test fun `missing or non boolean unlimited flag does not reinterpret zero budget`() {
        budget["budget"] = 0L
        for (mode in listOf(null, "true", 1, false)) {
            if (mode == null) budget.remove("budget_unlimited") else budget["budget_unlimited"] = mode
            reward["state"] = "ready"
            assertEquals("reward_budget_invalid", policy.beforeCredit(pending, receipt).reasonCode)
        }
        verify(exactly = 0) { jdbc.update(match { it.startsWith("UPDATE promos") }, *anyVararg()) }
    }

    @Test fun `quantity amount beneficiary and source mismatches cannot bypass the common credit gate`() {
        for((key,value) in listOf("total_bonus" to 601L,"eligible_packages" to 4L,"pharmacist_id" to "other",
            "source_sale_id" to "other-sale","product_id" to "other-product","state" to "refund_review")) {
            val original=reward[key]!!;reward[key]=value
            assertEquals(409,assertThrows(AppException::class.java){policy.beforeCredit(pending,receipt)}.status.value())
            reward[key]=original
        }
        person["status"]="blocked"
        assertThrows(AppException::class.java){policy.beforeCredit(pending,receipt)}
        verify(exactly=0) { jdbc.update(match { it.startsWith("UPDATE promos") }, *anyVararg()) }
    }

    @Test fun `overflow and nonfinite paid quantities are rejected before budget reservation`() {
        reward["unit_bonus"]=Long.MAX_VALUE;pending.bonus=Long.MAX_VALUE
        assertThrows(AppException::class.java){policy.beforeCredit(pending,receipt)}
        reward["unit_bonus"]=200L;pending.bonus=200
        for(value in listOf(Double.NaN,Double.POSITIVE_INFINITY,-1.0,0.0)) {
            sale.items=listOf(PosSaleItem(productId="product",qty=value,price=400,total=1200))
            assertThrows(AppException::class.java){policy.beforeCredit(pending,receipt)}
        }
        verify(exactly=0) { jdbc.update(match { it.startsWith("UPDATE promos") }, *anyVararg()) }
    }

    @Test fun `review attachment preserves its reason without reserving campaign funds`() {
        reward["state"]="review_required";reward["reason_code"]="untrusted_employee"
        assertEquals("untrusted_employee",policy.attachReceipt(pending,receipt,"sale"))
        verify(exactly=0) { jdbc.update(match { it.startsWith("UPDATE promos") }, *anyVararg()) }
        verify { sales wasNot Called }
        assertThrows(AppException::class.java) { policy.attachReceipt(pending,receipt,"different-sale") }
    }

    @Test fun `an expired pending or a missing campaign cannot become an automatic credit`() {
        pending.status=PendingBonusStatus.expired
        assertThrows(AppException::class.java) { policy.beforeCredit(pending,receipt) }
        pending.status=PendingBonusStatus.awaiting_receipt
        reward.remove("promo_id")
        val decision=policy.beforeCredit(pending,receipt)
        assertFalse(decision.allowed);assertEquals("reward_budget_missing",decision.reasonCode)
        verify(exactly=0) { jdbc.update(match { it.startsWith("UPDATE promos") }, *anyVararg()) }
    }

    @Test fun `one receipt cannot silently choose between conflicting reward cases`() {
        every { jdbc.queryForList(match { it.contains("FROM recommendation_sale_rewards") }, *anyVararg()) } returns listOf(HashMap(reward),HashMap(reward))
        assertThrows(AppException::class.java) { policy.beforeCredit(pending,receipt) }
        verify(exactly=0) { jdbc.update(any<String>(), *anyVararg()) }
    }

    @Test fun `a beneficiary changed before the wallet lock cannot be marked credited`() {
        policy.beforeCredit(pending,receipt)
        person["pharmacy_id"]="other"
        assertThrows(AppException::class.java){policy.afterCredit(pending,receipt,600)}
        assertEquals("ready",reward["state"])
    }
}
