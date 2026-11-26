package kz.epharm.finance.premium

import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.YearMonth
import java.util.concurrent.atomic.AtomicBoolean
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service

/** The days one calculation covers: the 1st of [period] through the closed day [through], both Almaty calendar days. */
data class PremiumWindow(val period: String, val from: LocalDate, val through: LocalDate) {
    /** Exclusive upper bound of the commit dates the source is asked for: the day after [through]. */
    val until: LocalDate get() = through.plusDays(1)

    companion object {
        /**
         * [requested] null means the month of yesterday: on the 1st that is the month that has just ended, with its last
         * day as the final cut-off. A requested month ends at yesterday, or at its own last day when that is past. A
         * month that has not begun, or has no closed day yet, is refused.
         */
        fun resolve(requested: String?, today: LocalDate): PremiumWindow {
            val yesterday = today.minusDays(1)
            val month = if (requested == null) YearMonth.from(yesterday) else YearMonth.parse(SalesPremiumRules.requirePeriod(requested))
            if (month.isAfter(YearMonth.from(today))) {
                throw AppException(ErrorCode.VALIDATION_FAILED, "Месяц $month ещё не наступил")
            }
            val lastDay = month.atEndOfMonth()
            val through = if (yesterday.isBefore(lastDay)) yesterday else lastDay
            if (through.isBefore(month.atDay(1))) {
                throw AppException(ErrorCode.VALIDATION_FAILED, "В месяце $month ещё нет закрытых дней")
            }
            return PremiumWindow(month.toString(), month.atDay(1), through)
        }
    }
}

/**
 * Totals of one calculation, no IINs. [packs] is the net of every seller with or without IIN, [premiumKzt] the sum of
 * the sellers with an IIN as sent to the import, [withoutIin] the sellers that were not credited for lack of an IIN.
 * [imported] is what the import did (or, for a dry run, would do); it is null when nothing was sent (no sale at all).
 */
data class CollectorResult(
    val period: String,
    val through: LocalDate,
    val dryRun: Boolean,
    val products: Int,
    val productsWithSales: Int,
    val packs: BigDecimal,
    val sellers: Int,
    val sellersWithPremium: Int,
    val premiumKzt: Long,
    val withoutIin: WithoutIin,
    val negativeSellers: Int,
    val imported: SalesPremiumImportResult?,
    val tookMillis: Long,
)

/**
 * Computes the sales premium itself: reads the sales and returns of every matrix product of the month from the central
 * Standard-N database (read only, one call per product, one connection), adds them up per seller IIN and gives the
 * cumulative month-to-date figures to the same import that a file goes through. Only a complete read is ever imported:
 * a figure that misses one product would take valid premium back from everybody who sold it.
 *
 * One calculation runs at a time, whoever asks (the daily job or an administrator): a second request is refused with 409.
 */
@Service
class SalesPremiumCollector(
    private val matrix: SalesPremiumMatrixStore,
    private val source: StandardNPremiumSource,
    private val premium: SalesPremiumService,
    private val clock: Clock = Clock.system(SalesPremiumRules.ALMATY),
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val running = AtomicBoolean(false)

    /** [period] null: the month of yesterday (see [PremiumWindow.resolve]). A calculation already running is a 409. */
    fun collect(period: String?, dryRun: Boolean, actor: PremiumActor): CollectorResult =
        tryCollect(period, dryRun, actor)
            ?: throw AppException(ErrorCode.CONFLICT, "Расчёт премии уже идёт, повторите через несколько минут", HttpStatus.CONFLICT)

    /** Null, without reading anything, when another calculation is running. */
    fun tryCollect(period: String?, dryRun: Boolean, actor: PremiumActor): CollectorResult? {
        if (!running.compareAndSet(false, true)) return null
        try {
            return run(period, dryRun, actor)
        } finally {
            running.set(false)
        }
    }

    private fun run(period: String?, dryRun: Boolean, actor: PremiumActor): CollectorResult {
        val started = clock.instant()
        val window = PremiumWindow.resolve(period, LocalDate.now(clock))
        val products = matrix.items(window.period)
        if (products.isEmpty()) {
            throw AppException(ErrorCode.CONFLICT, "Для месяца ${window.period} не загружена матрица товаров", HttpStatus.CONFLICT)
        }
        val accumulator = PremiumAccumulator()
        source.open().use { reader ->
            for (product in products) {
                if (Duration.between(started, clock.instant()) > MAX_READ) {
                    throw StandardNSourceException("Standard-N read took longer than $MAX_READ")
                }
                accumulator.add(product, reader.lines(product.wareId, window.from, window.until))
            }
        }
        val totals = accumulator.result()
        val imported = if (totals.rows.isEmpty()) null else send(window, totals, dryRun, actor)
        val result = CollectorResult(
            period = window.period,
            through = window.through,
            dryRun = dryRun,
            products = products.size,
            productsWithSales = totals.productsWithSales,
            packs = totals.packsNet,
            sellers = totals.rows.size,
            sellersWithPremium = totals.rows.count { it.amountKzt > 0 },
            premiumKzt = totals.rows.fold(0L) { sum, row -> Math.addExact(sum, row.amountKzt) },
            withoutIin = totals.withoutIin,
            negativeSellers = totals.negativeSellers,
            imported = imported,
            tookMillis = Duration.between(started, clock.instant()).toMillis(),
        )
        report(result)
        return result
    }

    private fun send(window: PremiumWindow, totals: AggregatedPremium, dryRun: Boolean, actor: PremiumActor): SalesPremiumImportResult {
        val request = SalesPremiumImportRequest(
            period = window.period,
            through = window.through,
            dryRun = dryRun,
            rows = totals.rows.map { SalesPremiumRow(it.iin, BigDecimal.valueOf(it.amountKzt), it.packs) },
        )
        val outcome = if (dryRun) premium.preview(request) else premium.applyAs(request, actor)
        // The administrator's answer carries totals only: the IINs of an ambiguous card stay in the server.
        return outcome.copy(ambiguous = outcome.ambiguous.copy(iins = emptyList()))
    }

    private fun report(result: CollectorResult) {
        val imported = result.imported
        log.info(
            "Sales premium collected{} period={} through={} products={} withSales={} packs={} amountKzt={} sellers={} " +
                "withPremium={} withoutIin={}/{}packs/{}KZT negative={} matched={} pending={} ambiguous={} creditedKzt={} " +
                "correctionsKzt={} uncoveredKzt={} tookMs={}",
            if (result.dryRun) " (dry run)" else "", result.period, result.through, result.products, result.productsWithSales,
            result.packs.stripTrailingZeros().toPlainString(), result.premiumKzt, result.sellers, result.sellersWithPremium,
            result.withoutIin.sellers, result.withoutIin.packs.stripTrailingZeros().toPlainString(), result.withoutIin.amountKzt,
            result.negativeSellers, imported?.matched?.count, imported?.pending?.count, imported?.ambiguous?.count,
            imported?.credited?.sumKzt, imported?.corrections?.appliedKzt, imported?.corrections?.uncoveredKzt, result.tookMillis,
        )
    }

    private companion object {
        /** Standard-N is the shop's working database: a run that drags on is stopped rather than left to compete with the tills. */
        val MAX_READ: Duration = Duration.ofMinutes(25)
    }
}
