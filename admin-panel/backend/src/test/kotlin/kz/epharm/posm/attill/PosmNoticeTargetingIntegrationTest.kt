package kz.epharm.posm.attill

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.auth.service.AdminAuthService
import kz.epharm.fulfillment.security.FulfillmentCrypto
import kz.epharm.posm.service.PosmDeviceIdentity
import org.assertj.core.api.Assertions.assertThat
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
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Who is at a till right now and notices to the people who have not finished a course or sit at a till (owner,
 * 26.11.2026), on real PostgreSQL through the real security chain: HQ JWT for the admin side, per-device POSM keys for
 * the till side. The till names the Standard-N user with its check (`/api/posm/self/messages`); the server resolves the
 * person through the directory and records who sits at the till. Synthetic pharmacies, people, courses and notices only.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class PosmNoticeTargetingIntegrationTest {
    companion object {
        @Container @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("posm_targeting_test").withUsername("epharm").withPassword("local-test-only")
            .apply { start() }

        @JvmStatic @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
        }

        private const val PASSWORD = "Synthetic-admin-pass-1"
        private const val PHARMACY = "nt-pharmacy-a"
        private const val OTHER = "nt-pharmacy-b"
        private const val AIGERIM = "ph_nt_aigerim"
        private const val ERBOL = "ph_nt_erbol"
        private const val DANA = "ph_nt_dana"
        private val ALMATY: ZoneId = ZoneId.of("Asia/Almaty")
    }

    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var mapper: ObjectMapper
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var crypto: FulfillmentCrypto
    @Autowired private lateinit var users: AdminUserRepository
    @Autowired private lateinit var auth: AdminAuthService
    @Autowired private lateinit var encoder: PasswordEncoder
    @Autowired private lateinit var presence: TillPersonPresence

    private lateinit var hq: String
    private lateinit var categoryLead: String
    private lateinit var admin: UUID
    private lateinit var tillA: String
    private lateinit var tillB: String
    private val versions = HashMap<UUID, UUID>()

    @BeforeEach fun seed() {
        // This class's own disposable PG16: receipts, targets and recipients go with the notices and the cards.
        jdbc.execute(
            "TRUNCATE posm_messages, training_programs, training_assignments, pharmacists, standardn_pharmacist_directory, fulfillment_devices CASCADE",
        )
        jdbc.update("DELETE FROM pharmacies WHERE id LIKE 'nt-%'")
        presence.forgetAll()
        versions.clear()
        jdbc.update("""INSERT INTO chains(id, name, "group") VALUES ('nt-chain', 'Synthetic', 'pilot') ON CONFLICT DO NOTHING""")
        for ((id, profile, address) in listOf(Triple(PHARMACY, 7501, "Абая 103"), Triple(OTHER, 7502, "Толе би 286"))) {
            jdbc.update(
                """INSERT INTO pharmacies(id, name, chain_id, chain_name, city, addr, "group", standardn_profile_id, standardn_profile_source)
                   VALUES (?, 'Аптека со склада', 'nt-chain', 'Synthetic', 'Алматы', ?, 'pilot', ?, 'manual')""",
                id, address, profile,
            )
        }
        // Standard-N users: 71 and 72 work at pharmacy A, 81 at pharmacy B.
        listOf(
            Directory(7501, 71, "000101500111", "Сапарова Айгерим Серикқызы"),
            Directory(7501, 72, "000101500122", "Тұрсынов Ербол"),
            Directory(7502, 81, "000101500133", "Омарова Дана"),
        ).forEach { row ->
            jdbc.update(
                """INSERT INTO standardn_pharmacist_directory(directory_key, source_profile_id, source_user_id, iin, full_name, source_status, profile_name)
                   VALUES (?, ?, ?, ?, ?, 0, 'Synthetic')""",
                "${row.profile}:${row.user}", row.profile, row.user, row.iin, row.name,
            )
        }
        card(AIGERIM, "Сапарова Айгерим Серикқызы", "000101500111", PHARMACY, phone = "+77011234567")
        card(ERBOL, "Тұрсынов Ербол", "000101500122", PHARMACY)
        card(DANA, "Омарова Дана", "000101500133", OTHER, phone = "+77025551234")
        tillA = till("KASSA-A", PHARMACY)
        tillB = till("KASSA-B", OTHER)
        val hqEmail = "hq-${UUID.randomUUID()}@example.invalid"
        hq = token(hqEmail, AdminRole.HQ_HEAD)
        admin = users.findByEmailIgnoreCase(hqEmail).get().id
        categoryLead = token("lead-${UUID.randomUUID()}@example.invalid", AdminRole.CATEGORY_LEAD)
    }

    // ── who is at a till ────────────────────────────────────────────────

    @Test fun `the page lists who each till last named, and a colleague who signs in takes the till over`() {
        assertThat(atTill()["total"].asInt()).isZero()

        checkIn(tillA, PHARMACY, "71")
        checkIn(tillB, OTHER, "81")
        val page = atTill()
        assertThat(page["total"].asInt()).isEqualTo(2)
        assertThat(page["shown"].asInt()).isEqualTo(2)
        assertThat(page["windowMinutes"].asInt()).isEqualTo(20)
        val aigerim = page["items"].single { it["pharmacistId"].asText() == AIGERIM }
        assertThat(aigerim["name"].asText()).isEqualTo("Сапарова Айгерим Серикқызы")
        assertThat(aigerim["pharmacyId"].asText()).isEqualTo(PHARMACY)
        assertThat(aigerim["pharmacyName"].asText()).isEqualTo("Аптека со склада")
        assertThat(aigerim["city"].asText()).isEqualTo("Алматы")
        assertThat(aigerim["address"].asText()).isEqualTo("Абая 103")
        assertThat(aigerim["deviceId"].asText()).isEqualTo("KASSA-A")
        assertThat(aigerim["homePharmacyId"].asText()).isEqualTo(PHARMACY)
        assertThat(aigerim["phoneTail"].asText()).isEqualTo("4567")
        assertThat(Instant.parse(aigerim["seenAt"].asText())).isAfter(Instant.now().minusSeconds(60))
        // The answer never carries the phone or the IIN, only the last four digits of the phone.
        assertThat(aigerim.fieldNames().asSequence().toSet()).doesNotContain("phone", "iin")
        assertThat(aigerim.toString()).doesNotContain("77011234567", "000101500111")

        // A colleague signs in at the same till: it names one person, the latest.
        checkIn(tillA, PHARMACY, "72")
        val after = atTill()
        assertThat(after["items"].map { it["pharmacistId"].asText() }).containsExactlyInAnyOrder(ERBOL, DANA)
    }

    @Test fun `a till that went offline, or a person not named for twenty minutes, is no longer at a till`() {
        checkIn(tillA, PHARMACY, "71")
        checkIn(tillB, OTHER, "81")
        assertThat(atTill()["total"].asInt()).isEqualTo(2)

        // The till of pharmacy A stopped sending its pulse more than 150 seconds ago.
        jdbc.update("UPDATE fulfillment_devices SET last_seen_at = now() - interval '10 minutes' WHERE device_id = 'KASSA-A'")
        assertThat(atTill()["items"].map { it["pharmacistId"].asText() }).containsExactly(DANA)

        // Pharmacy B's till is online but its last check named the person 25 minutes ago (a record never goes back in
        // time, so the live one is forgotten first).
        presence.forgetAll()
        presence.record(PosmDeviceIdentity("KASSA-B", OTHER, legacy = false), DANA, Instant.now().minus(Duration.ofMinutes(25)))
        assertThat(atTill()["total"].asInt()).isZero()
    }

    @Test fun `the list is filtered by the pharmacy of the till and by text, and still says how many are at tills`() {
        checkIn(tillA, PHARMACY, "71")
        checkIn(tillB, OTHER, "81")

        val byPharmacy = atTill(pharmacyId = PHARMACY)
        assertThat(byPharmacy["total"].asInt()).isEqualTo(2)
        assertThat(byPharmacy["shown"].asInt()).isEqualTo(1)
        assertThat(byPharmacy["items"].single()["pharmacistId"].asText()).isEqualTo(AIGERIM)

        assertThat(atTill(q = "омарова")["items"].map { it["pharmacistId"].asText() }).containsExactly(DANA)
        // By phone digits however they are typed, and by IIN.
        assertThat(atTill(q = "+7 (701) 123-45-67")["items"].map { it["pharmacistId"].asText() }).containsExactly(AIGERIM)
        assertThat(atTill(q = "000101500133")["items"].map { it["pharmacistId"].asText() }).containsExactly(DANA)
        // By the address of the pharmacy, together with a name.
        assertThat(atTill(q = "Толе Дана")["items"].map { it["pharmacistId"].asText() }).containsExactly(DANA)
        assertThat(atTill(q = "Ербол")["shown"].asInt()).isZero()
        assertThat(atTill(pharmacyId = OTHER, q = "Айгерим")["shown"].asInt()).isZero()
    }

    @Test fun `the roster shows who is at a till and when each person was last seen at one`() {
        checkIn(tillA, PHARMACY, "71")
        val roster = mvc.perform(get("/api/admin/pharmacists").bearer(hq)).andExpect(status().isOk).json()
        val aigerim = roster.single { it["id"].asText() == AIGERIM }
        assertThat(aigerim["atTillNow"].asBoolean()).isTrue()
        assertThat(Instant.parse(aigerim["lastTillSeenAt"].asText())).isAfter(Instant.now().minusSeconds(60))
        val erbol = roster.single { it["id"].asText() == ERBOL }
        assertThat(erbol["atTillNow"].asBoolean()).isFalse()
        assertThat(erbol["lastTillSeenAt"].isNull).isTrue()

        // The till went offline: the person was at a till a moment ago, not now.
        jdbc.update("UPDATE fulfillment_devices SET last_seen_at = now() - interval '10 minutes' WHERE device_id = 'KASSA-A'")
        val later = mvc.perform(get("/api/admin/pharmacists").bearer(hq)).andExpect(status().isOk).json()
            .single { it["id"].asText() == AIGERIM }
        assertThat(later["atTillNow"].asBoolean()).isFalse()
        assertThat(later["lastTillSeenAt"].isNull).isFalse()
        assertThat(mvc.perform(get("/api/admin/pharmacists/$AIGERIM").bearer(hq)).andExpect(status().isOk).json()["lastTillSeenAt"].isNull).isFalse()
    }

    @Test fun `every check of a till writes the last seen of the person once in five minutes`() {
        checkIn(tillA, PHARMACY, "71")
        checkIn(tillA, PHARMACY, "71")
        val row = jdbc.queryForMap("SELECT pharmacist_id, pharmacy_id, device_id, seen_at FROM pharmacist_till_seen")
        assertThat(row["pharmacist_id"]).isEqualTo(AIGERIM)
        assertThat(row["pharmacy_id"]).isEqualTo(PHARMACY)
        assertThat(row["device_id"]).isEqualTo("KASSA-A")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pharmacist_till_seen", Long::class.java)).isEqualTo(1L)
    }

    @Test fun `the personal window and the tasks feed name the person at the till as well`() {
        mvc.perform(till(post("/api/posm/self"), tillA, PHARMACY, "71")).andExpect(status().isOk)
        assertThat(atTill()["items"].map { it["pharmacistId"].asText() }).containsExactly(AIGERIM)
        mvc.perform(till(post("/api/posm/self/tasks"), tillB, OTHER, "81")).andExpect(status().isOk)
        assertThat(atTill()["items"].map { it["pharmacistId"].asText() }).containsExactlyInAnyOrder(AIGERIM, DANA)
    }

    @Test fun `a report that a notice was closed does not put a person at the till`() {
        val id = send(mapOf("title" to "Всем", "text" to "Текст.", "audience" to "all")).json()["id"].asText()
        mvc.perform(till(post("/api/posm/self/messages/$id/closed"), tillA, PHARMACY, "71")).andExpect(status().isOk)
        // A report can be resent hours later by a till that was offline, so it says nothing about who sits there now.
        assertThat(atTill()["total"].asInt()).isZero()
    }

    @Test fun `only HQ sees who is at a till`() {
        mvc.perform(get("/api/admin/posm-at-till").bearer(categoryLead)).andExpect(status().isForbidden)
        mvc.perform(get("/api/admin/posm-at-till")).andExpect(status().isUnauthorized)
        mvc.perform(get("/api/admin/posm-at-till").bearer(hq)).andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
    }

    // ── notices to the people at a till ─────────────────────────────────

    @Test fun `a notice only for those at a till reaches the people there when it was sent and nobody who sits down later`() {
        checkIn(tillA, PHARMACY, "71")
        checkIn(tillB, OTHER, "81")

        val created = send(mapOf("title" to "Срочно", "text" to "Зайдите в кабинет.", "audience" to "all", "onlyAtTill" to true))
            .andExpect(status().isCreated).json()
        val id = created["id"].asText()
        assertThat(created["audience"].asText()).isEqualTo("all")
        assertThat(created["onlyAtTill"].asBoolean()).isTrue()
        assertThat(created["recipientsTotal"].asInt()).isEqualTo(2)
        assertThat(jdbc.queryForList("SELECT pharmacist_id FROM posm_message_recipients ORDER BY pharmacist_id", String::class.java))
            .containsExactly(AIGERIM, DANA)

        assertThat(feed(tillA, PHARMACY, "71").json()["pendingCount"].asInt()).isEqualTo(1)
        assertThat(feed(tillB, OTHER, "81").json()["pendingCount"].asInt()).isEqualTo(1)
        // Erbol sits down at a till after the notice was sent: the check of his till names him, but the notice is not his.
        val erbol = feed(tillA, PHARMACY, "72").json()
        assertThat(erbol["messages"].isEmpty).isTrue()
        ack(tillA, PHARMACY, "72", id, "shown").andExpect(status().isNotFound)
        assertThat(atTill()["items"].map { it["pharmacistId"].asText() }).contains(ERBOL)

        ack(tillA, PHARMACY, "71", id, "shown").andExpect(status().isOk)
        ack(tillA, PHARMACY, "71", id, "closed").andExpect(status().isOk)
        val counts = listed(id)
        assertThat(counts["shownCount"].asInt()).isEqualTo(1)
        assertThat(counts["closedCount"].asInt()).isEqualTo(1)
        assertThat(counts["recipientsTotal"].asInt()).isEqualTo(2)
    }

    @Test fun `the switch narrows an audience of pharmacies and of chosen people to those at a till now`() {
        checkIn(tillA, PHARMACY, "71")

        val pharmacies = send(mapOf("title" to "Аптеке", "text" to "Текст.", "audience" to "pharmacies", "pharmacyIds" to listOf(PHARMACY, OTHER), "onlyAtTill" to true))
            .andExpect(status().isCreated).json()
        assertThat(pharmacies["recipientsTotal"].asInt()).isEqualTo(1)
        assertThat(pharmacies["targetCount"].asInt()).isEqualTo(2)
        assertThat(pharmacies["onlyAtTill"].asBoolean()).isTrue()

        val people = send(mapOf("title" to "Людям", "text" to "Текст.", "audience" to "pharmacists", "pharmacistIds" to listOf(AIGERIM, DANA), "onlyAtTill" to true))
            .andExpect(status().isCreated).json()
        assertThat(people["recipientsTotal"].asInt()).isEqualTo(1)
        assertThat(people["targetNames"].map(JsonNode::asText)).containsExactlyInAnyOrder("Сапарова Айгерим Серикқызы", "Омарова Дана")
        // Dana was chosen but not at a till: her notice does not exist, even when she appears later.
        assertThat(feed(tillB, OTHER, "81").json()["messages"].isEmpty).isTrue()
    }

    @Test fun `with nobody at a till the notice is refused and says why`() {
        val message = send(mapOf("title" to "Срочно", "text" to "Текст.", "audience" to "all", "onlyAtTill" to true))
            .andExpect(status().isBadRequest).json()["message"].asText()
        assertThat(message).startsWith("Сейчас за кассой нет никого из адресатов")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM posm_messages", Long::class.java)).isZero()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM posm_message_recipients", Long::class.java)).isZero()
    }

    // ── notices to the people who have not finished a course ────────────

    @Test fun `a course reminder goes to the people who have not finished it and stops for those who finish`() {
        val dueDay = LocalDate.now(ALMATY).plusDays(5)
        val due = dueDay.atTime(12, 0).atZone(ALMATY).toInstant()
        val course = program("Витамины группы B")
        val aigerimsTask = assign(course, AIGERIM, "not_started", dueAt = due)
        val erbolsTask = assign(course, ERBOL, "in_progress", dueAt = due)
        assign(course, DANA, "completed")
        // Not people HQ waits for: paused, cancelled, blocked, not started by HQ yet.
        assign(course, card("ph_nt_paused", "Пауза Пауза", "000101500201", PHARMACY), "paused", dueAt = due)
        assign(course, card("ph_nt_cancelled", "Отмена Отмена", "000101500202", PHARMACY), "cancelled")
        assign(course, card("ph_nt_blocked", "Блок Блок", "000101500203", PHARMACY, status = "blocked"), "not_started", dueAt = due)
        assign(course, card("ph_nt_later", "Позже Позже", "000101500204", PHARMACY), "scheduled", startsAt = Instant.now().plus(Duration.ofDays(2)), dueAt = due)
        // Courses that are not offered: in the archive, or finished by everybody.
        assign(program("Архивный курс", archived = true), AIGERIM, "not_started")
        assign(program("Все прошли"), AIGERIM, "completed")

        val courses = mvc.perform(get("/api/admin/posm-messages/courses").bearer(hq)).andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store")).json()
        assertThat(courses.map { it["name"].asText() }).containsExactly("Витамины группы B")
        assertThat(courses[0]["id"].asText()).isEqualTo(course.toString())
        assertThat(courses[0]["openCount"].asInt()).isEqualTo(2)
        assertThat(courses[0]["dueOn"].asText()).isEqualTo(dueDay.toString())

        val preview = preview(mapOf("audience" to "course", "programId" to course.toString())).json()
        assertThat(preview["recipients"].asInt()).isEqualTo(2)
        assertThat(preview["frozen"].asBoolean()).isTrue()
        assertThat(preview["atTillNow"].asInt()).isZero()
        assertThat(preview["sample"].map { it["name"].asText() }).containsExactly("Сапарова Айгерим Серикқызы", "Тұрсынов Ербол")
        assertThat(preview["sampleMore"].asInt()).isZero()

        val created = send(
            mapOf("title" to "Напоминание о курсе", "text" to "Пройдите курс «Витамины группы B» до ${dueDay.dayOfMonth}.", "audience" to "course", "programId" to course.toString()),
        ).andExpect(status().isCreated).json()
        val id = created["id"].asText()
        assertThat(created["audience"].asText()).isEqualTo("course")
        assertThat(created["programId"].asText()).isEqualTo(course.toString())
        assertThat(created["programName"].asText()).isEqualTo("Витамины группы B")
        assertThat(created["onlyAtTill"].asBoolean()).isFalse()
        assertThat(created["recipientsTotal"].asInt()).isEqualTo(2)
        assertThat(created["targetCount"].asInt()).isZero()

        assertThat(feed(tillA, PHARMACY, "71").json()["pendingCount"].asInt()).isEqualTo(1)
        assertThat(feed(tillA, PHARMACY, "72").json()["pendingCount"].asInt()).isEqualTo(1)
        // Dana finished it already: not a recipient.
        assertThat(feed(tillB, OTHER, "81").json()["messages"].isEmpty).isTrue()

        // Erbol finishes the course before he sees the notice: the next check of his till does not bring it.
        jdbc.update("UPDATE training_assignments SET status = 'completed', completed_at = now() WHERE id = ?", erbolsTask)
        val afterCompletion = feed(tillA, PHARMACY, "72").json()
        assertThat(afterCompletion["messages"].isEmpty).isTrue()
        assertThat(afterCompletion["pendingCount"].asInt()).isZero()
        // A notice already on his screen can still be closed, and is counted.
        ack(tillA, PHARMACY, "72", id, "shown").andExpect(status().isOk)
        ack(tillA, PHARMACY, "72", id, "closed").andExpect(status().isOk)
        // Aigerim has not finished it: she still gets it. HQ pausing her assignment stops it for her too.
        assertThat(feed(tillA, PHARMACY, "71").json()["pendingCount"].asInt()).isEqualTo(1)
        jdbc.update("UPDATE training_assignments SET status = 'paused' WHERE id = ?", aigerimsTask)
        assertThat(feed(tillA, PHARMACY, "71").json()["messages"].isEmpty).isTrue()
        jdbc.update("UPDATE training_assignments SET status = 'in_progress' WHERE id = ?", aigerimsTask)
        assertThat(feed(tillA, PHARMACY, "71").json()["pendingCount"].asInt()).isEqualTo(1)

        // Whoever got a task after the notice was sent is not on its list.
        assign(course, card("ph_nt_late", "Поздний Поздний", "000101500205", PHARMACY), "not_started", dueAt = due)
        assertThat(jdbc.queryForList("SELECT pharmacist_id FROM posm_message_recipients WHERE message_id = ? ORDER BY 1", String::class.java, UUID.fromString(id)))
            .containsExactly(AIGERIM, ERBOL)
        val counts = listed(id)
        assertThat(counts["shownCount"].asInt()).isEqualTo(1)
        assertThat(counts["closedCount"].asInt()).isEqualTo(1)
        assertThat(counts["recipientsTotal"].asInt()).isEqualTo(2)
    }

    @Test fun `the reminder names a due day only when every open task has the same one`() {
        val course = program("Разные сроки")
        assign(course, AIGERIM, "not_started", dueAt = Instant.now().plus(Duration.ofDays(3)))
        assign(course, ERBOL, "not_started", dueAt = Instant.now().plus(Duration.ofDays(9)))
        assertThat(courseOption(course)["dueOn"].isNull).isTrue()

        val open = program("Без срока")
        assign(open, AIGERIM, "not_started")
        assertThat(courseOption(open)["dueOn"].isNull).isTrue()

        val mixed = program("Часть без срока")
        assign(mixed, AIGERIM, "not_started", dueAt = Instant.now().plus(Duration.ofDays(3)))
        assign(mixed, ERBOL, "not_started")
        assertThat(courseOption(mixed)["dueOn"].isNull).isTrue()
    }

    @Test fun `a course reminder only for those at a till goes to the people who have not finished it and sit there now`() {
        val course = program("Сорбенты")
        assign(course, AIGERIM, "not_started")
        assign(course, ERBOL, "in_progress")
        assign(course, DANA, "not_started")
        checkIn(tillA, PHARMACY, "71")
        checkIn(tillB, OTHER, "81")

        val preview = preview(mapOf("audience" to "course", "programId" to course.toString(), "onlyAtTill" to true)).json()
        assertThat(preview["recipients"].asInt()).isEqualTo(2)
        assertThat(preview["atTillNow"].asInt()).isEqualTo(2)
        assertThat(preview["sample"].map { it["id"].asText() }).containsExactly(DANA, AIGERIM)
        assertThat(preview["sample"].all { it["atTillNow"].asBoolean() }).isTrue()
        // Without the switch the people who are not at a till are counted too, and the ones at tills are told apart.
        val everybody = preview(mapOf("audience" to "course", "programId" to course.toString())).json()
        assertThat(everybody["recipients"].asInt()).isEqualTo(3)
        assertThat(everybody["atTillNow"].asInt()).isEqualTo(2)

        val created = send(
            mapOf("title" to "Курс", "text" to "Пройдите курс.", "audience" to "course", "programId" to course.toString(), "onlyAtTill" to true),
        ).andExpect(status().isCreated).json()
        assertThat(created["recipientsTotal"].asInt()).isEqualTo(2)
        assertThat(created["onlyAtTill"].asBoolean()).isTrue()
        assertThat(feed(tillA, PHARMACY, "71").json()["pendingCount"].asInt()).isEqualTo(1)
        // Erbol has not finished it either, but nobody saw him at a till when the notice was sent.
        assertThat(feed(tillA, PHARMACY, "72").json()["messages"].isEmpty).isTrue()
    }

    @Test fun `a course reminder is refused for an archived or unknown course, without one, and when everybody finished it`() {
        val archived = program("Архивный курс", archived = true)
        assign(archived, AIGERIM, "not_started")
        val finished = program("Все прошли")
        assign(finished, AIGERIM, "completed")
        fun body(programId: String?) = buildMap<String, Any> {
            put("title", "Курс"); put("text", "Пройдите курс."); put("audience", "course"); programId?.let { put("programId", it) }
        }

        assertThat(send(body(archived.toString())).andExpect(status().isBadRequest).json()["message"].asText()).startsWith("Курс в архиве")
        assertThat(send(body(UUID.randomUUID().toString())).andExpect(status().isBadRequest).json()["message"].asText()).isEqualTo("Курс не найден")
        assertThat(send(body(null)).andExpect(status().isBadRequest).json()["message"].asText()).isEqualTo("Выберите курс")
        send(body("not-a-uuid")).andExpect(status().isBadRequest)
        assertThat(send(body(finished.toString())).andExpect(status().isBadRequest).json()["message"].asText())
            .isEqualTo("Некому напоминать: все уже прошли этот курс")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM posm_messages", Long::class.java)).isZero()
    }

    // ── the count before sending and the search for people ──────────────

    @Test fun `the count before sending needs no title and no text and does not send anything`() {
        checkIn(tillA, PHARMACY, "71")
        val everybody = preview(mapOf("audience" to "all")).json()
        assertThat(everybody["recipients"].asInt()).isEqualTo(3)
        assertThat(everybody["frozen"].asBoolean()).isFalse()
        assertThat(everybody["atTillNow"].asInt()).isEqualTo(1)
        assertThat(everybody["sample"].isEmpty).isTrue()
        assertThat(everybody["windowMinutes"].asInt()).isEqualTo(20)

        val atTill = preview(mapOf("audience" to "all", "onlyAtTill" to true)).json()
        assertThat(atTill["recipients"].asInt()).isEqualTo(1)
        assertThat(atTill["frozen"].asBoolean()).isTrue()
        assertThat(atTill["sample"].single()["id"].asText()).isEqualTo(AIGERIM)
        assertThat(atTill["sample"].single()["atTillNow"].asBoolean()).isTrue()

        val pharmacy = preview(mapOf("audience" to "pharmacies", "pharmacyIds" to listOf(OTHER))).json()
        assertThat(pharmacy["recipients"].asInt()).isEqualTo(1)
        assertThat(pharmacy["atTillNow"].asInt()).isZero()

        val people = preview(mapOf("audience" to "pharmacists", "pharmacistIds" to listOf(ERBOL, AIGERIM))).json()
        assertThat(people["recipients"].asInt()).isEqualTo(2)
        assertThat(people["atTillNow"].asInt()).isEqualTo(1)
        assertThat(people["sample"].map { it["id"].asText() }).containsExactly(AIGERIM, ERBOL)
        assertThat(people["sample"].map { it["atTillNow"].asBoolean() }).containsExactly(true, false)

        assertThat(jdbc.queryForObject("SELECT count(*) FROM posm_messages", Long::class.java)).isZero()
        // The same checks as for the send, and HQ only.
        preview(mapOf("audience" to "pharmacies", "pharmacyIds" to listOf("nt-nope"))).andExpect(status().isBadRequest)
        preview(mapOf("audience" to "pharmacists")).andExpect(status().isBadRequest)
        preview(mapOf("audience" to "everyone")).andExpect(status().isBadRequest)
        preview(mapOf("audience" to "all"), categoryLead).andExpect(status().isForbidden)
    }

    @Test fun `the picker finds people by name, phone digits, IIN and pharmacy, and never returns the phone or the IIN`() {
        checkIn(tillA, PHARMACY, "71")
        card("ph_nt_blocked", "Блокова Блок", "000101500203", PHARMACY, status = "blocked")

        val all = search("")
        assertThat(all["total"].asInt()).isEqualTo(3)
        assertThat(all["items"].map { it["name"].asText() }).containsExactly("Омарова Дана", "Сапарова Айгерим Серикқызы", "Тұрсынов Ербол")
        val aigerim = all["items"].single { it["id"].asText() == AIGERIM }
        assertThat(aigerim["pharmacyName"].asText()).isEqualTo("Аптека со склада")
        assertThat(aigerim["city"].asText()).isEqualTo("Алматы")
        assertThat(aigerim["address"].asText()).isEqualTo("Абая 103")
        assertThat(aigerim["phoneTail"].asText()).isEqualTo("4567")
        assertThat(aigerim["status"].asText()).isEqualTo("active")
        assertThat(aigerim["atTillNow"].asBoolean()).isTrue()
        assertThat(aigerim["lastTillSeenAt"].isNull).isFalse()
        assertThat(all["items"].single { it["id"].asText() == ERBOL }["atTillNow"].asBoolean()).isFalse()
        assertThat(all["items"].single { it["id"].asText() == ERBOL }["phoneTail"].isNull).isTrue()
        assertThat(all.toString()).doesNotContain("77011234567", "77025551234", "000101500111", "\"phone\"", "\"iin\"")

        assertThat(search("сапарова айгерим")["items"].map { it["id"].asText() }).containsExactly(AIGERIM)
        assertThat(search("Турсынов")["items"].map { it["id"].asText() }).containsExactly(ERBOL)
        assertThat(search("сериккызы")["items"].map { it["id"].asText() }).containsExactly(AIGERIM)
        assertThat(search("87025551234")["items"].map { it["id"].asText() }).containsExactly(DANA)
        assertThat(search("000101500122")["items"].map { it["id"].asText() }).containsExactly(ERBOL)
        assertThat(search("Толе би")["items"].map { it["id"].asText() }).containsExactly(DANA)
        assertThat(search("Абая")["items"].map { it["id"].asText() }).containsExactlyInAnyOrder(AIGERIM, ERBOL)
        // A blocked card is not offered; nobody found is an empty list, not an error.
        assertThat(search("Блокова")["total"].asInt()).isZero()
        assertThat(search("Блокова")["items"].isEmpty).isTrue()

        val limited = mvc.perform(get("/api/admin/posm-messages/pharmacists?limit=2").bearer(hq)).andExpect(status().isOk).json()
        assertThat(limited["total"].asInt()).isEqualTo(3)
        assertThat(limited["items"].size()).isEqualTo(2)
        mvc.perform(get("/api/admin/posm-messages/pharmacists").bearer(categoryLead)).andExpect(status().isForbidden)
        mvc.perform(get("/api/admin/posm-messages/courses").bearer(categoryLead)).andExpect(status().isForbidden)
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private data class Directory(val profile: Int, val user: Int, val iin: String, val name: String)

    private fun card(id: String, name: String, iin: String, pharmacy: String, status: String = "active", phone: String = ""): String {
        jdbc.update(
            """INSERT INTO pharmacists(id, name, iin, phone, tier, status, pharmacy_id, city, account_source)
               VALUES (?, ?, ?, ?, 'Silver', ?, ?, 'Алматы', 'standardn')""",
            id, name, iin, phone, status, pharmacy,
        )
        return id
    }

    private fun program(name: String, archived: Boolean = false): UUID {
        val program = UUID.randomUUID()
        val version = UUID.randomUUID()
        jdbc.update(
            """INSERT INTO training_programs(id, name, status, allowed_formats, created_by, archived_at)
               VALUES (?, ?, ?, 'online', ?, ?)""",
            program, name, if (archived) "archived" else "published", admin, if (archived) Timestamp.from(Instant.now()) else null,
        )
        jdbc.update(
            """INSERT INTO training_program_versions(id, program_id, version_no, allowed_formats, snapshot_json, created_by)
               VALUES (?, ?, 1, 'online', '{}', ?)""",
            version, program, admin,
        )
        versions[program] = version
        return program
    }

    private fun assign(program: UUID, pharmacist: String, status: String, dueAt: Instant? = null, startsAt: Instant? = null): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            """INSERT INTO training_assignments(id, program_version_id, pharmacist_id, format, status, assigned_by, starts_at, due_at, completed_at)
               VALUES (?, ?, ?, 'online', ?, ?, ?, ?, ?)""",
            id, versions.getValue(program), pharmacist, status, admin, startsAt?.let(Timestamp::from), dueAt?.let(Timestamp::from),
            if (status == "completed") Timestamp.from(Instant.now().minus(Duration.ofDays(1))) else null,
        )
        return id
    }

    private fun courseOption(program: UUID): JsonNode =
        mvc.perform(get("/api/admin/posm-messages/courses").bearer(hq)).andExpect(status().isOk).json()
            .single { it["id"].asText() == program.toString() }

    private fun send(body: Map<String, Any>, bearer: String = hq): ResultActions = mvc.perform(
        post("/api/admin/posm-messages").bearer(bearer).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body)),
    )

    private fun preview(body: Map<String, Any>, bearer: String = hq): ResultActions = mvc.perform(
        post("/api/admin/posm-messages/preview").bearer(bearer).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body)),
    )

    private fun search(q: String): JsonNode =
        mvc.perform(get("/api/admin/posm-messages/pharmacists").param("q", q).bearer(hq)).andExpect(status().isOk).json()

    private fun atTill(pharmacyId: String? = null, q: String? = null): JsonNode {
        val request = get("/api/admin/posm-at-till").bearer(hq)
        pharmacyId?.let { request.param("pharmacyId", it) }
        q?.let { request.param("q", it) }
        return mvc.perform(request).andExpect(status().isOk).json()
    }

    private fun listed(id: String): JsonNode =
        mvc.perform(get("/api/admin/posm-messages").bearer(hq)).andExpect(status().isOk).json().single { it["id"].asText() == id }

    /** The till's check: it names the Standard-N user signed in at it and asks for that person's notices. */
    private fun checkIn(key: String, pharmacyId: String, user: String) {
        feed(key, pharmacyId, user).andExpect(status().isOk)
    }

    private fun feed(key: String?, pharmacyId: String, user: String): ResultActions =
        mvc.perform(till(post("/api/posm/self/messages"), key, pharmacyId, user))

    private fun ack(key: String, pharmacyId: String, user: String, id: String, kind: String): ResultActions =
        mvc.perform(till(post("/api/posm/self/messages/$id/$kind"), key, pharmacyId, user))

    private fun till(request: MockHttpServletRequestBuilder, key: String?, pharmacyId: String, user: String): MockHttpServletRequestBuilder {
        request.contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsBytes(mapOf("pharmacyId" to pharmacyId, "standardNUserId" to user, "link" to false)))
        key?.let { request.header("X-Posm-Key", it) }
        return request
    }

    private fun till(deviceId: String, pharmacyId: String): String {
        val token = "nt-${UUID.randomUUID()}-${UUID.randomUUID()}".take(64)
        jdbc.update(
            """INSERT INTO fulfillment_devices (id, device_id, pharmacy_id, secret_sha256, secret_hint, active)
               VALUES (?, ?, ?, ?, 'nt', true)""",
            UUID.randomUUID(), deviceId, pharmacyId, crypto.sha256Hex(token),
        )
        return token
    }

    private fun token(email: String, role: AdminRole): String {
        users.saveAndFlush(AdminUserEntity(email = email, name = "Synthetic", roleRaw = role.name, passwordHash = encoder.encode(PASSWORD)))
        return auth.login(email, PASSWORD).tokens.accessToken
    }

    private fun ResultActions.json(): JsonNode = mapper.readTree(andReturn().response.contentAsByteArray)

    private fun MockHttpServletRequestBuilder.bearer(token: String) = header("Authorization", "Bearer $token")
}
