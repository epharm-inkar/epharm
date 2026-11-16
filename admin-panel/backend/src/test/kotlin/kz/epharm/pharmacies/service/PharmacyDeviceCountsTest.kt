package kz.epharm.pharmacies.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kz.epharm.auth.access.AdministrativeScope
import kz.epharm.pharmacies.dto.PharmacyDto
import kz.epharm.pharmacies.dto.UpdatePharmacyRequest
import kz.epharm.pharmacies.entity.PharmacyEntity
import kz.epharm.pharmacies.repository.PharmacyDeviceCounts
import kz.epharm.pharmacies.repository.PharmacyRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertThrows
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import java.util.Optional
import java.time.Instant

class PharmacyDeviceCountsTest {
    private val repository = mockk<PharmacyRepository>()
    private val scope = mockk<AdministrativeScope>()
    private val service = PharmacyService(repository, mockk(), mockk(), scope, 90)

    @Test
    fun `one aggregate is restricted to returned permitted rows and missing groups are real zero`() {
        val allowed = setOf("allowed", "empty", "filtered-out")
        every { scope.currentPharmacyIds() } returns allowed
        every { scope.pharmacy(any()) } answers { firstArg<PharmacyDto>() }
        every { repository.findForAdministration(allowed, null, "chain") } returns listOf(
            PharmacyEntity(id = "allowed"), PharmacyEntity(id = "empty"),
        )
        val counts = mockk<PharmacyDeviceCounts>()
        every { counts.pharmacyId } returns "allowed"
        every { counts.registered } returns 4
        every { counts.online } returns 1
        every { repository.deviceCounts(setOf("allowed", "empty"), any(), any()) } answers {
            val cutoff = secondArg<java.time.Instant>()
            val observed = thirdArg<java.time.Instant>()
            assertThat(java.time.Duration.between(cutoff, observed).seconds).isEqualTo(90)
            listOf(counts)
        }

        val result = service.list(chainId = "chain")
        assertThat(result.map { it.registeredDeviceCount }).containsExactly(4L, 0L)
        assertThat(result.map { it.onlineDeviceCount }).containsExactly(1L, 0L)
        assertThat(result.map { it.deviceCountsObservedAt }.distinct()).hasSize(1)
        verify(exactly = 1) { repository.deviceCounts(setOf("allowed", "empty"), any(), any()) }
        verify(exactly = 0) { repository.findAllByOrderByNameAsc() }
    }

    @Test
    fun `empty authorization never reads the device registry`() {
        every { scope.currentPharmacyIds() } returns emptySet()
        assertThat(service.list()).isEmpty()
        verify(exactly = 0) { repository.deviceCounts(any(), any(), any()) }
    }

    @Test
    fun `inventory writes keep scope locked lookup and server-owned observation time`() {
        val allowed = setOf("allowed")
        val row = PharmacyEntity(id = "allowed")
        every { scope.currentPharmacyIds() } returns allowed
        every { scope.requirePharmacyUpdate(any()) } returns Unit
        every { scope.pharmacy(any()) } answers { firstArg<PharmacyDto>() }
        every { repository.findInAdministrativeScopeForUpdate("allowed", allowed) } returns Optional.of(row)
        every { repository.findInAdministrativeScopeForUpdate("foreign", allowed) } returns Optional.empty()
        every { repository.save(row) } returns row
        every { repository.deviceCounts(allowed, any(), any()) } returns emptyList()
        val before = Instant.now()
        val result = service.update("allowed", UpdatePharmacyRequest(
            inventoryDeviceCount = 3, inventorySource = " Synthetic count ",
        ))
        assertThat(result.inventoryDeviceCount).isEqualTo(3)
        assertThat(result.inventorySource).isEqualTo("Synthetic count")
        assertThat(result.inventoryUpdatedAt).isBetween(before, Instant.now())
        assertThat(result.registeredDeviceCount).isZero()
        val denied = assertThrows(AppException::class.java) {
            service.update("foreign", UpdatePharmacyRequest(inventoryDeviceCount = 1, inventorySource = "Synthetic"))
        }
        assertThat(denied.status).isEqualTo(HttpStatus.NOT_FOUND)
        verify(exactly = 1) { repository.save(any()) }
        verify(exactly = 0) { repository.findById(any()) }
    }

    @Test
    fun `inventory validation also protects internal callers before mutating other fields`() {
        val row = PharmacyEntity(id = "allowed", name = "Original")
        every { scope.currentPharmacyIds() } returns setOf("allowed")
        every { scope.requirePharmacyUpdate(any()) } returns Unit
        every { repository.findInAdministrativeScopeForUpdate("allowed", setOf("allowed")) } returns Optional.of(row)
        assertThrows(AppException::class.java) {
            service.update("allowed", UpdatePharmacyRequest(name = "Changed", inventoryDeviceCount = -1, inventorySource = "Synthetic"))
        }
        assertThat(row.name).isEqualTo("Original")
        assertThat(row.inventoryDeviceCount).isNull()
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `unrestricted inventory update locks the row before loading mutable fields`() {
        val row = PharmacyEntity(id = "allowed")
        every { scope.currentPharmacyIds() } returns null
        every { scope.requirePharmacyUpdate(any()) } returns Unit
        every { scope.pharmacy(any()) } answers { firstArg<PharmacyDto>() }
        every { repository.findByIdForUpdate("allowed") } returns Optional.of(row)
        every { repository.save(row) } returns row
        every { repository.deviceCounts(setOf("allowed"), any(), any()) } returns emptyList()

        assertThat(service.update("allowed", UpdatePharmacyRequest(
            inventoryDeviceCount = 2, inventorySource = "Synthetic inventory",
        )).inventoryDeviceCount).isEqualTo(2)
        verify(exactly = 1) { repository.findByIdForUpdate("allowed") }
        verify(exactly = 0) { repository.findById(any()) }
    }

    @Test
    fun `inventory cannot bypass existing update permission check`() {
        every { scope.requirePharmacyUpdate(any()) } throws AppException(
            ErrorCode.FORBIDDEN, "Denied", HttpStatus.FORBIDDEN,
        )
        assertThat(assertThrows(AppException::class.java) {
            service.update("allowed", UpdatePharmacyRequest(inventoryDeviceCount = 0, inventorySource = "Synthetic"))
        }.status).isEqualTo(HttpStatus.FORBIDDEN)
        verify(exactly = 0) { repository.findInAdministrativeScopeForUpdate(any(), any()) }
        verify(exactly = 0) { repository.save(any()) }
    }
}
