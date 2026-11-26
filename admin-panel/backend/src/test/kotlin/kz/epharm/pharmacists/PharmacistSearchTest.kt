package kz.epharm.pharmacists

import kz.epharm.pharmacists.service.PharmacistSearch
import kz.epharm.pharmacists.service.PharmacistSearchRow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Finding a pharmacist by what HQ remembers: name parts, phone digits, IIN, pharmacy. Pure rules, no database. */
class PharmacistSearchTest {
    private val aigerim = PharmacistSearchRow(
        id = "ph_aigerim",
        name = "Сапарова Айгерим Серикқызы",
        phone = "+77011234567",
        iin = "000101500111",
        places = listOf("Аптека со склада", "Алматы", "Абая 103"),
    )
    private val erbol = PharmacistSearchRow(
        id = "ph_erbol",
        name = "Тұрсынов Ербол",
        phone = "8 (702) 555-12-34",
        iin = "000101500122",
        places = listOf("Аптека со склада", "Астана", "Толе би 286"),
    )
    private val oleg = PharmacistSearchRow(
        id = "ph_oleg",
        name = "Ёлкин Олег Петрович",
        phone = "",
        iin = "900202300333",
        places = listOf("Аптека на углу", "Шымкент", "Тауке хана 5"),
    )
    private val all = listOf(aigerim, erbol, oleg)

    private fun ids(query: String?) = PharmacistSearch.filter(all, query).map { it.id }

    @Test fun `an empty query matches everybody`() {
        assertEquals(listOf("ph_aigerim", "ph_erbol", "ph_oleg"), ids(null))
        assertEquals(listOf("ph_aigerim", "ph_erbol", "ph_oleg"), ids("   "))
        assertEquals(listOf("ph_aigerim", "ph_erbol", "ph_oleg"), ids(" , ; "))
    }

    @Test fun `any part of the surname, the name or the patronymic matches without regard to case`() {
        assertEquals(listOf("ph_aigerim"), ids("сапарова"))
        assertEquals(listOf("ph_aigerim"), ids("АЙГЕРИМ"))
        assertEquals(listOf("ph_aigerim"), ids("серикк"))
        assertEquals(listOf("ph_erbol"), ids("рбол"))
        assertEquals(listOf("ph_oleg"), ids("петрович"))
    }

    @Test fun `every word of the query must match, in any order`() {
        assertEquals(listOf("ph_aigerim"), ids("Сапарова Айгерим"))
        assertEquals(listOf("ph_aigerim"), ids("айгерим сапарова"))
        assertEquals(emptyList<String>(), ids("Сапарова Ербол"))
    }

    @Test fun `commas and semicolons separate words like spaces`() {
        assertEquals(listOf("ph_aigerim"), ids("Сапарова,Айгерим"))
        assertEquals(listOf("ph_erbol"), ids("Тұрсынов; Ербол"))
    }

    @Test fun `Kazakh letters are found by their Russian twins and yo by ye`() {
        assertEquals(listOf("ph_erbol"), ids("Турсынов"))
        assertEquals(listOf("ph_aigerim"), ids("Серикқызы"))
        assertEquals(listOf("ph_aigerim"), ids("Сериккызы"))
        assertEquals(listOf("ph_oleg"), ids("Елкин"))
        assertEquals(listOf("ph_oleg"), ids("ёлкин"))
        assertEquals("турсынов", PharmacistSearch.fold("ТҰРСЫНОВ"))
        assertEquals("айгерим", PharmacistSearch.fold("Әйгерім"))
        assertEquals("гулнар", PharmacistSearch.fold("Ғүлнар"))
        assertEquals("кожа нурхан хаким", PharmacistSearch.fold("Қожа Нұрхан Һәким"))
        assertEquals("жанар онгар", PharmacistSearch.fold("Жанар Оңғар"))
    }

    @Test fun `the pharmacy name, the city and the address match`() {
        assertEquals(listOf("ph_aigerim", "ph_erbol"), ids("со склада"))
        assertEquals(listOf("ph_erbol"), ids("астана"))
        assertEquals(listOf("ph_aigerim"), ids("абая"))
        assertEquals(listOf("ph_aigerim"), ids("Абая 103"))
        assertEquals(listOf("ph_oleg"), ids("шымкент углу"))
    }

    @Test fun `a phone matches by its digits however it was typed or stored`() {
        assertEquals(listOf("ph_aigerim"), ids("+7 701 123 45 67"))
        assertEquals(listOf("ph_aigerim"), ids("87011234567"))
        assertEquals(listOf("ph_aigerim"), ids("7011234567"))
        assertEquals(listOf("ph_aigerim"), ids("(701) 123-45-67"))
        assertEquals(listOf("ph_erbol"), ids("77025551234"))
        assertEquals(listOf("ph_erbol"), ids("8 702 555 12 34"))
        // A part of a phone from four digits.
        assertEquals(listOf("ph_aigerim"), ids("4567"))
        assertEquals(listOf("ph_erbol"), ids("555-12"))
    }

    @Test fun `an IIN matches by digits, whole or in part`() {
        assertEquals(listOf("ph_aigerim"), ids("000101500111"))
        assertEquals(listOf("ph_oleg"), ids("900202"))
        assertEquals(listOf("ph_aigerim", "ph_erbol"), ids("0001015001"))
    }

    @Test fun `a short number is a part of an address, never of a phone or an IIN`() {
        // 103 is in «Абая 103» only; no phone or IIN here holds it as a match target.
        assertEquals(listOf("ph_aigerim"), ids("103"))
        assertEquals(listOf("ph_erbol"), ids("286"))
        val onlyPhone = PharmacistSearchRow("ph_x", "Тест Тестов", "+77010001030", "111111111111", listOf("Аптека"))
        assertFalse(PharmacistSearch.matches(PharmacistSearch.parse("103"), onlyPhone))
        assertTrue(PharmacistSearch.matches(PharmacistSearch.parse("1030"), onlyPhone))
    }

    @Test fun `a name and a number can be asked together`() {
        assertEquals(listOf("ph_aigerim"), ids("Айгерим 4567"))
        assertEquals(emptyList<String>(), ids("Ербол 4567"))
    }

    @Test fun `no phone on the card means a number finds nothing there`() {
        assertEquals(emptyList<String>(), ids("8701"))
    }

    @Test fun `no more than eight words are read`() {
        val query = PharmacistSearch.parse("а б в г д е ж з и й")
        assertEquals(PharmacistSearch.MAX_TERMS, query.terms.size)
    }
}
