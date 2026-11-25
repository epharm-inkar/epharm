package kz.epharm.pharmacists

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kz.epharm.auth.access.AdministrativeScope
import kz.epharm.pharmacists.dto.PharmacistDto
import kz.epharm.pharmacists.entity.PharmacistEntity
import kz.epharm.pharmacists.repository.PharmacistEarnedRow
import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.pharmacists.repository.PharmacistSalesRow
import kz.epharm.pharmacists.service.PharmacistService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.Optional

/**
 * The roster's receipts, sales and earnings are read per response from the sales and the wallet ledger
 * (25.11.2026: «продажи и заработок каждого»), never from the stale stored counters and never per row.
 */
class PharmacistReceiptCountsTest {
    private val repository = mockk<PharmacistRepository>()
    private val scope = mockk<AdministrativeScope>()
    private val service = PharmacistService(repository, mockk(), mockk(), mockk(), scope, mockk())

    init {
        every { scope.currentPharmacyIds() } returns setOf("pharmacy")
        every { scope.pharmacist(any()) } answers { firstArg<PharmacistDto>() }
        every { repository.walletEarned(any(), any(), any()) } returns emptyList()
    }

    private fun person(id: String, pharmacy: String? = "pharmacy") =
        PharmacistEntity(id = id, pharmacyId = pharmacy, receipts30d = 999, earned30d = 9_999)

    private fun sales(id: String, pharmacy: String = "pharmacy", value: Long, sum: Long = value * 100) =
        object : PharmacistSalesRow {
            override val pharmacistId = id
            override val pharmacyId = pharmacy
            override val count = value
            override val amount = sum
        }

    private fun earned(id: String, value: Long) = object : PharmacistEarnedRow {
        override val pharmacistId = id
        override val earned = value
    }

    @Test
    fun `list aggregates visible assigned IDs once with a bounded rolling window and zero for missing counts`() {
        val rows = listOf(person("one"), person("two"), person("pending", null))
        every { repository.findForAdministration(setOf("pharmacy"), null, null) } returns rows
        val since = slot<Instant>()
        val observed = slot<Instant>()
        every { repository.salesTotals(setOf("one", "two"), capture(since), capture(observed)) } returns listOf(
            sales("one", value = 7), sales("two", pharmacy = "foreign", value = 90),
            sales("hidden", value = 100),
        )
        every { repository.walletEarned(setOf("one", "two", "pending"), any(), any()) } returns listOf(
            earned("one", 500), earned("pending", 50),
        )

        val roster = service.list()
        assertEquals(listOf(7, 0, 0), roster.map { it.receipts30d })
        assertEquals(listOf(700L, 0L, 0L), roster.map { it.sales30d })
        // Earnings come from the ledger of 30 days, not from the stored running counter (9 999).
        assertEquals(listOf(500L, 0L, 50L), roster.map { it.earned30d })
        assertEquals(Duration.ofDays(30), Duration.between(since.captured, observed.captured))
        assertEquals(listOf(999, 999, 999), rows.map { it.receipts30d })
        verify(exactly = 1) { repository.salesTotals(any(), any(), any()) }
        verify(exactly = 1) { repository.walletEarned(any(), any(), any()) }
        verify(exactly = 0) { repository.receiptCounts(any(), any(), any()) }
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `get and status mutation use the same actual count and financial masking remains final`() {
        val row = person("one")
        every { repository.findInAdministrativeScope(row.id, setOf("pharmacy")) } returns Optional.of(row)
        every { repository.findInAdministrativeScopeForUpdate(row.id, setOf("pharmacy")) } returns Optional.of(row)
        every { repository.save(row) } returns row
        every { repository.salesTotals(setOf(row.id), any(), any()) } returns listOf(sales(row.id, value = 3))
        assertEquals(3, service.get(row.id).receipts30d)
        assertEquals(3, service.block(row.id).receipts30d)
        assertEquals(300L, service.get(row.id).sales30d)

        every { scope.pharmacist(any()) } answers {
            firstArg<PharmacistDto>().copy(receipts30d = null, sales30d = null, earned30d = null)
        }
        assertNull(service.get(row.id).receipts30d)
        assertNull(service.get(row.id).sales30d)
        assertEquals(999, row.receipts30d)
    }

    @Test
    fun `no scope no rows and unassigned profiles never trigger an unbounded sales query`() {
        every { scope.currentPharmacyIds() } returns emptySet()
        assertEquals(emptyList<PharmacistDto>(), service.list())
        verify(exactly = 0) { repository.walletEarned(any(), any(), any()) }
        every { scope.currentPharmacyIds() } returns null
        every { repository.findAllByOrderByNameAsc() } returns listOf(person("pending", null))
        assertEquals(0, service.list().single().receipts30d)
        verify(exactly = 0) { repository.salesTotals(any(), any(), any()) }
        // The wallet read is bounded by the roster's own IDs.
        verify(exactly = 1) { repository.walletEarned(setOf("pending"), any(), any()) }
    }

    @Test
    fun `large receipt counts cannot wrap into negative DTO values`() {
        every { repository.findForAdministration(setOf("pharmacy"), null, null) } returns listOf(person("one"))
        every { repository.salesTotals(setOf("one"), any(), any()) } returns listOf(sales("one", value = Long.MAX_VALUE, sum = 1))
        assertEquals(Int.MAX_VALUE, service.list().single().receipts30d)
    }
}
