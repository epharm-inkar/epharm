package kz.epharm.posm

import io.mockk.*
import java.time.Instant
import kz.epharm.pharmacists.entity.PharmacistEntity
import kz.epharm.pharmacists.entity.PharmacistStatus
import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.posm.entity.PosmPharmacistMappingEntity
import kz.epharm.posm.repository.PosmPharmacistMappingRepository
import kz.epharm.posm.service.*
import kz.epharm.posm.staff.SourceStaffBindingService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.Optional

class NativeSellerIdentityIsolationTest {
    private val people = mockk<PharmacistRepository>()
    private val mappings = mockk<PosmPharmacistMappingRepository>()
    private val bindings = mockk<SourceStaffBindingService>()
    private val service = PosmPharmacistIdentityService(people, mappings, bindings)
    private val namespace = "a".repeat(64)
    private val at = Instant.parse("2026-11-16T08:00:00Z")
    private val person = PharmacistEntity(id="17", name="Synthetic Person", pharmacyId="ph")
        .also { it.status = PharmacistStatus.active }

    init {
        every { mappings.findByPharmacyIdAndExternalUserIdAndActiveTrue("ph", "17") } returns null
        every { bindings.resolve("ph", "device", any(), "17", at, "41", "ws") } returns
            SourceStaffBindingService.Resolution(false)
    }

    private fun resolve(source: String?, name: String? = null) =
        service.resolve("ph", "17", name, "device", source, at, "41", "ws")

    @Test fun `native raw user equal to internal person id remains unmapped without proof`() {
        val result = resolve(namespace)
        assertEquals("", result.pharmacistId)
        assertEquals(PharmacistIdentitySource.STANDARDN_UNMAPPED, result.source)
        assertEquals("17", result.reportedPharmacistId)
        verify { people wasNot Called }
    }

    @Test fun `source-less legacy identity keeps the internal id compatibility path`() {
        every { people.findById("17") } returns Optional.of(person)
        val result = resolve(null)
        assertEquals("17", result.pharmacistId)
        assertEquals(PharmacistIdentitySource.POSM_INTERNAL, result.source)
    }

    @Test fun `an exact native name is diagnostic matching rather than internal identity`() {
        every { people.findAllByPharmacyIdAndNameIgnoreCaseAndStatusRaw("ph", person.name, "active") } returns listOf(person)
        val result = resolve(namespace, person.name)
        assertEquals(PharmacistIdentitySource.STANDARDN_NAME_MATCH, result.source)
        verify(exactly=0) { people.findById(any()) }
    }

    @Test fun `confirmed HQ mapping still authorizes an explicit native user`() {
        every { mappings.findByPharmacyIdAndExternalUserIdAndActiveTrue("ph", "17") } returns
            PosmPharmacistMappingEntity(pharmacyId="ph", externalUserId="17", pharmacistId="17")
        every { people.findById("17") } returns Optional.of(person)
        val result = resolve(namespace)
        assertEquals("17", result.pharmacistId)
        assertEquals(PharmacistIdentitySource.STANDARDN_EXPLICIT_MAPPING, result.source)
    }

    @Test fun `receipt-time source-bound proof still authorizes its exact person`() {
        every { bindings.resolve("ph", "device", namespace, "17", at, "41", "ws") } returns
            SourceStaffBindingService.Resolution(true, SourceStaffBindingService.Recipient("recipient", "Verified Person"))
        val result = resolve(namespace)
        assertEquals("recipient", result.pharmacistId)
        assertEquals(PharmacistIdentitySource.STANDARDN_RPS_BINDING, result.source)
        verify { people wasNot Called }
    }
}
