package kz.epharm.finance.premium

import java.math.BigDecimal
import java.time.LocalDate

/** One seller of the file. [amountKzt] is cumulative for the month through the cut-off date, [packs] is informational. */
data class SalesPremiumRow(val iin: String, val amountKzt: BigDecimal, val packs: Double? = null)

/**
 * `POST /api/admin/finance/sales-premium/import`. [dryRun] is nullable on purpose: Jackson turns a missing primitive
 * Boolean into `false`, which would move money, so the controller rejects a file that does not say it. A null row is
 * a 400 (checked by the rules), not a server error.
 */
data class SalesPremiumImportRequest(
    val period: String,
    val through: LocalDate,
    val dryRun: Boolean?,
    val rows: List<SalesPremiumRow?>,
)

data class PremiumTotals(val count: Int, val sumKzt: Long)

/** [count] cumulative figures below what the ledger holds; [appliedKzt] taken back; [uncoveredKzt] not on the balance. */
data class PremiumCorrections(val count: Int, val appliedKzt: Long, val uncoveredKzt: Long)

/** Figures that wait for an active card; [notActiveCount] of them belong to cards that exist but are not active. */
data class PremiumPending(val count: Int, val sumKzt: Long, val notActiveCount: Int)

data class PremiumAmbiguous(val count: Int, val sumKzt: Long, val iins: List<String>)

/**
 * What an import did (or, for a dry run, would do). [rows] splits into [matched], [pending] and [ambiguous];
 * [credited] is the positive difference written to wallets, [corrections] the negative one, [unchanged] the matched
 * sellers whose figure already equals the ledger. [auditId] is set only for a real import.
 */
data class SalesPremiumImportResult(
    val period: String,
    val through: LocalDate,
    val dryRun: Boolean,
    val rows: PremiumTotals,
    val matched: PremiumTotals,
    val credited: PremiumTotals,
    val corrections: PremiumCorrections,
    val unchanged: Int,
    val pending: PremiumPending,
    val ambiguous: PremiumAmbiguous,
    val packsTotal: BigDecimal,
    val auditId: String?,
)
