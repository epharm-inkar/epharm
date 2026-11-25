package kz.epharm.posm

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.posm.self.PosmSelfRequest
import kz.epharm.posm.self.PosmSelfService
import kz.epharm.posm.self.PosmSelfTasksService
import kz.epharm.posm.service.PosmDeviceIdentity
import kz.epharm.shared.error.AppException
import kz.epharm.training.dto.StageProgressRequest
import kz.epharm.training.service.TrainingService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Real PostgreSQL: the till's «Задания» list built from training assignments of the person signed in to
 * Standard-N at the till. Synthetic data only; no real task is created anywhere.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class PosmSelfTasksIntegrationTest {
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
    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var json: ObjectMapper
    @Autowired private lateinit var users: AdminUserRepository
    @Autowired private lateinit var tasks: PosmSelfTasksService
    @Autowired private lateinit var self: PosmSelfService
    @Autowired private lateinit var training: TrainingService

    private val pharmacy = "tf-pharmacy"
    private val otherPharmacy = "tf-other-pharmacy"
    private val key = "tasks-feed-synthetic-device-secret-0000000001"
    private val otherKey = "tasks-feed-synthetic-device-secret-0000000002"
    private val device = PosmDeviceIdentity("tf-device", pharmacy, legacy = false)
    private val person = "tf-person"
    private val iin = "000101500077"
    private val now = Instant.now()
    private lateinit var admin: UUID

    @BeforeEach fun seed() {
        // This class's own disposable PG16. TRUNCATE, not DELETE: the wallet ledger is append-only and a
        // completed course in one test credits it.
        jdbc.execute(
            "TRUNCATE training_programs, training_assignments, pharmacists, standardn_pharmacist_directory, fulfillment_devices CASCADE",
        )
        jdbc.update("DELETE FROM pharmacies WHERE id LIKE 'tf-%'")
        jdbc.update("""INSERT INTO chains(id,name,"group") VALUES ('tf-chain','Synthetic','pilot') ON CONFLICT DO NOTHING""")
        for ((id, profile) in listOf(pharmacy to 7201, otherPharmacy to 7202)) {
            jdbc.update(
                """INSERT INTO pharmacies(id,name,chain_id,chain_name,city,"group",standardn_profile_id,standardn_profile_source)
                   VALUES (?, ?, 'tf-chain', 'Synthetic', 'Алматы', 'pilot', ?, 'manual')""",
                id, "Synthetic $id", profile,
            )
        }
        for ((user, personIin, name) in listOf(Triple(41, iin, "Синтетикова Айгерим"), Triple(42, "000101500088", "Синтетиков Новый"))) {
            jdbc.update(
                """INSERT INTO standardn_pharmacist_directory(directory_key,source_profile_id,source_user_id,iin,full_name,source_status,profile_name)
                   VALUES (?, 7201, ?, ?, ?, 0, 'Synthetic tf-pharmacy')""",
                "7201:$user", user, personIin, name,
            )
        }
        for ((deviceId, pharmacyId, secret) in listOf(Triple("tf-device", pharmacy, key), Triple("tf-other-device", otherPharmacy, otherKey))) {
            jdbc.update(
                "INSERT INTO fulfillment_devices(id,device_id,pharmacy_id,secret_sha256,secret_hint) VALUES (?,?,?,?,?)",
                UUID.randomUUID(), deviceId, pharmacyId, sha256(secret), "test",
            )
        }
        for ((id, personIin) in listOf(person to iin, "tf-colleague" to "000101500099")) {
            jdbc.update(
                """INSERT INTO pharmacists(id,name,iin,phone,tier,status,pharmacy_id,city,account_source)
                   VALUES (?, 'Синтетикова Айгерим', ?, '', 'Silver', 'active', ?, 'Алматы', 'standardn')""",
                id, personIin, pharmacy,
            )
        }
        admin = users.findByEmailIgnoreCase("tasks-feed@example.invalid").orElseGet {
            users.saveAndFlush(
                AdminUserEntity(email = "tasks-feed@example.invalid", name = "Synthetic training", passwordHash = "unused",
                    roleRaw = AdminRole.TRAINING_MANAGER.name),
            )
        }.id
    }

    @Test fun `statuses map, order is overdue, new, in progress, done, and the JSON is what the till reads`() {
        val overdue = assignment(version("Просроченный курс"), "not_started", dueAt = days(-2))
        val fresh = assignment(
            version("Витамины группы B", bonus = 1500, short = "Как советовать витамины группы B."),
            "not_started", dueAt = days(5),
        )
        val started = assignment(version("Сорбенты"), "waiting_online", startedAt = days(-1), progress = 40, dueAt = days(3))
        val paused = assignment(version("Пауза"), "paused")
        val done = assignment(version("Пройденный курс", bonus = 2000), "completed", startedAt = days(-6), progress = 100, completedAt = days(-3))
        // Not shown: finished long ago, cancelled, not started by HQ yet, another person's task.
        assignment(version("Давно пройденный"), "completed", progress = 100, completedAt = days(-20))
        assignment(version("Отменённый"), "cancelled")
        assignment(version("Позже"), "scheduled", startsAt = days(2), dueAt = days(9))
        assignment(version("Чужой курс"), "not_started", pharmacist = "tf-colleague")

        val body = feed(body = mapOf("pharmacyId" to pharmacy, "standardNUserId" to "41"))

        assertEquals(setOf("tasks", "newCount", "openCount"), body.fieldNames().asSequence().toSet())
        val list = body["tasks"].toList()
        assertEquals(listOf(overdue, fresh, started, paused, done).map(UUID::toString), list.map { it["id"].asText() })
        assertEquals(listOf("overdue", "new", "in_progress", "in_progress", "done"), list.map { it["status"].asText() })
        assertEquals(1, body["newCount"].asInt())
        assertEquals(4, body["openCount"].asInt())

        val task = list[1]
        assertEquals(
            setOf("id", "kind", "title", "instructions", "rewardKzt", "required", "status", "progressPct", "dueAt", "completedAt"),
            task.fieldNames().asSequence().toSet(),
        )
        assertEquals("course", task["kind"].asText())
        assertEquals("Витамины группы B", task["title"].asText())
        assertEquals(
            "Откройте «Мой кабинет», раздел «Обучение», курс «Витамины группы B». Как советовать витамины группы B.",
            task["instructions"].asText(),
        )
        assertTrue(task["rewardKzt"].isIntegralNumber)
        assertEquals(1500L, task["rewardKzt"].asLong())
        assertTrue(task["required"].asBoolean())
        assertEquals(0, task["progressPct"].asInt())
        assertEquals(days(5).epochSecond, Instant.parse(task["dueAt"].asText()).epochSecond)
        assertTrue(task["completedAt"].isNull)

        // No completion bonus: the reward is an explicit null, not a zero.
        assertTrue(list[0]["rewardKzt"].isNull)
        assertEquals(40, list[2]["progressPct"].asInt())
        assertTrue(list[3]["dueAt"].isNull)
        assertEquals(2000L, list[4]["rewardKzt"].asLong())
        assertEquals(100, list[4]["progressPct"].asInt())
        assertEquals(days(-3).epochSecond, Instant.parse(list[4]["completedAt"].asText()).epochSecond)
    }

    @Test fun `a course completed through the training module turns the task done, with its bonus`() {
        val version = version("Материал для фармацевта", bonus = 1200)
        val stageDefinition = UUID.randomUUID()
        jdbc.update(
            """INSERT INTO training_program_stages(id,version_id,stage_key,type,title,order_no,required,applicable_formats)
               VALUES (?, ?, 'read', 'material', 'Прочитать материал', 0, true, 'online')""",
            stageDefinition, version,
        )
        val assignment = assignment(version, "not_started", dueAt = days(7))
        val stage = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO training_assignment_stages(id,assignment_id,program_stage_id,status) VALUES (?, ?, ?, 'available')",
            stage, assignment, stageDefinition,
        )
        assertEquals("new", feed()["tasks"][0]["status"].asText())

        training.startAssignment(person, assignment)
        assertEquals("in_progress", feed()["tasks"][0]["status"].asText())

        training.updateStageProgress(person, assignment, stage, StageProgressRequest(100))
        val task = feed()["tasks"][0]
        assertEquals("done", task["status"].asText())
        assertEquals(100, task["progressPct"].asInt())
        assertFalse(task["completedAt"].isNull)
        assertEquals(1200L, task["rewardKzt"].asLong())
        assertEquals(0, feed()["openCount"].asInt())
        assertEquals(1200L, jdbc.queryForObject("SELECT amount FROM training_rewards WHERE assignment_id = ?", Long::class.java, assignment))
    }

    @Test fun `the till never saves what else the window sends, and a person seen first gets an empty list`() {
        val body = feed(
            body = mapOf("pharmacyId" to pharmacy, "standardNUserId" to "42", "phone" to "+77015550000", "password" to "synthetic-pass-1"),
        )
        assertTrue(body["tasks"].isEmpty)
        assertEquals(0, body["newCount"].asInt())
        assertEquals(0, body["openCount"].asInt())
        val card = jdbc.queryForMap("SELECT phone, password_hash, account_source FROM pharmacists WHERE iin = '000101500088'")
        assertEquals("", card["phone"])
        assertEquals(null, card["password_hash"])
        assertEquals("standardn", card["account_source"])
    }

    @Test fun `an unknown Standard-N user gets the same 404 as the personal window`() {
        val unknown = PosmSelfRequest(pharmacyId = pharmacy, standardNUserId = "99")
        val error = assertThrows(AppException::class.java) { tasks.tasks(device, unknown) }
        val window = assertThrows(AppException::class.java) { self.view(device, unknown.copy(link = false)) }
        assertEquals(404, error.status.value())
        assertEquals(window.message, error.message)
        val body = feed(body = mapOf("pharmacyId" to pharmacy, "standardNUserId" to "99"), expected = 404)
        assertEquals("USER_NOT_FOUND", body["code"].asText())
    }

    @Test fun `a till of another pharmacy is refused like the personal window`() {
        val foreign = PosmDeviceIdentity("tf-device", "tf-foreign", legacy = false)
        val request = PosmSelfRequest(pharmacyId = pharmacy, standardNUserId = "41")
        val error = assertThrows(AppException::class.java) { tasks.tasks(foreign, request) }
        val window = assertThrows(AppException::class.java) { self.view(foreign, request.copy(link = false)) }
        assertEquals(403, error.status.value())
        assertEquals(window.message, error.message)
        // Over HTTP the device key of another pharmacy does not even authenticate for this pharmacy.
        feed(credential = otherKey, expected = 401)
        feed(credential = null, expected = 401)
    }

    @Test fun `a blocked card gets 403`() {
        jdbc.update("UPDATE pharmacists SET status = 'blocked' WHERE id = ?", person)
        val error = assertThrows(AppException::class.java) {
            tasks.tasks(device, PosmSelfRequest(pharmacyId = pharmacy, standardNUserId = "41"))
        }
        assertEquals(403, error.status.value())
    }

    private fun feed(
        body: Map<String, String> = mapOf("pharmacyId" to pharmacy, "standardNUserId" to "41"),
        credential: String? = key,
        expected: Int = 200,
    ): JsonNode {
        val request = post("/api/posm/self/tasks").contentType("application/json").content(json.writeValueAsBytes(body))
        credential?.let { request.header("X-Posm-Key", it) }
        val result = mvc.perform(request).andExpect(status().`is`(expected)).andReturn()
        return json.readTree(result.response.contentAsByteArray)
    }

    private fun version(name: String, bonus: Long = 0, short: String = "", description: String = ""): UUID {
        val program = UUID.randomUUID()
        val version = UUID.randomUUID()
        jdbc.update(
            """INSERT INTO training_programs(id,name,short_description,description,status,allowed_formats,created_by)
               VALUES (?, ?, ?, ?, 'published', 'online', ?)""",
            program, name, short, description, admin,
        )
        jdbc.update(
            """INSERT INTO training_program_versions(id,program_id,version_no,allowed_formats,completion_bonus,snapshot_json,created_by)
               VALUES (?, ?, 1, 'online', ?, '{}', ?)""",
            version, program, bonus, admin,
        )
        return version
    }

    private fun assignment(
        version: UUID,
        status: String,
        pharmacist: String = person,
        startedAt: Instant? = null,
        progress: Int = 0,
        startsAt: Instant? = null,
        dueAt: Instant? = null,
        completedAt: Instant? = null,
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            """INSERT INTO training_assignments(id,program_version_id,pharmacist_id,format,status,assigned_by,starts_at,due_at,
                   progress_pct,started_at,completed_at,created_at,updated_at)
               VALUES (?, ?, ?, 'online', ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            id, version, pharmacist, status, admin, ts(startsAt), ts(dueAt), progress, ts(startedAt), ts(completedAt),
            ts(days(-30)), ts(completedAt ?: days(-1)),
        )
        return id
    }

    private fun days(n: Long): Instant = now.plus(Duration.ofDays(n))
    private fun ts(value: Instant?): Timestamp? = value?.let(Timestamp::from)
    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
