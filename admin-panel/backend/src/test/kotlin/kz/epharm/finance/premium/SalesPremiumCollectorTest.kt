package kz.epharm.finance.premium

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.UUID
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.security.AdminPrincipal
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class SalesPremiumCollectorTest {
    private val anna = "111111111111"
    private val boris = "222222222222"
    private val wareA = "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA"
    private val wareB = "BBBBBBBB-BBBB-BBBB-BBBB-BBBBBBBBBBBB"
    private val matrix = listOf(
        MatrixItem(wareA, "Товар A", BigDecimal("100")),
        MatrixItem(wareB, "Товар B", BigDecimal("250")),
    )

    private val clock = MutableClock.almaty(2026, 11, 26, 6, 41)
    private val store = mockk<SalesPremiumMatrixStore>()
    private val source = mockk<StandardNPremiumSource>()
    private val reader = mockk<StandardNPremiumReader>(relaxed = true)
    private val premium = mockk<SalesPremiumService>()
    private val collector = SalesPremiumCollector(store, source, premium, clock)

    private fun line(iin: String?, type: Int, quantity: String) = StandardNPremiumLine(1, 10, iin, type, BigDecimal(quantity))

    private fun imported(dryRun: Boolean, iins: List<String> = emptyList()) = SalesPremiumImportResult(
        period = "2026-11", through = LocalDate.of(2026, 11, 25), dryRun = dryRun,
        rows = PremiumTotals(2, 1250), matched = PremiumTotals(1, 600), credited = PremiumTotals(1, 600),
        corrections = PremiumCorrections(0, 0, 0), unchanged = 0, pending = PremiumPending(1, 650, 0),
        ambiguous = PremiumAmbiguous(iins.size, 0, iins), packsTotal = BigDecimal("6.00"),
        auditId = if (dryRun) null else UUID.randomUUID().toString(),
    )

    private fun givenMatrixAndSales() {
        every { store.items("2026-11") } returns matrix
        every { source.open() } returns reader
        every { reader.lines(wareA, any(), any()) } returns listOf(line(anna, 3, "-4"), line(boris, 3, "-1"), line(null, 3, "-9"))
        every { reader.lines(wareB, any(), any()) } returns listOf(line(anna, 3, "-2"), line(boris, 9, "1"))
    }

    @Test
    fun `it reads every product from the first of the month through yesterday and imports the cumulative figures`() {
        givenMatrixAndSales()
        val request = slot<SalesPremiumImportRequest>()
        every { premium.applyAs(capture(request), PremiumActor.SYSTEM_JOB) } returns imported(dryRun = false)

        val result = collector.collect(null, dryRun = false, actor = PremiumActor.SYSTEM_JOB)

        verify(exactly = 1) { reader.lines(wareA, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 26)) }
        verify(exactly = 1) { reader.lines(wareB, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 26)) }
        verify(exactly = 1) { source.open() }
        verify(exactly = 1) { reader.close() }
        assertThat(request.captured.period).isEqualTo("2026-11")
        assertThat(request.captured.through).isEqualTo(LocalDate.of(2026, 11, 25))
        assertThat(request.captured.dryRun).isFalse()
        // anna: 4 x 100 + 2 x 250 = 900 for 6 packs; boris: 1 x 100 - 1 x 250 = -150, credited 0 (he returned B).
        assertThat(request.captured.rows.filterNotNull().map { Triple(it.iin, it.amountKzt.toLong(), it.packs) })
            .containsExactly(Triple(anna, 900L, 6.0), Triple(boris, 0L, 0.0))
        assertThat(result.period).isEqualTo("2026-11")
        assertThat(result.through).isEqualTo(LocalDate.of(2026, 11, 25))
        assertThat(result.products).isEqualTo(2)
        assertThat(result.sellers).isEqualTo(2)
        assertThat(result.sellersWithPremium).isEqualTo(1)
        assertThat(result.premiumKzt).isEqualTo(900)
        assertThat(result.negativeSellers).isEqualTo(1)
        assertThat(result.packs).isEqualByComparingTo("15")
        assertThat(result.withoutIin.sellers).isEqualTo(1)
        assertThat(result.withoutIin.amountKzt).isEqualTo(900)
        assertThat(result.imported!!.dryRun).isFalse()
    }

    @Test
    fun `a dry run previews and never writes`() {
        givenMatrixAndSales()
        every { premium.preview(any()) } returns imported(dryRun = true)

        val result = collector.collect(null, dryRun = true, actor = PremiumActor.SYSTEM_JOB)

        verify(exactly = 1) { premium.preview(match { it.dryRun == true }) }
        verify(exactly = 0) { premium.applyAs(any(), any()) }
        assertThat(result.dryRun).isTrue()
        assertThat(result.imported!!.auditId).isNull()
    }

    @Test
    fun `an administrator's run is credited as that administrator`() {
        givenMatrixAndSales()
        val admin = AdminPrincipal(UUID.randomUUID(), "hq@example.test", "HQ", AdminRole.HQ_HEAD, "Inkar")
        val actor = slot<PremiumActor>()
        every { premium.applyAs(any(), capture(actor)) } returns imported(dryRun = false)

        collector.collect(null, dryRun = false, actor = PremiumActor.of(admin))

        assertThat(actor.captured).isEqualTo(PremiumActor(admin.userId, "HQ_HEAD"))
    }

    @Test
    fun `a requested month is read through its own last day when it is over`() {
        clock.set(java.time.LocalDateTime.of(2026, 12, 9, 12, 0))
        every { store.items("2026-11") } returns matrix
        every { source.open() } returns reader
        every { reader.lines(any(), any(), any()) } returns listOf(line(anna, 3, "-1"))
        every { premium.preview(any()) } returns imported(dryRun = true)

        val result = collector.collect("2026-11", dryRun = true, actor = PremiumActor.SYSTEM_JOB)

        verify { reader.lines(wareA, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 1)) }
        assertThat(result.through).isEqualTo(LocalDate.of(2026, 11, 30))
    }

    @Test
    fun `on the first of the month the month that has just ended gets its final figure`() {
        clock.set(java.time.LocalDateTime.of(2026, 12, 1, 6, 45))
        every { store.items("2026-11") } returns matrix
        every { source.open() } returns reader
        every { reader.lines(any(), any(), any()) } returns listOf(line(anna, 3, "-1"))
        val request = slot<SalesPremiumImportRequest>()
        every { premium.applyAs(capture(request), any()) } returns imported(dryRun = false)

        collector.collect(null, dryRun = false, actor = PremiumActor.SYSTEM_JOB)

        assertThat(request.captured.period).isEqualTo("2026-11")
        assertThat(request.captured.through).isEqualTo(LocalDate.of(2026, 11, 30))
        verify { reader.lines(wareA, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 1)) }
    }

    @Test
    fun `without a single sale nothing is sent`() {
        every { store.items("2026-11") } returns matrix
        every { source.open() } returns reader
        every { reader.lines(any(), any(), any()) } returns emptyList()

        val result = collector.collect(null, dryRun = false, actor = PremiumActor.SYSTEM_JOB)

        assertThat(result.imported).isNull()
        assertThat(result.sellers).isZero()
        verify(exactly = 0) { premium.preview(any()) }
        verify(exactly = 0) { premium.applyAs(any(), any()) }
    }

    @Test
    fun `a failed read of one product imports nothing and still closes the connection`() {
        every { store.items("2026-11") } returns matrix
        every { source.open() } returns reader
        every { reader.lines(wareA, any(), any()) } returns listOf(line(anna, 3, "-4"))
        every { reader.lines(wareB, any(), any()) } throws StandardNSourceException("Standard-N read failed")

        assertThatThrownBy { collector.collect(null, dryRun = false, actor = PremiumActor.SYSTEM_JOB) }
            .isInstanceOf(StandardNSourceException::class.java)

        verify(exactly = 0) { premium.applyAs(any(), any()) }
        verify(exactly = 0) { premium.preview(any()) }
        verify(exactly = 1) { reader.close() }
    }

    @Test
    fun `a database that does not open imports nothing`() {
        every { store.items("2026-11") } returns matrix
        every { source.open() } throws StandardNSourceException("Standard-N did not open")

        assertThatThrownBy { collector.collect(null, dryRun = false, actor = PremiumActor.SYSTEM_JOB) }
            .isInstanceOf(StandardNSourceException::class.java)

        verify(exactly = 0) { premium.applyAs(any(), any()) }
    }

    @Test
    fun `a month without a matrix is a conflict and the database is not even opened`() {
        every { store.items("2026-11") } returns emptyList()

        assertThatThrownBy { collector.collect(null, dryRun = false, actor = PremiumActor.SYSTEM_JOB) }
            .isInstanceOfSatisfying(AppException::class.java) { error ->
                assertThat(error.code).isEqualTo(ErrorCode.CONFLICT)
                assertThat(error.message).contains("2026-11").contains("матриц")
            }

        verify(exactly = 0) { source.open() }
    }

    @Test
    fun `a read that takes too long is stopped before the next product`() {
        every { store.items("2026-11") } returns matrix
        every { source.open() } returns reader
        every { reader.lines(wareA, any(), any()) } answers {
            clock.advance(Duration.ofMinutes(26))
            listOf(line(anna, 3, "-4"))
        }

        assertThatThrownBy { collector.collect(null, dryRun = false, actor = PremiumActor.SYSTEM_JOB) }
            .isInstanceOf(StandardNSourceException::class.java).hasMessageContaining("longer")

        verify(exactly = 0) { reader.lines(wareB, any(), any()) }
        verify(exactly = 0) { premium.applyAs(any(), any()) }
        verify(exactly = 1) { reader.close() }
    }

    @Test
    fun `the answer carries totals only, never the IINs of an ambiguous card`() {
        givenMatrixAndSales()
        every { premium.preview(any()) } returns imported(dryRun = true, iins = listOf("333333333333"))

        val result = collector.collect(null, dryRun = true, actor = PremiumActor.SYSTEM_JOB)

        assertThat(result.imported!!.ambiguous.count).isEqualTo(1)
        assertThat(result.imported!!.ambiguous.iins).isEmpty()
    }

    @Test
    fun `only one calculation runs at a time, the second is refused without reading anything`() {
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { store.items("2026-11") } returns matrix
        every { source.open() } returns reader
        every { reader.lines(wareA, any(), any()) } answers {
            reading.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "the test did not release the first calculation" }
            listOf(line(anna, 3, "-1"))
        }
        every { reader.lines(wareB, any(), any()) } returns emptyList()
        every { premium.preview(any()) } returns imported(dryRun = true)

        val pool = Executors.newSingleThreadExecutor()
        try {
            val first = pool.submit<CollectorResult> { collector.collect(null, dryRun = true, actor = PremiumActor.SYSTEM_JOB) }
            assertThat(reading.await(10, TimeUnit.SECONDS)).isTrue()

            assertThatThrownBy { collector.collect(null, dryRun = true, actor = PremiumActor.SYSTEM_JOB) }
                .isInstanceOfSatisfying(AppException::class.java) { error ->
                    assertThat(error.code).isEqualTo(ErrorCode.CONFLICT)
                    assertThat(error.status).isEqualTo(HttpStatus.CONFLICT)
                }
            assertThat(collector.tryCollect(null, dryRun = true, actor = PremiumActor.SYSTEM_JOB)).isNull()
            verify(exactly = 1) { source.open() }

            release.countDown()
            assertThat(first.get(10, TimeUnit.SECONDS).sellers).isEqualTo(1)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }

        // The lock is released: the next calculation runs.
        every { reader.lines(wareA, any(), any()) } returns listOf(line(anna, 3, "-1"))
        assertThat(collector.collect(null, dryRun = true, actor = PremiumActor.SYSTEM_JOB).sellers).isEqualTo(1)
    }

    @Test
    fun `the lock is released when a calculation fails`() {
        every { store.items("2026-11") } returns matrix
        every { source.open() } throws StandardNSourceException("Standard-N did not open")

        repeat(2) {
            assertThatThrownBy { collector.collect(null, dryRun = true, actor = PremiumActor.SYSTEM_JOB) }
                .isInstanceOf(StandardNSourceException::class.java)
        }
        verify(exactly = 2) { source.open() }
    }
}
