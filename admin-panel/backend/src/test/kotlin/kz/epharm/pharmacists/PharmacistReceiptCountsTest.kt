package kz.epharm.pharmacists

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kz.epharm.auth.access.AdministrativeScope
import kz.epharm.pharmacists.dto.PharmacistDto
import kz.epharm.pharmacists.entity.PharmacistEntity
import kz.epharm.pharmacists.repository.PharmacistReceiptCountRow
import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.pharmacists.service.PharmacistService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.Optional

class PharmacistReceiptCountsTest {
    private val repository = mockk<PharmacistRepository>()
    private val scope = mockk<AdministrativeScope>()
    private val service = PharmacistService(repository, mockk(), mockk(), mockk(), scope, mockk())

    init {
        every { scope.currentPharmacyIds() } returns setOf("pharmacy")
        every { scope.pharmacist(any()) } answers { firstArg<PharmacistDto>() }
    }

    private fun person(id: String, pharmacy: String? = "pharmacy") =
        PharmacistEntity(id = id, pharmacyId = pharmacy, receipts30d = 999)

    private fun count(id: String, pharmacy: String = "pharmacy", value: Long) = object : PharmacistReceiptCountRow {
        override val pharmacistId = id
        override val pharmacyId = pharmacy
        override val count = value
    }

    @Test
    fun `list aggregates visible assigned IDs once with a bounded rolling window and zero for missing counts`() {
        val rows = listOf(person("one"), person("two"), person("pending", null))
        every { repository.findForAdministration(setOf("pharmacy"), null, null) } returns rows
        val since = slot<Instant>()
        val observed = slot<Instant>()
        every { repository.receiptCounts(setOf("one", "two"), capture(since), capture(observed)) } returns listOf(
            count("one", value = 7), count("two", pharmacy = "foreign", value = 90),
            count("hidden", value = 100),
        )

        assertEquals(listOf(7, 0, 0), service.list().map { it.receipts30d })
        assertEquals(Duration.ofDays(30), Duration.between(since.captured, observed.captured))
        assertEquals(listOf(999, 999, 999), rows.map { it.receipts30d })
        verify(exactly = 1) { repository.receiptCounts(any(), any(), any()) }
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `get and status mutation use the same actual count and financial masking remains final`() {
        val row = person("one")
        every { repository.findInAdministrativeScope(row.id, setOf("pharmacy")) } returns Optional.of(row)
        every { repository.findInAdministrativeScopeForUpdate(row.id, setOf("pharmacy")) } returns Optional.of(row)
        every { repository.save(row) } returns row
        every { repository.receiptCounts(setOf(row.id), any(), any()) } returns listOf(count(row.id, value = 3))
        assertEquals(3, service.get(row.id).receipts30d)
        assertEquals(3, service.block(row.id).receipts30d)

        every { scope.pharmacist(any()) } answers { firstArg<PharmacistDto>().copy(receipts30d = null) }
        assertNull(service.get(row.id).receipts30d)
        assertEquals(999, row.receipts30d)
    }

    @Test
    fun `no scope no rows and unassigned profiles never trigger an unbounded sales query`() {
        every { scope.currentPharmacyIds() } returns emptySet()
        assertEquals(emptyList<PharmacistDto>(), service.list())
        every { scope.currentPharmacyIds() } returns null
        every { repository.findAllByOrderByNameAsc() } returns listOf(person("pending", null))
        assertEquals(0, service.list().single().receipts30d)
        verify(exactly = 0) { repository.receiptCounts(any(), any(), any()) }
    }

    @Test
    fun `large receipt counts cannot wrap into negative DTO values`() {
        every { repository.findForAdministration(setOf("pharmacy"), null, null) } returns listOf(person("one"))
        every { repository.receiptCounts(setOf("one"), any(), any()) } returns listOf(count("one", value = Long.MAX_VALUE))
        assertEquals(Int.MAX_VALUE, service.list().single().receipts30d)
    }
}
