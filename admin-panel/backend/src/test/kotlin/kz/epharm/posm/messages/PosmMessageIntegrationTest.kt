package kz.epharm.posm.messages

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
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
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Notices from HQ at the till on real PostgreSQL through the real security chain: HQ JWT for the admin side, per-device
 * POSM keys for the till side, the person found through the Standard-N directory as in the personal window.
 * Synthetic pharmacies, people and notices only.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class PosmMessageIntegrationTest {
    companion object {
        @Container @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("posm_messages_test").withUsername("epharm").withPassword("local-test-only")
            .apply { start() }

        @JvmStatic @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
        }

        private const val PASSWORD = "Synthetic-admin-pass-1"
        private const val PHARMACY = "pm-pharmacy-a"
        private const val OTHER = "pm-pharmacy-b"
        private const val AIGERIM = "ph_pm_aigerim"
        private const val ERBOL = "ph_pm_erbol"
        private const val DANA = "ph_pm_dana"
    }

    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var mapper: ObjectMapper
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var crypto: FulfillmentCrypto
    @Autowired private lateinit var users: AdminUserRepository
    @Autowired private lateinit var auth: AdminAuthService
    @Autowired private lateinit var encoder: PasswordEncoder

    private lateinit var hq: String
    private lateinit var hqEmail: String
    private lateinit var categoryLead: String
    private lateinit var tillA: String
    private lateinit var tillB: String

    @BeforeEach fun seed() {
        // This class's own disposable PG16: receipts and targets go with the notices and the cards.
        jdbc.execute("TRUNCATE posm_messages, pharmacists, standardn_pharmacist_directory CASCADE")
        jdbc.update("DELETE FROM fulfillment_devices WHERE pharmacy_id LIKE 'pm-%'")
        jdbc.update("DELETE FROM pharmacies WHERE id LIKE 'pm-%'")
        jdbc.update("""INSERT INTO chains(id, name, "group") VALUES ('pm-chain', 'Synthetic', 'pilot') ON CONFLICT DO NOTHING""")
        for ((id, profile, address) in listOf(Triple(PHARMACY, 7401, "Абая 103"), Triple(OTHER, 7402, "Толе би 286"))) {
            jdbc.update(
                """INSERT INTO pharmacies(id, name, chain_id, chain_name, city, addr, "group", standardn_profile_id, standardn_profile_source)
                   VALUES (?, 'Аптека со склада', 'pm-chain', 'Synthetic', 'Алматы', ?, 'pilot', ?, 'manual')""",
                id, address, profile,
            )
        }
        // Standard-N users: 51-53 in the database of pharmacy A, 61-62 in that of pharmacy B. Aigerim and Dana also
        // have a user in the other pharmacy (they sometimes work there).
        listOf(
            Directory(7401, 51, "000101500111", "Синтетикова Айгерим Серикқызы"),
            Directory(7401, 52, "000101500122", "Синтетиков Ербол"),
            Directory(7401, 53, "000101500133", "Синтетикова Дана"),
            Directory(7402, 61, "000101500133", "Синтетикова Дана"),
            Directory(7402, 62, "000101500111", "Синтетикова Айгерим Серикқызы"),
        ).forEach { row ->
            jdbc.update(
                """INSERT INTO standardn_pharmacist_directory(directory_key, source_profile_id, source_user_id, iin, full_name, source_status, profile_name)
                   VALUES (?, ?, ?, ?, ?, 0, 'Synthetic')""",
                "${row.profile}:${row.user}", row.profile, row.user, row.iin, row.name,
            )
        }
        for ((id, iin, home) in listOf(Triple(AIGERIM, "000101500111", PHARMACY), Triple(ERBOL, "000101500122", PHARMACY), Triple(DANA, "000101500133", OTHER))) {
            jdbc.update(
                """INSERT INTO pharmacists(id, name, iin, phone, tier, status, pharmacy_id, city, account_source)
                   VALUES (?, ?, ?, '', 'Silver', 'active', ?, 'Алматы', 'standardn')""",
                id, if (id == ERBOL) "Синтетиков Ербол" else "Синтетик $id", iin, home,
            )
        }
        tillA = till("KASSA-A", PHARMACY)
        tillB = till("KASSA-B", OTHER)
        hqEmail = "hq-${UUID.randomUUID()}@example.invalid"
        hq = token(hqEmail, AdminRole.HQ_HEAD)
        categoryLead = token("lead-${UUID.randomUUID()}@example.invalid", AdminRole.CATEGORY_LEAD)
    }

    @Test fun `a notice for everybody stays on the till until the person closes it, and HQ sees who saw and closed it`() {
        val created = send(mapOf("title" to "  Инвентаризация ", "text" to "Завтра в 9:00 инвентаризация.", "audience" to "all", "days" to 3))
            .andExpect(status().isCreated).json()
        val id = created["id"].asText()
        assertThat(created["audience"].asText()).isEqualTo("all")
        assertThat(created["title"].asText()).isEqualTo("Инвентаризация")
        assertThat(created["targetCount"].asInt()).isZero()
        assertThat(created["recipientsTotal"].asInt()).isEqualTo(3)
        assertThat(created["shownCount"].asInt()).isZero()
        assertThat(created["active"].asBoolean()).isTrue()
        assertThat(created["createdBy"].asText()).isEqualTo(hqEmail)
        val createdAt = Instant.parse(created["createdAt"].asText())
        assertThat(Instant.parse(created["expiresAt"].asText())).isEqualTo(createdAt.plus(Duration.ofDays(3)))

        val feed = feed(tillA, PHARMACY, "51").andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store")).json()
        assertThat(feed["pendingCount"].asInt()).isEqualTo(1)
        val message = feed["messages"].single()
        assertThat(message.fieldNames().asSequence().toSet()).isEqualTo(setOf("id", "title", "text", "sentAt", "closedAt", "pending"))
        assertThat(message["id"].asText()).isEqualTo(id)
        assertThat(message["text"].asText()).isEqualTo("Завтра в 9:00 инвентаризация.")
        assertThat(message["pending"].asBoolean()).isTrue()
        assertThat(message["closedAt"].isNull).isTrue()

        val shown = ack(tillA, PHARMACY, "51", id, "shown").andExpect(status().isOk).json()
        assertThat(shown["closedAt"].isNull).isTrue()
        // A repeated report keeps the first time.
        assertThat(ack(tillA, PHARMACY, "51", id, "shown").json()["shownAt"].asText()).isEqualTo(shown["shownAt"].asText())
        assertThat(listed(id)["shownCount"].asInt()).isEqualTo(1)
        assertThat(listed(id)["closedCount"].asInt()).isZero()

        val closed = ack(tillA, PHARMACY, "51", id, "closed").andExpect(status().isOk).json()
        assertThat(closed["closedAt"].isNull).isFalse()
        assertThat(ack(tillA, PHARMACY, "51", id, "closed").json()["closedAt"].asText()).isEqualTo(closed["closedAt"].asText())
        val after = feed(tillA, PHARMACY, "51").json()
        assertThat(after["pendingCount"].asInt()).isZero()
        assertThat(after["messages"].single()["pending"].asBoolean()).isFalse()
        assertThat(after["messages"].single()["closedAt"].asText()).isEqualTo(closed["closedAt"].asText())

        // Another person at another till: closing without a «shown» report also counts as seen.
        assertThat(feed(tillB, OTHER, "61").json()["pendingCount"].asInt()).isEqualTo(1)
        ack(tillB, OTHER, "61", id, "closed").andExpect(status().isOk)
        val counts = listed(id)
        assertThat(counts["shownCount"].asInt()).isEqualTo(2)
        assertThat(counts["closedCount"].asInt()).isEqualTo(2)
    }

    @Test fun `a notice for a pharmacy reaches whoever works at its till and its staff at another till`() {
        val created = send(mapOf("title" to "Проверка", "text" to "В аптеке на Толе би проверка.", "audience" to "pharmacies", "pharmacyIds" to listOf(OTHER)))
            .andExpect(status().isCreated).json()
        val id = created["id"].asText()
        assertThat(created["targetCount"].asInt()).isEqualTo(1)
        assertThat(created["targetNames"].map(JsonNode::asText)).containsExactly("Аптека со склада, Толе би 286")
        // Dana's card belongs to pharmacy B.
        assertThat(created["recipientsTotal"].asInt()).isEqualTo(1)

        assertThat(feed(tillB, OTHER, "61").json()["pendingCount"].asInt()).isEqualTo(1)
        // Aigerim (pharmacy A) signed in at the till of pharmacy B.
        assertThat(feed(tillB, OTHER, "62").json()["pendingCount"].asInt()).isEqualTo(1)
        // Dana (pharmacy B) signed in at the till of pharmacy A.
        assertThat(feed(tillA, PHARMACY, "53").json()["pendingCount"].asInt()).isEqualTo(1)
        // Erbol at his own till of pharmacy A: not for him.
        val erbol = feed(tillA, PHARMACY, "52").json()
        assertThat(erbol["messages"].isEmpty).isTrue()
        ack(tillA, PHARMACY, "52", id, "shown").andExpect(status().isNotFound)
        ack(tillA, PHARMACY, "52", id, "closed").andExpect(status().isNotFound)
    }

    @Test fun `a notice for chosen people reaches only them, and a withdrawn one leaves the till`() {
        val created = send(mapOf("title" to "Ербол", "text" to "Подойдите к заведующей.", "audience" to "pharmacists", "pharmacistIds" to listOf(ERBOL)))
            .andExpect(status().isCreated).json()
        val id = created["id"].asText()
        assertThat(created["targetNames"].map(JsonNode::asText)).containsExactly("Синтетиков Ербол")
        assertThat(created["recipientsTotal"].asInt()).isEqualTo(1)
        assertThat(feed(tillA, PHARMACY, "51").json()["messages"].isEmpty).isTrue()
        assertThat(feed(tillA, PHARMACY, "52").json()["pendingCount"].asInt()).isEqualTo(1)
        ack(tillA, PHARMACY, "52", id, "shown").andExpect(status().isOk)

        val withdrawn = mvc.perform(post("/api/admin/posm-messages/$id/withdraw").bearer(hq)).andExpect(status().isOk).json()
        assertThat(withdrawn["active"].asBoolean()).isFalse()
        assertThat(withdrawn["withdrawnBy"].asText()).isEqualTo(hqEmail)
        assertThat(withdrawn["shownCount"].asInt()).isEqualTo(1)
        // Withdrawing twice keeps the first time.
        val again = mvc.perform(post("/api/admin/posm-messages/$id/withdraw").bearer(hq)).andExpect(status().isOk).json()
        assertThat(again["withdrawnAt"].asText()).isEqualTo(withdrawn["withdrawnAt"].asText())

        val feed = feed(tillA, PHARMACY, "52").json()
        assertThat(feed["messages"].isEmpty).isTrue()
        assertThat(feed["pendingCount"].asInt()).isZero()
        ack(tillA, PHARMACY, "52", id, "shown").andExpect(status().isNotFound)
        // The close of a notice already on screen is still recorded.
        ack(tillA, PHARMACY, "52", id, "closed").andExpect(status().isOk)
        assertThat(listed(id)["closedCount"].asInt()).isEqualTo(1)
    }

    @Test fun `people who chose Kazakh read the Kazakh text at the till, the others the Russian one`() {
        // Aigerim chose Kazakh in the cabinet; Erbol has not chosen a language.
        jdbc.update("UPDATE pharmacists SET language = 'kk' WHERE id = ?", AIGERIM)
        val created = send(
            mapOf(
                "title" to "Инвентаризация", "text" to "Завтра в 9:00.", "audience" to "all",
                "titleKk" to " Түгендеу ", "textKk" to "Ертең сағат 9:00-де.",
            ),
        ).andExpect(status().isCreated).json()
        assertThat(created["title"].asText()).isEqualTo("Инвентаризация")
        assertThat(created["titleKk"].asText()).isEqualTo("Түгендеу")
        assertThat(created["textKk"].asText()).isEqualTo("Ертең сағат 9:00-де.")
        assertThat(listed(created["id"].asText())["titleKk"].asText()).isEqualTo("Түгендеу")

        val aigerim = feed(tillA, PHARMACY, "51").andExpect(status().isOk).json()["messages"].single()
        assertThat(aigerim["title"].asText()).isEqualTo("Түгендеу")
        assertThat(aigerim["text"].asText()).isEqualTo("Ертең сағат 9:00-де.")
        val erbol = feed(tillA, PHARMACY, "52").json()["messages"].single()
        assertThat(erbol["title"].asText()).isEqualTo("Инвентаризация")
        assertThat(erbol["text"].asText()).isEqualTo("Завтра в 9:00.")

        // A notice without a Kazakh text reaches her in Russian.
        val russian = send(mapOf("title" to "Только по-русски", "text" to "Текст.", "audience" to "all")).andExpect(status().isCreated).json()
        assertThat(russian["titleKk"].isNull).isTrue()
        assertThat(feed(tillA, PHARMACY, "51").json()["messages"].map { it["title"].asText() })
            .containsExactly("Только по-русски", "Түгендеу")

        // Half of the Kazakh pair is refused, and nothing is written.
        assertThat(
            send(mapOf("title" to "x", "text" to "y", "audience" to "all", "titleKk" to "Тек тақырып"))
                .andExpect(status().isBadRequest).json()["message"].asText(),
        ).isEqualTo("Введите текст на казахском или очистите казахский заголовок")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM posm_messages", Long::class.java)).isEqualTo(2)
    }

    @Test fun `the till's minute check learns when notices changed`() {
        val created = send(mapOf("title" to "Новое", "text" to "Текст.", "audience" to "all")).json()
        val noticeAt = notice(tillA)["messagesAt"].asText()
        assertThat(Instant.parse(noticeAt)).isEqualTo(Instant.parse(created["createdAt"].asText()))
        val withdrawn = mvc.perform(post("/api/admin/posm-messages/${created["id"].asText()}/withdraw").bearer(hq)).json()
        assertThat(Instant.parse(notice(tillA)["messagesAt"].asText())).isEqualTo(Instant.parse(withdrawn["withdrawnAt"].asText()))
    }

    @Test fun `only HQ sends notices and what it sends is checked`() {
        mvc.perform(get("/api/admin/posm-messages").bearer(categoryLead)).andExpect(status().isForbidden)
        send(mapOf("title" to "x", "text" to "y", "audience" to "all"), categoryLead).andExpect(status().isForbidden)

        assertThat(send(mapOf("title" to "x", "text" to "y", "audience" to "pharmacies", "pharmacyIds" to listOf("pm-nope")))
            .andExpect(status().isBadRequest).json()["message"].asText()).isEqualTo("Не найдены аптеки: pm-nope")
        send(mapOf("title" to " ", "text" to "y", "audience" to "all")).andExpect(status().isBadRequest)
        send(mapOf("title" to "x", "text" to "y", "audience" to "pharmacists")).andExpect(status().isBadRequest)
        send(mapOf("title" to "x", "text" to "y", "audience" to "all", "days" to 31)).andExpect(status().isBadRequest)
        mvc.perform(post("/api/admin/posm-messages/${UUID.randomUUID()}/withdraw").bearer(hq)).andExpect(status().isNotFound)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM posm_messages", Long::class.java)).isZero()

        // The till side: no key, or the key of another pharmacy, does not authenticate.
        feed(null, PHARMACY, "51").andExpect(status().isUnauthorized)
        feed(tillB, PHARMACY, "51").andExpect(status().isUnauthorized)
        // Unknown Standard-N user: the same 404 as the personal window, with the code the 1.0.101 till reads to show the IIN form.
        assertThat(feed(tillA, PHARMACY, "99").andExpect(status().isNotFound).json()["code"].asText()).isEqualTo("STANDARDN_USER_UNKNOWN")
    }

    @Test fun `HQ list is newest first with counts`() {
        val first = send(mapOf("title" to "Первое", "text" to "Текст.", "audience" to "all")).json()["id"].asText()
        val second = send(mapOf("title" to "Второе", "text" to "Текст.", "audience" to "pharmacists", "pharmacistIds" to listOf(DANA, ERBOL)))
            .json()["id"].asText()
        val list = mvc.perform(get("/api/admin/posm-messages").bearer(hq)).andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store")).json()
        assertThat(list.map { it["id"].asText() }).containsExactly(second, first)
        assertThat(list[0]["targetCount"].asInt()).isEqualTo(2)
        assertThat(list[0]["targetNames"].map(JsonNode::asText)).containsExactlyInAnyOrder("Синтетик ph_pm_dana", "Синтетиков Ербол")
    }

    private data class Directory(val profile: Int, val user: Int, val iin: String, val name: String)

    private fun send(body: Map<String, Any>, bearer: String = hq): ResultActions = mvc.perform(
        post("/api/admin/posm-messages").bearer(bearer).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body)),
    )

    private fun listed(id: String): JsonNode =
        mvc.perform(get("/api/admin/posm-messages").bearer(hq)).andExpect(status().isOk).json().single { it["id"].asText() == id }

    private fun feed(key: String?, pharmacyId: String, user: String): ResultActions =
        mvc.perform(till(post("/api/posm/self/messages"), key, pharmacyId, user))

    private fun ack(key: String, pharmacyId: String, user: String, id: String, kind: String): ResultActions =
        mvc.perform(till(post("/api/posm/self/messages/$id/$kind"), key, pharmacyId, user))

    private fun notice(key: String): JsonNode =
        mvc.perform(get("/api/posm/notice").header("X-Posm-Key", key)).andExpect(status().isOk).json()

    private fun till(request: MockHttpServletRequestBuilder, key: String?, pharmacyId: String, user: String): MockHttpServletRequestBuilder {
        request.contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsBytes(mapOf("pharmacyId" to pharmacyId, "standardNUserId" to user, "link" to false)))
        key?.let { request.header("X-Posm-Key", it) }
        return request
    }

    private fun till(deviceId: String, pharmacyId: String): String {
        val token = "pm-${UUID.randomUUID()}-${UUID.randomUUID()}".take(64)
        jdbc.update(
            """INSERT INTO fulfillment_devices (id, device_id, pharmacy_id, secret_sha256, secret_hint, active)
               VALUES (?, ?, ?, ?, 'pm', true)""",
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
