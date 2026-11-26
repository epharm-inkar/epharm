package kz.epharm.finance.premium

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SalesPremiumLabelsTest {
    @Test
    fun `a premium entry names its month in Russian`() {
        assertThat(SalesPremiumLabels.entryLabel("premium:2026-11")).isEqualTo("Премия за продажи, ноябрь 2026")
        assertThat(SalesPremiumLabels.entryLabel("premium:2027-01")).isEqualTo("Премия за продажи, январь 2027")
        assertThat(SalesPremiumLabels.entryLabel("premium:2026-12")).isEqualTo("Премия за продажи, декабрь 2026")
    }

    @Test
    fun `a correction is named separately`() {
        assertThat(SalesPremiumLabels.correctionLabel("premium:2026-11")).isEqualTo("Корректировка премии, ноябрь 2026")
    }

    @Test
    fun `without a readable month the name alone is shown`() {
        for (context in listOf(null, "", "premium:", "premium:2026-13", "payout-item:poi_1", "approved:1")) {
            assertThat(SalesPremiumLabels.entryLabel(context)).isEqualTo("Премия за продажи")
        }
    }

    @Test
    fun `no label carries a long dash`() {
        val labels = listOf(SalesPremiumLabels.entryLabel("premium:2026-11"), SalesPremiumLabels.correctionLabel("premium:2026-11"))

        val emDash = 0x2014.toChar().toString()
        val enDash = 0x2013.toChar().toString()
        labels.forEach { assertThat(it).doesNotContain(emDash).doesNotContain(enDash) }
    }
}
