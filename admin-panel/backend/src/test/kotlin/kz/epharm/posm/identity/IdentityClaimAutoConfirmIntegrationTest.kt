package kz.epharm.posm.identity

import kz.epharm.posm.self.PosmSelfRequest
import kz.epharm.posm.self.PosmSelfService
import kz.epharm.posm.service.PharmacistIdentitySource
import kz.epharm.posm.service.PosmDeviceIdentity
import kz.epharm.posm.service.PosmPharmacistIdentityService
import kz.epharm.shared.error.AppException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
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
import java.util.UUID

/**
 * Real PostgreSQL, default settings: a till claim with nothing for HQ to look at is confirmed at once (owner,
 * 26.11.2026: «Автоматизируй это»), anything doubtful still waits for HQ. Synthetic data only.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class IdentityClaimAutoConfirmIntegrationTest {
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
    @Autowired private lateinit var submission: IdentityClaimSubmissionService
    @Autowired private lateinit var admin: IdentityClaimAdminService
    @Autowired private lateinit var self: PosmSelfService
    @Autowired private lateinit var identity: PosmPharmacistIdentityService

    private val pharmacy = "ac-pharmacy"
    private val device = PosmDeviceIdentity("ac-device", pharmacy, legacy = false)
    private val iinA = "900115300013"
    private val iinB = "850615400016"
    private val nameA = "Бузурбаева Лаура Ермековна"
    private val nameB = "Касымова Айгерим Болатовна"

    @BeforeEach fun setup() {
        jdbc.execute(
            "TRUNCATE pharmacist_identity_claims, posm_pharmacist_mapping_audit, posm_pharmacist_mappings, pharmacists, " +
                "standardn_pharmacist_directory, sales_premium_pending, posm_cabinet_links CASCADE",
        )
        jdbc.update("DELETE FROM posm_staff_rate_limits")
        jdbc.update("DELETE FROM pharmacies WHERE id = ?", pharmacy)
        jdbc.update("""INSERT INTO chains(id,name,"group") VALUES ('ac-chain','Synthetic','pilot') ON CONFLICT DO NOTHING""")
        jdbc.update(
            """INSERT INTO pharmacies(id,name,chain_id,chain_name,city,"group",standardn_profile_id,standardn_profile_source)
               VALUES (?, 'Synthetic AC pharmacy', 'ac-chain', 'Synthetic', 'Алматы', 'pilot', 7201, 'manual')""",
            pharmacy,
        )
    }

    private fun idRequest(user: String = "18", iin: String = iinA, name: String = nameA) =
        PosmIdentityRequest(pharmacy, user, "Бузурбаева Л", iin, name)

    private fun count(from: String, vararg args: Any): Int = jdbc.queryForObject("SELECT count(*) FROM $from", Int::class.java, *args)!!

    private fun claimStatus(user: String): String =
        jdbc.queryForObject(
            "SELECT status FROM pharmacist_identity_claims WHERE standardn_user_id = ? ORDER BY created_at DESC LIMIT 1",
            String::class.java, user,
        )!!

    private fun cardRow(id: String, iin: String, name: String) {
        jdbc.update(
            """INSERT INTO pharmacists(id,name,iin,phone,tier,status,pharmacy_id,pharmacy_name,city,account_source,iin_source,name_source)
               VALUES (?, ?, ?, '', 'Silver', 'active', ?, 'Synthetic', 'Алматы', 'standardn', 'standardn', 'standardn')""",
            id, name, iin, pharmacy,
        )
    }

    @Test fun `a clean claim is taken at once, the card and the mapping name no admin, the receipts follow, HQ sees it was automatic`() {
        val sent = submission.tillSubmit(device, idRequest())
        assertEquals("known", sent.state)

        val card = jdbc.queryForMap("SELECT * FROM pharmacists WHERE iin = ?", iinA)
        assertEquals("self", card["account_source"])
        assertEquals("self", card["iin_source"])
        assertEquals(nameA, card["name"])
        assertEquals(pharmacy, card["pharmacy_id"])
        assertEquals("active", card["status"])

        val mapping = jdbc.queryForMap("SELECT * FROM posm_pharmacist_mappings WHERE pharmacy_id = ?", pharmacy)
        assertEquals("18", mapping["external_user_id"])
        assertEquals(true, mapping["active"])
        assertEquals(card["id"], mapping["pharmacist_id"])
        assertNull(mapping["created_by"])
        val audit = jdbc.queryForMap("SELECT * FROM posm_pharmacist_mapping_audit WHERE mapping_id = ?", mapping["id"])
        assertEquals("created", audit["action"])
        assertNull(audit["actor_id"])

        val claim = jdbc.queryForMap("SELECT * FROM pharmacist_identity_claims")
        assertEquals("confirmed", claim["status"])
        assertNull(claim["decided_by"])
        assertNotNull(claim["decided_at"])
        assertEquals(IdentityClaimRules.AUTO_CONFIRMED, claim["decision_reason"])
        assertEquals(card["id"], claim["pharmacist_id"])
        assertEquals(mapping["id"], claim["mapping_id"])

        val resolved = identity.resolve(
            pharmacyId = pharmacy, reportedPharmacistId = "18", reportedPharmacistName = "Бузурбаева Л", useDirectory = true,
        )
        assertEquals(card["id"], resolved.pharmacistId)
        assertEquals(PharmacistIdentitySource.STANDARDN_EXPLICIT_MAPPING, resolved.source)

        assertEquals("known", submission.tillState(device, idRequest()).state)
        assertEquals(nameA, self.view(device, PosmSelfRequest(pharmacyId = pharmacy, standardNUserId = "18")).fullName)
        val listed = admin.list("confirmed").single()
        assertEquals(IdentityClaimRules.AUTO_DECIDER_NAME, listed.decidedByName)
        // The IIN cannot be changed once it is taken: a second claim is refused.
        assertEquals(409, assertThrows(AppException::class.java) { submission.tillSubmit(device, idRequest(iin = iinB)) }.status.value())
    }

    @Test fun `a sales premium waiting for the IIN is credited to the card ePharm creates`() {
        jdbc.update("INSERT INTO sales_premium_pending(iin, period, amount_kzt, through) VALUES (?, '2026-11', 15000, DATE '2026-11-25')", iinA)
        assertEquals("known", submission.tillSubmit(device, idRequest()).state)
        val deadline = System.nanoTime() + 15_000_000_000L
        while (count("sales_premium_pending WHERE iin = ?", iinA) > 0 && System.nanoTime() < deadline) Thread.sleep(100)
        assertEquals(0, count("sales_premium_pending WHERE iin = ?", iinA))
        assertEquals(15000L, jdbc.queryForObject("SELECT balance FROM pharmacists WHERE iin = ?", Long::class.java, iinA))
    }

    @Test fun `the IIN of an existing card waits for HQ with its flag, nothing is attached`() {
        cardRow("ph_existing", iinA, "Бузурбаева Лаура")
        assertEquals("pending", submission.tillSubmit(device, idRequest()).state)
        assertEquals("pending", claimStatus("18"))
        assertEquals(IdentityClaimRules.CONFLICT_IIN_OTHER_CARD, jdbc.queryForObject("SELECT conflict FROM pharmacist_identity_claims", String::class.java))
        assertEquals(0, count("posm_pharmacist_mappings WHERE pharmacy_id = ?", pharmacy))
    }

    @Test fun `an IIN the Standard-N directory holds for another user waits for HQ`() {
        jdbc.update(
            """INSERT INTO standardn_pharmacist_directory(directory_key,source_profile_id,source_user_id,iin,full_name,source_status,profile_name)
               VALUES ('7201:44', 7201, 44, ?, 'Другой Человек Иванович', 0, 'Synthetic')""",
            iinA,
        )
        assertEquals("pending", submission.tillSubmit(device, idRequest()).state)
        assertEquals(0, count("pharmacists WHERE iin = ?", iinA))
    }

    @Test fun `an IIN another open claim names waits for HQ, both stay open`() {
        jdbc.update(
            """INSERT INTO pharmacist_identity_claims(id, kind, pharmacy_id, standardn_user_id, iin, full_name)
               VALUES (?, 'person', ?, '19', ?, ?)""",
            UUID.randomUUID(), pharmacy, iinA, nameB,
        )
        assertEquals("pending", submission.tillSubmit(device, idRequest()).state)
        assertEquals(2, count("pharmacist_identity_claims WHERE status = 'pending'"))
        assertEquals(0, count("pharmacists WHERE iin = ?", iinA))
    }

    @Test fun `after an HQ rejection of this person or this IIN the next claim waits for HQ again`() {
        jdbc.update(
            """INSERT INTO pharmacist_identity_claims(id, kind, pharmacy_id, standardn_user_id, iin, full_name, status, decided_at, decision_reason)
               VALUES (?, 'person', ?, '18', ?, ?, 'rejected', now(), 'Не тот ИИН')""",
            UUID.randomUUID(), pharmacy, iinB, nameA,
        )
        assertEquals("pending", submission.tillSubmit(device, idRequest()).state)
        jdbc.update(
            """INSERT INTO pharmacist_identity_claims(id, kind, pharmacy_id, standardn_user_id, iin, full_name, status, decided_at, decision_reason)
               VALUES (?, 'person', ?, '30', ?, ?, 'rejected', now(), 'Не тот ИИН')""",
            UUID.randomUUID(), pharmacy, iinB, nameB,
        )
        assertEquals("pending", submission.tillSubmit(device, idRequest(user = "31", iin = iinB, name = nameB)).state)
        assertEquals(0, count("posm_pharmacist_mappings WHERE pharmacy_id = ?", pharmacy))
    }

    @Test fun `a second Standard-N user typing an IIN ePharm has just taken waits for HQ`() {
        assertEquals("known", submission.tillSubmit(device, idRequest()).state)
        assertEquals("pending", submission.tillSubmit(device, idRequest(user = "25")).state)
        assertEquals(1, count("posm_pharmacist_mappings WHERE pharmacy_id = ?", pharmacy))
        assertEquals("pending", claimStatus("25"))
    }
}
