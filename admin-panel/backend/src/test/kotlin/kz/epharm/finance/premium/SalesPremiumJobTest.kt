package kz.epharm.finance.premium

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SalesPremiumJobTest {
    private val clock = MutableClock.almaty(2026, 11, 26, 6, 39)
    private val collector = mockk<SalesPremiumCollector>()
    private val runLog = mockk<SalesPremiumRunLog>()

    private fun job(
        enabled: Boolean = true,
        start: String = "06:40",
        deadline: String = "11:00",
        retryMinutes: Long = 30,
    ) = SalesPremiumJob(collector, runLog, enabled, start, deadline, retryMinutes, clock)

    private fun at(hour: Int, minute: Int, day: Int = 26) = clock.set(LocalDateTime.of(2026, 11, day, hour, minute))

    private fun collected(withImport: Boolean = true) = CollectorResult(
        period = "2026-11", through = LocalDate.of(2026, 11, 25), dryRun = false, products = 188, productsWithSales = 150,
        packs = BigDecimal("56411"), sellers = 971, sellersWithPremium = 960, premiumKzt = 7_118_062,
        withoutIin = WithoutIin(12, BigDecimal("2100"), 247_366), negativeSellers = 0,
        imported = if (withImport) {
            SalesPremiumImportResult(
                "2026-11", LocalDate.of(2026, 11, 25), false, PremiumTotals(971, 7_118_062), PremiumTotals(900, 7_000_000),
                PremiumTotals(900, 7_000_000), PremiumCorrections(0, 0, 0), 0, PremiumPending(71, 118_062, 0),
                PremiumAmbiguous(0, 0, emptyList()), BigDecimal("56411.00"), UUID.randomUUID().toString(),
            )
        } else null,
        tookMillis = 4_000,
    )

    @Test
    fun `a disabled job reads nothing, writes nothing and does not even look at the audit table`() {
        val disabled = job(enabled = false)

        for (hour in listOf(3, 6, 7, 9, 10, 12)) {
            at(hour, 45)
            assertThat(disabled.tick()).isEqualTo(JobOutcome.DISABLED)
        }

        verify(exactly = 0) { collector.tryCollect(any(), any(), any()) }
        verify(exactly = 0) { runLog.collectedBy(any(), any()) }
    }

    @Test
    fun `nothing runs before the start time or from the deadline on`() {
        val job = job()

        for ((hour, minute) in listOf(0 to 0, 6 to 39, 11 to 0, 11 to 1, 14 to 30, 23 to 59)) {
            at(hour, minute)
            assertThat(job.tick()).describedAs("%02d:%02d", hour, minute).isEqualTo(JobOutcome.NOT_DUE)
        }

        verify(exactly = 0) { collector.tryCollect(any(), any(), any()) }
    }

    @Test
    fun `at the start time it collects the month of yesterday as the system actor and credits it`() {
        every { runLog.collectedBy(any(), any()) } returns false
        every { collector.tryCollect(null, false, PremiumActor.SYSTEM_JOB) } returns collected()
        at(6, 40)

        assertThat(job().tick()).isEqualTo(JobOutcome.DONE)

        verify(exactly = 1) { collector.tryCollect(null, false, PremiumActor.SYSTEM_JOB) }
    }

    @Test
    fun `after a success the day is done, the next morning runs again`() {
        every { runLog.collectedBy(any(), any()) } returns false
        every { collector.tryCollect(any(), any(), any()) } returns collected()
        val job = job()
        at(6, 40)
        assertThat(job.tick()).isEqualTo(JobOutcome.DONE)

        for ((hour, minute) in listOf(6 to 41, 7 to 10, 8 to 0, 10 to 59)) {
            at(hour, minute)
            assertThat(job.tick()).describedAs("%02d:%02d", hour, minute).isEqualTo(JobOutcome.NOT_DUE)
        }
        verify(exactly = 1) { collector.tryCollect(any(), any(), any()) }

        at(6, 40, day = 27)
        assertThat(job.tick()).isEqualTo(JobOutcome.DONE)
        verify(exactly = 2) { collector.tryCollect(any(), any(), any()) }
    }

    @Test
    fun `while the database does not open it retries every thirty minutes until eleven and then gives up for the day`() {
        every { runLog.collectedBy(any(), any()) } returns false
        every { collector.tryCollect(any(), any(), any()) } throws StandardNSourceException("Standard-N did not open")
        val job = job()
        val attempts = mutableListOf<String>()

        for (minuteOfDay in (6 * 60 + 40)..(12 * 60)) {
            at(minuteOfDay / 60, minuteOfDay % 60)
            if (job.tick() == JobOutcome.FAILED) attempts += "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)
        }

        assertThat(attempts).containsExactly("06:40", "07:10", "07:40", "08:10", "08:40", "09:10", "09:40", "10:10", "10:40")
    }

    @Test
    fun `a failure of any other kind is also retried and never escapes the tick`() {
        every { runLog.collectedBy(any(), any()) } returns false
        every { collector.tryCollect(any(), any(), any()) } throws IllegalStateException("boom") andThen collected()
        val job = job()

        at(6, 40)
        assertThat(job.tick()).isEqualTo(JobOutcome.FAILED)
        at(7, 0)
        assertThat(job.tick()).isEqualTo(JobOutcome.NOT_DUE)
        at(7, 10)
        assertThat(job.tick()).isEqualTo(JobOutcome.DONE)
    }

    @Test
    fun `a failed day is not marked done, the next morning tries again`() {
        every { runLog.collectedBy(any(), any()) } returns false
        every { collector.tryCollect(any(), any(), any()) } throws StandardNSourceException("closed") andThen collected()
        val job = job()
        at(10, 40)
        assertThat(job.tick()).isEqualTo(JobOutcome.FAILED)

        at(6, 40, day = 27)

        assertThat(job.tick()).isEqualTo(JobOutcome.DONE)
    }

    @Test
    fun `a calculation that an administrator started is not repeated, the job tries again later`() {
        every { runLog.collectedBy(any(), any()) } returns false
        every { collector.tryCollect(any(), any(), any()) } returns null andThen collected()
        val job = job()

        at(6, 40)
        assertThat(job.tick()).isEqualTo(JobOutcome.BUSY)
        at(7, 10)
        assertThat(job.tick()).isEqualTo(JobOutcome.DONE)
    }

    @Test
    fun `a read without any sale counts as done for the day and sends nothing`() {
        every { runLog.collectedBy(any(), any()) } returns false
        every { collector.tryCollect(any(), any(), any()) } returns collected(withImport = false)
        val job = job()

        at(6, 40)
        assertThat(job.tick()).isEqualTo(JobOutcome.NOTHING_TO_SEND)
        at(7, 10)
        assertThat(job.tick()).isEqualTo(JobOutcome.NOT_DUE)
        verify(exactly = 1) { collector.tryCollect(any(), any(), any()) }
    }

    @Test
    fun `after a restart the audit table proves the run of today and nothing is read again`() {
        every { runLog.collectedBy(PremiumActor.SYSTEM_JOB, LocalDate.of(2026, 11, 26)) } returns true
        at(9, 15)

        assertThat(job().tick()).isEqualTo(JobOutcome.NOT_DUE)
        assertThat(job().tick()).isEqualTo(JobOutcome.NOT_DUE)

        verify(exactly = 0) { collector.tryCollect(any(), any(), any()) }
    }

    @Test
    fun `an unreadable audit table does not stop the job`() {
        every { runLog.collectedBy(any(), any()) } throws IllegalStateException("db down")
        every { collector.tryCollect(any(), any(), any()) } returns collected()
        at(6, 40)

        assertThat(job().tick()).isEqualTo(JobOutcome.DONE)
    }

    @Test
    fun `the schedule is configurable`() {
        every { runLog.collectedBy(any(), any()) } returns false
        every { collector.tryCollect(any(), any(), any()) } returns collected()
        val job = job(start = "07:15", deadline = "09:00", retryMinutes = 10)

        at(7, 14)
        assertThat(job.tick()).isEqualTo(JobOutcome.NOT_DUE)
        at(7, 15)
        assertThat(job.tick()).isEqualTo(JobOutcome.DONE)

        val late = job(start = "07:15", deadline = "09:00", retryMinutes = 10)
        at(9, 0)
        assertThat(late.tick()).isEqualTo(JobOutcome.NOT_DUE)
    }

    @Test
    fun `settings that make no sense fall back to the defaults instead of stopping the server`() {
        val defaults = SalesPremiumJobSettings(true, LocalTime.of(6, 40), LocalTime.of(11, 0), Duration.ofMinutes(30))

        assertThat(SalesPremiumJobSettings.parse(true, "06:40", "11:00", 30)).isEqualTo(defaults)
        assertThat(SalesPremiumJobSettings.parse(true, "6 40", "eleven", 30)).isEqualTo(defaults)
        assertThat(SalesPremiumJobSettings.parse(true, "12:00", "08:00", 30)).isEqualTo(defaults)
        assertThat(SalesPremiumJobSettings.parse(true, "08:00", "08:00", 30)).isEqualTo(defaults)
        assertThat(SalesPremiumJobSettings.parse(true, "06:40", "11:00", 0)).isEqualTo(defaults)
        assertThat(SalesPremiumJobSettings.parse(true, "06:40", "11:00", 5000)).isEqualTo(defaults)
        assertThat(SalesPremiumJobSettings.parse(false, "07:00", "10:30", 15))
            .isEqualTo(SalesPremiumJobSettings(false, LocalTime.of(7, 0), LocalTime.of(10, 30), Duration.ofMinutes(15)))
    }

    @Test
    fun `the job is off by default`() {
        assertThat(SalesPremiumJobSettings.parse(false, "06:40", "11:00", 30).enabled).isFalse()
    }
}
