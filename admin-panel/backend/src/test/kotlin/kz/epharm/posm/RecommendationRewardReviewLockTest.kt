package kz.epharm.posm

import io.mockk.*
import jakarta.persistence.EntityManager
import java.util.Optional
import kz.epharm.auth.access.AdministrativeScope
import kz.epharm.pharmacists.entity.PharmacistEntity
import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.posm.entity.PosSaleEntity
import kz.epharm.posm.repository.PosSaleRepository
import kz.epharm.posm.service.PosmPharmacistIdentityService
import kz.epharm.posm.service.RecommendationRewardReviewService
import kz.epharm.posm.service.RewardReviewDecision
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate

class RecommendationRewardReviewLockTest {
    @Test
    fun `review locks recipient and native account before pending receipt and source identity read`() {
        val jdbc = mockk<JdbcTemplate>()
        val scope = mockk<AdministrativeScope>(relaxed = true)
        every { scope.currentPharmacyIds() } returns null
        val sales = mockk<PosSaleRepository>()
        val people = mockk<PharmacistRepository>()
        val identities = mockk<PosmPharmacistIdentityService>()
        val steps = mutableListOf<String>()
        val row = mapOf<String, Any>("id" to "reward", "pharmacy_id" to "pharmacy", "pharmacist_id" to "person",
            "canonical_receipt_id" to "canonical", "source_sale_id" to "sale", "pending_bonus_id" to "pending",
            "receipt_id" to "receipt", "state" to "review_required", "reason_code" to "package_unit_unverified")
        every { jdbc.queryForList(any<String>(), *anyVararg()) } answers {
            val sql = firstArg<String>()
            steps += sql
            if (sql.startsWith("SELECT * FROM recommendation_sale_rewards")) listOf(row) else emptyList()
        }
        every { jdbc.queryForObject("SELECT session_id FROM recommendation_reward_receipts WHERE id=?", String::class.java, "canonical") } returns "checkout"
        every { jdbc.queryForMap("SELECT physical_key,fiscal_key FROM recommendation_reward_receipts WHERE id=?", "canonical") } returns mapOf("physical_key" to "physical", "fiscal_key" to "fiscal")
        every { sales.findById("sale") } returns Optional.of(PosSaleEntity(id="sale", pharmacistId="person",
            pharmacyId="pharmacy", reportedPharmacistId="native-user"))
        every { people.findById("person") } returns Optional.of(PharmacistEntity(id="person", pharmacyId="pharmacy", statusRaw="active"))
        every { identities.resolve(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            steps += "resolve-identity"
            throw IllegalStateException("stop-after-identity")
        }
        val service = RecommendationRewardReviewService(jdbc, scope, sales, people, identities, mockk(), mockk(),
            mockk(), mockk(), mockk<EntityManager>(relaxed=true))
        assertEquals("stop-after-identity", assertThrows(IllegalStateException::class.java) {
            service.decide("reward", RewardReviewDecision("approve", "Verified original receipt", eligiblePackages=1,
                confirmedPayment=true, confirmedPackages=true), "reviewer")
        }.message)
        val recipient = steps.indexOf("SELECT id FROM pharmacists WHERE id=? FOR UPDATE")
        val account = steps.indexOf("SELECT pg_advisory_xact_lock(hashtextextended(?,729182))")
        val pending = steps.indexOf("SELECT id FROM pending_bonuses WHERE id=? FOR UPDATE")
        val receipt = steps.indexOf("SELECT id FROM receipts WHERE id=? FOR UPDATE")
        assertTrue(recipient >= 0, "Recipient row must be held before the native account lock")
        assertTrue(account > recipient, "Account lock must follow recipient")
        assertTrue(pending > account && receipt > pending, "Financial row locks must follow account proof lock")
        assertTrue(steps.indexOf("resolve-identity") > receipt, "Read authorization while all prerequisite locks are held")
    }
}
