package kz.epharm.finance.premium

import com.fasterxml.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.domain.AdminUserStatus
import kz.epharm.auth.dto.LoginRequest
import kz.epharm.auth.dto.LoginResponse
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.auth.security.AdminPrincipal
import kz.epharm.finance.service.PharmacistWalletService
import kz.epharm.finance.service.WalletCreditSource
import kz.epharm.pharmacies.entity.ChainEntity
import kz.epharm.pharmacies.entity.PharmacyEntity
import kz.epharm.pharmacies.entity.PharmacyGroup
import kz.epharm.pharmacies.repository.ChainRepository
import kz.epharm.pharmacies.repository.PharmacyRepository
import kz.epharm.pharmacists.dto.ActivatePharmacistRequest
import kz.epharm.pharmacists.entity.PharmacistEntity
import kz.epharm.pharmacists.entity.PharmacistStatus
import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.pharmacists.service.PharmacistService
import kz.epharm.posm.self.PosmSelfRequest
import kz.epharm.posm.self.PosmSelfService
import kz.epharm.posm.self.StandardNAccountService
import kz.epharm.posm.service.PosmDeviceIdentity
import kz.epharm.posm.service.StandardNDirectoryPerson
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Sales premium from Standard-N (26.11.2026): cumulative month-to-date figures per IIN are credited to the wallet
 * by the difference to the ledger. Every test works in its own month, because the audit table keeps the cut-offs.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class SalesPremiumIntegrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("premium_test").withUsername("epharm").withPassword("test-only")
            .apply { start() }

        private const val URL = "/api/admin/finance/sales-premium/import"
        private val personNumber = AtomicLong(900_000_000_000)
        private val adminNumber = AtomicLong(0)

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
        }
    }

    @Autowired private lateinit var service: SalesPremiumService
    @Autowired private lateinit var credits: SalesPremiumPendingCredits
    @Autowired private lateinit var sweep: SalesPremiumSweep
    @Autowired private lateinit var wallet: PharmacistWalletService
    @Autowired private lateinit var pharmacistService: PharmacistService
    @Autowired private lateinit var selfService: PosmSelfService
    @Autowired private lateinit var accounts: StandardNAccountService
    @Autowired private lateinit var people: PharmacistRepository
    @Autowired private lateinit var chains: ChainRepository
    @Autowired private lateinit var pharmacies: PharmacyRepository
    @Autowired private lateinit var admins: AdminUserRepository
    @Autowired private lateinit var encoder: PasswordEncoder
    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var jdbc: JdbcTemplate

    private val actor = AdminPrincipal(UUID.randomUUID(), "hq@example.test", "HQ", AdminRole.HQ_HEAD, "Inkar")

    // ---- dry run ------------------------------------------------------------------------------------------------

    @Test
    fun `a dry run writes nothing and shows exactly what the real import then does`() {
        val active = person(opening = 100)
        val notActive = person(status = PharmacistStatus.pending)
        val unknown = nextIin()
        val rows = listOf(row(active.iin, 500, 5.0), row(notActive.iin, 300, 3.0), row(unknown, 200, 2.0))

        val preview = service.preview(request("2025-01", "2025-01-24", dryRun = true, rows))

        assertThat(preview.dryRun).isTrue()
        assertThat(preview.auditId).isNull()
        assertThat(preview.rows).isEqualTo(PremiumTotals(3, 1000))
        assertThat(preview.matched).isEqualTo(PremiumTotals(1, 500))
        assertThat(preview.credited).isEqualTo(PremiumTotals(1, 500))
        assertThat(preview.pending).isEqualTo(PremiumPending(2, 500, notActiveCount = 1))
        assertThat(preview.ambiguous).isEqualTo(PremiumAmbiguous(0, 0, emptyList()))
        assertThat(preview.packsTotal).isEqualByComparingTo(BigDecimal("10"))
        assertThat(balance(active.id)).isEqualTo(100)
        assertThat(entries(active.id)).isZero()
        assertThat(pendingRows("2025-01")).isZero()
        assertThat(importRows("2025-01")).isZero()

        val real = service.apply(request("2025-01", "2025-01-24", dryRun = false, rows), actor)

        assertThat(real.dryRun).isFalse()
        assertThat(real.auditId).isNotNull()
        assertThat(real.copy(dryRun = true, auditId = null)).isEqualTo(preview)
    }

    // ---- the first import --------------------------------------------------------------------------------------

    @Test
    fun `the first import credits the wallet as earned money and keeps the waiting sellers and one audit row`() {
        val seller = person(opening = 100)
        val unknown = nextIin()

        val result = service.apply(request("2025-02", "2025-02-24", false, listOf(row(seller.iin, 500, 5.0), row(unknown, 200, 2.0))), actor)

        assertThat(result.matched).isEqualTo(PremiumTotals(1, 500))
        assertThat(result.credited).isEqualTo(PremiumTotals(1, 500))
        assertThat(result.pending).isEqualTo(PremiumPending(1, 200, notActiveCount = 0))
        assertThat(balance(seller.id)).isEqualTo(600)
        assertThat(people.findById(seller.id).orElseThrow().earned30d).isEqualTo(500)
        val entry = jdbc.queryForMap("SELECT * FROM pharmacist_wallet_entries WHERE pharmacist_id=?", seller.id)
        assertThat(entry["source"]).isEqualTo("sales_premium")
        assertThat(entry["source_id"]).isEqualTo("premium:2025-02:${seller.id}:2025-02-24")
        assertThat(entry["amount_kzt"]).isEqualTo(500L)
        assertThat(entry["earned_delta_kzt"]).isEqualTo(500L)
        assertThat(entry["context_key"]).isEqualTo("premium:2025-02")
        assertThat(entry["balance_after_kzt"]).isEqualTo(600L)
        // The balance before the first movement is kept as the opening checkpoint, not invented as history.
        assertThat(jdbc.queryForObject("SELECT balance_kzt FROM pharmacist_wallet_checkpoints WHERE pharmacist_id=?",
            Long::class.java, seller.id)).isEqualTo(100)

        val waiting = jdbc.queryForMap("SELECT * FROM sales_premium_pending WHERE iin=? AND period='2025-02'", unknown)
        assertThat(waiting["amount_kzt"]).isEqualTo(200L)
        assertThat(waiting["through"].toString()).isEqualTo("2025-02-24")

        val audit = jdbc.queryForMap("SELECT * FROM sales_premium_imports WHERE period='2025-02'")
        assertThat(audit["id"].toString()).isEqualTo(result.auditId)
        assertThat(audit["actor_id"]).isEqualTo(actor.userId)
        assertThat(audit["actor_role"]).isEqualTo("HQ_HEAD")
        assertThat(audit["rows_count"]).isEqualTo(2)
        assertThat(audit["payload_sha256"].toString()).matches("[0-9a-f]{64}")
        // The audit row names no seller: neither IIN appears in it.
        val auditText = audit.values.joinToString(" ")
        assertThat(auditText).doesNotContain(seller.iin).doesNotContain(unknown)
        assertThat(jdbc.queryForObject("SELECT summary->'credited'->>'sumKzt' FROM sales_premium_imports WHERE period='2025-02'",
            String::class.java)).isEqualTo("500")
    }

    // ---- idempotence and the next days --------------------------------------------------------------------------

    @Test
    fun `sending the same file again credits nothing and writes one more audit row`() {
        val seller = person()
        val file = request("2025-03", "2025-03-20", false, listOf(row(seller.iin, 900)))

        service.apply(file, actor)
        val again = service.apply(file, actor)

        assertThat(again.credited).isEqualTo(PremiumTotals(0, 0))
        assertThat(again.unchanged).isEqualTo(1)
        assertThat(again.matched).isEqualTo(PremiumTotals(1, 900))
        assertThat(entries(seller.id)).isEqualTo(1)
        assertThat(balance(seller.id)).isEqualTo(900)
        assertThat(importRows("2025-03")).isEqualTo(2)
    }

    @Test
    fun `a later cut-off credits only the growth and a changed figure for the same cut-off gets its own entry`() {
        val seller = person()

        service.apply(request("2025-04", "2025-04-24", false, listOf(row(seller.iin, 500))), actor)
        service.apply(request("2025-04", "2025-04-25", false, listOf(row(seller.iin, 800))), actor)
        // The source corrected the figure of the same day: 900 instead of 800.
        service.apply(request("2025-04", "2025-04-25", false, listOf(row(seller.iin, 900))), actor)

        assertThat(balance(seller.id)).isEqualTo(900)
        assertThat(jdbc.queryForList(
            "SELECT source_id || '=' || amount_kzt FROM pharmacist_wallet_entries WHERE pharmacist_id=? ORDER BY created_at, source_id",
            String::class.java, seller.id,
        )).containsExactly(
            "premium:2025-04:${seller.id}:2025-04-24=500",
            "premium:2025-04:${seller.id}:2025-04-25=300",
            "premium:2025-04:${seller.id}:2025-04-25#2=100",
        )
        assertThat(people.findById(seller.id).orElseThrow().earned30d).isEqualTo(900)
    }

    @Test
    fun `a cut-off older than one already loaded is refused whole and changes nothing`() {
        val seller = person()
        service.apply(request("2025-05", "2025-05-25", false, listOf(row(seller.iin, 1000))), actor)

        for (preview in listOf(true, false)) {
            val older = request("2025-05", "2025-05-24", dryRun = preview, listOf(row(seller.iin, 500)))
            assertThatThrownBy { if (preview) service.preview(older) else service.apply(older, actor) }
                .isInstanceOfSatisfying(AppException::class.java) { error ->
                    assertThat(error.code).isEqualTo(ErrorCode.CONFLICT)
                    assertThat(error.status).isEqualTo(HttpStatus.CONFLICT)
                    assertThat(error.message).contains("2025-05-24").contains("2025-05-25")
                }
        }
        assertThat(balance(seller.id)).isEqualTo(1000)
        assertThat(entries(seller.id)).isEqualTo(1)
        assertThat(importRows("2025-05")).isEqualTo(1)
    }

    // ---- returns ------------------------------------------------------------------------------------------------

    @Test
    fun `a return lowers earned and balance but never below zero and the uncovered rest is tried again`() {
        val seller = person()
        service.apply(request("2025-06", "2025-06-20", false, listOf(row(seller.iin, 1000))), actor)
        // The whole balance goes into a payout, then 200 arrives from elsewhere.
        wallet.reserveForPayout(seller.id, "poi_premium_${seller.id}")
        wallet.credit(WalletCreditSource.TRAINING_BONUS, "assignment-${seller.id}", seller.id, 200, "assignment:test")
        assertThat(balance(seller.id)).isEqualTo(200)

        // A return: the source now says 700, so 300 is wanted back, 200 is on the balance.
        val first = service.apply(request("2025-06", "2025-06-21", false, listOf(row(seller.iin, 700))), actor)

        assertThat(first.corrections).isEqualTo(PremiumCorrections(count = 1, appliedKzt = 200, uncoveredKzt = 100))
        assertThat(first.credited).isEqualTo(PremiumTotals(0, 0))
        assertThat(balance(seller.id)).isZero()
        val correction = jdbc.queryForMap("SELECT * FROM pharmacist_wallet_entries WHERE pharmacist_id=? AND source='sales_premium_correction'", seller.id)
        assertThat(correction["amount_kzt"]).isEqualTo(-200L)
        assertThat(correction["earned_delta_kzt"]).isEqualTo(-200L)
        assertThat(correction["context_key"]).isEqualTo("premium:2025-06")
        assertThat(correction["balance_after_kzt"]).isEqualTo(0L)

        // Nothing on the balance: the same figure writes no entry and reports the whole rest.
        val second = service.apply(request("2025-06", "2025-06-22", false, listOf(row(seller.iin, 700))), actor)
        assertThat(second.corrections).isEqualTo(PremiumCorrections(count = 1, appliedKzt = 0, uncoveredKzt = 100))
        assertThat(entries(seller.id)).isEqualTo(4)

        // Money arrives, the next import takes the rest back.
        wallet.credit(WalletCreditSource.MERCH_TASK, "dispatch-${seller.id}", seller.id, 500, "approved:test")
        val third = service.apply(request("2025-06", "2025-06-23", false, listOf(row(seller.iin, 700))), actor)
        assertThat(third.corrections).isEqualTo(PremiumCorrections(count = 1, appliedKzt = 100, uncoveredKzt = 0))
        assertThat(balance(seller.id)).isEqualTo(400)
        val fourth = service.apply(request("2025-06", "2025-06-24", false, listOf(row(seller.iin, 700))), actor)
        assertThat(fourth.unchanged).isEqualTo(1)
        assertThat(fourth.corrections).isEqualTo(PremiumCorrections(0, 0, 0))
    }

    @Test
    fun `a return that the balance covers is taken back in full and a dry run predicts it`() {
        val seller = person()
        service.apply(request("2025-07", "2025-07-20", false, listOf(row(seller.iin, 1000))), actor)

        val preview = service.preview(request("2025-07", "2025-07-21", true, listOf(row(seller.iin, 400))))
        assertThat(preview.corrections).isEqualTo(PremiumCorrections(1, 600, 0))
        assertThat(balance(seller.id)).isEqualTo(1000)

        val real = service.apply(request("2025-07", "2025-07-21", false, listOf(row(seller.iin, 400))), actor)
        assertThat(real.corrections).isEqualTo(PremiumCorrections(1, 600, 0))
        assertThat(balance(seller.id)).isEqualTo(400)
        assertThat(people.findById(seller.id).orElseThrow().earned30d).isEqualTo(400)
    }

    // ---- sellers without an active card ------------------------------------------------------------------------

    @Test
    fun `a seller without a card waits with the latest figure and is credited when the card appears`() {
        val iin = nextIin()
        service.apply(request("2025-08", "2025-08-19", false, listOf(row(iin, 1000))), actor)
        service.apply(request("2025-08", "2025-08-20", false, listOf(row(iin, 1200))), actor)

        assertThat(pendingRows("2025-08")).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT amount_kzt FROM sales_premium_pending WHERE iin=?", Long::class.java, iin)).isEqualTo(1200)

        val card = person(iin = iin)
        val credited = credits.creditFor(card.id)

        assertThat(credited).isEqualTo(PendingCreditResult(1, 1200, 0))
        assertThat(balance(card.id)).isEqualTo(1200)
        assertThat(jdbc.queryForObject("SELECT source_id FROM pharmacist_wallet_entries WHERE pharmacist_id=?", String::class.java, card.id))
            .isEqualTo("premium:2025-08:${card.id}:2025-08-20")
        assertThat(pendingRows("2025-08")).isZero()
        // Nothing is credited twice.
        assertThat(credits.creditFor(card.id)).isEqualTo(PendingCreditResult(0, 0, 0))
        assertThat(entries(card.id)).isEqualTo(1)

        // From now on the seller is an ordinary matched row and only the growth is credited.
        val next = service.apply(request("2025-08", "2025-08-21", false, listOf(row(iin, 1500))), actor)
        assertThat(next.matched).isEqualTo(PremiumTotals(1, 1500))
        assertThat(next.credited).isEqualTo(PremiumTotals(1, 300))
        assertThat(balance(card.id)).isEqualTo(1500)
    }

    @Test
    fun `a zero figure owes nothing and clears the waiting row`() {
        val iin = nextIin()
        service.apply(request("2025-09", "2025-09-19", false, listOf(row(iin, 700))), actor)
        assertThat(pendingRows("2025-09")).isEqualTo(1)

        val cleared = service.apply(request("2025-09", "2025-09-20", false, listOf(row(iin, 0))), actor)

        assertThat(cleared.pending).isEqualTo(PremiumPending(1, 0, 0))
        assertThat(pendingRows("2025-09")).isZero()
    }

    @Test
    fun `a card that is not active waits too and the activation credits it after the commit`() {
        val chain = chains.save(ChainEntity(id = "premium-chain", name = "Premium", color = "#2A2BE2", points = 1).also { it.group = PharmacyGroup.pilot })
        pharmacies.save(PharmacyEntity(id = "ph_premium", name = "Premium 1", chainId = chain.id, chainName = chain.name, city = "Алматы")
            .also { it.group = PharmacyGroup.pilot })
        val card = person(status = PharmacistStatus.pending)

        val held = service.apply(request("2025-10", "2025-10-20", false, listOf(row(card.iin, 400))), actor)

        assertThat(held.pending).isEqualTo(PremiumPending(1, 400, notActiveCount = 1))
        assertThat(held.matched.count).isZero()
        assertThat(balance(card.id)).isZero()

        pharmacistService.activate(card.id, ActivatePharmacistRequest("ph_premium"))

        eventually("the waiting premium is credited after the activation") { balance(card.id) == 400L }
        assertThat(pendingRows("2025-10")).isZero()
        assertThat(jdbc.queryForObject("SELECT context_key FROM pharmacist_wallet_entries WHERE pharmacist_id=?", String::class.java, card.id))
            .isEqualTo("premium:2025-10")
    }

    @Test
    fun `a pending registration taken over by the Standard-N directory person is credited with the waiting premium after the commit`() {
        val pharmacyId = "prem-adopt-pharmacy"
        jdbc.update("""INSERT INTO chains(id,name,"group") VALUES ('prem-adopt-chain','Synthetic','pilot') ON CONFLICT DO NOTHING""")
        jdbc.update(
            """INSERT INTO pharmacies(id,name,chain_id,chain_name,city,"group",standardn_profile_id,standardn_profile_source)
               VALUES (?, 'Synthetic adopt pharmacy', 'prem-adopt-chain', 'Synthetic', 'Алматы', 'pilot', 7101, 'manual') ON CONFLICT DO NOTHING""",
            pharmacyId,
        )
        val registration = person(status = PharmacistStatus.pending)
        service.apply(request("2026-06", "2026-06-20", false, listOf(row(registration.iin, 900, 9.0))), actor)
        assertThat(pendingRows("2026-06")).isEqualTo(1)
        assertThat(balance(registration.id)).isZero()

        val card = accounts.ensure(StandardNDirectoryPerson(registration.iin, "Синтетикова Айгерим Нурлановна", 0), pharmacyId)!!

        assertThat(card.id).isEqualTo(registration.id)
        eventually("the waiting premium is credited after the takeover") { balance(registration.id) == 900L }
        assertThat(pendingRows("2026-06")).isZero()
    }

    @Test
    fun `the sweep credits an active card that no hook told about`() {
        val iin = nextIin()
        service.apply(request("2025-11", "2025-11-20", false, listOf(row(iin, 750))), actor)
        val card = person(iin = iin)

        assertThat(sweep.sweepOnce()).isGreaterThanOrEqualTo(1)

        assertThat(balance(card.id)).isEqualTo(750)
        assertThat(pendingRows("2025-11")).isZero()
        assertThat(entries(card.id)).isEqualTo(1)
    }

    @Test
    fun `a seller first seen at a till is credited and the till window names the premium and counts it as earned`() {
        val iin = nextIin()
        jdbc.update("""INSERT INTO chains(id,name,"group") VALUES ('prem-till-chain','Synthetic','pilot') ON CONFLICT DO NOTHING""")
        jdbc.update(
            """INSERT INTO pharmacies(id,name,chain_id,chain_name,city,"group",standardn_profile_id,standardn_profile_source)
               VALUES ('prem-till-pharmacy','Synthetic till pharmacy','prem-till-chain','Synthetic','Алматы','pilot',7101,'manual')""",
        )
        jdbc.update(
            """INSERT INTO standardn_pharmacist_directory(directory_key,source_profile_id,source_user_id,iin,full_name,source_status,profile_name)
               VALUES ('7101:34',7101,34,?,'Синтетикова Айгерим Нурлановна',0,'Synthetic till pharmacy')""", iin,
        )
        val device = PosmDeviceIdentity("prem-till-device", "prem-till-pharmacy", legacy = false)
        val till = PosmSelfRequest(pharmacyId = "prem-till-pharmacy", standardNUserId = "34", link = false)
        // The premium arrives before the person has a card: it waits.
        service.apply(request("2024-04", "2024-04-20", false, listOf(row(iin, 1200))), actor)
        assertThat(pendingRows("2024-04")).isEqualTo(1)

        // First sight at the till creates the card from the directory; the hook credits what waited.
        val first = selfService.view(device, till)
        assertThat(first.fullName).isEqualTo("Синтетикова Айгерим Нурлановна")
        eventually("the card created at the till gets its waiting premium") { people.findByIin(iin)?.balance == 1200L }

        val credited = selfService.view(device, till)
        assertThat(credited.balanceKzt).isEqualTo(1200)
        assertThat(credited.earnedMonthKzt).isEqualTo(1200)
        assertThat(credited.recent.single().kind).isEqualTo("sales_premium")
        assertThat(credited.recent.single().bonusKzt).isEqualTo(1200)
        assertThat(credited.recent.single().label).isEqualTo("Премия за продажи, апрель 2024")

        // A return takes 200 back: earned and balance fall, the list of credits still shows the credit only.
        service.apply(request("2024-04", "2024-04-21", false, listOf(row(iin, 1000))), actor)
        val corrected = selfService.view(device, till)
        assertThat(corrected.balanceKzt).isEqualTo(1000)
        assertThat(corrected.earnedMonthKzt).isEqualTo(1000)
        assertThat(corrected.recent).hasSize(1)
    }

    // ---- concurrency --------------------------------------------------------------------------------------------

    @Test
    fun `identical imports at the same time credit every seller exactly once`() {
        val sellers = (1..3).map { person(opening = 10) }
        val file = request("2025-12", "2025-12-20", false, sellers.mapIndexed { index, seller -> row(seller.iin, 100L * (index + 1)) })

        val results = parallel((1..4).map { { service.apply(file, actor) } })

        assertThat(results.sumOf { it.credited.sumKzt }).isEqualTo(600)
        assertThat(results.count { it.credited.count > 0 }).isEqualTo(1)
        sellers.forEachIndexed { index, seller ->
            assertThat(entries(seller.id)).isEqualTo(1)
            assertThat(balance(seller.id)).isEqualTo(10 + 100L * (index + 1))
        }
        assertThat(importRows("2025-12")).isEqualTo(4)
    }

    // ---- the HTTP surface ---------------------------------------------------------------------------------------

    @Test
    fun `only the head of the HQ and the system administrator may import`() {
        val seller = person()
        val body = json("2024-01", "2024-01-20", dryRun = true, listOf(row(seller.iin, 500, 5.0)))

        for (role in listOf(AdminRole.FINANCE_REVIEWER, AdminRole.BRAND_MANAGER, AdminRole.TRAINING_MANAGER)) {
            mockMvc.perform(post(URL).header("Authorization", bearer(role)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
        }
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized)
        for (role in listOf(AdminRole.HQ_HEAD, AdminRole.SYSTEM_ADMIN)) {
            mockMvc.perform(post(URL).header("Authorization", bearer(role)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.rows.count").value(1))
                .andExpect(jsonPath("$.matched.count").value(1))
                .andExpect(jsonPath("$.matched.sumKzt").value(500))
                .andExpect(jsonPath("$.credited.sumKzt").value(500))
                .andExpect(jsonPath("$.pending.count").value(0))
                .andExpect(jsonPath("$.ambiguous.count").value(0))
                .andExpect(jsonPath("$.auditId").doesNotExist())
        }
        assertThat(entries(seller.id)).isZero()
        assertThat(importRows("2024-01")).isZero()
    }

    @Test
    fun `a real import over HTTP credits the wallet and records who did it`() {
        val seller = person()
        val hq = admin(AdminRole.HQ_HEAD)

        mockMvc.perform(
            post(URL).header("Authorization", bearer(hq)).contentType(MediaType.APPLICATION_JSON)
                .content(json("2024-02", "2024-02-20", dryRun = false, listOf(row(seller.iin, 800, 8.0)))),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.dryRun").value(false))
            .andExpect(jsonPath("$.credited.sumKzt").value(800))
            .andExpect(jsonPath("$.auditId").isString)

        assertThat(balance(seller.id)).isEqualTo(800)
        assertThat(jdbc.queryForObject("SELECT actor_id FROM sales_premium_imports WHERE period='2024-02'", UUID::class.java)).isEqualTo(hq.id)
        assertThat(jdbc.queryForObject("SELECT actor_role FROM sales_premium_imports WHERE period='2024-02'", String::class.java)).isEqualTo("HQ_HEAD")
    }

    @Test
    fun `a malformed file is a 400 with the reason and moves no money`() {
        val seller = person()
        val hq = bearer(AdminRole.HQ_HEAD)
        val noDryRun = """{"period":"2024-03","through":"2024-03-20","rows":[{"iin":"${seller.iin}","amountKzt":500}]}"""
        val fraction = """{"period":"2024-03","through":"2024-03-20","dryRun":false,"rows":[{"iin":"${seller.iin}","amountKzt":500.5}]}"""
        val badIin = json("2024-03", "2024-03-20", dryRun = false, listOf(row("12345", 500)))
        val badThrough = json("2024-03", "2024-04-02", dryRun = false, listOf(row(seller.iin, 500)))

        for (body in listOf(noDryRun, "{", "[]")) {
            mockMvc.perform(post(URL).header("Authorization", hq).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest)
        }
        mockMvc.perform(post(URL).header("Authorization", hq).contentType(MediaType.APPLICATION_JSON).content(fraction))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("целым числом тенге")))
        mockMvc.perform(post(URL).header("Authorization", hq).contentType(MediaType.APPLICATION_JSON).content(badIin))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("строка 1")))
        mockMvc.perform(post(URL).header("Authorization", hq).contentType(MediaType.APPLICATION_JSON).content(badThrough))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("внутри периода")))
        assertThat(entries(seller.id)).isZero()
        assertThat(importRows("2024-03")).isZero()
    }

    // ---- the ledger and the numbers around it --------------------------------------------------------------------

    @Test
    fun `the ledger accepts the two new sources in their own direction and keeps every old rule`() {
        val owner = person()
        fun insert(source: String, id: String, amount: Long, earned: Long) = jdbc.update(
            """INSERT INTO pharmacist_wallet_entries(id,pharmacist_id,source,source_id,amount_kzt,earned_delta_kzt,context_key,balance_after_kzt)
                VALUES (?,?,?,?,?,?,'probe',0)""", UUID.randomUUID(), owner.id, source, id, amount, earned,
        )

        insert("sales_premium", "ok-1", 100, 100)
        insert("sales_premium_correction", "ok-2", -30, -30)
        insert("receipt_bonus", "ok-3", 50, 50)
        insert("payout_reservation", "ok-4", -120, 0)
        insert("admin_adjustment", "ok-5", -5, 0)
        for ((source, amount, earned) in listOf(
            Triple("sales_premium", -10L, -10L), Triple("sales_premium", 10L, 0L),
            Triple("sales_premium_correction", 10L, 10L), Triple("sales_premium_correction", -10L, 0L),
            Triple("sales_premium_correction", -10L, 10L), Triple("receipt_bonus", -10L, -10L),
            Triple("training_bonus", 10L, -10L), Triple("merch_task", 10L, -10L),
        )) {
            assertThatThrownBy { insert(source, "bad-${UUID.randomUUID()}", amount, earned) }
                .describedAs("%s %d %d", source, amount, earned).isInstanceOf(DataIntegrityViolationException::class.java)
        }
        assertThatThrownBy { insert("bogus_source", "bad-source", 10, 10) }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `the audit and the waiting figures keep their shape and the audit cannot be rewritten`() {
        val id = UUID.randomUUID()
        jdbc.update("""INSERT INTO sales_premium_imports(id,period,through,actor_id,actor_role,payload_sha256,rows_count,summary)
            VALUES (?,'2023-01','2023-01-20',?,'HQ_HEAD',repeat('a',64),1,'{}'::jsonb)""", id, UUID.randomUUID())

        assertThatThrownBy { jdbc.update("UPDATE sales_premium_imports SET rows_count=2 WHERE id=?", id) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy { jdbc.update("DELETE FROM sales_premium_imports WHERE id=?", id) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        for ((iin, period, amount) in listOf(
            Triple("12345", "2023-01", 100L), Triple("123456789012", "2023-13", 100L), Triple("123456789012", "2023-01", 0L),
        )) {
            assertThatThrownBy {
                jdbc.update("INSERT INTO sales_premium_pending(iin,period,amount_kzt,through) VALUES (?,?,?,'2023-01-20')", iin, period, amount)
            }.describedAs("%s %s %d", iin, period, amount).isInstanceOf(DataIntegrityViolationException::class.java)
        }
    }

    @Test
    fun `the admin roster counts the premium as earned and never shows a negative figure`() {
        val seller = person()
        val now = Instant.now()
        fun entry(source: String, id: String, amount: Long, at: Instant) = jdbc.update(
            """INSERT INTO pharmacist_wallet_entries(id,pharmacist_id,source,source_id,amount_kzt,earned_delta_kzt,context_key,balance_after_kzt,created_at)
                VALUES (?,?,?,?,?,?,'premium:2025-01',0,?)""",
            UUID.randomUUID(), seller.id, source, id, amount, amount, Timestamp.from(at),
        )
        fun earned() = people.walletEarned(setOf(seller.id), now.minus(30, ChronoUnit.DAYS), now.plusSeconds(60)).single().earned

        // A credit of this month and a smaller return of the same month: the net is what was earned.
        entry("sales_premium", "e-1", 500, now.minus(1, ChronoUnit.DAYS))
        entry("sales_premium_correction", "e-2", -200, now.minus(1, ChronoUnit.HOURS))
        assertThat(earned()).isEqualTo(300)

        // A return of a premium credited long ago is a negative net in the window: shown as zero.
        val other = person()
        jdbc.update(
            """INSERT INTO pharmacist_wallet_entries(id,pharmacist_id,source,source_id,amount_kzt,earned_delta_kzt,context_key,balance_after_kzt,created_at)
                VALUES (?,?,'sales_premium_correction','late-1',-150,-150,'premium:2024-12',0,?)""",
            UUID.randomUUID(), other.id, Timestamp.from(now.minus(1, ChronoUnit.HOURS)),
        )
        assertThat(people.walletEarned(setOf(other.id), now.minus(30, ChronoUnit.DAYS), now.plusSeconds(60)).single().earned).isZero()
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private fun nextIin(): String = personNumber.incrementAndGet().toString()

    private fun person(
        opening: Long = 0,
        status: PharmacistStatus = PharmacistStatus.active,
        iin: String = nextIin(),
    ): PharmacistEntity = people.saveAndFlush(
        PharmacistEntity(id = "ph_premium_${UUID.randomUUID()}", name = "Premium test", iin = iin, phone = "+7$iin", city = "", balance = opening)
            .also { it.status = status },
    )

    private fun row(iin: String, amount: Long, packs: Double? = null) = SalesPremiumRow(iin, BigDecimal(amount), packs)

    private fun request(period: String, through: String, dryRun: Boolean, rows: List<SalesPremiumRow>) =
        SalesPremiumImportRequest(period, LocalDate.parse(through), dryRun, rows)

    private fun json(period: String, through: String, dryRun: Boolean, rows: List<SalesPremiumRow>): String =
        objectMapper.writeValueAsString(request(period, through, dryRun, rows))

    private fun balance(id: String) = people.findById(id).orElseThrow().balance

    private fun entries(id: String) = jdbc.queryForObject("SELECT count(*) FROM pharmacist_wallet_entries WHERE pharmacist_id=?", Long::class.java, id)!!

    private fun pendingRows(period: String) = jdbc.queryForObject("SELECT count(*) FROM sales_premium_pending WHERE period=?", Long::class.java, period)!!

    private fun importRows(period: String) = jdbc.queryForObject("SELECT count(*) FROM sales_premium_imports WHERE period=?", Long::class.java, period)!!

    private fun admin(role: AdminRole): AdminUserEntity = admins.save(
        AdminUserEntity(
            email = "premium-${adminNumber.incrementAndGet()}-${UUID.randomUUID()}@example.test",
            passwordHash = encoder.encode("premium-test-pass"), name = "Premium admin", company = "Inkar",
        ).also { it.role = role; it.status = AdminUserStatus.ACTIVE },
    )

    private fun bearer(role: AdminRole): String = bearer(admin(role))

    private fun bearer(user: AdminUserEntity): String {
        val result = mockMvc.perform(
            post("/api/admin/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(LoginRequest(email = user.email, password = "premium-test-pass"))),
        ).andExpect(status().isOk).andReturn()
        return "Bearer " + objectMapper.readValue(result.response.contentAsString, LoginResponse::class.java).tokens.accessToken
    }

    private fun eventually(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        throw AssertionError("Not reached in 15 seconds: $what")
    }

    private fun <T> parallel(actions: List<() -> T>): List<T> {
        val executor = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        try {
            val futures = actions.map { action -> executor.submit(Callable { start.await(); action() }) }
            start.countDown()
            return futures.map { it.get(60, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }
}
