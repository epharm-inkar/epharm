package kz.epharm.finance.premium

import java.math.BigDecimal
import java.math.RoundingMode

/** One product of the month's matrix: the bonus per pack in tenge, zero for a product that pays nothing. */
data class MatrixItem(val wareId: String, val name: String, val bonusPerPack: BigDecimal)

/** Sellers without a usable IIN: summed and reported, never credited. [sellers] counts distinct Standard-N users. */
data class WithoutIin(val sellers: Int, val packs: BigDecimal, val amountKzt: Long)

/**
 * What the lines of every product add up to. [rows] are the sellers with an IIN, one per IIN in IIN order, ready for
 * the import: the amount is whole tenge and never below zero, [PremiumRow.packs] never below zero. [packsNet] is the
 * plain net of packs of every seller, with or without IIN, as the source reports it. [negativeSellers] are sellers whose
 * month came out below zero (they returned more than they sold) and are credited zero.
 */
data class AggregatedPremium(
    val rows: List<PremiumRow>,
    val withoutIin: WithoutIin,
    val packsNet: BigDecimal,
    val productsWithSales: Int,
    val negativeSellers: Int,
)

/** Standard-N document types the premium reads. */
object StandardNDocTypes {
    /** A sale receipt: the quantities are negative. */
    const val SALE = 3

    /** A return: the quantities are positive. */
    const val RETURN = 9
}

/**
 * Adds the lines of the products one after another, so a run never holds the lines of all products at once. A seller's
 * premium is the sum over products of the packs that stayed sold (minus the quantities of receipts and returns) times
 * the bonus per pack, rounded to whole tenge once, at the end.
 */
class PremiumAccumulator {
    private class Sum {
        var packs: BigDecimal = BigDecimal.ZERO
        var amount: BigDecimal = BigDecimal.ZERO
    }

    private val byIin = HashMap<String, Sum>()
    private val withoutIin = HashMap<Pair<Long?, Long?>, Sum>()
    private var packsNet: BigDecimal = BigDecimal.ZERO
    private var productsWithSales = 0

    fun add(item: MatrixItem, lines: List<StandardNPremiumLine>) {
        var moved = false
        for (line in lines) {
            if (line.docType != StandardNDocTypes.SALE && line.docType != StandardNDocTypes.RETURN) continue
            if (line.quantity.signum() != 0) moved = true
            val packs = line.quantity.negate()
            val sum = SalesPremiumAggregator.normalizeIin(line.iin)?.let { byIin.getOrPut(it) { Sum() } }
                ?: withoutIin.getOrPut(line.profileId to line.userId) { Sum() }
            sum.packs = sum.packs.add(packs)
            sum.amount = sum.amount.add(packs.multiply(item.bonusPerPack))
            packsNet = packsNet.add(packs)
        }
        if (moved) productsWithSales++
    }

    fun result(): AggregatedPremium {
        var negative = 0
        val rows = byIin.entries.sortedBy { it.key }.map { (iin, sum) ->
            val amount = whole(sum.amount)
            if (amount.signum() < 0) negative++
            PremiumRow(iin, amount.max(BigDecimal.ZERO).longValueExact(), sum.packs.max(BigDecimal.ZERO).toDouble())
        }
        val anonymousPacks = withoutIin.values.fold(BigDecimal.ZERO) { total, sum -> total.add(sum.packs.max(BigDecimal.ZERO)) }
        val anonymousAmount = withoutIin.values.fold(0L) { total, sum ->
            Math.addExact(total, whole(sum.amount).max(BigDecimal.ZERO).longValueExact())
        }
        return AggregatedPremium(
            rows = rows,
            withoutIin = WithoutIin(withoutIin.size, anonymousPacks, anonymousAmount),
            packsNet = packsNet,
            productsWithSales = productsWithSales,
            negativeSellers = negative,
        )
    }

    private fun whole(amount: BigDecimal): BigDecimal = amount.setScale(0, RoundingMode.HALF_UP)
}

object SalesPremiumAggregator {
    private val IIN = Regex("[0-9]{12}")

    /** The IIN as 12 digits, or null for an empty or malformed one: such a seller is never credited. */
    fun normalizeIin(raw: String?): String? = raw?.trim()?.takeIf { IIN.matches(it) }

    /** The whole matrix at once; the collector uses [PremiumAccumulator] directly, product by product. */
    fun aggregate(matrix: List<MatrixItem>, linesByWare: Map<String, List<StandardNPremiumLine>>): AggregatedPremium {
        val accumulator = PremiumAccumulator()
        matrix.forEach { accumulator.add(it, linesByWare[it.wareId].orEmpty()) }
        return accumulator.result()
    }
}
