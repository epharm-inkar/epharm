package kz.epharm.posm.commands

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.auth.service.AdminAuthService
import kz.epharm.fulfillment.security.FulfillmentCrypto
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Remote till commands on real PostgreSQL through the real security chain: HQ JWT for the admin side, per-device
 * POSM keys for the till side. Synthetic pharmacies, tills and archives only.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class TillCommandIntegrationTest {
    companion object {
        @Container @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("till_commands_test").withUsername("epharm").withPassword("local-test-only")
            .apply { start() }

        @JvmStatic @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            // The hourly cleanup must not race the assertions; it is called directly where tested.
            registry.add("app.posm.commands.cleanup-cron") { "-" }
        }

        private const val PHARMACY = "tc-pharmacy-1"
        private const val OTHER_PHARMACY = "tc-pharmacy-2"
        private const val PASSWORD = "Synthetic-admin-pass-1"
    }

    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var mapper: ObjectMapper
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var crypto: FulfillmentCrypto
    @Autowired private lateinit var users: AdminUserRepository
    @Autowired private lateinit var auth: AdminAuthService
    @Autowired private lateinit var encoder: PasswordEncoder
    @Autowired private lateinit var storage: InMemoryTillArtifactStorage
    @Autowired private lateinit var commands: TillCommandService

    private lateinit var hq: String
    private lateinit var hqEmail: String
    private lateinit var categoryLead: String
    private lateinit var tillA: String
    private lateinit var tillB: String
    private lateinit var tillC: String

    private val zip = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + "synthetic diagnostics".toByteArray()

    @BeforeEach fun seed() {
        storage.clear()
        jdbc.update("DELETE FROM till_commands")
        jdbc.update("DELETE FROM fulfillment_devices WHERE pharmacy_id LIKE 'tc-%'")
        jdbc.update("DELETE FROM pharmacies WHERE id LIKE 'tc-%'")
        jdbc.update("""INSERT INTO chains(id, name, "group") VALUES ('tc-chain', 'Synthetic', 'pilot') ON CONFLICT DO NOTHING""")
        for ((id, name) in listOf(PHARMACY to "Synthetic pharmacy 1", OTHER_PHARMACY to "Synthetic pharmacy 2")) {
            jdbc.update(
                """INSERT INTO pharmacies(id, name, chain_id, chain_name, city, "group")
                   VALUES (?, ?, 'tc-chain', 'Synthetic', 'Алматы', 'pilot')""",
                id,
                name,
            )
        }
        tillA = till("KASSA-A", PHARMACY)
        tillB = till("KASSA-B", PHARMACY)
        tillC = till("KASSA-C", OTHER_PHARMACY)
        hqEmail = "hq-${UUID.randomUUID()}@example.invalid"
        hq = token(hqEmail, AdminRole.HQ_HEAD)
        categoryLead = token("lead-${UUID.randomUUID()}@example.invalid", AdminRole.CATEGORY_LEAD)
    }

    @Test fun `HQ asks for diagnostics, the till takes it once, answers with an archive and HQ downloads it`() {
        val created = create("KASSA-A", "diagnostics").andExpect(status().isCreated)
            .andExpect(jsonPath("$.status").value("pending"))
            .andExpect(jsonPath("$.action").value("diagnostics"))
            .andExpect(jsonPath("$.requestedBy").value(hqEmail))
            .andExpect(jsonPath("$.artifactAvailable").value(false))
            .json()
        val id = created["id"].asText()
        val createdAt = Instant.parse(created["createdAt"].asText())
        assertThat(Instant.parse(created["expiresAt"].asText())).isEqualTo(createdAt.plusSeconds(30 * 60))

        val taken = next(tillA).andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(id))
            .andExpect(jsonPath("$.action").value("diagnostics"))
            .andExpect(header().string("Cache-Control", "no-store"))
            .json()
        assertThat(Instant.parse(taken["expiresAt"].asText())).isAfter(Instant.now().plusSeconds(9 * 60))
        // Taken once: the next poll has nothing.
        next(tillA).andExpect(status().isNoContent)
        list(PHARMACY, "KASSA-A").andExpect(jsonPath("$[0].status").value("delivered"))

        result(tillA, id, "done", "Журналы за 3 дня, версии, мониторы", zip)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(id))
            .andExpect(jsonPath("$.status").value("done"))
        list(PHARMACY, "KASSA-A").andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].status").value("done"))
            .andExpect(jsonPath("$[0].resultMessage").value("Журналы за 3 дня, версии, мониторы"))
            .andExpect(jsonPath("$[0].artifactAvailable").value(true))
            .andExpect(jsonPath("$[0].artifactBytes").value(zip.size))
            .andExpect(jsonPath("$[0].finishedAt").isNotEmpty)
        val key = jdbc.queryForObject("SELECT artifact_key FROM till_commands WHERE id = ?::uuid", String::class.java, id)!!
        assertThat(key).startsWith("till-diagnostics/$id/")
        assertThat(storage.keys()).contains(key)

        mvc.perform(get("/api/admin/tills/commands/$id/artifact").bearer(hq))
            .andExpect(status().isOk)
            .andExpect(content().bytes(zip))
            .andExpect(content().contentType("application/zip"))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment; filename=\"epharm-diagnostics-tc-pharmacy-1-KASSA-A-")))

        // A repeated answer does not overwrite the first one.
        result(tillA, id, "failed", "second", null).andExpect(status().isConflict)
        assertThat(jdbc.queryForObject("SELECT status FROM till_commands WHERE id = ?::uuid", String::class.java, id)).isEqualTo("done")
    }

    @Test fun `one command in work per till, other tills are independent`() {
        val first = create("KASSA-A", "diagnostics").andExpect(status().isCreated).json()["id"].asText()
        create("KASSA-A", "restart").andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("CONFLICT"))
        create("KASSA-B", "restart").andExpect(status().isCreated)
        // Taken but not answered still blocks.
        next(tillA).andExpect(status().isOk)
        create("KASSA-A", "restart").andExpect(status().isConflict)
        result(tillA, first, "failed", "Стандарт-Н не отвечает", null).andExpect(status().isOk)
        create("KASSA-A", "restart").andExpect(status().isCreated)
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM till_commands WHERE pharmacy_id = ? AND device_id = 'KASSA-A'", Long::class.java, PHARMACY,
        )).isEqualTo(2L)
    }

    @Test fun `a till never takes or answers a command of another till`() {
        val id = create("KASSA-A", "diagnostics").andExpect(status().isCreated).json()["id"].asText()
        next(tillB).andExpect(status().isNoContent)
        next(tillC).andExpect(status().isNoContent)
        next(tillA).andExpect(status().isOk)
        // Another till of the same pharmacy and a till of another pharmacy see no such command.
        result(tillB, id, "done", "spoofed", zip).andExpect(status().isNotFound)
        result(tillC, id, "done", "spoofed", zip).andExpect(status().isNotFound)
        assertThat(storage.keys()).isEmpty()
        // The retired fleet key carries no till identity, a missing or unknown key is refused.
        mvc.perform(post("/api/posm/commands/next").header("X-Posm-Key", "dev-posm-key")).andExpect(status().isUnauthorized)
        mvc.perform(post("/api/posm/commands/next")).andExpect(status().isUnauthorized)
        mvc.perform(post("/api/posm/commands/next").header("X-Posm-Key", "x".repeat(43))).andExpect(status().isUnauthorized)
        result("dev-posm-key", id, "done", "spoofed", null).andExpect(status().isUnauthorized)
        result(tillA, id, "done", "Готово", zip).andExpect(status().isOk)
    }

    @Test fun `a revoked till key stops both the till and new commands for it`() {
        val id = create("KASSA-A", "diagnostics").andExpect(status().isCreated).json()["id"].asText()
        jdbc.update("UPDATE fulfillment_devices SET active = false, revoked_at = now() WHERE device_id = 'KASSA-A'")
        next(tillA).andExpect(status().isUnauthorized)
        create("KASSA-B", "diagnostics").andExpect(status().isCreated)
        jdbc.update("UPDATE till_commands SET status = 'expired', finished_at = now() WHERE id = ?::uuid", id)
        create("KASSA-A", "diagnostics").andExpect(status().isNotFound)
        create("KASSA-X", "diagnostics").andExpect(status().isNotFound)
        // A till of a closed pharmacy cannot authenticate, so it gets no command either.
        jdbc.update("UPDATE pharmacies SET active = false WHERE id = ?", OTHER_PHARMACY)
        createFor(OTHER_PHARMACY, "KASSA-C", "diagnostics").andExpect(status().isNotFound)
    }

    @Test fun `a waiting command expires after 30 minutes and a taken one after its answer window`() {
        val waiting = create("KASSA-A", "restart").andExpect(status().isCreated).json()["id"].asText()
        jdbc.update(
            "UPDATE till_commands SET created_at = now() - interval '31 minutes', expires_at = now() - interval '1 minute' WHERE id = ?::uuid",
            waiting,
        )
        // HQ sees it expired at once, before any write marks it.
        list(PHARMACY, "KASSA-A").andExpect(jsonPath("$[0].status").value("expired"))
            .andExpect(jsonPath("$[0].finishedAt").isNotEmpty)
        assertThat(rowStatus(waiting)).isEqualTo("pending")
        next(tillA).andExpect(status().isNoContent)
        assertThat(rowStatus(waiting)).isEqualTo("expired")

        val taken = create("KASSA-A", "diagnostics").andExpect(status().isCreated).json()["id"].asText()
        next(tillA).andExpect(status().isOk)
        jdbc.update("UPDATE till_commands SET expires_at = now() - interval '1 second' WHERE id = ?::uuid", taken)
        result(tillA, taken, "done", "late", zip).andExpect(status().isConflict)
        assertThat(rowStatus(taken)).isEqualTo("expired")
        assertThat(storage.keys()).isEmpty()
        // An elapsed command no longer blocks the till.
        create("KASSA-A", "restart").andExpect(status().isCreated)
    }

    @Test fun `archive is limited to 5 MB and must be a zip of a diagnostics command`() {
        val id = create("KASSA-A", "diagnostics").andExpect(status().isCreated).json()["id"].asText()
        next(tillA).andExpect(status().isOk)
        val max = TillCommandRules.MAX_ARCHIVE_BYTES.toInt()
        result(tillA, id, "done", null, zipOf(max + 1)).andExpect(status().isPayloadTooLarge)
        result(tillA, id, "done", null, "not a zip".toByteArray()).andExpect(status().isBadRequest)
        result(tillA, id, "finished", null, zip).andExpect(status().isBadRequest)
        assertThat(rowStatus(id)).isEqualTo("delivered")
        assertThat(storage.keys()).isEmpty()
        // Exactly 5 MB is accepted.
        result(tillA, id, "done", "x".repeat(2500), zipOf(max)).andExpect(status().isOk)
        assertThat(jdbc.queryForObject("SELECT artifact_bytes FROM till_commands WHERE id = ?::uuid", Long::class.java, id))
            .isEqualTo(max.toLong())
        assertThat(jdbc.queryForObject("SELECT length(result_message) FROM till_commands WHERE id = ?::uuid", Int::class.java, id))
            .isEqualTo(2000)

        val restart = create("KASSA-B", "restart").andExpect(status().isCreated).json()["id"].asText()
        next(tillB).andExpect(status().isOk)
        result(tillB, restart, "done", "Перезапуск через планировщик", zip).andExpect(status().isBadRequest)
        result(tillB, restart, "done", "Перезапуск через планировщик", null).andExpect(status().isOk)
    }

    @Test fun `HQ list is newest first, filters by till and is closed to other roles`() {
        create("KASSA-A", "diagnostics").andExpect(status().isCreated)
        Thread.sleep(5)
        create("KASSA-B", "restart").andExpect(status().isCreated)
        Thread.sleep(5)
        createFor(OTHER_PHARMACY, "KASSA-C", "diagnostics").andExpect(status().isCreated)

        mvc.perform(get("/api/admin/tills/commands").bearer(hq)).andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.length()").value(3))
            .andExpect(jsonPath("$[0].deviceId").value("KASSA-C"))
            .andExpect(jsonPath("$[1].deviceId").value("KASSA-B"))
            .andExpect(jsonPath("$[2].deviceId").value("KASSA-A"))
        list(PHARMACY, null).andExpect(jsonPath("$.length()").value(2))
        list(PHARMACY, "KASSA-B").andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].action").value("restart"))
        mvc.perform(get("/api/admin/tills/commands").param("limit", "1").bearer(hq))
            .andExpect(jsonPath("$.length()").value(1))

        create("KASSA-A", "format-c").andExpect(status().isBadRequest)
        mvc.perform(post("/api/admin/tills/commands").bearer(hq).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest)
        mvc.perform(get("/api/admin/tills/commands/${UUID.randomUUID()}/artifact").bearer(hq)).andExpect(status().isNotFound)

        mvc.perform(get("/api/admin/tills/commands").bearer(categoryLead)).andExpect(status().isForbidden)
        create("KASSA-A", "diagnostics", categoryLead).andExpect(status().isForbidden)
        mvc.perform(get("/api/admin/tills/commands")).andExpect(status().isUnauthorized)
    }

    @Test fun `archives leave storage after 14 days, the size stays for history`() {
        val id = create("KASSA-A", "diagnostics").andExpect(status().isCreated).json()["id"].asText()
        next(tillA).andExpect(status().isOk)
        result(tillA, id, "done", null, zip).andExpect(status().isOk)
        assertThat(commands.removeOldArchives()).isZero()
        jdbc.update("UPDATE till_commands SET finished_at = now() - interval '15 days' WHERE id = ?::uuid", id)
        assertThat(commands.removeOldArchives()).isEqualTo(1)
        assertThat(storage.keys()).isEmpty()
        list(PHARMACY, "KASSA-A").andExpect(jsonPath("$[0].artifactAvailable").value(false))
            .andExpect(jsonPath("$[0].artifactBytes").value(zip.size))
        mvc.perform(get("/api/admin/tills/commands/$id/artifact").bearer(hq)).andExpect(status().isNotFound)

        val stale = create("KASSA-B", "restart").andExpect(status().isCreated).json()["id"].asText()
        jdbc.update("UPDATE till_commands SET expires_at = now() - interval '1 minute' WHERE id = ?::uuid", stale)
        assertThat(commands.expireAllOverdue()).isEqualTo(1)
        assertThat(rowStatus(stale)).isEqualTo("expired")
    }

    private fun till(deviceId: String, pharmacyId: String): String {
        val token = "tc-${UUID.randomUUID()}-${UUID.randomUUID()}".take(64)
        jdbc.update(
            """INSERT INTO fulfillment_devices (id, device_id, pharmacy_id, secret_sha256, secret_hint, active)
               VALUES (?, ?, ?, ?, 'tc', true)""",
            UUID.randomUUID(),
            deviceId,
            pharmacyId,
            crypto.sha256Hex(token),
        )
        return token
    }

    private fun token(email: String, role: AdminRole): String {
        users.saveAndFlush(AdminUserEntity(email = email, name = "Synthetic", roleRaw = role.name, passwordHash = encoder.encode(PASSWORD)))
        return auth.login(email, PASSWORD).tokens.accessToken
    }

    private fun create(deviceId: String, action: String, bearer: String = hq) = createFor(PHARMACY, deviceId, action, bearer)

    private fun createFor(pharmacyId: String, deviceId: String, action: String, bearer: String = hq): ResultActions =
        mvc.perform(
            post("/api/admin/tills/commands").bearer(bearer).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("pharmacyId" to pharmacyId, "deviceId" to deviceId, "action" to action))),
        )

    private fun list(pharmacyId: String?, deviceId: String?): ResultActions {
        val request = get("/api/admin/tills/commands").bearer(hq)
        pharmacyId?.let { request.param("pharmacyId", it) }
        deviceId?.let { request.param("deviceId", it) }
        return mvc.perform(request)
    }

    private fun next(key: String): ResultActions = mvc.perform(post("/api/posm/commands/next").header("X-Posm-Key", key))

    private fun result(key: String, id: String, status: String, message: String?, file: ByteArray?): ResultActions {
        val request = multipart("/api/posm/commands/$id/result")
        file?.let { request.file(MockMultipartFile("file", "diagnostics.zip", "application/zip", it)) }
        request.header("X-Posm-Key", key)
        request.param("status", status)
        message?.let { request.param("message", it) }
        return mvc.perform(request)
    }

    private fun zipOf(size: Int) = ByteArray(size).also {
        it[0] = 0x50
        it[1] = 0x4B
        it[2] = 0x03
        it[3] = 0x04
    }

    private fun rowStatus(id: String) =
        jdbc.queryForObject("SELECT status FROM till_commands WHERE id = ?::uuid", String::class.java, id)

    private fun ResultActions.json(): JsonNode = mapper.readTree(andReturn().response.contentAsString)

    private fun org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder.bearer(token: String) =
        header("Authorization", "Bearer $token")
}
