package kz.epharm.finance.premium

import java.math.BigDecimal
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SalesPremiumMatrixCsvTest {
    private val nubi = "BB0D85CD-225B-480E-AE84-C7EA8CC01A76"
    private val cifran = "AB9330FC-BE27-4045-B4A7-1DBF57E5589D"

    private fun parse(text: String) = SalesPremiumMatrixCsv.parse(text.toByteArray(Charsets.UTF_8))

    private fun rejected(bytes: ByteArray): String {
        var message = ""
        assertThatThrownBy { SalesPremiumMatrixCsv.parse(bytes) }.isInstanceOfSatisfying(AppException::class.java) { error ->
            assertThat(error.code).isEqualTo(ErrorCode.VALIDATION_FAILED)
            message = error.message.orEmpty()
        }
        return message
    }

    private fun rejected(text: String) = rejected(text.toByteArray(Charsets.UTF_8))

    @Test
    fun `the owner's list is read as it is, an empty bonus is zero and the extra column is ignored`() {
        val items = parse(
            "ware_id,promo_name,bonus_per_pack,sales_03_07_packs\n" +
                "$nubi,NUBIXEEL D3 и K2 30 мл,100,6\n" +
                "0ABC41F1-A2C7-4ED5-89FB-231549C51756,Без бонуса,,225\n",
        )

        assertThat(items).hasSize(2)
        assertThat(items[0].wareId).isEqualTo(nubi)
        assertThat(items[0].name).isEqualTo("NUBIXEEL D3 и K2 30 мл")
        assertThat(items[0].bonusPerPack).isEqualByComparingTo(BigDecimal("100"))
        assertThat(items[1].bonusPerPack).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    fun `a spreadsheet file with a BOM, semicolons and a decimal comma is read too`() {
        val items = parse("﻿ware_id;promo_name;bonus_per_pack\r\n$nubi;Товар;33,5\r\n$cifran;Другой;1 180\r\n")

        assertThat(items).hasSize(2)
        assertThat(items[0].bonusPerPack).isEqualByComparingTo(BigDecimal("33.50"))
        assertThat(items[1].bonusPerPack).isEqualByComparingTo(BigDecimal("1180"))
    }

    @Test
    fun `a thousands separator that is a non-breaking space is dropped`() {
        val items = parse("ware_id,bonus_per_pack\n$nubi,\"1 770\"\n")

        assertThat(items.single().bonusPerPack).isEqualByComparingTo(BigDecimal("1770"))
    }

    @Test
    fun `a name with a comma in quotes stays one cell`() {
        val items = parse("ware_id,promo_name,bonus_per_pack\n$nubi,\"Тест, 10 мг №30\",50\n")

        assertThat(items.single().name).isEqualTo("Тест, 10 мг №30")
    }

    @Test
    fun `the id is written in upper case whatever the file has`() {
        val items = parse("ware_id,bonus_per_pack\n${nubi.lowercase()},10\n")

        assertThat(items.single().wareId).isEqualTo(nubi)
    }

    @Test
    fun `the name column is optional and may be called name`() {
        assertThat(parse("ware_id,bonus_per_pack\n$nubi,10\n").single().name).isEmpty()
        assertThat(parse("name,ware_id,bonus_per_pack\nПростое имя,$nubi,10\n").single().name).isEqualTo("Простое имя")
    }

    @Test
    fun `the same product twice is refused with the row numbers`() {
        val message = rejected("ware_id,bonus_per_pack\n$nubi,10\n$cifran,20\n${nubi.lowercase()},30\n")

        assertThat(message).contains("строка 4").contains("строке 2")
    }

    @Test
    fun `an id that is not a GUID is refused`() {
        for (bad in listOf("", "123", "BB0D85CD225B480EAE84C7EA8CC01A76", "BB0D85CD-225B-480E-AE84-C7EA8CC01A7G", "{$nubi}")) {
            assertThat(rejected("ware_id,bonus_per_pack\n$bad,10\n")).contains("строка 2").contains("GUID")
        }
    }

    @Test
    fun `a bonus must be a number of tenge from zero to the limit with at most two decimals`() {
        for (bad in listOf("abc", "-1", "100001", "10.123", "1e9", "NaN")) {
            assertThat(rejected("ware_id,bonus_per_pack\n$nubi,$bad\n")).contains("строка 2").contains("бонус")
        }
        assertThat(parse("ware_id,bonus_per_pack\n$nubi,100000\n").single().bonusPerPack).isEqualByComparingTo(BigDecimal("100000"))
        assertThat(parse("ware_id,bonus_per_pack\n$nubi,0.05\n").single().bonusPerPack).isEqualByComparingTo(BigDecimal("0.05"))
    }

    @Test
    fun `a name longer than the column is refused`() {
        // The shared CSV reader refuses a cell over 255 characters itself, before the row checks.
        assertThat(rejected("ware_id,promo_name,bonus_per_pack\n$nubi,${"я".repeat(256)},10\n")).isNotBlank()
        assertThat(parse("ware_id,promo_name,bonus_per_pack\n$nubi,${"я".repeat(255)},10\n").single().name).hasSize(255)
    }

    @Test
    fun `the required columns must be in the header exactly once`() {
        assertThat(rejected("promo_name,bonus_per_pack\nТовар,10\n")).contains("ware_id")
        assertThat(rejected("ware_id,promo_name\n$nubi,Товар\n")).contains("bonus_per_pack")
        assertThat(rejected("ware_id,ware_id,bonus_per_pack\n$nubi,$nubi,10\n")).contains("несколько раз")
    }

    @Test
    fun `a file with a header only is refused`() {
        rejected("ware_id,bonus_per_pack\n")
    }

    @Test
    fun `an empty file and a file that is not UTF-8 are refused`() {
        assertThat(rejected(ByteArray(0))).contains("пустой")
        val cp1251 = "ware_id,promo_name,bonus_per_pack\n$nubi,Аспирин,10\n".toByteArray(charset("windows-1251"))
        assertThat(rejected(cp1251)).contains("UTF-8")
    }

    @Test
    fun `a file over the size limit is refused before it is read`() {
        assertThat(rejected(ByteArray(SalesPremiumMatrixCsv.MAX_BYTES + 1) { 'a'.code.toByte() })).contains("КБ")
    }

    @Test
    fun `at most ten problems are listed and the rest is counted`() {
        val rows = (1..14).joinToString("") { "bad$it,10\n" }

        val message = rejected("ware_id,bonus_per_pack\n$rows")

        assertThat(message).contains("строка 2").contains("строка 11").doesNotContain("строка 12").contains("и ещё 4")
    }

    @Test
    fun `a malformed quote is refused`() {
        rejected("ware_id,promo_name,bonus_per_pack\n$nubi,\"Открыта,10\n")
    }
}
