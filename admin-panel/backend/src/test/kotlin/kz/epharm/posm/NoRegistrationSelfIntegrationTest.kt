package kz.epharm.posm

import kz.epharm.mobile.auth.service.CabinetLinkService
import kz.epharm.mobile.auth.service.MobileAuthService
import kz.epharm.posm.self.PosmSelfRequest
import kz.epharm.posm.self.PosmSelfService
import kz.epharm.posm.self.StandardNAccountService
import kz.epharm.posm.service.PosmDeviceIdentity
import kz.epharm.posm.service.StandardNDirectoryPerson
import kz.epharm.shared.error.AppException
import org.junit.jupiter.api.Assertions.*
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
import java.time.Duration
import java.time.Instant

/**
 * Real PostgreSQL: no-registration cards from the Standard-N directory, the till's personal window, phone and
 * password saved from the till, and the one-time cabinet link. Synthetic data only.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class NoRegistrationSelfIntegrationTest {
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
    @Autowired private lateinit var accounts: StandardNAccountService
    @Autowired private lateinit var self: PosmSelfService
    @Autowired private lateinit var links: CabinetLinkService
    @Autowired private lateinit var auth: MobileAuthService

    private val pharmacy = "nr-pharmacy"
    private val device = PosmDeviceIdentity("nr-device", pharmacy, legacy = false)
    private val iin = "000101500055"

    @BeforeEach fun setup() {
        jdbc.update("DELETE FROM posm_cabinet_links")
        jdbc.update("DELETE FROM pharmacists WHERE iin IN ('000101500055', '000101500066') OR id LIKE 'nr-%'")
        jdbc.update("DELETE FROM standardn_pharmacist_directory WHERE source_profile_id = 7001")
        jdbc.update("DELETE FROM pharmacies WHERE id LIKE 'nr-%'")
        jdbc.update("""INSERT INTO chains(id,name,"group") VALUES ('nr-chain','Synthetic','pilot') ON CONFLICT DO NOTHING""")
        jdbc.update(
            """INSERT INTO pharmacies(id,name,chain_id,chain_name,city,"group",standardn_profile_id,standardn_profile_source)
               VALUES (?, 'Synthetic NR pharmacy', 'nr-chain', 'Synthetic', 'Алматы', 'pilot', 7001, 'manual')""",
            pharmacy,
        )
        jdbc.update(
            """INSERT INTO standardn_pharmacist_directory(directory_key,source_profile_id,source_user_id,iin,full_name,source_status,profile_name)
               VALUES ('7001:34', 7001, 34, ?, 'Синтетикова Айгерим Нурлановна', 0, 'Synthetic NR pharmacy')""",
            iin,
        )
    }

    private fun request(phone: String? = null, password: String? = null) =
        PosmSelfRequest(pharmacyId = pharmacy, standardNUserId = "34", phone = phone, password = password)

    @Test fun `a card is created once from the directory, without phone or password, and reused`() {
        val person = StandardNDirectoryPerson(iin, "Синтетикова Айгерим Нурлановна", 0)
        val first = accounts.ensure(person, pharmacy)!!
        val second = accounts.ensure(person, pharmacy)!!
        assertEquals(first.id, second.id)
        assertEquals("", first.phone)
        assertNull(first.passwordHash)
        assertEquals("standardn", jdbc.queryForObject("SELECT account_source FROM pharmacists WHERE id = ?", String::class.java, first.id))
        // Two cards without a phone do not collide on the phone index.
        accounts.ensure(StandardNDirectoryPerson("000101500066", "Синтетиков Второй", 0), pharmacy)
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM pharmacists WHERE phone = '' AND iin IN ('000101500055','000101500066')", Int::class.java))
    }

    @Test fun `the till window shows the person, asks for phone and password and gives a cabinet link`() {
        val view = self.view(device, request())
        assertEquals("Синтетикова Айгерим Нурлановна", view.fullName)
        assertEquals("000101•••••5", view.iinMasked)
        assertFalse(view.phoneBound)
        assertFalse(view.passwordSet)
        assertEquals(0L, view.earnedMonthKzt)
        assertTrue(view.cabinetUrl!!.contains("/learn/enter#t="))
        assertTrue(view.cabinetUrlExpiresAt!!.isAfter(Instant.now()))
    }

    @Test fun `the background check for the red dot issues no link`() {
        val view = self.view(device, request().copy(link = false))
        assertNull(view.cabinetUrl)
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM posm_cabinet_links", Int::class.java))
    }

    @Test fun `phone and password saved from the till fill the profile, phone stays self-declared`() {
        val saved = self.saveContact(device, request(phone = "8 701 555 12 34", password = "synthetic-pass-1"))
        assertTrue(saved.phoneBound)
        assertTrue(saved.passwordSet)
        val row = jdbc.queryForMap("SELECT phone, phone_verification_status FROM pharmacists WHERE iin = ?", iin)
        assertEquals("+77015551234", row["phone"])
        assertEquals("self_declared", row["phone_verification_status"])
    }

    @Test fun `the till fills an empty profile but never replaces a phone or password`() {
        self.saveContact(device, request(phone = "+7 701 555 12 34", password = "synthetic-pass-1"))
        val hash = jdbc.queryForObject("SELECT password_hash FROM pharmacists WHERE iin = ?", String::class.java, iin)
        val password = assertThrows(AppException::class.java) { self.saveContact(device, request(password = "synthetic-pass-2")) }
        assertEquals(409, password.status.value())
        val phone = assertThrows(AppException::class.java) { self.saveContact(device, request(phone = "+7 701 555 00 00")) }
        assertEquals(409, phone.status.value())
        // The same number typed again is not a change.
        assertTrue(self.saveContact(device, request(phone = "87015551234")).phoneBound)
        val row = jdbc.queryForMap("SELECT phone, password_hash FROM pharmacists WHERE iin = ?", iin)
        assertEquals("+77015551234", row["phone"])
        assertEquals(hash, row["password_hash"])
    }

    @Test fun `a phone that belongs to another card is refused`() {
        jdbc.update(
            """INSERT INTO pharmacists(id,name,iin,phone,tier,status,pharmacy_id,city) VALUES ('nr-other','Other','000101500066','+77015559999','Silver','active',?,'')""",
            pharmacy,
        )
        val error = assertThrows(AppException::class.java) { self.saveContact(device, request(phone = "+7 701 555 99 99")) }
        assertEquals(409, error.status.value())
    }

    @Test fun `a till of another pharmacy cannot open the window`() {
        val foreign = PosmDeviceIdentity("nr-device", "nr-foreign", legacy = false)
        assertThrows(AppException::class.java) { self.view(foreign, request()) }
    }

    @Test fun `an unknown Standard-N user gets a clear not-found`() {
        val error = assertThrows(AppException::class.java) {
            self.view(device, PosmSelfRequest(pharmacyId = pharmacy, standardNUserId = "99"))
        }
        assertEquals(404, error.status.value())
    }

    @Test fun `the one-time link signs in once and never again`() {
        val view = self.view(device, request())
        val token = view.cabinetUrl!!.substringAfter("#t=")
        val session = auth.loginWithCabinetLink(token)
        assertTrue(session.tokens.accessToken.isNotBlank())
        assertEquals("Синтетикова Айгерим Нурлановна", session.pharmacist.name)
        assertThrows(AppException::class.java) { auth.loginWithCabinetLink(token) }
        assertNull(links.consume("not-a-token"))
    }

    @Test fun `a cabinet opened from the till stays signed in for one shift, through refreshes`() {
        val token = self.view(device, request()).cabinetUrl!!.substringAfter("#t=")
        val first = auth.loginWithCabinetLink(token).tokens
        val shiftEnd = Instant.now().plus(Duration.ofHours(12))
        assertTrue(first.refreshTokenExpiresAt.isBefore(shiftEnd.plusSeconds(60)))
        val second = auth.refresh(first.refreshToken).tokens
        assertTrue(Duration.between(second.refreshTokenExpiresAt, first.refreshTokenExpiresAt).abs() < Duration.ofSeconds(1))
    }

    @Test fun `an expired link does not sign in`() {
        val cardId = accounts.ensure(StandardNDirectoryPerson(iin, "Синтетикова", 0), pharmacy)!!.id
        val issued = links.issue(cardId, pharmacy, "nr-device", now = Instant.now().minusSeconds(4000))
        assertNull(links.consume(issued.url.substringAfter("#t=")))
    }
}
