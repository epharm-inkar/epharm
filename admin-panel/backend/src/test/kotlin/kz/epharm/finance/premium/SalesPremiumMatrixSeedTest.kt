package kz.epharm.finance.premium

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * V125 seeds the November 2026 matrix from the owner's list. The database is not needed to know that every tuple
 * would pass the table's CHECK constraints and that the list arrived whole.
 */
class SalesPremiumMatrixSeedTest {
    private val sql = checkNotNull(javaClass.getResource("/db/migration/V125__sales_premium_matrix_and_figures.sql")) {
        "V125 is not on the classpath"
    }.readText(Charsets.UTF_8)

    private val tuple = Regex("""\('2026-11', '([0-9A-F]{8}(?:-[0-9A-F]{4}){3}-[0-9A-F]{12})', '((?:[^']|'')*)', (\d+)\)""")
    private val seeded = tuple.findAll(sql).map { Triple(it.groupValues[1], it.groupValues[2].replace("''", "'"), it.groupValues[3].toLong()) }.toList()

    @Test
    fun `every row of the seed is a well formed tuple`() {
        assertThat(Regex("""^\s+\('2026-11',""", RegexOption.MULTILINE).findAll(sql).count()).isEqualTo(seeded.size)
        assertThat(seeded).hasSize(188)
    }

    @Test
    fun `the products are distinct and their names fit the column`() {
        assertThat(seeded.map { it.first }).doesNotHaveDuplicates()
        assertThat(seeded.map { it.second }).allSatisfy { name -> assertThat(name).isNotBlank().hasSizeLessThanOrEqualTo(255) }
    }

    @Test
    fun `the bonuses are within the limit, one product pays nothing and the total is the list's`() {
        assertThat(seeded.map { it.third }).allSatisfy { bonus -> assertThat(bonus).isBetween(0, SalesPremiumMatrixCsv.MAX_BONUS) }
        assertThat(seeded.count { it.third == 0L }).isEqualTo(1)
        // The sum of bonus_per_pack of knowledge/epharm/data/november-promo-list.csv (188 rows, one empty bonus).
        assertThat(seeded.sumOf { it.third }).isEqualTo(50_481)
    }

    @Test
    fun `the migration creates both tables once and seeds only the matrix`() {
        assertThat(Regex("CREATE TABLE sales_premium_matrix \\(").findAll(sql).count()).isEqualTo(1)
        assertThat(Regex("CREATE TABLE sales_premium_figures \\(").findAll(sql).count()).isEqualTo(1)
        assertThat(Regex("INSERT INTO").findAll(sql).count()).isEqualTo(1)
        assertThat(sql).doesNotContain("pharmacist_wallet_entries (").doesNotContain("UPDATE ").doesNotContain("DELETE ")
    }
}
