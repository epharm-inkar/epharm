package kz.epharm.finance.premium

import java.time.LocalDate
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class PremiumWindowTest {
    private fun day(year: Int, month: Int, day: Int) = LocalDate.of(year, month, day)

    private fun refused(requested: String?, today: LocalDate): String {
        var message = ""
        assertThatThrownBy { PremiumWindow.resolve(requested, today) }.isInstanceOfSatisfying(AppException::class.java) { error ->
            assertThat(error.code).isEqualTo(ErrorCode.VALIDATION_FAILED)
            message = error.message.orEmpty()
        }
        return message
    }

    @Test
    fun `by default it is the month of yesterday through yesterday`() {
        val window = PremiumWindow.resolve(null, day(2026, 11, 26))

        assertThat(window).isEqualTo(PremiumWindow("2026-11", day(2026, 11, 1), day(2026, 11, 25)))
        assertThat(window.until).isEqualTo(day(2026, 11, 26))
    }

    @Test
    fun `on the second of the month it is the first day only`() {
        val window = PremiumWindow.resolve(null, day(2026, 12, 2))

        assertThat(window).isEqualTo(PremiumWindow("2026-12", day(2026, 12, 1), day(2026, 12, 1)))
        assertThat(window.until).isEqualTo(day(2026, 12, 2))
    }

    @Test
    fun `on the first of the month it is the month that has just ended, through its last day`() {
        val december = PremiumWindow.resolve(null, day(2026, 12, 1))
        val newYear = PremiumWindow.resolve(null, day(2027, 1, 1))
        val leap = PremiumWindow.resolve(null, day(2028, 3, 1))

        assertThat(december).isEqualTo(PremiumWindow("2026-11", day(2026, 11, 1), day(2026, 11, 30)))
        assertThat(december.until).isEqualTo(day(2026, 12, 1))
        assertThat(newYear).isEqualTo(PremiumWindow("2026-12", day(2026, 12, 1), day(2026, 12, 31)))
        assertThat(leap).isEqualTo(PremiumWindow("2028-02", day(2028, 2, 1), day(2028, 2, 29)))
    }

    @Test
    fun `a requested current month ends at yesterday, a past month at its own last day`() {
        assertThat(PremiumWindow.resolve("2026-11", day(2026, 11, 26)).through).isEqualTo(day(2026, 11, 25))
        assertThat(PremiumWindow.resolve("2026-11", day(2026, 12, 9)))
            .isEqualTo(PremiumWindow("2026-11", day(2026, 11, 1), day(2026, 11, 30)))
        assertThat(PremiumWindow.resolve(" 2026-10 ", day(2026, 11, 26)).through).isEqualTo(day(2026, 10, 31))
    }

    @Test
    fun `a month that has not begun, or has no closed day yet, is refused`() {
        assertThat(refused("2026-12", day(2026, 11, 26))).contains("не наступил")
        assertThat(refused("2026-11", day(2026, 11, 1))).contains("нет закрытых дней")
    }

    @Test
    fun `a period that is not year and month is refused`() {
        for (bad in listOf("2026-13", "2026-1", "26-11", "2026/11", "", "november")) {
            assertThat(refused(bad, day(2026, 11, 26))).contains("ГГГГ-ММ")
        }
    }
}
