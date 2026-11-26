package kz.epharm.posm.identity

import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.security.AdminPrincipal
import kz.epharm.mobile.auth.service.MobileAuthService
import kz.epharm.posm.dto.UpsertPosmPharmacistMappingRequest
import kz.epharm.posm.self.PosmSelfRequest
import kz.epharm.posm.self.PosmSelfService
import kz.epharm.posm.service.PharmacistIdentitySource
import kz.epharm.posm.service.PosmDeviceIdentity
import kz.epharm.posm.service.PosmPharmacistIdentityService
import kz.epharm.posm.service.PosmPharmacistMappingService
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
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
 * Real PostgreSQL: a pharmacist without an IIN in Standard-N types it at the till, HQ confirms, the receipts follow, and
 * the Standard-N directory wins when it later has the person. Synthetic data only.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class IdentityClaimIntegrationTest {
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
    @Autowired private lateinit var reconciler: IdentityClaimDirectoryReconciler
    @Autowired private lateinit var self: PosmSelfService
    @Autowired private lateinit var identity: PosmPharmacistIdentityService
    @Autowired private lateinit var mappingService: PosmPharmacistMappingService
    @Autowired private lateinit var auth: MobileAuthService

    private val pharmacy = "ic-pharmacy"
    private val device = PosmDeviceIdentity("ic-device", pharmacy, legacy = false)
    private val hq = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private val actor get() = AdminPrincipal(hq, "ic-hq@test.invalid", "Синтетический HQ", AdminRole.HQ_HEAD, "TEST")
    private val iinA = "900115300013"
    private val iinB = "850615400016"
    private val iinC = "990303500014"
    private val nameA = "Бузурбаева Лаура Ермековна"

    @BeforeEach fun setup() {
        // This class's own disposable PG16. TRUNCATE, not DELETE: the wallet ledger is append-only and a premium credit
        // in one test writes to it.
        jdbc.execute(
            "TRUNCATE pharmacist_identity_claims, posm_pharmacist_mapping_audit, posm_pharmacist_mappings, pharmacists, " +
                "standardn_pharmacist_directory, sales_premium_pending, posm_cabinet_links CASCADE",
        )
        jdbc.update("DELETE FROM posm_staff_rate_limits")
        jdbc.update("DELETE FROM pharmacies WHERE id IN ('ic-pharmacy', 'ic-other')")
        jdbc.update("DELETE FROM admin_users WHERE id = ?", hq)
        jdbc.update("""INSERT INTO chains(id,name,"group") VALUES ('ic-chain','Synthetic','pilot') ON CONFLICT DO NOTHING""")
        jdbc.update(
            """INSERT INTO pharmacies(id,name,chain_id,chain_name,city,"group",standardn_profile_id,standardn_profile_source)
               VALUES ('ic-pharmacy', 'Synthetic IC pharmacy', 'ic-chain', 'Synthetic', 'Алматы', 'pilot', 7101, 'manual')""",
        )
        jdbc.update(
            """INSERT INTO pharmacies(id,name,chain_id,chain_name,city,"group",standardn_profile_id,standardn_profile_source)
               VALUES ('ic-other', 'Synthetic other pharmacy', 'ic-chain', 'Synthetic', 'Астана', 'pilot', 7102, 'manual')""",
        )
        jdbc.update(
            """INSERT INTO admin_users(id,email,password_hash,name,role,company,status)
               VALUES (?, 'ic-hq@test.invalid', 'unused', 'Синтетический HQ', 'HQ_HEAD', 'TEST', 'active')""",
            hq,
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun idRequest(user: String = "18", iin: String? = iinA, name: String? = nameA, userName: String? = "Бузурбаева Л") =
        PosmIdentityRequest(pharmacy, user, userName, iin, name)

    private fun selfRequest(user: String = "18", phone: String? = null, password: String? = null) =
        PosmSelfRequest(pharmacyId = pharmacy, standardNUserId = user, phone = phone, password = password)

    private fun count(from: String, vararg args: Any): Int = jdbc.queryForObject("SELECT count(*) FROM $from", Int::class.java, *args)!!

    private fun claimId(status: String = "pending"): UUID =
        jdbc.queryForObject("SELECT id FROM pharmacist_identity_claims WHERE status = ? ORDER BY created_at DESC LIMIT 1", UUID::class.java, status)!!

    private fun confirm(iin: String = iinA, name: String = nameA, id: UUID = claimId()) =
        admin.confirm(id, ConfirmIdentityClaimRequest(iin, name), actor)

    private fun card(iin: String) = jdbc.queryForMap("SELECT * FROM pharmacists WHERE iin = ?", iin)

    private fun directoryRow(user: Long, iin: String, name: String, profile: Long = 7101, status: Int = 0) {
        jdbc.update(
            """INSERT INTO standardn_pharmacist_directory(directory_key,source_profile_id,source_user_id,iin,full_name,source_status,profile_name)
               VALUES (?, ?, ?, ?, ?, ?, 'Synthetic')""",
            "$profile:$user", profile, user, iin, name, status,
        )
    }

    private fun cardRow(id: String, iin: String, name: String, status: String = "active", pharmacyId: String? = pharmacy, source: String = "standardn") {
        jdbc.update(
            """INSERT INTO pharmacists(id,name,iin,phone,tier,status,pharmacy_id,pharmacy_name,city,account_source,iin_source,name_source)
               VALUES (?, ?, ?, '', 'Silver', ?, ?, 'Synthetic', 'Алматы', 'standardn', ?, ?)""",
            id, name, iin, status, pharmacyId, source, source,
        )
    }

    private fun receipt(user: String = "18") = identity.resolve(
        pharmacyId = pharmacy, reportedPharmacistId = user, reportedPharmacistName = "Бузурбаева Л", useDirectory = true,
    )

    private fun refused(status: Int, block: () -> Unit): AppException {
        val error = assertThrows(AppException::class.java) { block() }
        assertEquals(status, error.status.value())
        return error
    }

    // ── the till sends, nothing is paid ─────────────────────────────────────

    @Test fun `a pending claim is saved, credits nothing and leaves the receipts of the user unmapped`() {
        assertEquals("unknown", submission.tillState(device, idRequest()).state)
        val sent = submission.tillSubmit(device, idRequest())
        assertEquals("pending", sent.state)
        assertEquals("900115•••••3", sent.iinMasked)
        assertEquals(nameA, sent.fullName)

        val row = jdbc.queryForMap("SELECT * FROM pharmacist_identity_claims")
        assertEquals("pending", row["status"])
        assertEquals("person", row["kind"])
        assertEquals(iinA, row["iin"])
        assertEquals(nameA, row["full_name"])
        assertEquals("18", row["standardn_user_id"])
        assertEquals(7101L, row["standardn_profile_id"])
        assertEquals("ic-device", row["device_id"])
        assertEquals("Бузурбаева Л", row["standardn_user_name"])
        assertNull(row["conflict"])
        assertNull(row["decided_at"])

        // No card, no mapping: the receipts of this Standard-N user still belong to nobody, so no bonus is credited.
        assertEquals(0, count("pharmacists WHERE iin = ?", iinA))
        assertEquals(0, count("posm_pharmacist_mappings WHERE pharmacy_id = ?", pharmacy))
        val resolved = receipt()
        assertEquals("", resolved.pharmacistId)
        assertEquals(PharmacistIdentitySource.STANDARDN_UNMAPPED, resolved.source)
        // The till window still has nobody to show; the new code lets the till tell this case from any other 404.
        val window = refused(404) { self.view(device, selfRequest()) }
        assertEquals(ErrorCode.STANDARDN_USER_UNKNOWN, window.code)
        assertEquals("pending", submission.tillState(device, idRequest()).state)
    }

    @Test fun `a typo corrected before HQ decides rewrites the same open claim, and HQ confirms only what it saw`() {
        submission.tillSubmit(device, idRequest(iin = iinB))
        submission.tillSubmit(device, idRequest(iin = iinA, name = "Бузурбаева Лаура"))
        assertEquals(1, count("pharmacist_identity_claims"))
        val row = jdbc.queryForMap("SELECT iin, full_name FROM pharmacist_identity_claims")
        assertEquals(iinA, row["iin"])
        assertEquals("Бузурбаева Лаура", row["full_name"])
        // The reviewer opened the list when the claim held the first values.
        refused(409) { confirm(iin = iinB, name = nameA) }
        assertEquals(0, count("pharmacists WHERE iin IN (?, ?)", iinA, iinB))
        assertEquals("pending", jdbc.queryForObject("SELECT status FROM pharmacist_identity_claims", String::class.java))
    }

    @Test fun `a claim about a user the directory, a confirmed claim or an HQ mapping already names is refused`() {
        directoryRow(18, iinB, "Другой Человек Иванович")
        refused(409) { submission.tillSubmit(device, idRequest()) }
        jdbc.update("DELETE FROM standardn_pharmacist_directory WHERE source_profile_id = 7101")

        cardRow("ic-mapped", iinC, "Уже Привязанная Карточка")
        mappingService.upsert(UpsertPosmPharmacistMappingRequest(pharmacy, "18", "x", "ic-mapped"), actor)
        refused(409) { submission.tillSubmit(device, idRequest()) }
        assertEquals(0, count("pharmacist_identity_claims"))
    }

    @Test fun `the attempts are counted even when the data is refused, and a sixth attempt is stopped`() {
        repeat(5) { refused(400) { submission.tillSubmit(device, idRequest(iin = "123")) } }
        val limited = refused(429) { submission.tillSubmit(device, idRequest()) }
        assertTrue((limited.retryAfterSeconds ?: 0) > 0)
        assertEquals(0, count("pharmacist_identity_claims"))
        // Another Standard-N user at the same till has a budget of their own.
        assertEquals("pending", submission.tillSubmit(device, idRequest(user = "19", iin = iinB)).state)
    }

    // ── HQ decides ──────────────────────────────────────────────────────────

    @Test fun `HQ confirms, a card of its own appears, the mapping is active with the HQ actor in the audit, the receipts follow`() {
        submission.tillSubmit(device, idRequest())
        val confirmed = confirm()
        assertEquals("confirmed", confirmed.status)
        assertEquals("Синтетический HQ", confirmed.decidedByName)

        val card = card(iinA)
        assertEquals("self", card["account_source"])
        assertEquals("self", card["iin_source"])
        assertEquals("self", card["name_source"])
        assertEquals("active", card["status"])
        assertEquals(nameA, card["name"])
        assertEquals("", card["phone"])
        assertNull(card["password_hash"])
        assertEquals(pharmacy, card["pharmacy_id"])
        assertEquals(0L, card["balance"])

        val mapping = jdbc.queryForMap("SELECT * FROM posm_pharmacist_mappings WHERE pharmacy_id = ?", pharmacy)
        assertEquals("18", mapping["external_user_id"])
        assertEquals(true, mapping["active"])
        assertEquals(card["id"], mapping["pharmacist_id"])
        assertEquals(hq, mapping["created_by"])
        assertEquals("Бузурбаева Л", mapping["external_user_name"])
        val audit = jdbc.queryForMap("SELECT * FROM posm_pharmacist_mapping_audit WHERE mapping_id = ?", mapping["id"])
        assertEquals("created", audit["action"])
        assertEquals(hq, audit["actor_id"])

        val claim = jdbc.queryForMap("SELECT * FROM pharmacist_identity_claims")
        assertEquals("confirmed", claim["status"])
        assertEquals(hq, claim["decided_by"])
        assertNotNull(claim["decided_at"])
        assertEquals(card["id"], claim["pharmacist_id"])
        assertEquals(mapping["id"], claim["mapping_id"])

        // Later receipts of that Standard-N user belong to the card.
        val resolved = receipt()
        assertEquals(card["id"], resolved.pharmacistId)
        assertEquals(PharmacistIdentitySource.STANDARDN_EXPLICIT_MAPPING, resolved.source)
        assertEquals(iinA, resolved.sellerIin)

        // The till window works for the person now, and they can add a phone and a password.
        assertEquals("known", submission.tillState(device, idRequest()).state)
        val view = self.view(device, selfRequest())
        assertEquals(nameA, view.fullName)
        assertEquals("900115•••••3", view.iinMasked)
        assertFalse(view.passwordSet)
        val saved = self.saveContact(device, selfRequest(phone = "8 701 555 12 34", password = "synthetic-pass-1"))
        assertTrue(saved.passwordSet)
        assertEquals(nameA, auth.login(iinA, "synthetic-pass-1").pharmacist.name)
        // A second claim for a user who is known is refused.
        refused(409) { submission.tillSubmit(device, idRequest()) }
    }

    @Test fun `HQ confirms a claim whose IIN already belongs to an active card of the same pharmacy, it attaches and nothing is rewritten`() {
        cardRow("ic-existing", iinA, "Существующая Карточка Иванович")
        submission.tillSubmit(device, idRequest())
        val open = admin.list("pending").single()
        assertEquals(listOf("iin_other_card"), open.conflicts.map { it.code })
        assertEquals("ic-existing", open.conflicts.single().pharmacistId)
        assertEquals("iin_other_card", jdbc.queryForObject("SELECT conflict FROM pharmacist_identity_claims", String::class.java))

        confirm()
        assertEquals(1, count("pharmacists WHERE iin = ?", iinA))
        val card = card(iinA)
        assertEquals("ic-existing", card["id"])
        assertEquals("Существующая Карточка Иванович", card["name"])
        assertEquals("standardn", card["name_source"])
        val mapping = jdbc.queryForMap("SELECT pharmacist_id, active FROM posm_pharmacist_mappings WHERE pharmacy_id = ?", pharmacy)
        assertEquals("ic-existing", mapping["pharmacist_id"])
        assertEquals("ic-existing", receipt().pharmacistId)
    }

    @Test fun `a card that cannot take the user stops the confirmation, and the claim stays open`() {
        for ((label, prepare) in listOf<Pair<String, () -> Unit>>(
            "another pharmacy" to { cardRow("ic-elsewhere", iinA, "Чужая Карточка Иванович", pharmacyId = "ic-other") },
            "blocked" to { cardRow("ic-blocked", iinA, "Заблокированная Карточка Иванович", status = "blocked") },
            "pending" to { cardRow("ic-pending", iinA, "Ожидающая Карточка Иванович", status = "pending", pharmacyId = null) },
        )) {
            jdbc.update("DELETE FROM pharmacist_identity_claims")
            jdbc.update("DELETE FROM pharmacists WHERE id LIKE 'ic-%'")
            prepare()
            submission.tillSubmit(device, idRequest())
            refused(409) { confirm() }
            assertEquals("pending", jdbc.queryForObject("SELECT status FROM pharmacist_identity_claims", String::class.java), label)
            assertEquals(0, count("posm_pharmacist_mappings WHERE pharmacy_id = ?", pharmacy), label)
            jdbc.update("DELETE FROM posm_staff_rate_limits")
        }
    }

    @Test fun `a claim that went stale while it waited is refused, an HQ mapping or the directory came first`() {
        submission.tillSubmit(device, idRequest())
        cardRow("ic-mapped", iinC, "Уже Привязанная Карточка")
        mappingService.upsert(UpsertPosmPharmacistMappingRequest(pharmacy, "18", "x", "ic-mapped"), actor)
        refused(409) { confirm() }
        assertEquals(0, count("pharmacists WHERE iin = ?", iinA))

        jdbc.update("UPDATE posm_pharmacist_mappings SET active = false, revoked_at = now() WHERE pharmacy_id = ?", pharmacy)
        directoryRow(18, iinB, "Другой Человек Иванович")
        refused(409) { confirm() }
        assertEquals(0, count("pharmacists WHERE iin = ?", iinA))
        assertEquals("pending", jdbc.queryForObject("SELECT status FROM pharmacist_identity_claims", String::class.java))
    }

    @Test fun `a decided claim cannot be decided again`() {
        submission.tillSubmit(device, idRequest())
        val id = claimId()
        confirm(id = id)
        refused(409) { confirm(id = id) }
        refused(409) { admin.reject(id, RejectIdentityClaimRequest("Передумали"), actor) }
        refused(404) { confirm(id = UUID.randomUUID()) }
    }

    @Test fun `a rejection keeps the reason for the till, creates nothing, and the person may send again`() {
        submission.tillSubmit(device, idRequest())
        val rejected = admin.reject(claimId(), RejectIdentityClaimRequest("  ИИН не совпадает с документом  "), actor)
        assertEquals("rejected", rejected.status)
        assertEquals("ИИН не совпадает с документом", rejected.decisionReason)
        assertEquals("Синтетический HQ", rejected.decidedByName)
        assertEquals(0, count("pharmacists WHERE iin = ?", iinA))
        assertEquals(0, count("posm_pharmacist_mappings WHERE pharmacy_id = ?", pharmacy))
        assertEquals("", receipt().pharmacistId)

        val state = submission.tillState(device, idRequest())
        assertEquals("rejected", state.state)
        assertEquals("ИИН не совпадает с документом", state.reason)

        assertEquals("pending", submission.tillSubmit(device, idRequest(iin = iinB)).state)
        assertEquals(2, count("pharmacist_identity_claims"))
        assertEquals(listOf("pending", "rejected"), admin.list(null).map { it.status })
    }

    @Test fun `the list shows open claims first, flags a shared IIN and a known directory IIN, and filters by status`() {
        cardRow("ic-existing", iinA, "Существующая Карточка Иванович", pharmacyId = "ic-other")
        directoryRow(5, iinB, "Из Справочника Иванович", profile = 7102)
        submission.tillSubmit(device, idRequest(user = "18", iin = iinA))
        submission.tillSubmit(device, idRequest(user = "19", iin = iinB))
        submission.tillSubmit(device, idRequest(user = "20", iin = iinB, name = "Другая Фамилия Иванович"))
        admin.reject(UUID.fromString(admin.list("pending").first { it.standardNUserId == "19" }.id), RejectIdentityClaimRequest("Нет"), actor)

        val all = admin.list(null)
        assertEquals(listOf("pending", "pending", "rejected"), all.map { it.status })
        val byUser = all.associateBy { it.standardNUserId }
        assertEquals(listOf("iin_other_card"), byUser.getValue("18").conflicts.map { it.code })
        assertEquals(setOf("iin_in_directory"), byUser.getValue("20").conflicts.map { it.code }.toSet())
        assertEquals("Из Справочника Иванович", byUser.getValue("20").conflicts.single().directoryName)
        assertEquals("Synthetic IC pharmacy", byUser.getValue("18").pharmacyName)
        assertEquals(1, admin.list("rejected").size)
        assertEquals(2, admin.list("pending").size)
        refused(400) { admin.list("bogus") }
    }

    @Test fun `an HQ mapping revoked by hand ends the reach of the claim, the till asks again`() {
        submission.tillSubmit(device, idRequest())
        confirm()
        val mappingId = jdbc.queryForObject("SELECT id FROM posm_pharmacist_mappings WHERE pharmacy_id = ?", UUID::class.java, pharmacy)!!
        mappingService.revoke(mappingId, actor)
        assertEquals("", receipt().pharmacistId)
        assertEquals(ErrorCode.STANDARDN_USER_UNKNOWN, refused(404) { self.view(device, selfRequest()) }.code)
        assertEquals("unknown", submission.tillState(device, idRequest()).state)
    }

    @Test fun `a sales premium waiting for the IIN is credited to the card HQ's confirmation creates`() {
        jdbc.update("INSERT INTO sales_premium_pending(iin, period, amount_kzt, through) VALUES (?, '2026-11', 15000, DATE '2026-11-25')", iinA)
        submission.tillSubmit(device, idRequest())
        confirm()
        // The hook runs on its own thread after the commit; the sweep would do it within ten minutes anyway.
        val deadline = System.nanoTime() + 15_000_000_000L
        while (count("sales_premium_pending WHERE iin = ?", iinA) > 0 && System.nanoTime() < deadline) Thread.sleep(100)
        assertEquals(0, count("sales_premium_pending WHERE iin = ?", iinA))
        assertEquals(15000L, jdbc.queryForObject("SELECT balance FROM pharmacists WHERE iin = ?", Long::class.java, iinA))
    }

    // ── the directory wins ──────────────────────────────────────────────────

    @Test fun `the directory brings the same IIN, the claim yields, the card shows the directory data, a second run changes nothing`() {
        submission.tillSubmit(device, idRequest(name = "Бузурбаева Лаура"))
        confirm(name = "Бузурбаева Лаура")
        directoryRow(18, iinA, "Бузурбаева Лаура Ермековна")

        val result = reconciler.reconcile()
        assertEquals(1, result.superseded)
        assertEquals(0, result.flagged)
        assertEquals(0, result.mappingsRevoked)
        val claim = jdbc.queryForMap("SELECT status, decision_reason, decided_by, conflict FROM pharmacist_identity_claims")
        assertEquals("superseded", claim["status"])
        assertEquals("directory_same_iin", claim["decision_reason"])
        assertNull(claim["decided_by"])
        assertNull(claim["conflict"])
        val card = card(iinA)
        assertEquals("Бузурбаева Лаура Ермековна", card["name"])
        assertEquals("standardn", card["name_source"])
        assertEquals("standardn", card["iin_source"])
        // The mapping stays: it points at the same person as the directory.
        assertEquals(1, count("posm_pharmacist_mappings WHERE pharmacy_id = ? AND active", pharmacy))
        // The till window now goes through the directory and finds the same card.
        assertEquals(card["id"], jdbc.queryForObject("SELECT id FROM pharmacists WHERE iin = ?", String::class.java, iinA))
        assertEquals("Бузурбаева Лаура Ермековна", self.view(device, selfRequest()).fullName)
        assertEquals(1, count("pharmacists WHERE iin = ?", iinA))

        assertEquals(IdentityReconcileResult(), reconciler.reconcile())
    }

    @Test fun `the directory brings another IIN, it is flagged for HQ, the claim mapping is switched off, receipts follow the directory, nothing is merged`() {
        submission.tillSubmit(device, idRequest())
        confirm()
        val claimCard = card(iinA)
        directoryRow(18, iinB, "Другой Человек Иванович")

        val result = reconciler.reconcile()
        assertEquals(1, result.superseded)
        assertEquals(1, result.flagged)
        assertEquals(1, result.mappingsRevoked)
        val claim = jdbc.queryForMap("SELECT status, conflict, directory_iin, decided_by FROM pharmacist_identity_claims")
        assertEquals("superseded", claim["status"])
        assertEquals("directory_iin_differs", claim["conflict"])
        assertEquals(iinB, claim["directory_iin"])
        assertNull(claim["decided_by"])

        val mapping = jdbc.queryForMap("SELECT id, active, revoked_at FROM posm_pharmacist_mappings WHERE pharmacy_id = ?", pharmacy)
        assertEquals(false, mapping["active"])
        assertNotNull(mapping["revoked_at"])
        val audit = jdbc.queryForList("SELECT action, actor_id FROM posm_pharmacist_mapping_audit WHERE mapping_id = ? ORDER BY id", mapping["id"])
        assertEquals(listOf("created", "revoked"), audit.map { it["action"] })
        assertNull(audit.last()["actor_id"])

        // The self-made card is untouched; the directory person has a card of their own, the two are not merged.
        assertEquals(claimCard["id"], card(iinA)["id"])
        assertEquals(claimCard["name"], card(iinA)["name"])
        val resolved = receipt()
        assertEquals(PharmacistIdentitySource.STANDARDN_DIRECTORY, resolved.source)
        assertEquals(iinB, resolved.sellerIin)
        assertNotEquals(claimCard["id"], resolved.pharmacistId)
        assertEquals(iinB, jdbc.queryForObject("SELECT iin FROM pharmacists WHERE id = ?", String::class.java, resolved.pharmacistId))

        // HQ sees the contradiction in the list.
        val flagged = admin.list("superseded").single()
        assertEquals(listOf("directory_iin_differs"), flagged.conflicts.map { it.code })
        assertEquals(iinB, flagged.conflicts.single().directoryIin)
        assertEquals(iinA, flagged.iin)
    }

    @Test fun `an open claim yields to the directory too, the same IIN just closes it, another one flags it and creates no card`() {
        submission.tillSubmit(device, idRequest(user = "18", iin = iinA))
        submission.tillSubmit(device, idRequest(user = "19", iin = iinB))
        directoryRow(18, iinA, "Бузурбаева Лаура Ермековна")
        directoryRow(19, iinC, "Другой Человек Иванович")

        val result = reconciler.reconcile()
        assertEquals(2, result.superseded)
        assertEquals(1, result.flagged)
        assertEquals(0, result.mappingsRevoked)
        val byUser = jdbc.queryForList("SELECT standardn_user_id, status, conflict FROM pharmacist_identity_claims").associateBy { it["standardn_user_id"] }
        assertNull(byUser.getValue("18")["conflict"])
        assertEquals("directory_iin_differs", byUser.getValue("19")["conflict"])
        assertEquals(0, count("pharmacists WHERE iin IN (?, ?)", iinA, iinB))
        assertEquals(0, count("posm_pharmacist_mappings WHERE pharmacy_id = ?", pharmacy))
        assertEquals(0, admin.list("pending").size)
    }

    @Test fun `a claim of a pharmacy without a Standard-N profile link is never matched against the directory`() {
        jdbc.update("UPDATE pharmacies SET standardn_profile_id = NULL, standardn_profile_source = NULL WHERE id = 'ic-pharmacy'")
        submission.tillSubmit(device, idRequest())
        assertNull(jdbc.queryForObject("SELECT standardn_profile_id FROM pharmacist_identity_claims", Long::class.javaObjectType))
        directoryRow(18, iinB, "Другой Человек Иванович")
        assertEquals(IdentityReconcileResult(), reconciler.reconcile())
        assertEquals("pending", jdbc.queryForObject("SELECT status FROM pharmacist_identity_claims", String::class.java))
    }

    // ── a full name for a card whose name is cut short (cabinet) ────────────

    @Test fun `a card with a one-word name proposes a full name, HQ confirms, the card shows it as the pharmacist's own`() {
        cardRow("ic-short", iinA, "Бузурбаева")
        var state = submission.cabinetState("ic-short")
        assertTrue(state.canPropose)
        assertEquals("standardn", state.nameSource)

        state = submission.cabinetPropose("ic-short", "  Бузурбаева  Лаура Ермековна ")
        assertFalse(state.canPropose)
        assertEquals("pending", state.claim!!.status)
        assertEquals("Бузурбаева", state.name)
        val open = admin.list("pending").single()
        assertEquals("name", open.kind)
        assertEquals(nameA, open.fullName)
        assertEquals("Бузурбаева", open.pharmacistName)
        assertEquals(emptyList<IdentityClaimConflictDto>(), open.conflicts)

        // A second proposal rewrites the open one, it does not pile up.
        submission.cabinetPropose("ic-short", "Бузурбаева Лаура")
        assertEquals(1, count("pharmacist_identity_claims"))
        refused(409) { confirm(name = nameA) }

        val confirmed = confirm(name = "Бузурбаева Лаура")
        assertEquals("confirmed", confirmed.status)
        val card = card(iinA)
        assertEquals("Бузурбаева Лаура", card["name"])
        assertEquals("self", card["name_source"])
        assertEquals("standardn", card["iin_source"])
        // A name proposal creates no mapping and moves no receipts.
        assertEquals(0, count("posm_pharmacist_mappings WHERE pharmacy_id = ?", pharmacy))
        state = submission.cabinetState("ic-short")
        assertFalse(state.nameIncomplete)
        assertFalse(state.canPropose)
        assertEquals("self", state.nameSource)
    }

    @Test fun `a rejected proposal comes back with its reason and may be sent again`() {
        cardRow("ic-short", iinA, "Бузурбаева")
        submission.cabinetPropose("ic-short", nameA)
        admin.reject(claimId(), RejectIdentityClaimRequest("Напишите имя как в документе"), actor)
        val state = submission.cabinetState("ic-short")
        assertTrue(state.canPropose)
        assertEquals("rejected", state.claim!!.status)
        assertEquals("Напишите имя как в документе", state.claim!!.reason)
        submission.cabinetPropose("ic-short", nameA)
        assertEquals(2, count("pharmacist_identity_claims"))
    }

    @Test fun `the directory full name wins over a proposal pending or confirmed, and a name HQ typed stays`() {
        cardRow("ic-short", iinA, "Бузурбаева")
        submission.cabinetPropose("ic-short", "Бузурбаева Лаура")
        // The directory still has one word: nothing yields.
        directoryRow(30, iinA, "Бузурбаева")
        assertEquals(IdentityReconcileResult(), reconciler.reconcile())
        assertEquals("pending", jdbc.queryForObject("SELECT status FROM pharmacist_identity_claims", String::class.java))

        jdbc.update("UPDATE standardn_pharmacist_directory SET full_name = 'Бузурбаева Лаура Ермековна' WHERE source_user_id = 30")
        val result = reconciler.reconcile()
        assertEquals(1, result.superseded)
        assertEquals(1, result.namesTaken)
        assertEquals("directory_name", jdbc.queryForObject("SELECT decision_reason FROM pharmacist_identity_claims", String::class.java))
        assertEquals("Бузурбаева Лаура Ермековна", card(iinA)["name"])
        assertEquals("standardn", card(iinA)["name_source"])

        // A confirmed proposal yields the same way.
        jdbc.update("DELETE FROM pharmacist_identity_claims")
        jdbc.update("UPDATE pharmacists SET name = 'Бузурбаева', name_source = 'standardn' WHERE id = 'ic-short'")
        jdbc.update("UPDATE standardn_pharmacist_directory SET full_name = 'Бузурбаева' WHERE source_user_id = 30")
        submission.cabinetPropose("ic-short", "Бузурбаева Лаура")
        confirm(name = "Бузурбаева Лаура")
        assertEquals("self", card(iinA)["name_source"])
        jdbc.update("UPDATE standardn_pharmacist_directory SET full_name = 'Бузурбаева Лаура Ермековна' WHERE source_user_id = 30")
        reconciler.reconcile()
        assertEquals("Бузурбаева Лаура Ермековна", card(iinA)["name"])
        assertEquals("standardn", card(iinA)["name_source"])

        // A name HQ typed by hand is never rewritten.
        jdbc.update("DELETE FROM pharmacist_identity_claims")
        jdbc.update("UPDATE pharmacists SET name = 'Бузурбаева', name_source = 'hq' WHERE id = 'ic-short'")
        jdbc.update("UPDATE standardn_pharmacist_directory SET full_name = 'Бузурбаева Лаура Ермековна' WHERE source_user_id = 30")
        jdbc.update(
            "INSERT INTO pharmacist_identity_claims(id, kind, pharmacy_id, pharmacist_id, iin, full_name) VALUES (?, 'name', ?, 'ic-short', ?, 'Бузурбаева Лаура')",
            UUID.randomUUID(), pharmacy, iinA,
        )
        reconciler.reconcile()
        assertEquals("Бузурбаева", card(iinA)["name"])
        assertEquals("hq", card(iinA)["name_source"])
    }

    @Test fun `when the directory already has the full name the card takes it at once and nothing is asked`() {
        cardRow("ic-short", iinA, "Бузурбаева")
        directoryRow(31, iinA, "Бузурбаева Лаура Ермековна")
        val state = submission.cabinetPropose("ic-short", "Бузурбаева Лаура")
        assertEquals("Бузурбаева Лаура Ермековна", state.name)
        assertEquals("standardn", state.nameSource)
        assertFalse(state.nameIncomplete)
        assertEquals(0, count("pharmacist_identity_claims"))
    }

    @Test fun `a card with a full name, a blocked card and a card of the registration without an IIN cannot propose`() {
        cardRow("ic-full", iinA, "Бузурбаева Лаура")
        refused(409) { submission.cabinetPropose("ic-full", nameA) }
        cardRow("ic-blocked", iinB, "Заблокированная", status = "blocked")
        refused(409) { submission.cabinetPropose("ic-blocked", nameA) }
        assertEquals(0, count("pharmacist_identity_claims"))
    }

    // ── what the pharmacist card shows ──────────────────────────────────────

    @Test fun `cards say where the IIN and the name came from`() {
        // From the directory: created on first sight.
        directoryRow(40, iinB, "Из Справочника Иванович")
        self.view(device, selfRequest(user = "40"))
        assertEquals("standardn", card(iinB)["iin_source"])
        assertEquals("standardn", card(iinB)["name_source"])
        assertEquals("standardn", card(iinB)["account_source"])
        // From a claim HQ confirmed.
        submission.tillSubmit(device, idRequest())
        confirm()
        assertEquals("self", card(iinA)["account_source"])
        assertEquals("self", card(iinA)["iin_source"])
        // A card written the old way (no source named) says registration, as before V126.
        jdbc.update(
            """INSERT INTO pharmacists(id,name,iin,phone,tier,status,pharmacy_id,pharmacy_name,city)
               VALUES ('ic-plain', 'Старая Регистрация Иванович', ?, '', 'Silver', 'active', ?, 'Synthetic', 'Алматы')""",
            iinC, pharmacy,
        )
        assertEquals("registration", card(iinC)["iin_source"])
        assertEquals("registration", card(iinC)["name_source"])
        assertEquals("registration", card(iinC)["account_source"])
    }
}
