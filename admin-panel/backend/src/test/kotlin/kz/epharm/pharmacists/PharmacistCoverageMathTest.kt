package kz.epharm.pharmacists

import kz.epharm.pharmacists.service.CoverageMath
import kz.epharm.pharmacists.service.CoveragePerson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * The rows behind «Фармацевты в ePharm» on the Analytics page (Grigoriy, 26.11.2026): who is in the system
 * on a day, who worked with ePharm on that very day and in the seven days up to it, and the shares. Plain
 * data, no database: the SQL that collects the activity is covered by [PharmacistActivityIntegrationTest].
 */
class PharmacistCoverageMathTest {
    private fun day(d: Int) = LocalDate.of(2026, 11, d)

    private val people = mapOf(
        // In the system for a while, worked on the 20th and the 26th (the 26th was a sale).
        "a" to CoveragePerson(joined = day(1), source = "registration", blocked = false),
        // Brought from Standard-N on the 24th, worked on the 25th.
        "b" to CoveragePerson(joined = day(24), source = "standardn", blocked = false),
        // Blocked: never counted, even though the till still saw them on the 26th.
        "c" to CoveragePerson(joined = day(1), source = "registration", blocked = true),
        // In the system, never worked.
        "d" to CoveragePerson(joined = day(1), source = "standardn", blocked = false),
    )
    private val worked = mapOf(
        "a" to setOf(day(20), day(26)),
        "b" to setOf(day(25)),
        "c" to setOf(day(26)),
    )
    private val sold = mapOf("a" to setOf(day(26)))

    private val rows = CoverageMath.days(people, worked, sold, first = day(22), span = 5)

    @Test
    fun `one row per day, oldest first`() {
        assertEquals((22..26).map(::day), rows.map { it.date })
    }

    @Test
    fun `a pharmacist is in the system from the day they joined and blocked ones never are`() {
        assertEquals(listOf(2, 2, 3, 3, 3), rows.map { it.total })
        assertEquals(listOf(0, 0, 1, 0, 0), rows.map { it.joined })
    }

    @Test
    fun `worked that very day counts only that day`() {
        // a on the 26th, b on the 25th; a's 20th is outside every one of these single days.
        assertEquals(listOf(0, 0, 0, 1, 1), rows.map { it.active1d })
    }

    @Test
    fun `worked in the seven days up to the day keeps the older activity`() {
        // 22nd: a (20th). 24th: a (20th), b not yet. 25th: a (20th) and b (25th). 26th: a (20th, 26th) and b.
        assertEquals(listOf(1, 1, 1, 2, 2), rows.map { it.active7d })
        // Only a sold, on the 26th.
        assertEquals(listOf(0, 0, 0, 0, 1), rows.map { it.sellers7d })
    }

    @Test
    fun `shares are rounded to a tenth of a percent over the pharmacists in the system`() {
        assertEquals(listOf(0.0, 0.0, 0.0, 33.3, 33.3), rows.map { it.coverage1dPct })
        assertEquals(listOf(50.0, 50.0, 33.3, 66.7, 66.7), rows.map { it.coveragePct })
    }

    @Test
    fun `activity before the person joined or of a blocked person does not raise any count`() {
        val early = CoverageMath.days(
            people = mapOf("x" to CoveragePerson(day(25), "registration", blocked = false)),
            activeDays = mapOf("x" to setOf(day(23))),
            saleDays = emptyMap(),
            first = day(23),
            span = 3,
        )
        // On the 23rd x is not in the system yet, so nobody is: no work, no share, not a division by zero.
        assertEquals(0, early[0].total)
        assertEquals(0, early[0].active1d)
        assertEquals(0.0, early[0].coverage1dPct)
        // From the 25th x is in the system and the 23rd is within the seven days, but not that very day.
        assertEquals(1, early[2].total)
        assertEquals(0, early[2].active1d)
        assertEquals(1, early[2].active7d)
    }

    @Test
    fun `percent rounds and never divides by zero`() {
        assertEquals(33.3, CoverageMath.percent(1, 3))
        assertEquals(66.7, CoverageMath.percent(2, 3))
        assertEquals(100.0, CoverageMath.percent(5, 5))
        assertEquals(0.0, CoverageMath.percent(0, 0))
    }
}
