package kz.epharm.posm

import io.mockk.*
import java.time.Instant
import kz.epharm.pharmacists.entity.PharmacistEntity
import kz.epharm.pharmacists.entity.PharmacistStatus
import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.pharmacists.service.StandardNPharmacistSyncService
import kz.epharm.posm.entity.PosmPharmacistMappingEntity
import kz.epharm.posm.repository.PosmPharmacistMappingRepository
import kz.epharm.posm.service.*
import kz.epharm.posm.staff.SourceStaffBindingService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.Optional

/** Seller by Standard-N USER_ID -> central directory -> IIN, without ePharm registration (V112). */
class StandardNDirectorySellerIdentityTest {
    private val people = mockk<PharmacistRepository>()
    private val mappings = mockk<PosmPharmacistMappingRepository>()
    private val bindings = mockk<SourceStaffBindingService>()
    private val directory = mockk<StandardNDirectoryIdentityLookup>()
    private val service = PosmPharmacistIdentityService(people, mappings, bindings, directory)
    private val namespace = "a".repeat(64)
    private val at = Instant.parse("2026-11-24T13:00:00Z")
    private val iin = "000101500011"
    private val directoryPerson = StandardNDirectoryPerson(iin, "Synthetic Directory Person", 0)

    init {
        every { mappings.findByPharmacyIdAndExternalUserIdAndActiveTrue("ph", "34") } returns null
        every { bindings.resolve("ph", "device", any(), "34", at, "41", "ws") } returns SourceStaffBindingService.Resolution(false)
        every { people.findAllByPharmacyIdAndNameIgnoreCaseAndStatusRaw(any(), any(), any()) } returns emptyList()
    }

    private fun receipt(name: String? = null) =
        service.resolve("ph", "34", name, "device", namespace, at, "41", "ws", useDirectory = true)

    private fun person(id: String, pharmacy: String, status: PharmacistStatus = PharmacistStatus.active) =
        PharmacistEntity(id = id, name = "Synthetic Registered", pharmacyId = pharmacy).also {
            it.iin = iin
            it.status = status
        }

    @Test fun `unregistered seller is named from the directory and keeps the IIN`() {
        every { directory.find("ph", "34") } returns directoryPerson
        every { people.findByIin(iin) } returns null
        val result = receipt()
        assertEquals("", result.pharmacistId)
        assertEquals("Synthetic Directory Person", result.pharmacistName)
        assertEquals(PharmacistIdentitySource.STANDARDN_DIRECTORY_UNREGISTERED, result.source)
        assertEquals(iin, result.sellerIin)
        assertEquals("34", result.reportedPharmacistId)
    }

    @Test fun `registered seller from another home pharmacy is credited where they sell`() {
        every { directory.find("ph", "34") } returns directoryPerson
        every { people.findByIin(iin) } returns person("ph_person", pharmacy = "other")
        val result = receipt()
        assertEquals("ph_person", result.pharmacistId)
        assertEquals("Synthetic Registered", result.pharmacistName)
        assertEquals(PharmacistIdentitySource.STANDARDN_DIRECTORY, result.source)
        assertEquals(iin, result.sellerIin)
    }

    @Test fun `blocked ePharm account is not used, the IIN still is`() {
        every { directory.find("ph", "34") } returns directoryPerson
        every { people.findByIin(iin) } returns person("ph_person", pharmacy = "ph", status = PharmacistStatus.blocked)
        val result = receipt()
        assertEquals("", result.pharmacistId)
        assertEquals(PharmacistIdentitySource.STANDARDN_DIRECTORY_UNREGISTERED, result.source)
        assertEquals(iin, result.sellerIin)
    }

    @Test fun `recommendation requests keep the proven paths and never ask the directory`() {
        val result = service.resolve("ph", "34", null, "device", namespace, at, "41", "ws")
        assertEquals(PharmacistIdentitySource.STANDARDN_UNMAPPED, result.source)
        assertNull(result.sellerIin)
        verify { directory wasNot Called }
    }

    @Test fun `HQ mapping stays first and also records the IIN`() {
        every { mappings.findByPharmacyIdAndExternalUserIdAndActiveTrue("ph", "34") } returns
            PosmPharmacistMappingEntity(pharmacyId = "ph", externalUserId = "34", pharmacistId = "ph_person")
        every { people.findById("ph_person") } returns Optional.of(person("ph_person", pharmacy = "ph"))
        val result = receipt()
        assertEquals(PharmacistIdentitySource.STANDARDN_EXPLICIT_MAPPING, result.source)
        assertEquals(iin, result.sellerIin)
        verify { directory wasNot Called }
    }

    @Test fun `no directory row falls back to the old unmapped result`() {
        every { directory.find("ph", "34") } returns null
        val result = receipt()
        assertEquals("", result.pharmacistId)
        assertEquals(PharmacistIdentitySource.STANDARDN_UNMAPPED, result.source)
        assertNull(result.sellerIin)
    }

    @Test fun `without registration the card is created from the directory and receives the receipt`() {
        val accounts = mockk<kz.epharm.posm.self.StandardNAccountService>()
        val withAccounts = PosmPharmacistIdentityService(people, mappings, bindings, directory, accounts)
        every { directory.find("ph", "34") } returns directoryPerson
        every { people.findByIin(iin) } returns null
        every { accounts.ensure(directoryPerson, "ph") } returns person("ph_auto", pharmacy = "ph")
        val result = withAccounts.resolve("ph", "34", null, "device", namespace, at, "41", "ws", useDirectory = true)
        assertEquals("ph_auto", result.pharmacistId)
        assertEquals(PharmacistIdentitySource.STANDARDN_DIRECTORY, result.source)
        assertEquals(iin, result.sellerIin)
    }

    @Test fun `a blocked card found by IIN is never re-created`() {
        val accounts = mockk<kz.epharm.posm.self.StandardNAccountService>()
        val withAccounts = PosmPharmacistIdentityService(people, mappings, bindings, directory, accounts)
        every { directory.find("ph", "34") } returns directoryPerson
        every { people.findByIin(iin) } returns person("ph_person", pharmacy = "ph", status = PharmacistStatus.blocked)
        val result = withAccounts.resolve("ph", "34", null, "device", namespace, at, "41", "ws", useDirectory = true)
        assertEquals("", result.pharmacistId)
        verify(exactly = 0) { accounts.ensure(any(), any()) }
    }

    @Test fun `directory beats a weak same-name match`() {
        every { directory.find("ph", "34") } returns directoryPerson
        every { people.findByIin(iin) } returns null
        val result = receipt(name = "Synthetic Registered")
        assertEquals(PharmacistIdentitySource.STANDARDN_DIRECTORY_UNREGISTERED, result.source)
        verify(exactly = 0) { people.findAllByPharmacyIdAndNameIgnoreCaseAndStatusRaw(any(), any(), any()) }
    }

    @Test fun `double-encoded Standard-N names are repaired, real names are kept`() {
        val garbled = String("Каримова Айгуль".toByteArray(Charsets.UTF_8), charset("windows-1251"))
        assertEquals("Каримова Айгуль", StandardNPharmacistSyncService.repairDoubleEncoded(garbled))
        val kazakh = String("Қызырхан Ұлжан".toByteArray(Charsets.UTF_8), charset("windows-1251"))
        assertEquals("Қызырхан Ұлжан", StandardNPharmacistSyncService.repairDoubleEncoded(kazakh))
        listOf("Строева Роза", "Рустем Сагинтаев", "Ivanova Anna", "Фармацевт Standard-N", "").forEach {
            assertEquals(it, StandardNPharmacistSyncService.repairDoubleEncoded(it))
        }
    }
}
