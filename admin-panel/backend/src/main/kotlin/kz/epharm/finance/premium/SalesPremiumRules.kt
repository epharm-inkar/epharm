package kz.epharm.finance.premium

import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode

/** One seller of the month: the IIN and the premium earned from the 1st through the cut-off date, in whole tenge. */
data class PremiumRow(val iin: String, val amountKzt: Long, val packs: Double?)

/** A request that passed every input check; [rows] are in the order of the file, [payloadSha256] ignores that order. */
data class ValidatedImport(
    val period: String,
    val through: LocalDate,
    val rows: List<PremiumRow>,
    val payloadSha256: String,
    val packsTotal: BigDecimal,
)

/** What to write for one pharmacist so that the ledger matches the cumulative figure of the source. */
data class PremiumDecision(val creditKzt: Long, val correctionKzt: Long, val uncoveredKzt: Long)

/** A card that carries an IIN, as far as the premium is concerned. */
data class PremiumCandidate(val id: String, val active: Boolean)

sealed interface IinResolution {
    /** Exactly one active card: the premium is credited to it. */
    data class Matched(val pharmacistId: String) : IinResolution
    /** No card with this IIN yet: the amount waits in the pending table. */
    data object NoPharmacist : IinResolution
    /** Cards exist but none is active (pending or blocked): the amount waits until one becomes active. */
    data object NotActive : IinResolution
    /** More than one active card: nobody is guessed, the IIN is only reported. */
    data object Ambiguous : IinResolution
}

/** Pure rules of the sales premium import: the input checks, the delta decision and the IIN resolution. */
object SalesPremiumRules {
    const val MAX_ROWS = 5000

    /**
     * Upper bound for one seller and one month. It only guards against a unit mistake (tiyn instead of tenge) and is
     * far above what the whole network pays one person; a real figure never comes near it.
     */
    const val MAX_AMOUNT_KZT = 20_000_000L
    const val MAX_PACKS = 1_000_000.0

    /** Kazakhstan has one time zone; the cut-off date is a calendar day there. */
    val ALMATY: ZoneId = ZoneId.of("Asia/Almaty")

    const val CONTEXT_PREFIX = "premium:"
    private const val MAX_PROBLEMS = 10
    private val PERIOD = Regex("[0-9]{4}-(0[1-9]|1[0-2])")
    private val IIN = Regex("[0-9]{12}")

    fun contextKey(period: String): String = "$CONTEXT_PREFIX$period"

    /** The month of a wallet entry context (`premium:2026-11`), or null for any other context. */
    fun periodOf(contextKey: String?): String? =
        contextKey?.takeIf { it.startsWith(CONTEXT_PREFIX) }?.removePrefix(CONTEXT_PREFIX)?.takeIf { PERIOD.matches(it) }

    /** First entry of a month, a person and a cut-off; repeated runs of the same cut-off add a `#n` suffix. */
    fun sourceIdBase(period: String, pharmacistId: String, through: LocalDate): String =
        "$CONTEXT_PREFIX$period:$pharmacistId:$through"

    fun validate(request: SalesPremiumImportRequest, today: LocalDate): ValidatedImport {
        val period = request.period.trim()
        if (!PERIOD.matches(period)) invalid("Период должен быть в формате ГГГГ-ММ, например 2026-11")
        if (YearMonth.from(request.through) != YearMonth.parse(period)) {
            invalid("Дата среза должна быть внутри периода $period")
        }
        if (request.through.isAfter(today)) invalid("Дата среза не может быть позже сегодняшнего дня")
        if (request.rows.isEmpty()) invalid("В загрузке нет строк")
        if (request.rows.size > MAX_ROWS) invalid("В загрузке больше $MAX_ROWS строк")

        val problems = mutableListOf<String>()
        val rows = ArrayList<PremiumRow>(request.rows.size)
        val firstSeen = HashMap<String, Int>()
        request.rows.forEachIndexed { index, raw ->
            val number = index + 1
            val problem = rowProblem(raw)
            if (problem != null) {
                problems += "строка $number: $problem"
                return@forEachIndexed
            }
            val row = PremiumRow(raw!!.iin.trim(), raw.amountKzt.longValueExact(), raw.packs)
            val first = firstSeen.putIfAbsent(row.iin, number)
            if (first != null) problems += "строка $number: этот ИИН уже есть в строке $first" else rows += row
        }
        if (problems.isNotEmpty()) {
            val shown = problems.take(MAX_PROBLEMS).joinToString("; ")
            invalid(if (problems.size > MAX_PROBLEMS) "$shown; и ещё ${problems.size - MAX_PROBLEMS}" else shown)
        }
        return ValidatedImport(period, request.through, rows, fingerprint(period, request.through, rows), packsTotal(rows))
    }

    /** Delta against the net amount already credited for the month; a return is taken back only from funds on hand. */
    fun decide(cumulativeKzt: Long, creditedKzt: Long, balanceKzt: Long): PremiumDecision {
        val delta = Math.subtractExact(cumulativeKzt, creditedKzt)
        return when {
            delta > 0 -> PremiumDecision(creditKzt = delta, correctionKzt = 0, uncoveredKzt = 0)
            delta < 0 -> {
                val wanted = Math.negateExact(delta)
                val taken = minOf(wanted, maxOf(balanceKzt, 0))
                PremiumDecision(creditKzt = 0, correctionKzt = taken, uncoveredKzt = wanted - taken)
            }
            else -> PremiumDecision(0, 0, 0)
        }
    }

    /** The unique index on `pharmacists.iin` allows one card; the rule still never guesses between several. */
    fun resolve(candidates: List<PremiumCandidate>): IinResolution {
        if (candidates.isEmpty()) return IinResolution.NoPharmacist
        val active = candidates.filter { it.active }
        return when {
            active.size == 1 -> IinResolution.Matched(active.single().id)
            active.size > 1 -> IinResolution.Ambiguous
            else -> IinResolution.NotActive
        }
    }

    private fun rowProblem(row: SalesPremiumRow?): String? {
        if (row == null) return "строка пустая"
        if (!IIN.matches(row.iin.trim())) return "ИИН должен состоять из 12 цифр"
        val amount = row.amountKzt
        if (amount.signum() < 0 || amount.compareTo(BigDecimal.valueOf(MAX_AMOUNT_KZT)) > 0 ||
            amount.stripTrailingZeros().scale() > 0) {
            return "сумма должна быть целым числом тенге от 0 до $MAX_AMOUNT_KZT"
        }
        val packs = row.packs
        if (packs != null && (!packs.isFinite() || packs < 0 || packs > MAX_PACKS)) {
            return "число упаковок должно быть числом от 0 до ${MAX_PACKS.toLong()}"
        }
        return null
    }

    private fun packsTotal(rows: List<PremiumRow>): BigDecimal =
        rows.fold(BigDecimal.ZERO) { sum, row -> row.packs?.let { sum + BigDecimal.valueOf(it) } ?: sum }

    /** The same figures in another order give the same hash, so a re-sent file is recognisable. */
    private fun fingerprint(period: String, through: LocalDate, rows: List<PremiumRow>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("v1|$period|$through".toByteArray(Charsets.UTF_8))
        rows.sortedBy { it.iin }.forEach { row ->
            val packs = row.packs?.let { BigDecimal.valueOf(it).stripTrailingZeros().toPlainString() } ?: "-"
            digest.update("\n${row.iin}:${row.amountKzt}:$packs".toByteArray(Charsets.UTF_8))
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun invalid(message: String): Nothing = throw AppException(ErrorCode.VALIDATION_FAILED, message)
}
