package kz.epharm.posm.identity

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.auth.service.AdminAuthService
import kz.epharm.fulfillment.security.FulfillmentCrypto
import kz.epharm.mobile.auth.service.MobileAuthService
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
import java.util.UUID

/**
 * The seven routes of the identity claims over HTTP: who may call them, the status codes, the JSON the till, the admin
 * and the cabinet read. Synthetic data only.
 */
// These tests are about HQ deciding: every claim waits for HQ here. ePharm taking clean claims itself is
// IdentityClaimAutoConfirmIntegrationTest.
@SpringBootTest(properties = ["epharm.identity-claims.auto-confirm=false"])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class IdentityClaimHttpIntegrationTest {
    companion object {
        @Container @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine").apply { start() }
        @JvmStatic @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
        }
        private const val PASSWORD = "Synthetic-admin-pass-1"
        private const val PHARMACY = "ih-pharmacy"
        private const val OTHER = "ih-other"
        private const val IIN = "900115300013"
        private const val NAME = "Бузурбаева Лаура Ермековна"
    }

    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var mapper: ObjectMapper
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var crypto: FulfillmentCrypto
    @Autowired private lateinit var users: AdminUserRepository
    @Autowired private lateinit var adminAuth: AdminAuthService
    @Autowired private lateinit var mobileAuth: MobileAuthService
    @Autowired private lateinit var encoder: PasswordEncoder

    private lateinit var tillA: String
    private lateinit var tillB: String
    private lateinit var hq: String
    private lateinit var lead: String

    @BeforeEach fun seed() {
        jdbc.update("DELETE FROM pharmacist_identity_claims")
        jdbc.update("DELETE FROM posm_staff_rate_limits")
        jdbc.update("DELETE FROM posm_pharmacist_mapping_audit WHERE pharmacy_id LIKE 'ih-%'")
        jdbc.update("DELETE FROM posm_pharmacist_mappings WHERE pharmacy_id LIKE 'ih-%'")
        jdbc.update("DELETE FROM posm_cabinet_links")
        jdbc.update("DELETE FROM pharmacists WHERE id LIKE 'ih-%' OR iin = ?", IIN)
        jdbc.update("DELETE FROM fulfillment_devices WHERE pharmacy_id LIKE 'ih-%'")
        jdbc.update("DELETE FROM standardn_pharmacist_directory WHERE source_profile_id IN (7301, 7302)")
        jdbc.update("DELETE FROM pharmacies WHERE id LIKE 'ih-%'")
        jdbc.update("""INSERT INTO chains(id, name, "group") VALUES ('ih-chain', 'Synthetic', 'pilot') ON CONFLICT DO NOTHING""")
        for ((id, profile) in listOf(PHARMACY to 7301, OTHER to 7302)) {
            jdbc.update(
                """INSERT INTO pharmacies(id, name, chain_id, chain_name, city, "group", standardn_profile_id, standardn_profile_source)
                   VALUES (?, 'Synthetic ih', 'ih-chain', 'Synthetic', 'Алматы', 'pilot', ?, 'manual')""",
                id, profile,
            )
        }
        tillA = till("IH-A", PHARMACY)
        tillB = till("IH-B", OTHER)
        hq = adminToken("hq-${UUID.randomUUID()}@example.invalid", AdminRole.HQ_HEAD)
        lead = adminToken("lead-${UUID.randomUUID()}@example.invalid", AdminRole.CATEGORY_LEAD)
    }

    // ── the till ────────────────────────────────────────────────────────────

    @Test fun `the till routes need the key of the pharmacy and answer with no-store JSON`() {
        val body = mapOf("pharmacyId" to PHARMACY, "standardNUserId" to "18")
        tillCall("/api/posm/self/identity", null, body).andExpect(status().isUnauthorized)
        tillCall("/api/posm/self/identity", tillB, body).andExpect(status().isUnauthorized)
        tillCall("/api/posm/self/identity/claim", tillB, body + mapOf("iin" to IIN, "fullName" to NAME)).andExpect(status().isUnauthorized)

        val state = tillCall("/api/posm/self/identity", tillA, body).andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store")).json()
        assertThat(state["state"].asText()).isEqualTo("unknown")
    }

    @Test fun `a claim over HTTP, refused data answers 400 in Russian, a good one is pending and shown back masked`() {
        val base = mapOf("pharmacyId" to PHARMACY, "standardNUserId" to "18", "standardNUserName" to "Бузурбаева Л")
        val badIin = tillCall("/api/posm/self/identity/claim", tillA, base + mapOf("iin" to "900115300010", "fullName" to NAME))
            .andExpect(status().isBadRequest).json()
        assertThat(badIin["code"].asText()).isEqualTo("VALIDATION_FAILED")
        assertThat(badIin["message"].asText()).contains("контрольная цифра")
        val oneWord = tillCall("/api/posm/self/identity/claim", tillA, base + mapOf("iin" to IIN, "fullName" to "Лаура"))
            .andExpect(status().isBadRequest).json()
        assertThat(oneWord["message"].asText()).contains("не меньше двух слов")
        tillCall("/api/posm/self/identity/claim", tillA, mapOf("pharmacyId" to PHARMACY, "standardNUserId" to "", "iin" to IIN, "fullName" to NAME))
            .andExpect(status().isBadRequest)

        val sent = tillCall("/api/posm/self/identity/claim", tillA, base + mapOf("iin" to IIN, "fullName" to NAME))
            .andExpect(status().isOk).andExpect(header().string("Cache-Control", "no-store")).json()
        assertThat(sent["state"].asText()).isEqualTo("pending")
        assertThat(sent["iinMasked"].asText()).isEqualTo("900115•••••3")
        assertThat(sent["fullName"].asText()).isEqualTo(NAME)
        assertThat(sent.has("iin")).isFalse()
        assertThat(tillCall("/api/posm/self/identity", tillA, base).json()["state"].asText()).isEqualTo("pending")
    }

    @Test fun `the window and its feeds tell an unknown user by a code, with the old message`() {
        val body = mapOf("pharmacyId" to PHARMACY, "standardNUserId" to "18", "link" to false)
        for (route in listOf("/api/posm/self", "/api/posm/self/cabinet-link", "/api/posm/self/tasks", "/api/posm/self/messages")) {
            val error = tillCall(route, tillA, body).andExpect(status().isNotFound).json()
            assertThat(error["code"].asText()).describedAs(route).isEqualTo("STANDARDN_USER_UNKNOWN")
            assertThat(error["message"].asText()).describedAs(route).contains("Не нашли вас в справочнике Стандарт-Н")
        }
    }

    // ── HQ ──────────────────────────────────────────────────────────────────

    @Test fun `the admin routes are for HQ only and carry what the list shows`() {
        tillCall("/api/posm/self/identity/claim", tillA, mapOf("pharmacyId" to PHARMACY, "standardNUserId" to "18", "iin" to IIN, "fullName" to NAME)).andExpect(status().isOk)
        mvc.perform(get("/api/admin/posm/identity-claims")).andExpect(status().isUnauthorized)
        mvc.perform(get("/api/admin/posm/identity-claims").bearer(lead)).andExpect(status().isForbidden)

        val list = mvc.perform(get("/api/admin/posm/identity-claims").bearer(hq)).andExpect(status().isOk).json()
        val claim = list.single()
        assertThat(claim["status"].asText()).isEqualTo("pending")
        assertThat(claim["kind"].asText()).isEqualTo("person")
        assertThat(claim["iin"].asText()).isEqualTo(IIN)
        assertThat(claim["fullName"].asText()).isEqualTo(NAME)
        assertThat(claim["standardNUserId"].asText()).isEqualTo("18")
        assertThat(claim["conflicts"].isArray).isTrue()
        val id = claim["id"].asText()

        mvc.perform(get("/api/admin/posm/identity-claims?status=bogus").bearer(hq)).andExpect(status().isBadRequest)
        decide(id, "confirm", lead, mapOf("iin" to IIN, "fullName" to NAME)).andExpect(status().isForbidden)
        decide(id, "confirm", hq, mapOf("iin" to "850615400016", "fullName" to NAME)).andExpect(status().isConflict)
        decide(id, "reject", hq, mapOf("reason" to "no")).andExpect(status().isBadRequest)
        decide(UUID.randomUUID().toString(), "reject", hq, mapOf("reason" to "Нет такого человека")).andExpect(status().isNotFound)

        val confirmed = decide(id, "confirm", hq, mapOf("iin" to IIN, "fullName" to NAME)).andExpect(status().isOk).json()
        assertThat(confirmed["status"].asText()).isEqualTo("confirmed")
        assertThat(confirmed["pharmacistId"].asText()).isNotBlank()
        assertThat(confirmed["decidedByName"].asText()).isEqualTo("Synthetic")
        decide(id, "reject", hq, mapOf("reason" to "Передумали")).andExpect(status().isConflict)

        // The till window now knows the person: the answer is a profile, not the unknown-user code.
        val window = tillCall("/api/posm/self", tillA, mapOf("pharmacyId" to PHARMACY, "standardNUserId" to "18", "link" to false))
            .andExpect(status().isOk).json()
        assertThat(window["fullName"].asText()).isEqualTo(NAME)
        assertThat(tillCall("/api/posm/self/identity", tillA, mapOf("pharmacyId" to PHARMACY, "standardNUserId" to "18")).json()["state"].asText())
            .isEqualTo("known")
    }

    // ── the cabinet ─────────────────────────────────────────────────────────

    @Test fun `the cabinet routes are for a pharmacist, show the sources and take a proposal`() {
        jdbc.update(
            """INSERT INTO pharmacists(id, name, iin, phone, password_hash, tier, status, pharmacy_id, pharmacy_name, city, account_source, iin_source, name_source)
               VALUES ('ih-card', 'Бузурбаева', ?, '+77015551234', ?, 'Silver', 'active', ?, 'Synthetic ih', 'Алматы', 'standardn', 'standardn', 'standardn')""",
            IIN, encoder.encode("synthetic-pass-1"), PHARMACY,
        )
        val token = mobileAuth.login(IIN, "synthetic-pass-1").tokens.accessToken
        mvc.perform(get("/api/mobile/me/identity")).andExpect(status().isUnauthorized)
        mvc.perform(get("/api/mobile/me/identity").bearer(hq)).andExpect(status().isForbidden)

        val state = mvc.perform(get("/api/mobile/me/identity").bearer(token)).andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store")).json()
        assertThat(state["name"].asText()).isEqualTo("Бузурбаева")
        assertThat(state["nameSource"].asText()).isEqualTo("standardn")
        assertThat(state["iinSource"].asText()).isEqualTo("standardn")
        assertThat(state["nameIncomplete"].asBoolean()).isTrue()
        assertThat(state["canPropose"].asBoolean()).isTrue()
        assertThat(state["claim"].isNull).isTrue()

        proposal(token, "Лаура").andExpect(status().isBadRequest)
        val sent = proposal(token, " Бузурбаева   Лаура Ермековна ").andExpect(status().isOk).json()
        assertThat(sent["canPropose"].asBoolean()).isFalse()
        assertThat(sent["claim"]["status"].asText()).isEqualTo("pending")
        assertThat(sent["claim"]["fullName"].asText()).isEqualTo(NAME)
        // HQ sees it as a name claim for this card and confirms it; the cabinet then shows the name as the person's own.
        val claim = mvc.perform(get("/api/admin/posm/identity-claims?status=pending").bearer(hq)).andExpect(status().isOk).json().single()
        assertThat(claim["kind"].asText()).isEqualTo("name")
        decide(claim["id"].asText(), "confirm", hq, mapOf("iin" to IIN, "fullName" to NAME)).andExpect(status().isOk)
        val after = mvc.perform(get("/api/mobile/me/identity").bearer(token)).andExpect(status().isOk).json()
        assertThat(after["name"].asText()).isEqualTo(NAME)
        assertThat(after["nameSource"].asText()).isEqualTo("self")
        assertThat(after["canPropose"].asBoolean()).isFalse()
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun tillCall(path: String, key: String?, body: Map<String, Any>): ResultActions {
        val request = post(path).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body))
        key?.let { request.header("X-Posm-Key", it) }
        return mvc.perform(request)
    }

    private fun decide(id: String, action: String, bearer: String, body: Map<String, Any>): ResultActions = mvc.perform(
        post("/api/admin/posm/identity-claims/$id/$action").bearer(bearer).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsBytes(body)),
    )

    private fun proposal(token: String, name: String): ResultActions = mvc.perform(
        post("/api/mobile/me/identity/name").bearer(token).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsBytes(mapOf("fullName" to name))),
    )

    private fun till(deviceId: String, pharmacyId: String): String {
        val token = "ih-${UUID.randomUUID()}-${UUID.randomUUID()}".take(64)
        jdbc.update(
            """INSERT INTO fulfillment_devices (id, device_id, pharmacy_id, secret_sha256, secret_hint, active)
               VALUES (?, ?, ?, ?, 'ih', true)""",
            UUID.randomUUID(), deviceId, pharmacyId, crypto.sha256Hex(token),
        )
        return token
    }

    private fun adminToken(email: String, role: AdminRole): String {
        users.saveAndFlush(AdminUserEntity(email = email, name = "Synthetic", roleRaw = role.name, passwordHash = encoder.encode(PASSWORD)))
        return adminAuth.login(email, PASSWORD).tokens.accessToken
    }

    private fun ResultActions.json(): JsonNode = mapper.readTree(andReturn().response.contentAsByteArray)

    private fun MockHttpServletRequestBuilder.bearer(token: String) = header("Authorization", "Bearer $token")
}
