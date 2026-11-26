package kz.epharm.finance.premium

import java.math.BigDecimal
import java.time.LocalDate
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SalesPremiumRulesTest {
    private val today = LocalDate.of(2026, 11, 26)

    private fun row(iin: String = "123456789012", amount: String = "1000", packs: Double? = 2.0) =
        SalesPremiumRow(iin, BigDecimal(amount), packs)

    private fun request(
        period: String = "2026-11",
        through: LocalDate = LocalDate.of(2026, 11, 25),
        rows: List<SalesPremiumRow?> = listOf(row()),
    ) = SalesPremiumImportRequest(period, through, dryRun = true, rows = rows)

    private fun rejected(request: SalesPremiumImportRequest, today: LocalDate = this.today): String {
        var message = ""
        assertThatThrownBy { SalesPremiumRules.validate(request, today) }
            .isInstanceOfSatisfying(AppException::class.java) { error ->
                assertThat(error.code).isEqualTo(ErrorCode.VALIDATION_FAILED)
                message = error.message.orEmpty()
            }
        return message
    }

    // ---- input checks -------------------------------------------------------------------------------------------

    @Test
    fun `a well formed request is accepted and keeps the file order`() {
        val result = SalesPremiumRules.validate(
            request(rows = listOf(row("111111111111", "500", 1.5), row("222222222222", "0", null), row("333333333333", "25000.0", 3.0))),
            today,
        )

        assertThat(result.period).isEqualTo("2026-11")
        assertThat(result.through).isEqualTo(LocalDate.of(2026, 11, 25))
        assertThat(result.rows.map { it.iin }).containsExactly("111111111111", "222222222222", "333333333333")
        assertThat(result.rows.map { it.amountKzt }).containsExactly(500L, 0L, 25000L)
        assertThat(result.packsTotal).isEqualByComparingTo(BigDecimal("4.5"))
    }

    @Test
    fun `the cut-off may be today and the first day of the month`() {
        SalesPremiumRules.validate(request(through = today), today)
        SalesPremiumRules.validate(request(through = LocalDate.of(2026, 11, 1)), today)
    }

    @Test
    fun `a period must be year and month with two digits`() {
        for (bad in listOf("2026-13", "2026-1", "2026-00", "26-11", "2026/11", "", "  ", "2026-11-01")) {
            assertThat(rejected(request(period = bad))).contains("ГГГГ-ММ")
        }
    }

    @Test
    fun `the cut-off must be inside the period and not in the future`() {
        assertThat(rejected(request(through = LocalDate.of(2026, 10, 31)))).contains("внутри периода 2026-11")
        assertThat(rejected(request(through = LocalDate.of(2026, 12, 1)))).contains("внутри периода 2026-11")
        assertThat(rejected(request(through = LocalDate.of(2026, 11, 27)))).contains("позже сегодняшнего")
    }

    @Test
    fun `an empty file and a file over 5000 rows are rejected`() {
        assertThat(rejected(request(rows = emptyList()))).contains("нет строк")
        val many = (1..5001).map { row(iin = (100_000_000_000L + it).toString()) }
        assertThat(rejected(request(rows = many))).contains("больше 5000")
        SalesPremiumRules.validate(request(rows = many.take(5000)), today)
    }

    @Test
    fun `an IIN must be exactly twelve digits but its checksum is not required`() {
        for (bad in listOf("12345678901", "1234567890123", "12345678901a", "", "            ", "٣٣٣٣٣٣٣٣٣٣٣٣")) {
            assertThat(rejected(request(rows = listOf(row(iin = bad))))).contains("строка 1", "12 цифр")
        }
        // 123456789012 does not pass the IIN checksum; the premium file comes from the same directory as the cards.
        SalesPremiumRules.validate(request(rows = listOf(row(iin = " 123456789012 "))), today)
    }

    @Test
    fun `an amount must be a whole number of tenge from zero to the cap`() {
        for (bad in listOf("-1", "10.5", "0.01", "20000001", "1E+30")) {
            assertThat(rejected(request(rows = listOf(row(amount = bad))))).contains("строка 1", "целым числом тенге")
        }
        for (good in listOf("0", "20000000", "12345.0", "1.2E+3")) {
            SalesPremiumRules.validate(request(rows = listOf(row(amount = good))), today)
        }
    }

    @Test
    fun `packs are optional but must be a finite number from zero`() {
        SalesPremiumRules.validate(request(rows = listOf(row(packs = null))), today)
        for (bad in listOf(-0.5, Double.NaN, Double.POSITIVE_INFINITY, 2_000_000.0)) {
            assertThat(rejected(request(rows = listOf(row(packs = bad))))).contains("упаковок")
        }
    }

    @Test
    fun `a repeated IIN and an empty row name the rows and nothing is accepted`() {
        val message = rejected(request(rows = listOf(row("111111111111"), null, row("111111111111"), row("222222222222"))))

        assertThat(message).contains("строка 2: строка пустая").contains("строка 3: этот ИИН уже есть в строке 1")
        assertThat(message).doesNotContain("111111111111")
    }

    @Test
    fun `only the first ten problems are listed`() {
        val message = rejected(request(rows = (1..14).map { row(iin = "bad$it") }))

        assertThat(message).contains("строка 10:").doesNotContain("строка 11:").endsWith("и ещё 4")
    }

    // ---- the hash of a file -------------------------------------------------------------------------------------

    @Test
    fun `the hash ignores the order of rows and changes with any figure`() {
        val a = row("111111111111", "100", 1.0)
        val b = row("222222222222", "200", null)
        val first = SalesPremiumRules.validate(request(rows = listOf(a, b)), today).payloadSha256
        val reordered = SalesPremiumRules.validate(request(rows = listOf(b, a)), today).payloadSha256

        assertThat(first).matches("[0-9a-f]{64}").isEqualTo(reordered)
        assertThat(SalesPremiumRules.validate(request(rows = listOf(a, row("222222222222", "201", null))), today).payloadSha256)
            .isNotEqualTo(first)
        assertThat(SalesPremiumRules.validate(request(through = LocalDate.of(2026, 11, 24), rows = listOf(a, b)), today).payloadSha256)
            .isNotEqualTo(first)
        assertThat(SalesPremiumRules.validate(request(rows = listOf(a, row("222222222222", "200", 0.0))), today).payloadSha256)
            .isNotEqualTo(first)
    }

    // ---- the delta ----------------------------------------------------------------------------------------------

    @Test
    fun `a higher figure credits the difference and an equal one does nothing`() {
        assertThat(SalesPremiumRules.decide(cumulativeKzt = 1000, creditedKzt = 0, balanceKzt = 0))
            .isEqualTo(PremiumDecision(1000, 0, 0))
        assertThat(SalesPremiumRules.decide(cumulativeKzt = 1500, creditedKzt = 1000, balanceKzt = 0))
            .isEqualTo(PremiumDecision(500, 0, 0))
        assertThat(SalesPremiumRules.decide(cumulativeKzt = 1500, creditedKzt = 1500, balanceKzt = 99))
            .isEqualTo(PremiumDecision(0, 0, 0))
    }

    @Test
    fun `a lower figure takes the difference back from the balance and never below zero`() {
        // 1000 credited, the source now says 700: 300 is wanted back.
        assertThat(SalesPremiumRules.decide(700, 1000, balanceKzt = 5000)).isEqualTo(PremiumDecision(0, 300, 0))
        assertThat(SalesPremiumRules.decide(700, 1000, balanceKzt = 300)).isEqualTo(PremiumDecision(0, 300, 0))
        // Only 120 is left on the balance: take it and report the rest as uncovered.
        assertThat(SalesPremiumRules.decide(700, 1000, balanceKzt = 120)).isEqualTo(PremiumDecision(0, 120, 180))
        // Everything was already reserved for a payout.
        assertThat(SalesPremiumRules.decide(700, 1000, balanceKzt = 0)).isEqualTo(PremiumDecision(0, 0, 300))
        assertThat(SalesPremiumRules.decide(0, 1000, balanceKzt = -5)).isEqualTo(PremiumDecision(0, 0, 1000))
    }

    @Test
    fun `figures beyond the range of a long fail instead of wrapping`() {
        assertThatThrownBy { SalesPremiumRules.decide(Long.MAX_VALUE, -1, 0) }.isInstanceOf(ArithmeticException::class.java)
    }

    // ---- who gets it --------------------------------------------------------------------------------------------

    @Test
    fun `one active card is the recipient and nobody is guessed among several`() {
        assertThat(SalesPremiumRules.resolve(emptyList())).isEqualTo(IinResolution.NoPharmacist)
        assertThat(SalesPremiumRules.resolve(listOf(PremiumCandidate("a", true)))).isEqualTo(IinResolution.Matched("a"))
        assertThat(SalesPremiumRules.resolve(listOf(PremiumCandidate("a", false)))).isEqualTo(IinResolution.NotActive)
        // The active one is preferred over a pending or a blocked one.
        assertThat(SalesPremiumRules.resolve(listOf(PremiumCandidate("a", false), PremiumCandidate("b", true))))
            .isEqualTo(IinResolution.Matched("b"))
        assertThat(SalesPremiumRules.resolve(listOf(PremiumCandidate("a", true), PremiumCandidate("b", true))))
            .isEqualTo(IinResolution.Ambiguous)
        assertThat(SalesPremiumRules.resolve(listOf(PremiumCandidate("a", false), PremiumCandidate("b", false))))
            .isEqualTo(IinResolution.NotActive)
    }

    // ---- keys ---------------------------------------------------------------------------------------------------

    @Test
    fun `the context and the first source id carry the month, the person and the cut-off`() {
        assertThat(SalesPremiumRules.contextKey("2026-11")).isEqualTo("premium:2026-11")
        assertThat(SalesPremiumRules.sourceIdBase("2026-11", "ph_1", LocalDate.of(2026, 11, 25)))
            .isEqualTo("premium:2026-11:ph_1:2026-11-25")
        assertThat(SalesPremiumRules.periodOf("premium:2026-11")).isEqualTo("2026-11")
        assertThat(SalesPremiumRules.periodOf("premium:2026-13")).isNull()
        assertThat(SalesPremiumRules.periodOf("payout-item:poi_1")).isNull()
        assertThat(SalesPremiumRules.periodOf(null)).isNull()
    }
}
