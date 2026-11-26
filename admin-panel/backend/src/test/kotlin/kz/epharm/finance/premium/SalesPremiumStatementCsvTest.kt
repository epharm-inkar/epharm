package kz.epharm.finance.premium

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SalesPremiumStatementCsvTest {
    private fun row(
        name: String = "Иванова Анна Сергеевна",
        iin: String = "050112345678",
        pharmacy: String = "Аптека №5",
        city: String = "Алматы",
        packs: String? = "12",
        amount: Long = 3400,
        credited: Boolean = true,
    ) = StatementRow(name, iin, pharmacy, city, packs?.let(::BigDecimal), amount, credited)

    private fun render(vararg rows: StatementRow): List<String> =
        String(SalesPremiumStatementCsv.render(rows.toList()), Charsets.UTF_8).removePrefix("﻿").split("\r\n").dropLast(1)

    @Test
    fun `the file is UTF-8 with a byte order mark and the header of the statement`() {
        val bytes = SalesPremiumStatementCsv.render(emptyList())

        assertThat(bytes.take(3).map { it.toInt() and 0xFF }).containsExactly(0xEF, 0xBB, 0xBF)
        assertThat(String(bytes, Charsets.UTF_8)).isEqualTo("﻿ФИО;ИИН;аптека;город;упаковок;начислено;статус\r\n")
    }

    @Test
    fun `a credited line has its figures in the columns, semicolons between them`() {
        val lines = render(row())

        assertThat(lines).hasSize(2)
        assertThat(lines[1]).isEqualTo("Иванова Анна Сергеевна;\"=\"\"050112345678\"\"\";Аптека №5;Алматы;12;3400;на кошельке")
    }

    @Test
    fun `a waiting line is marked as waiting for a card`() {
        assertThat(render(row(credited = false))[1]).endsWith(";ждёт карточку")
    }

    @Test
    fun `the IIN keeps its leading zero when a spreadsheet opens the file`() {
        // Plain 050112345678 would become the number 50112345678; the text formula holds all twelve digits.
        assertThat(render(row(iin = "050112345678"))[1]).contains("=\"\"050112345678\"\"")
    }

    @Test
    fun `fractional packs use a decimal comma, whole packs no fraction, unknown packs an empty cell`() {
        assertThat(render(row(packs = "12.5"))[1].split(';')[4]).isEqualTo("12,5")
        assertThat(render(row(packs = "12.000"))[1].split(';')[4]).isEqualTo("12")
        assertThat(render(row(packs = "100"))[1].split(';')[4]).isEqualTo("100")
        assertThat(render(row(packs = null))[1].split(';')[4]).isEmpty()
    }

    @Test
    fun `a name with a semicolon or a quote is quoted so that the columns stay in place`() {
        val lines = render(row(name = "Иванова; Анна", pharmacy = "Аптека \"Здоровье\""))

        assertThat(lines[1]).startsWith("\"Иванова; Анна\";")
        assertThat(lines[1]).contains(";\"Аптека \"\"Здоровье\"\"\";")
    }

    @Test
    fun `a text that starts like a spreadsheet formula is made plain text`() {
        for (danger in listOf("=SUM(A1)", "+7701", "-1", "@cmd")) {
            assertThat(render(row(name = danger))[1]).startsWith("'$danger;")
        }
        assertThat(render(row(name = "  Пётр "))[1]).startsWith("Пётр;")
    }

    @Test
    fun `an empty name or place is an empty cell`() {
        val cells = render(row(name = "", pharmacy = "", city = ""))[1].split(';')

        assertThat(cells[0]).isEmpty()
        assertThat(cells[2]).isEmpty()
        assertThat(cells[3]).isEmpty()
    }

    @Test
    fun `lines end with carriage return and line feed, one per row`() {
        val text = String(SalesPremiumStatementCsv.render(listOf(row(), row(iin = "060212345679"))), Charsets.UTF_8)

        assertThat(text.split("\r\n")).hasSize(4)
        assertThat(text).endsWith("\r\n")
    }

    @Test
    fun `a negative or zero amount is written as it is`() {
        assertThat(render(row(amount = 0))[1].split(';')[5]).isEqualTo("0")
    }
}
