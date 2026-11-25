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
import java.sql.Timestamp
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

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
    fun `roster facts read the last sale by IIN anywhere, Almaty month earnings, open training and Standard-N pharmacies`() {
        val observed = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val iin = "830909300014"
        val almaty = ZoneId.of("Asia/Almaty")
        val monthStart = LocalDate.now(almaty).withDayOfMonth(1).atStartOfDay(almaty).toInstant()
        for ((id, city) in listOf("europharma_1" to "Астана", "europharma_2" to "Шымкент")) {
            pharmacyRepository.saveAndFlush(
                PharmacyEntity(id = id, name = "Europharma №10${id.last()}", chainId = "europharma", chainName = "Europharma", city = city)
                    .also { it.group = PharmacyGroup.pilot },
            )
        }
        for ((pharmacy, profile) in listOf("europharma_0" to 701L, "europharma_1" to 702L, "europharma_2" to 703L)) {
            jdbc.update("UPDATE pharmacies SET standardn_profile_id = ?, standardn_profile_source = 'manual' WHERE id = ?", profile, pharmacy)
        }
        // Active users in two linked profiles; the blocked user (status 1) in the third must not appear.
        for ((profile, status) in listOf(701L to 0, 702L to 0, 703L to 1)) {
            jdbc.update(
                """INSERT INTO standardn_pharmacist_directory(directory_key,source_profile_id,source_user_id,iin,full_name,source_status)
                    VALUES(?,?,11,?,'Айгерим Касенова',?)""",
                "roster-$profile", profile, iin, status,
            )
        }
        jdbc.update(
            """INSERT INTO pharmacists(id, name, iin, phone, password_hash, tier, status, pharmacy_id, pharmacy_name, city, account_source)
                VALUES ('u_directory', 'Дана Сапарова', '900101400011', '', NULL, 'Silver', 'active', 'europharma_0', 'Europharma №100', 'Алматы', 'standardn')""",
        )

        fun sale(id: String, at: Instant, pharmacy: String, person: String = "", seller: String? = iin) = PosSaleEntity(
            id = id, pharmacistId = person, pharmacyId = pharmacy, printedAt = at, sellerIin = seller,
            items = listOf(PosSaleItem(name = "one")),
        )
        sales.saveAllAndFlush(listOf(
            // Older receipt attributed only by the saved seller ID (before V112), in the home pharmacy.
            sale("legacy_home", observed.minus(3, ChronoUnit.DAYS), "europharma_0", person = "u_active", seller = null),
            sale("other_pharmacy", observed.minus(2, ChronoUnit.HOURS), "europharma_1"),
            sale("refund_newer", observed.minus(1, ChronoUnit.HOURS), "europharma_1"),
            sale("future_clock", observed.plus(1, ChronoUnit.HOURS), "europharma_1"),
        ))
        jdbc.update("UPDATE pos_sales SET reward_evidence = CAST(? AS jsonb) WHERE id = 'refund_newer'",
            """{"completion":{"status":"refund"}}""")

        fun wallet(sourceId: String, source: String, amount: Long, earned: Long, at: Instant) = jdbc.update(
            """INSERT INTO pharmacist_wallet_entries
                (id, pharmacist_id, source, source_id, amount_kzt, earned_delta_kzt, context_key, balance_after_kzt, created_at)
                VALUES (?, 'u_active', ?, ?, ?, ?, 'synthetic-roster', ?, ?)""",
            UUID.randomUUID(), source, sourceId, amount, earned, amount, Timestamp.from(at),
        )
        wallet("bonus-now", "receipt_bonus", 1_500, 1_500, observed)
        wallet("training-at-month-start", "training_bonus", 500, 500, monthStart)
        wallet("bonus-last-month", "receipt_bonus", 700, 700, monthStart.minusSeconds(1))
        wallet("adjustment-now", "admin_adjustment", 300, 0, observed)

        val actor = adminUserRepository.findAll().single().id
        val program = UUID.randomUUID()
        val version = UUID.randomUUID()
        jdbc.update("""INSERT INTO training_programs(id,name,allowed_formats,status,created_by)
            VALUES(?,'Синтетическая программа','online','published',?)""", program, actor)
        jdbc.update("""INSERT INTO training_program_versions(id,program_id,version_no,allowed_formats,snapshot_json,created_by)
            VALUES(?,?,1,'online','{}',?)""", version, program, actor)
        listOf("in_progress", "scheduled", "completed", "cancelled").forEachIndexed { index, status ->
            jdbc.update("""INSERT INTO training_assignments(id,program_version_id,pharmacist_id,format,status,assigned_by,repeat_no)
                VALUES(?,?,'u_active','online',?,?,?)""", UUID.randomUUID(), version, status, actor, index + 1)
        }

        val body = mockMvc.perform(get("/api/admin/pharmacists").header("Authorization", bearer))
            .andExpect(status().isOk).andReturn().response.contentAsString
        val rows = objectMapper.readTree(body).associateBy { it["id"].asText() }

        val active = rows.getValue("u_active")
        assertEquals("registration", active["accountSource"].asText())
        assertEquals(true, active["hasPhone"].asBoolean())
        assertEquals(observed.minus(2, ChronoUnit.HOURS), Instant.parse(active["lastSaleAt"].asText()))
        assertEquals(2_000L, active["earnedMonthKzt"].asLong())
        assertEquals(2, active["openAssignments"].asInt())
        assertEquals(listOf("Europharma №100", "Europharma №101"), active["standardNPharmacies"].map { it["name"].asText() })
        assertEquals(listOf("europharma_0", "europharma_1"), active["standardNPharmacies"].map { it["id"].asText() })
        // The 30-day count keeps its contract: saved seller ID in the current pharmacy only.
        assertEquals(1, active["receipts30d"].asInt())

        val pending = rows.getValue("u_pending")
        assertEquals(true, pending["lastSaleAt"] == null || pending["lastSaleAt"].isNull)
        assertEquals(0L, pending["earnedMonthKzt"].asLong())
        assertEquals(0, pending["openAssignments"].asInt())
        assertEquals(0, pending["standardNPharmacies"].size())

        val directory = rows.getValue("u_directory")
        assertEquals("standardn", directory["accountSource"].asText())
        assertEquals(false, directory["hasPhone"].asBoolean())
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
