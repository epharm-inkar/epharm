package kz.epharm.pharmacists

import com.fasterxml.jackson.databind.ObjectMapper
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.domain.AdminUserStatus
import kz.epharm.auth.dto.LoginRequest
import kz.epharm.auth.dto.LoginResponse
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.pharmacies.entity.ChainEntity
import kz.epharm.pharmacies.entity.PharmacyEntity
import kz.epharm.pharmacies.entity.PharmacyGroup
import kz.epharm.pharmacies.repository.ChainRepository
import kz.epharm.pharmacies.repository.PharmacyRepository
import kz.epharm.pharmacists.entity.PharmacistEntity
import kz.epharm.pharmacists.entity.PharmacistStatus
import kz.epharm.pharmacists.entity.PharmacistTier
import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.posm.entity.PosSaleEntity
import kz.epharm.posm.entity.PosSaleItem
import kz.epharm.posm.repository.PosSaleRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDate
import java.time.Instant
import java.time.temporal.ChronoUnit

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Transactional
class PharmacistsIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("epharm_test")
            .withUsername("epharm")
            .withPassword("epharm_test")
            .apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun props(reg: DynamicPropertyRegistry) {
            reg.add("spring.datasource.url") { postgres.jdbcUrl }
            reg.add("spring.datasource.username") { postgres.username }
            reg.add("spring.datasource.password") { postgres.password }
        }
    }

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var pharmacistRepository: PharmacistRepository
    @Autowired private lateinit var pharmacyRepository: PharmacyRepository
    @Autowired private lateinit var chainRepository: ChainRepository
    @Autowired private lateinit var adminUserRepository: AdminUserRepository
    @Autowired private lateinit var passwordEncoder: PasswordEncoder
    @Autowired private lateinit var sales: PosSaleRepository
    @Autowired private lateinit var jdbc: JdbcTemplate

    private lateinit var bearer: String

    @BeforeEach
    fun seed() {
        sales.deleteAll()
        pharmacistRepository.deleteAll()
        pharmacyRepository.deleteAll()
        chainRepository.deleteAll()
        adminUserRepository.deleteAll()

        chainRepository.save(
            ChainEntity(id = "europharma", name = "Europharma", color = "#2A2BE2", points = 100)
                .also { it.group = PharmacyGroup.pilot },
        )
        pharmacyRepository.save(
            PharmacyEntity(
                id = "europharma_0", name = "Europharma №100",
                chainId = "europharma", chainName = "Europharma",
                city = "Алматы",
            ).also { it.group = PharmacyGroup.pilot },
        )
        pharmacistRepository.save(
            PharmacistEntity(
                id = "u_active", name = "Айгерим Касенова",
                iin = "830909300014", phone = "+7 (701) 100-10-20",
                pharmacyId = "europharma_0", pharmacyName = "Europharma №100",
                city = "Алматы", balance = 50_000, joinedAt = LocalDate.of(2026, 1, 15),
            ).also { it.tier = PharmacistTier.Gold; it.status = PharmacistStatus.active },
        )
        pharmacistRepository.save(
            PharmacistEntity(
                id = "u_pending", name = "Алмас Бекенов",
                iin = "960501600011", phone = "+7 (701) 100-10-21",
                pharmacyId = null, pharmacyName = null,
                city = "", joinedAt = LocalDate.of(2026, 2, 1),
            ).also { it.tier = PharmacistTier.Silver; it.status = PharmacistStatus.pending },
        )

        adminUserRepository.save(
            AdminUserEntity(
                email = "damir@jadran.com",
                passwordHash = passwordEncoder.encode("damir2026"),
                name = "Дамир", company = "Jadran",
            ).also { it.role = AdminRole.BRAND_MANAGER; it.status = AdminUserStatus.ACTIVE },
        )
        bearer = "Bearer " + login().tokens.accessToken
    }

    @Test
    fun `receipt counts use real receipts exact current pharmacy rolling dates and no raw seller fallback`() {
        val observed = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val since = observed.minus(30, ChronoUnit.DAYS)
        fun sale(id: String, person: String = "u_active", pharmacy: String = "europharma_0", at: Instant = observed.minusSeconds(60)) =
            PosSaleEntity(
                id = id, pharmacistId = person, pharmacyId = pharmacy, printedAt = at,
                reportedPharmacistId = "u_active", pharmacistName = "Айгерим Касенова",
                reportedPharmacistName = "Айгерим Касенова",
                // Receipt count, not item count: one sale can contain several positions.
                items = listOf(PosSaleItem(name = "one"), PosSaleItem(name = "two")),
            )
        sales.saveAllAndFlush(listOf(
            sale("recent"), sale("at_boundary", at = since), sale("expired", at = since.minusSeconds(1)),
            sale("future", at = observed.plusSeconds(3600)), sale("foreign_pharmacy", pharmacy = "other"),
            sale("raw_only", person = ""), sale("other_person", person = "other"),
            sale("refund"), sale("unassigned", person = "u_pending"),
        ))
        jdbc.update("UPDATE pos_sales SET reward_evidence = CAST(? AS jsonb) WHERE id = 'refund'",
            """{"completion":{"status":"refund"}}""")
        jdbc.update("UPDATE pharmacists SET receipts_30d = 999 WHERE id IN ('u_active', 'u_pending')")

        val counts = pharmacistRepository.receiptCounts(setOf("u_active", "u_pending"), since, observed)
        assertEquals(1, counts.size)
        assertEquals("u_active", counts.single().pharmacistId)
        assertEquals("europharma_0", counts.single().pharmacyId)
        assertEquals(2L, counts.single().count)

        // Real-time API's boundary is a little later than the fixed repository test: only recent remains.
        mockMvc.perform(get("/api/admin/pharmacists?status=active").header("Authorization", bearer))
            .andExpect(status().isOk).andExpect(jsonPath("$[0].receipts30d").value(1))
        mockMvc.perform(get("/api/admin/pharmacists/u_active").header("Authorization", bearer))
            .andExpect(status().isOk).andExpect(jsonPath("$.receipts30d").value(1))
        mockMvc.perform(get("/api/admin/pharmacists/u_pending").header("Authorization", bearer))
            .andExpect(status().isOk).andExpect(jsonPath("$.receipts30d").value(0))
        assertEquals(999, jdbc.queryForObject("SELECT receipts_30d FROM pharmacists WHERE id = 'u_active'", Int::class.java))
    }

    @Test
    fun `GET pharmacists список 2`() {
        mockMvc.perform(get("/api/admin/pharmacists").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
    }

    @Test
    fun `GET filter status=active → 1`() {
        mockMvc.perform(get("/api/admin/pharmacists?status=active").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value("u_active"))
    }

    @Test
    fun `GET filter pharmacyId`() {
        mockMvc.perform(
            get("/api/admin/pharmacists?pharmacyId=europharma_0").header("Authorization", bearer),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
    }

    @Test
    fun `GET unknown id → 404`() {
        mockMvc.perform(get("/api/admin/pharmacists/u_nope").header("Authorization", bearer))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `POST create — успешный`() {
        val payload = """
            {"name":"Бауыржан Тлеуов","iin":"900115300023","phone":"+7 (701) 100-10-99","password":"Farm123!","pharmacyId":"europharma_0"}
        """.trimIndent()
        mockMvc.perform(
            post("/api/admin/pharmacists")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("Бауыржан Тлеуов"))
            .andExpect(jsonPath("$.pharmacyName").value("Europharma №100"))
            .andExpect(jsonPath("$.tier").value("Silver"))
            .andExpect(jsonPath("$.status").value("pending"))
    }

    @Test
    fun `POST create с дубликатом IIN → 409 CONFLICT`() {
        val payload = """
            {"name":"X","iin":"830909300014","phone":"+7 (701) 999-99-99","password":"Farm123!","pharmacyId":"europharma_0"}
        """.trimIndent()
        mockMvc.perform(
            post("/api/admin/pharmacists")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("CONFLICT"))
    }

    @Test
    fun `POST create с дубликатом phone → 409 CONFLICT`() {
        val payload = """
            {"name":"X","iin":"850615400026","phone":"+7 (701) 100-10-20","password":"Farm123!","pharmacyId":"europharma_0"}
        """.trimIndent()
        mockMvc.perform(
            post("/api/admin/pharmacists")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload),
        )
            .andExpect(status().isConflict)
    }

    @Test
    fun `POST create с невалидным IIN → 400 VALIDATION_FAILED`() {
        val payload = """
            {"name":"X","iin":"not-digits","phone":"+7 (700) 000-00-00","password":"Farm123!","pharmacyId":"europharma_0"}
        """.trimIndent()
        mockMvc.perform(
            post("/api/admin/pharmacists")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload),
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `BRAND_MANAGER cannot PATCH a monetary balance`() {
        mockMvc.perform(
            patch("/api/admin/pharmacists/u_active")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"balance":100000}"""),
        )
            .andExpect(status().isForbidden)
        assert(pharmacistRepository.findById("u_active").orElseThrow().balance == 50_000L)
    }

    @Test
    fun `PATCH со status → 400 (used dedicated endpoint)`() {
        mockMvc.perform(
            patch("/api/admin/pharmacists/u_active")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"status":"blocked"}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
    }

    @Test
    fun `POST block → status=blocked, POST unblock → status=active`() {
        mockMvc.perform(post("/api/admin/pharmacists/u_active/block").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("blocked"))

        mockMvc.perform(post("/api/admin/pharmacists/u_active/unblock").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("active"))
    }

    @Test
    fun `POST activate назначает pending фармацевту аптеку и активирует профиль`() {
        mockMvc.perform(
            post("/api/admin/pharmacists/u_pending/activate")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"pharmacyId":"europharma_0"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("active"))
            .andExpect(jsonPath("$.pharmacyId").value("europharma_0"))
            .andExpect(jsonPath("$.pharmacyName").value("Europharma №100"))
            .andExpect(jsonPath("$.city").value("Алматы"))
    }

    @Test
    fun `POST activate отклоняет неизвестную аптеку`() {
        mockMvc.perform(
            post("/api/admin/pharmacists/u_pending/activate")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"pharmacyId":"missing"}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
    }

    private fun login(): LoginResponse {
        val req = LoginRequest(email = "damir@jadran.com", password = "damir2026")
        val result = mockMvc.perform(
            post("/api/admin/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)),
        ).andExpect(status().isOk).andReturn()
        return objectMapper.readValue(result.response.contentAsString, LoginResponse::class.java)
    }
}
