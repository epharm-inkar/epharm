package kz.epharm.finance.premium

import com.fasterxml.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.domain.AdminUserStatus
import kz.epharm.auth.dto.LoginRequest
import kz.epharm.auth.dto.LoginResponse
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.auth.security.AdminPrincipal
import kz.epharm.pharmacists.entity.PharmacistEntity
import kz.epharm.pharmacists.entity.PharmacistStatus
import kz.epharm.pharmacists.repository.PharmacistRepository
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * The daily collector of the sales premium (26.11.2026): the November matrix seeded by V125, the matrix upload, a
 * calculation from a fake Standard-N through the real import, the figures it leaves, the audit row of the job, the month
 * statement and the HTTP surface. The import itself is covered by [SalesPremiumIntegrationTest]. Every test works in its
 * own month, because the audit table keeps the cut-offs.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class SalesPremiumCollectorIntegrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("premium_collector_test").withUsername("epharm").withPassword("test-only")
            .apply { start() }

        private val personNumber = AtomicLong(800_000_000_000)
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
    @Autowired private lateinit var matrixStore: SalesPremiumMatrixStore
    @Autowired private lateinit var matrixService: SalesPremiumMatrixService
    @Autowired private lateinit var statement: SalesPremiumStatement
    @Autowired private lateinit var runLog: SalesPremiumRunLog
    @Autowired private lateinit var people: PharmacistRepository
    @Autowired private lateinit var admins: AdminUserRepository
    @Autowired private lateinit var encoder: PasswordEncoder
    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var jdbc: JdbcTemplate

    private val actor = AdminPrincipal(UUID.randomUUID(), "hq@example.test", "HQ", AdminRole.HQ_HEAD, "Inkar")

    private val wareA = "AAAAAAAA-0000-4000-8000-00000000000A"
    private val wareB = "BBBBBBBB-0000-4000-8000-00000000000B"
    private val wareC = "CCCCCCCC-0000-4000-8000-00000000000C"

    /** The Standard-N database as a list of lines per product; the collector reads it like the real one. */
    private class FakeStandardN(private val linesByWare: Map<String, List<StandardNPremiumLine>>) : StandardNPremiumSource {
        override fun open(): StandardNPremiumReader = object : StandardNPremiumReader {
            override fun lines(wareId: String, from: LocalDate, until: LocalDate) = linesByWare[wareId].orEmpty()

            override fun close() = Unit
        }
    }

    // ---- the matrix of the month --------------------------------------------------------------------------------

    @Test
    fun `the migration seeds the November matrix of the owner's list and no other month`() {
        val items = matrixStore.items("2026-11")

        assertThat(items).hasSize(188)
        assertThat(items.count { it.bonusPerPack.signum() == 0 }).isEqualTo(1)
        assertThat(items.fold(BigDecimal.ZERO) { sum, item -> sum + item.bonusPerPack }).isEqualByComparingTo(BigDecimal("50481"))
        assertThat(items.map { it.wareId }).allMatch { it.matches(Regex("[0-9A-F]{8}(-[0-9A-F]{4}){3}-[0-9A-F]{12}")) }
        assertThat(matrixStore.items("2026-12")).isEmpty()
    }

    @Test
    fun `a matrix upload only compares on a dry run and replaces the whole month on a real one`() {
        val period = "2031-05"
        val first = "ware_id,promo_name,bonus_per_pack\n$wareA,Первый,100\n$wareB,Второй,\n".toByteArray()

        val preview = matrixService.upload(period, first, dryRun = true, actor = actor)

        assertThat(preview).isEqualTo(MatrixUploadResult(period, true, 2, 1, 1, 0, 2, 0, 0, null))
        assertThat(matrixStore.items(period)).isEmpty()

        matrixService.upload(period, first, dryRun = false, actor = actor)
        assertThat(matrixStore.items(period).associate { it.wareId to it.bonusPerPack.toLong() })
            .isEqualTo(mapOf(wareA to 100L, wareB to 0L))

        val second = "ware_id,bonus_per_pack\n$wareA,150\n$wareC,10\n".toByteArray()
        val replaced = matrixService.upload(period, second, dryRun = false, actor = actor)

        assertThat(replaced).isEqualTo(MatrixUploadResult(period, false, 2, 2, 0, 2, 1, 1, 1, null))
        assertThat(matrixStore.items(period).associate { it.wareId to it.bonusPerPack.toLong() })
            .isEqualTo(mapOf(wareA to 150L, wareC to 10L))
        assertThat(matrixStore.items("2026-11")).hasSize(188)
    }

    // ---- a calculation, the figures, the log of the job and the statement ---------------------------------------

    @Test
    fun `the collector credits what it computed, stays idempotent, and the figures, the log and the statement follow`() {
        val seller = person()
        val unknown = nextIin()
        matrixStore.replace("2026-03", listOf(MatrixItem(wareA, "A", BigDecimal("100")), MatrixItem(wareB, "B", BigDecimal("250.50"))))
        val source = FakeStandardN(
            mapOf(
                wareA to listOf(
                    StandardNPremiumLine(1, 10, seller.iin, 3, BigDecimal("-4")),
                    StandardNPremiumLine(1, 11, unknown, 3, BigDecimal("-1")),
                    StandardNPremiumLine(1, null, null, 3, BigDecimal("-7")),
                ),
                wareB to listOf(
                    StandardNPremiumLine(1, 10, seller.iin, 3, BigDecimal("-2")),
                    StandardNPremiumLine(1, 10, seller.iin, 9, BigDecimal("1")),
                ),
            ),
        )
        val collector = SalesPremiumCollector(matrixStore, source, service, MutableClock.almaty(2026, 3, 15, 6, 41))

        val result = collector.collect(null, dryRun = false, actor = PremiumActor.SYSTEM_JOB)

        // seller: 4 x 100 + (2 - 1) x 250.50 = 650.50, rounded once to 651 for 5 packs; the unknown IIN: 100; no IIN: 700.
        assertThat(result.period).isEqualTo("2026-03")
        assertThat(result.through).isEqualTo(LocalDate.of(2026, 3, 14))
        assertThat(result.sellers).isEqualTo(2)
        assertThat(result.premiumKzt).isEqualTo(751)
        assertThat(result.withoutIin).isEqualTo(WithoutIin(1, BigDecimal("7"), 700))
        assertThat(result.imported!!.credited).isEqualTo(PremiumTotals(1, 651))
        assertThat(result.imported!!.pending).isEqualTo(PremiumPending(1, 100, notActiveCount = 0))
        assertThat(balance(seller.id)).isEqualTo(651)
        assertThat(jdbc.queryForMap("SELECT source, context_key, amount_kzt FROM pharmacist_wallet_entries WHERE pharmacist_id=?", seller.id))
            .containsEntry("source", "sales_premium").containsEntry("context_key", "premium:2026-03").containsEntry("amount_kzt", 651L)
        assertThat(jdbc.queryForObject("SELECT amount_kzt FROM sales_premium_pending WHERE iin=? AND period='2026-03'", Long::class.java, unknown))
            .isEqualTo(100)
        val audit = jdbc.queryForMap("SELECT actor_id, actor_role FROM sales_premium_imports WHERE period='2026-03'")
        assertThat(audit["actor_id"]).isEqualTo(PremiumActor.SYSTEM_JOB.id)
        assertThat(audit["actor_role"]).isEqualTo("SYSTEM_JOB")
        assertThat(jdbc.queryForObject("SELECT packs FROM sales_premium_figures WHERE iin=? AND period='2026-03'", BigDecimal::class.java, seller.iin))
            .isEqualByComparingTo(BigDecimal("5"))

        val today = LocalDate.now(SalesPremiumRules.ALMATY)
        assertThat(runLog.collectedBy(PremiumActor.SYSTEM_JOB, today)).isTrue()
        assertThat(runLog.collectedBy(PremiumActor.SYSTEM_JOB, today.plusDays(1))).isFalse()
        assertThat(runLog.collectedBy(PremiumActor.of(actor), today)).isFalse()

        val rows = statement.rows("2026-03")
        assertThat(rows).containsExactlyInAnyOrder(
            StatementRow("Premium test", seller.iin, "", "", BigDecimal("5.000"), 651, credited = true),
            StatementRow("", unknown, "", "", BigDecimal("1.000"), 100, credited = false),
        )
        val csv = String(SalesPremiumStatementCsv.render(rows), Charsets.UTF_8)
        assertThat(csv).contains("=\"\"${seller.iin}\"\"").contains(";651;на кошельке").contains(";100;ждёт карточку")

        // The next day brings the same figures: nothing is credited twice.
        val again = collector.collect(null, dryRun = false, actor = PremiumActor.SYSTEM_JOB)
        assertThat(again.imported!!.credited).isEqualTo(PremiumTotals(0, 0))
        assertThat(balance(seller.id)).isEqualTo(651)
        assertThat(entries(seller.id)).isEqualTo(1)
    }

    @Test
    fun `a dry run of the collector writes nothing`() {
        val seller = person()
        matrixStore.replace("2026-02", listOf(MatrixItem(wareA, "A", BigDecimal("100"))))
        val source = FakeStandardN(mapOf(wareA to listOf(StandardNPremiumLine(1, 10, seller.iin, 3, BigDecimal("-3")))))
        val collector = SalesPremiumCollector(matrixStore, source, service, MutableClock.almaty(2026, 2, 20, 6, 41))

        val result = collector.collect(null, dryRun = true, actor = PremiumActor.SYSTEM_JOB)

        assertThat(result.imported!!.credited).isEqualTo(PremiumTotals(1, 300))
        assertThat(balance(seller.id)).isZero()
        assertThat(entries(seller.id)).isZero()
        assertThat(importRows("2026-02")).isZero()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sales_premium_figures WHERE period='2026-02'", Long::class.java)).isZero()
    }

    @Test
    fun `the statement does not count twice the part of a waiting figure that is already in the ledger`() {
        val seller = person()
        service.apply(request("2026-04", "2026-04-20", false, listOf(row(seller.iin, 600, 6.0))), actor)
        jdbc.update("UPDATE pharmacists SET status = 'blocked' WHERE id = ?", seller.id)
        service.apply(request("2026-04", "2026-04-25", false, listOf(row(seller.iin, 1000, 10.0))), actor)

        val rows = statement.rows("2026-04")

        assertThat(rows.map { Triple(it.amountKzt, it.credited, it.packs?.toInt()) })
            .containsExactlyInAnyOrder(Triple(600L, true, 10), Triple(400L, false, null))
        assertThat(rows.sumOf { it.amountKzt }).isEqualTo(1000)
    }

    @Test
    fun `the statement of a month without premium is empty`() {
        assertThat(statement.rows("2031-09")).isEmpty()
    }

    // ---- the HTTP surface ---------------------------------------------------------------------------------------

    @Test
    fun `the statement is a CSV download for the HQ, refused to others and to a bad period`() {
        val seller = person()
        service.apply(request("2026-05", "2026-05-20", false, listOf(row(seller.iin, 700, 7.0))), actor)
        val url = "/api/admin/finance/sales-premium/statement.csv"

        val response = mockMvc.perform(get(url).param("period", "2026-05").header("Authorization", bearer(AdminRole.HQ_HEAD)))
            .andExpect(status().isOk)
            .andExpect(header().string("Content-Type", startsWith("text/csv")))
            .andExpect(header().string("Content-Disposition", "attachment; filename=\"sales-premium-2026-05.csv\""))
            .andExpect(header().string("Cache-Control", containsString("no-store")))
            .andReturn().response
        val text = String(response.contentAsByteArray, Charsets.UTF_8)
        assertThat(text).startsWith("﻿ФИО;ИИН;аптека;город;упаковок;начислено;статус\r\n")
        assertThat(text).contains("=\"\"${seller.iin}\"\"").contains(";7;700;на кошельке")

        mockMvc.perform(get(url).param("period", "2026-05").header("Authorization", bearer(AdminRole.FINANCE_REVIEWER)))
            .andExpect(status().isForbidden)
        mockMvc.perform(get(url).param("period", "2026-05")).andExpect(status().isUnauthorized)
        mockMvc.perform(get(url).param("period", "2026-13").header("Authorization", bearer(AdminRole.SYSTEM_ADMIN)))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        mockMvc.perform(get(url).header("Authorization", bearer(AdminRole.HQ_HEAD))).andExpect(status().isBadRequest)
    }

    @Test
    fun `a matrix file posted as text csv is compared on a dry run and stored on a real one, for the HQ only`() {
        val url = "/api/admin/finance/sales-premium/matrix"
        val file = "ware_id,promo_name,bonus_per_pack\n$wareA,Первый,100\n$wareB,Второй,\n".toByteArray(Charsets.UTF_8)
        val hq = bearer(AdminRole.HQ_HEAD)
        fun upload(dryRun: String?, token: String? = hq, body: ByteArray = file) = mockMvc.perform(
            post(url).param("period", "2031-06").apply { if (dryRun != null) param("dryRun", dryRun) }
                .contentType("text/csv;charset=UTF-8").content(body).apply { if (token != null) header("Authorization", token) },
        )

        upload("true").andExpect(status().isOk)
            .andExpect(jsonPath("$.dryRun").value(true)).andExpect(jsonPath("$.products").value(2))
            .andExpect(jsonPath("$.withBonus").value(1)).andExpect(jsonPath("$.zeroBonus").value(1))
            .andExpect(jsonPath("$.added").value(2)).andExpect(jsonPath("$.before").value(0))
        assertThat(matrixStore.items("2031-06")).isEmpty()

        upload("false").andExpect(status().isOk).andExpect(jsonPath("$.dryRun").value(false))
        assertThat(matrixStore.items("2031-06").map { it.name }).containsExactlyInAnyOrder("Первый", "Второй")

        upload(null).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        upload("false", body = "ware_id,promo_name,bonus_per_pack\nnot-a-guid,Плохой,5\n".toByteArray()).andExpect(status().isBadRequest)
        upload("false", body = "ware_id,promo_name,bonus_per_pack\n$wareC,Аспирин,5\n".toByteArray(charset("windows-1251")))
            .andExpect(status().isBadRequest)
        assertThat(matrixStore.items("2031-06")).hasSize(2)

        upload("false", token = bearer(AdminRole.FINANCE_REVIEWER)).andExpect(status().isForbidden)
        upload("false", token = null).andExpect(status().isUnauthorized)
    }

    @Test
    fun `recompute is a 503 while Standard-N is not reachable, a 400 without dryRun, and refused to others`() {
        val url = "/api/admin/finance/sales-premium/recompute"

        mockMvc.perform(post(url).param("period", "2026-11").param("dryRun", "true").header("Authorization", bearer(AdminRole.HQ_HEAD)))
            .andExpect(status().isServiceUnavailable).andExpect(jsonPath("$.code").value("UPSTREAM_UNAVAILABLE"))
        mockMvc.perform(post(url).param("period", "2026-11").header("Authorization", bearer(AdminRole.SYSTEM_ADMIN)))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        mockMvc.perform(post(url).param("period", "2026-13").param("dryRun", "true").header("Authorization", bearer(AdminRole.HQ_HEAD)))
            .andExpect(status().isBadRequest)
        mockMvc.perform(post(url).param("dryRun", "true").header("Authorization", bearer(AdminRole.FINANCE_REVIEWER)))
            .andExpect(status().isForbidden)
        mockMvc.perform(post(url).param("dryRun", "true")).andExpect(status().isUnauthorized)
        assertThat(importRows("2026-11")).isZero()
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private fun nextIin(): String = personNumber.incrementAndGet().toString()

    private fun person(opening: Long = 0, status: PharmacistStatus = PharmacistStatus.active, iin: String = nextIin()): PharmacistEntity =
        people.saveAndFlush(
            PharmacistEntity(id = "ph_collector_${UUID.randomUUID()}", name = "Premium test", iin = iin, phone = "+7$iin", city = "", balance = opening)
                .also { it.status = status },
        )

    private fun row(iin: String, amount: Long, packs: Double? = null) = SalesPremiumRow(iin, BigDecimal(amount), packs)

    private fun request(period: String, through: String, dryRun: Boolean, rows: List<SalesPremiumRow>) =
        SalesPremiumImportRequest(period, LocalDate.parse(through), dryRun, rows)

    private fun balance(id: String) = people.findById(id).orElseThrow().balance

    private fun entries(id: String) = jdbc.queryForObject("SELECT count(*) FROM pharmacist_wallet_entries WHERE pharmacist_id=?", Long::class.java, id)!!

    private fun importRows(period: String) = jdbc.queryForObject("SELECT count(*) FROM sales_premium_imports WHERE period=?", Long::class.java, period)!!

    private fun admin(role: AdminRole): AdminUserEntity = admins.save(
        AdminUserEntity(
            email = "collector-${adminNumber.incrementAndGet()}-${UUID.randomUUID()}@example.test",
            passwordHash = encoder.encode("collector-test-pass"), name = "Collector admin", company = "Inkar",
        ).also { it.role = role; it.status = AdminUserStatus.ACTIVE },
    )

    private fun bearer(role: AdminRole): String {
        val user = admin(role)
        val result = mockMvc.perform(
            post("/api/admin/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(LoginRequest(email = user.email, password = "collector-test-pass"))),
        ).andExpect(status().isOk).andReturn()
        return "Bearer " + objectMapper.readValue(result.response.contentAsString, LoginResponse::class.java).tokens.accessToken
    }
}
