package kz.epharm.posm.identity

import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkObject
import kz.epharm.auth.access.UnifiedAuthorityLock
import io.mockk.mockk
import io.mockk.verify
import kz.epharm.pharmacists.entity.PharmacistEntity
import kz.epharm.pharmacists.entity.PharmacistStatus
import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.posm.entity.PosmPharmacistMappingEntity
import kz.epharm.posm.repository.PosmPharmacistMappingRepository
import kz.epharm.posm.service.PosmDeviceIdentity
import kz.epharm.posm.service.StandardNDirectoryIdentityLookup
import kz.epharm.posm.service.StandardNDirectoryPerson
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.util.Optional
import java.util.UUID

/** The till's and the cabinet's side of identity claims, without a database: who may send, what is checked, what is stored. */
class IdentityClaimSubmissionServiceTest {
    private val store = mockk<IdentityClaimStore>(relaxed = true)
    private val directory = mockk<StandardNDirectoryIdentityLookup>()
    private val pharmacists = mockk<PharmacistRepository>()
    private val mappings = mockk<PosmPharmacistMappingRepository>()
    private val limiter = mockk<IdentityClaimLimiter>(relaxed = true)
    private val jdbc = mockk<JdbcTemplate>(relaxed = true)
    private val admin = mockk<IdentityClaimAdminService>()
    // HQ decides in most tests here; the tests of ePharm taking a clean claim itself build their own service.
    private val service = IdentityClaimSubmissionService(store, directory, pharmacists, mappings, limiter, jdbc, admin, autoConfirm = false)
    private val auto = IdentityClaimSubmissionService(store, directory, pharmacists, mappings, limiter, jdbc, admin, autoConfirm = true)

    private val device = PosmDeviceIdentity("dev-1", "ph-1", legacy = false)
    private val iin = "900115300013"
    private val name = "Бузурбаева Лаура Ермековна"

    init {
        every { directory.find(any(), any()) } returns null
        every { store.confirmedCardId(any(), any()) } returns null
        every { store.latestPerson(any(), any()) } returns null
        every { store.profileIdOf("ph-1") } returns 7001L
        every { mappings.findByPharmacyIdAndExternalUserIdAndActiveTrue(any(), any()) } returns null
        every { pharmacists.findByIin(any()) } returns null
    }

    private fun request(userId: String = "18", iin: String? = this.iin, fullName: String? = name, userName: String? = "Бузурбаева Л") =
        PosmIdentityRequest("ph-1", userId, userName, iin, fullName)

    private fun claim(status: String, reason: String? = null) = IdentityClaimRow(
        id = UUID.randomUUID(), kind = "person", pharmacyId = "ph-1", standardNProfileId = 7001, standardNUserId = "18",
        standardNUserName = null, deviceId = "dev-1", pharmacistId = null, iin = iin, fullName = name, status = status,
        conflict = null, conflictPharmacistId = null, directoryIin = null, decidedBy = null, decidedAt = null,
        decisionReason = reason, mappingId = null, createdAt = Instant.now(), updatedAt = Instant.now(),
    )

    private fun refused(status: HttpStatus, block: () -> Unit): AppException {
        val error = assertThrows(AppException::class.java) { block() }
        assertEquals(status, error.status)
        return error
    }

    // ── till: where the user stands ─────────────────────────────────────────

    @Test fun `a user the directory names is known, no form`() {
        every { directory.find("ph-1", "18") } returns StandardNDirectoryPerson(iin, name, 0)
        assertEquals("known", service.tillState(device, request()).state)
    }

    @Test fun `a user whose confirmed claim is still in force is known`() {
        every { store.confirmedCardId("ph-1", "18") } returns "ph_card"
        assertEquals("known", service.tillState(device, request()).state)
    }

    @Test fun `an open claim shows the masked IIN and the name back`() {
        every { store.latestPerson("ph-1", "18") } returns claim("pending")
        val state = service.tillState(device, request())
        assertEquals("pending", state.state)
        assertEquals("900115•••••3", state.iinMasked)
        assertEquals(name, state.fullName)
    }

    @Test fun `a rejected claim carries the reason of the office, the form comes back`() {
        every { store.latestPerson("ph-1", "18") } returns claim("rejected", "ИИН не совпадает с документом")
        val state = service.tillState(device, request())
        assertEquals("rejected", state.state)
        assertEquals("ИИН не совпадает с документом", state.reason)
        assertNull(state.iinMasked)
    }

    @Test fun `no claim, or a claim that has run its course without a mapping in force, is unknown`() {
        assertEquals("unknown", service.tillState(device, request()).state)
        every { store.latestPerson("ph-1", "18") } returns claim("superseded")
        assertEquals("unknown", service.tillState(device, request()).state)
        every { store.latestPerson("ph-1", "18") } returns claim("confirmed")
        assertEquals("unknown", service.tillState(device, request()).state)
    }

    @Test fun `a till of another pharmacy gets 403 and a garbage user id 400`() {
        refused(HttpStatus.FORBIDDEN) { service.tillState(PosmDeviceIdentity("dev-1", "ph-2", legacy = false), request()) }
        refused(HttpStatus.BAD_REQUEST) { service.tillState(device, request(userId = "abc")) }
    }

    // ── till: sending ───────────────────────────────────────────────────────

    @Test fun `a valid claim is stored with the canonical user id, the profile, the till and no flag`() {
        val state = service.tillSubmit(device, request(userId = "0018", iin = " $iin ", fullName = "  Бузурбаева   Лаура Ермековна "))
        assertEquals("pending", state.state)
        assertEquals("900115•••••3", state.iinMasked)
        assertEquals(name, state.fullName)
        verify(exactly = 1) {
            store.upsertPerson("ph-1", 7001L, "18", "Бузурбаева Л", "dev-1", iin, name, null, null)
        }
        // Both budgets are spent: the till and the person.
        verify { limiter.take("device:dev-1", IdentityClaimLimiter.PER_DEVICE) }
        verify { limiter.take("person:ph-1:18", IdentityClaimLimiter.PER_PERSON) }
    }

    @Test fun `the IIN of an existing card is not refused but flagged for HQ, and the answer does not tell the till`() {
        val other = PharmacistEntity(id = "ph_other", name = "Другая Карточка", iin = iin, pharmacyId = "ph-9")
        every { pharmacists.findByIin(iin) } returns other
        val state = service.tillSubmit(device, request())
        assertEquals("pending", state.state)
        assertNull(state.reason)
        verify { store.upsertPerson("ph-1", 7001L, "18", "Бузурбаева Л", "dev-1", iin, name, "iin_other_card", "ph_other") }
    }

    @Test fun `a wrong control digit or a one-word name is refused, yet the attempt is counted`() {
        refused(HttpStatus.BAD_REQUEST) { service.tillSubmit(device, request(iin = "900115300010")) }
        refused(HttpStatus.BAD_REQUEST) { service.tillSubmit(device, request(fullName = "Лаура")) }
        refused(HttpStatus.BAD_REQUEST) { service.tillSubmit(device, request(iin = null)) }
        verify(exactly = 3) { limiter.take("device:dev-1", IdentityClaimLimiter.PER_DEVICE) }
        verify(exactly = 0) { store.upsertPerson(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `a fleet-key till and a till of another pharmacy cannot send`() {
        refused(HttpStatus.FORBIDDEN) { service.tillSubmit(PosmDeviceIdentity("dev-1", "ph-1", legacy = true), request()) }
        refused(HttpStatus.FORBIDDEN) { service.tillSubmit(PosmDeviceIdentity(null, "ph-1", legacy = false), request()) }
        refused(HttpStatus.FORBIDDEN) { service.tillSubmit(PosmDeviceIdentity("dev-2", "ph-2", legacy = false), request()) }
        verify(exactly = 0) { store.upsertPerson(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `a user the directory or a confirmed claim already names cannot send a claim`() {
        every { directory.find("ph-1", "18") } returns StandardNDirectoryPerson(iin, name, 0)
        assertEquals(ErrorCode.CONFLICT, refused(HttpStatus.CONFLICT) { service.tillSubmit(device, request()) }.code)
        every { directory.find("ph-1", "18") } returns null
        every { store.confirmedCardId("ph-1", "18") } returns "ph_card"
        refused(HttpStatus.CONFLICT) { service.tillSubmit(device, request()) }
        verify(exactly = 0) { store.upsertPerson(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `a user HQ already mapped by hand cannot send a claim either`() {
        every { mappings.findByPharmacyIdAndExternalUserIdAndActiveTrue("ph-1", "18") } returns
            PosmPharmacistMappingEntity(pharmacyId = "ph-1", externalUserId = "18", pharmacistId = "ph_card")
        refused(HttpStatus.CONFLICT) { service.tillSubmit(device, request()) }
        verify(exactly = 0) { store.upsertPerson(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `a refused budget stops the request before anything is read or stored`() {
        every { limiter.take("person:ph-1:18", any(), any()) } throws
            AppException(ErrorCode.OTP_TOO_MANY_ATTEMPTS, "Слишком много попыток", HttpStatus.TOO_MANY_REQUESTS, retryAfterSeconds = 120)
        val error = refused(HttpStatus.TOO_MANY_REQUESTS) { service.tillSubmit(device, request()) }
        assertEquals(120L, error.retryAfterSeconds)
        verify(exactly = 0) { store.upsertPerson(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    // ── till: ePharm takes a clean claim itself (owner, 26.11.2026) ─────────

    @AfterEach fun releaseLock() = unmockkObject(UnifiedAuthorityLock)

    private fun lockless() {
        mockkObject(UnifiedAuthorityLock)
        every { UnifiedAuthorityLock.acquire(any()) } just runs
    }

    @Test fun `with the switch off nothing is taken without HQ`() {
        service.tillSubmit(device, request())
        verify(exactly = 0) { admin.autoConfirm(any()) }
    }

    @Test fun `a clean claim taken by ePharm answers known, under the HQ lock`() {
        lockless()
        val id = UUID.randomUUID()
        every { store.upsertPerson(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns id
        every { admin.autoConfirm(id) } returns "ph_new"
        val state = auto.tillSubmit(device, request())
        assertEquals("known", state.state)
        assertNull(state.iinMasked)
        verify(exactly = 1) { UnifiedAuthorityLock.acquire(jdbc) }
    }

    @Test fun `a claim ePharm leaves for HQ answers pending as before`() {
        lockless()
        val id = UUID.randomUUID()
        every { store.upsertPerson(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns id
        every { admin.autoConfirm(id) } returns null
        assertEquals("pending", auto.tillSubmit(device, request()).state)
    }

    @Test fun `the IIN of an existing card is never taken without HQ`() {
        lockless()
        every { pharmacists.findByIin(iin) } returns PharmacistEntity(id = "ph_other", name = "Другая Карточка", iin = iin, pharmacyId = "ph-9")
        assertEquals("pending", auto.tillSubmit(device, request()).state)
        verify(exactly = 0) { admin.autoConfirm(any()) }
    }

    // ── cabinet ─────────────────────────────────────────────────────────────

    private fun card(name: String, status: PharmacistStatus = PharmacistStatus.active, iin: String = this.iin) =
        PharmacistEntity(id = "ph_card", name = name, iin = iin, pharmacyId = "ph-1").also { it.status = status }

    @Test fun `the cabinet offers the form to an active card with a one-word name only`() {
        every { pharmacists.findById("ph_card") } returns Optional.of(card("Бузурбаева"))
        every { store.latestName("ph_card") } returns null
        val state = service.cabinetState("ph_card")
        assertTrue(state.nameIncomplete)
        assertTrue(state.canPropose)
        assertEquals("registration", state.nameSource)

        every { pharmacists.findById("ph_card") } returns Optional.of(card("Бузурбаева Лаура"))
        assertFalse(service.cabinetState("ph_card").canPropose)
        every { pharmacists.findById("ph_card") } returns Optional.of(card("Бузурбаева", PharmacistStatus.pending))
        assertFalse(service.cabinetState("ph_card").canPropose)
    }

    @Test fun `while a proposal waits the form is hidden and the proposal is shown, a rejected one comes with its reason`() {
        every { pharmacists.findById("ph_card") } returns Optional.of(card("Бузурбаева"))
        every { store.latestName("ph_card") } returns claim("pending").copy(kind = "name")
        val waiting = service.cabinetState("ph_card")
        assertFalse(waiting.canPropose)
        assertEquals("pending", waiting.claim!!.status)

        every { store.latestName("ph_card") } returns claim("rejected", "Нужно полное имя").copy(kind = "name")
        val rejected = service.cabinetState("ph_card")
        assertTrue(rejected.canPropose)
        assertEquals("Нужно полное имя", rejected.claim!!.reason)

        // A confirmed or superseded proposal is history: the card already shows the result.
        every { store.latestName("ph_card") } returns claim("superseded").copy(kind = "name")
        assertNull(service.cabinetState("ph_card").claim)
    }

    @Test fun `a proposal is stored for the card with its own IIN, spent against the card's budget`() {
        every { pharmacists.findByIdForUpdate("ph_card") } returns card("Бузурбаева")
        every { store.directoryNamesOf(iin) } returns emptyList()
        every { pharmacists.findById("ph_card") } returns Optional.of(card("Бузурбаева"))
        every { store.latestName("ph_card") } returns null
        service.cabinetPropose("ph_card", "  Бузурбаева  Лаура Ермековна ")
        verify { limiter.take("card:ph_card", IdentityClaimLimiter.PER_PERSON) }
        verify(exactly = 1) { store.upsertName("ph_card", "ph-1", iin, name) }
    }

    @Test fun `a full name, an inactive card and a card without a valid IIN are refused`() {
        every { pharmacists.findByIdForUpdate("ph_card") } returns card("Бузурбаева Лаура")
        refused(HttpStatus.CONFLICT) { service.cabinetPropose("ph_card", name) }
        every { pharmacists.findByIdForUpdate("ph_card") } returns card("Бузурбаева", PharmacistStatus.blocked)
        refused(HttpStatus.CONFLICT) { service.cabinetPropose("ph_card", name) }
        every { pharmacists.findByIdForUpdate("ph_card") } returns card("Бузурбаева", iin = "")
        refused(HttpStatus.CONFLICT) { service.cabinetPropose("ph_card", name) }
        every { pharmacists.findByIdForUpdate("ph_card") } returns null
        refused(HttpStatus.NOT_FOUND) { service.cabinetPropose("ph_card", name) }
        refused(HttpStatus.BAD_REQUEST) { service.cabinetPropose("ph_card", "Лаура") }
        verify(exactly = 0) { store.upsertName(any(), any(), any(), any()) }
    }

    @Test fun `when the directory already has the full name the card takes it and nothing is asked`() {
        val entity = card("Бузурбаева")
        every { pharmacists.findByIdForUpdate("ph_card") } returns entity
        every { store.directoryNamesOf(iin) } returns listOf("Бузурбаева", "Бузурбаева Лаура Ермековна")
        every { pharmacists.saveAndFlush(entity) } returns entity
        every { store.latestName("ph_card") } returns null
        val state = service.cabinetPropose("ph_card", "Бузурбаева Лаура")
        assertEquals("Бузурбаева Лаура Ермековна", entity.name)
        assertEquals("standardn", entity.nameSource)
        assertEquals("standardn", state.nameSource)
        assertFalse(state.nameIncomplete)
        verify(exactly = 0) { store.upsertName(any(), any(), any(), any()) }
    }

    @Test fun `a directory name that is still one word does not stop the proposal, a name HQ set by hand is never overwritten`() {
        val entity = card("Бузурбаева")
        every { pharmacists.findByIdForUpdate("ph_card") } returns entity
        every { store.directoryNamesOf(iin) } returns listOf("Бузурбаева")
        every { store.latestName("ph_card") } returns null
        service.cabinetPropose("ph_card", name)
        verify(exactly = 1) { store.upsertName("ph_card", "ph-1", iin, name) }

        val byHq = card("Бузурбаева").also { it.nameSource = "hq" }
        every { pharmacists.findByIdForUpdate("ph_card") } returns byHq
        every { store.directoryNamesOf(iin) } returns listOf("Бузурбаева Лаура Ермековна")
        service.cabinetPropose("ph_card", name)
        assertEquals("Бузурбаева", byHq.name)
        verify(exactly = 2) { store.upsertName("ph_card", "ph-1", iin, name) }
    }
}
