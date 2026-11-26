package kz.epharm.finance.premium

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SalesPremiumHooksTest {
    private val credits = mockk<SalesPremiumPendingCredits>()

    @Test
    fun `the quick path credits the card that became ready`() {
        every { credits.creditFor("ph_1") } returns PendingCreditResult(1, 700, 0)

        PharmacistCardReadyListener(credits).creditNow("ph_1")

        verify(exactly = 1) { credits.creditFor("ph_1") }
    }

    @Test
    fun `a failure of the quick path is only logged, the sweep tries again`() {
        every { credits.creditFor("ph_1") } throws IllegalStateException("database is gone")

        PharmacistCardReadyListener(credits).creditNow("ph_1")

        verify(exactly = 1) { credits.creditFor("ph_1") }
    }

    @Test
    fun `the sweep walks the pages of waiting cards and counts the cards it credited`() {
        val first = (1..200).map { "ph_%03d".format(it) }
        val second = listOf("ph_201", "ph_202")
        every { credits.candidates("", 200) } returns first
        every { credits.candidates("ph_200", 200) } returns second
        every { credits.creditFor(any()) } returns PendingCreditResult(1, 100, 0)

        assertThat(SalesPremiumSweep(credits).sweepOnce()).isEqualTo(202)

        verify(exactly = 202) { credits.creditFor(any()) }
        verify(exactly = 1) { credits.candidates("", 200) }
        verify(exactly = 1) { credits.candidates("ph_200", 200) }
    }

    @Test
    fun `one failing card does not stop the sweep and is not counted`() {
        every { credits.candidates("", 200) } returns listOf("ph_1", "ph_2", "ph_3")
        every { credits.creditFor("ph_1") } returns PendingCreditResult(2, 100, 0)
        every { credits.creditFor("ph_2") } throws IllegalStateException("locked")
        every { credits.creditFor("ph_3") } returns PendingCreditResult(0, 0, 0)

        assertThat(SalesPremiumSweep(credits).sweepOnce()).isEqualTo(1)

        verify(exactly = 1) { credits.creditFor("ph_3") }
    }

    @Test
    fun `nothing waiting means one cheap query and no credit`() {
        every { credits.candidates("", 200) } returns emptyList()

        assertThat(SalesPremiumSweep(credits).sweepOnce()).isZero()

        verify(exactly = 0) { credits.creditFor(any()) }
    }
}
