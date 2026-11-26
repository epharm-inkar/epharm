package kz.epharm.finance.premium

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SalesPremiumAggregatorTest {
    private val anna = "111111111111"
    private val boris = "222222222222"

    private fun item(ware: String, bonus: String) = MatrixItem(ware, "Товар $ware", BigDecimal(bonus))

    private fun line(iin: String?, type: Int, quantity: String, profile: Long? = 1, user: Long? = 10) =
        StandardNPremiumLine(profile, user, iin, type, BigDecimal(quantity))

    private fun aggregate(matrix: List<MatrixItem>, vararg lines: Pair<String, List<StandardNPremiumLine>>) =
        SalesPremiumAggregator.aggregate(matrix, lines.toMap())

    @Test
    fun `sale receipts are negative and returns positive, the packs that stayed sold are minus the net`() {
        val result = aggregate(
            listOf(item("A", "100")),
            "A" to listOf(line(anna, 3, "-10"), line(anna, 9, "3")),
        )

        assertThat(result.rows).containsExactly(PremiumRow(anna, 700, 7.0))
        assertThat(result.packsNet).isEqualByComparingTo("7")
        assertThat(result.negativeSellers).isZero()
    }

    @Test
    fun `the premium of a seller is the sum over the products of packs times the bonus`() {
        val result = aggregate(
            listOf(item("A", "100"), item("B", "250")),
            "A" to listOf(line(anna, 3, "-4"), line(boris, 3, "-1")),
            "B" to listOf(line(anna, 3, "-2")),
        )

        assertThat(result.rows).containsExactly(PremiumRow(anna, 900, 6.0), PremiumRow(boris, 100, 1.0))
        assertThat(result.productsWithSales).isEqualTo(2)
        assertThat(result.packsNet).isEqualByComparingTo("7")
    }

    @Test
    fun `a seller without a usable IIN is summed apart and never becomes a row`() {
        val result = aggregate(
            listOf(item("A", "100")),
            "A" to listOf(
                line(null, 3, "-2", profile = 1, user = 10),
                line("", 3, "-3", profile = 1, user = 10),
                line("12345", 3, "-1", profile = 2, user = 20),
                line("ABCDEFGHIJKL", 3, "-4", profile = 3, user = null),
                line(anna, 3, "-5"),
            ),
        )

        assertThat(result.rows).containsExactly(PremiumRow(anna, 500, 5.0))
        assertThat(result.withoutIin.sellers).isEqualTo(3)
        assertThat(result.withoutIin.packs).isEqualByComparingTo("10")
        assertThat(result.withoutIin.amountKzt).isEqualTo(1000)
        assertThat(result.packsNet).isEqualByComparingTo("15")
    }

    @Test
    fun `an IIN with spaces around it is still that IIN`() {
        val result = aggregate(listOf(item("A", "100")), "A" to listOf(line("  $anna ", 3, "-1")))

        assertThat(result.rows.map { it.iin }).containsExactly(anna)
        assertThat(result.withoutIin.sellers).isZero()
    }

    @Test
    fun `one person under two Standard-N users or pharmacies is one seller`() {
        val result = aggregate(
            listOf(item("A", "100")),
            "A" to listOf(line(anna, 3, "-2", profile = 1, user = 10), line(anna, 3, "-3", profile = 7, user = 99)),
        )

        assertThat(result.rows).containsExactly(PremiumRow(anna, 500, 5.0))
    }

    @Test
    fun `a product with no bonus pays nothing but its packs are counted`() {
        val result = aggregate(
            listOf(item("FREE", "0"), item("PAID", "100")),
            "FREE" to listOf(line(anna, 3, "-5"), line(boris, 3, "-2")),
            "PAID" to listOf(line(anna, 3, "-2")),
        )

        assertThat(result.rows).containsExactly(PremiumRow(anna, 200, 7.0), PremiumRow(boris, 0, 2.0))
        assertThat(result.packsNet).isEqualByComparingTo("9")
    }

    @Test
    fun `a product the source does not know counts as no sales`() {
        val result = aggregate(listOf(item("A", "100"), item("GHOST", "500")), "A" to listOf(line(anna, 3, "-1")))

        assertThat(result.rows).containsExactly(PremiumRow(anna, 100, 1.0))
        assertThat(result.productsWithSales).isEqualTo(1)
    }

    @Test
    fun `tenge are rounded once per seller at the end, not per line`() {
        // 0.333 packs of two products at 100 each: 33.3 + 33.3 = 66.6 -> 67, whereas rounding each line gives 66.
        val result = aggregate(
            listOf(item("A", "100"), item("B", "100")),
            "A" to listOf(line(anna, 3, "-0.333")),
            "B" to listOf(line(anna, 3, "-0.333")),
        )

        assertThat(result.rows.single().amountKzt).isEqualTo(67)
    }

    @Test
    fun `a half tenge is rounded up`() {
        val half = aggregate(listOf(item("A", "101")), "A" to listOf(line(anna, 3, "-0.5")))
        val below = aggregate(listOf(item("A", "101")), "A" to listOf(line(anna, 3, "-0.49")))

        assertThat(half.rows.single().amountKzt).isEqualTo(51)
        assertThat(below.rows.single().amountKzt).isEqualTo(49)
    }

    @Test
    fun `a decimal bonus per pack is honoured`() {
        val result = aggregate(listOf(item("A", "33.50")), "A" to listOf(line(anna, 3, "-3")))

        assertThat(result.rows.single().amountKzt).isEqualTo(101)
    }

    @Test
    fun `a seller who returned more than sold is credited zero and counted, the file never carries a negative figure`() {
        val result = aggregate(
            listOf(item("A", "100")),
            "A" to listOf(line(anna, 9, "5"), line(boris, 3, "-1")),
        )

        assertThat(result.rows).containsExactly(PremiumRow(anna, 0, 0.0), PremiumRow(boris, 100, 1.0))
        assertThat(result.negativeSellers).isEqualTo(1)
        assertThat(result.packsNet).isEqualByComparingTo("-4")
    }

    @Test
    fun `a return of another product can only lower the sum of the seller, never the file below zero`() {
        val result = aggregate(
            listOf(item("A", "100"), item("B", "100")),
            "A" to listOf(line(anna, 3, "-10")),
            "B" to listOf(line(anna, 9, "4")),
        )

        assertThat(result.rows).containsExactly(PremiumRow(anna, 600, 6.0))
    }

    @Test
    fun `an anonymous seller is clamped at zero like a named one`() {
        val result = aggregate(
            listOf(item("A", "250")),
            "A" to listOf(line(null, 3, "-4", profile = 1, user = 10), line(null, 9, "1", profile = 2, user = 5)),
        )

        assertThat(result.withoutIin.sellers).isEqualTo(2)
        assertThat(result.withoutIin.amountKzt).isEqualTo(1000)
        assertThat(result.withoutIin.packs).isEqualByComparingTo("4")
    }

    @Test
    fun `documents of other types are ignored`() {
        val result = aggregate(
            listOf(item("A", "100")),
            "A" to listOf(line(anna, 1, "-50"), line(anna, 2, "30"), line(anna, 3, "-1")),
        )

        assertThat(result.rows).containsExactly(PremiumRow(anna, 100, 1.0))
        assertThat(result.packsNet).isEqualByComparingTo("1")
    }

    @Test
    fun `a sale and its return on the same product net to zero but the seller stays in the file`() {
        val result = aggregate(listOf(item("A", "100")), "A" to listOf(line(anna, 3, "-2"), line(anna, 9, "2")))

        assertThat(result.rows).containsExactly(PremiumRow(anna, 0, 0.0))
        assertThat(result.negativeSellers).isZero()
    }

    @Test
    fun `rows come out in IIN order whatever the order of the lines`() {
        val result = aggregate(
            listOf(item("A", "10")),
            "A" to listOf(line(boris, 3, "-1"), line("000000000001", 3, "-1"), line(anna, 3, "-1")),
        )

        assertThat(result.rows.map { it.iin }).containsExactly("000000000001", anna, boris)
    }

    @Test
    fun `no lines at all give an empty result`() {
        val result = aggregate(listOf(item("A", "100")))

        assertThat(result.rows).isEmpty()
        assertThat(result.withoutIin).isEqualTo(WithoutIin(0, BigDecimal.ZERO, 0))
        assertThat(result.productsWithSales).isZero()
    }

    @Test
    fun `the accumulator gives the same answer as the one-shot aggregate`() {
        val matrix = listOf(item("A", "100"), item("B", "40"))
        val lines = mapOf(
            "A" to listOf(line(anna, 3, "-3"), line(null, 3, "-1")),
            "B" to listOf(line(anna, 3, "-5"), line(boris, 9, "1")),
        )
        val accumulator = PremiumAccumulator()
        matrix.forEach { accumulator.add(it, lines.getValue(it.wareId)) }

        assertThat(accumulator.result()).isEqualTo(SalesPremiumAggregator.aggregate(matrix, lines))
    }

    @Test
    fun `an IIN is twelve digits or nothing`() {
        assertThat(SalesPremiumAggregator.normalizeIin("123456789012")).isEqualTo("123456789012")
        assertThat(SalesPremiumAggregator.normalizeIin(" 123456789012 ")).isEqualTo("123456789012")
        assertThat(SalesPremiumAggregator.normalizeIin("12345678901")).isNull()
        assertThat(SalesPremiumAggregator.normalizeIin("1234567890123")).isNull()
        assertThat(SalesPremiumAggregator.normalizeIin("12345678901a")).isNull()
        assertThat(SalesPremiumAggregator.normalizeIin("")).isNull()
        assertThat(SalesPremiumAggregator.normalizeIin(null)).isNull()
    }
}
