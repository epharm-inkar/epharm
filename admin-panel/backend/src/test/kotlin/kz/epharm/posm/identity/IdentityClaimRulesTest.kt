package kz.epharm.posm.identity

import kz.epharm.shared.error.AppException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** What a pharmacist may type about themselves: synthetic IINs and names only. */
class IdentityClaimRulesTest {
    // ── IIN ─────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = ["900115300013", "850615400016", "990303500014", "781122300017", "951212500015", "123456012344"])
    fun `a valid IIN passes`(iin: String) {
        assertNull(IdentityClaimRules.iinProblem(iin))
        assertEquals(iin, IdentityClaimRules.requireIin(iin))
    }

    @Test fun `outer spaces are cut and the IIN is returned clean`() {
        assertEquals("900115300013", IdentityClaimRules.requireIin("  900115300013 "))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "12345", "9001153000130", "90011530001a", "900115 300013", "000000000000"])
    fun `a malformed IIN is refused with the digits message`(iin: String) {
        assertTrue(IdentityClaimRules.iinProblem(iin)!!.contains("12 цифр") || iin == "000000000000")
        assertThrows(AppException::class.java) { IdentityClaimRules.requireIin(iin) }
    }

    @Test fun `twelve digits with a wrong control digit are refused with the control digit message`() {
        assertEquals("Проверьте ИИН: контрольная цифра не сходится", IdentityClaimRules.iinProblem("900115300010"))
        assertEquals("Проверьте ИИН: контрольная цифра не сходится", IdentityClaimRules.iinProblem("000000000000"))
    }

    @Test fun `digits of other scripts and a missing value are refused`() {
        assertNotNull(IdentityClaimRules.iinProblem("１２３４５６０１２３４４"))
        assertNotNull(IdentityClaimRules.iinProblem("١٢٣٤٥٦٠١٢٣٤٤"))
        assertNotNull(IdentityClaimRules.iinProblem(null))
        assertThrows(AppException::class.java) { IdentityClaimRules.requireIin(null) }
    }

    @Test fun `the IIN is masked for screens that show it back`() {
        assertEquals("900115•••••3", IdentityClaimRules.maskIin("900115300013"))
        assertNull(IdentityClaimRules.maskIin("12345"))
        assertNull(IdentityClaimRules.maskIin(null))
    }

    // ── full name ───────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = [
        "Ахметова Айгерим Нурлановна",
        "Нұрсая Әбдіқалық Серікқызы",
        "Ким Алексей",
        "Мухамедьярова-Ли Мадина Бекзатовна",
        "Д'Артаньян Шарль",
        "Aigerim Nurlanovna",
    ])
    fun `a full name of two or more words passes`(name: String) {
        assertNull(IdentityClaimRules.fullNameProblem(name))
        assertEquals(name, IdentityClaimRules.requireFullName(name))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   ", "Айгерим", "Бузурбаева", "А. Б.", "Иванов 2 Иван", "Иванов Иван 87771234567", "Иванов Иван!!!", "а б"])
    fun `a nickname, initials, digits and symbols are not a full name`(name: String) {
        assertNotNull(IdentityClaimRules.fullNameProblem(name))
        assertThrows(AppException::class.java) { IdentityClaimRules.requireFullName(name) }
    }

    @Test fun `seven words and a very long name are refused`() {
        assertNotNull(IdentityClaimRules.fullNameProblem("Аа Бб Вв Гг Дд Ее Жж"))
        assertEquals("Слишком длинное имя", IdentityClaimRules.fullNameProblem("Иванов " + "а".repeat(260)))
        assertNotNull(IdentityClaimRules.fullNameProblem(null))
    }

    @Test fun `spaces, tabs, non-breaking spaces and control characters collapse to single spaces`() {
        assertEquals("Иванов Иван Иванович", IdentityClaimRules.normalizeName("  Иванов\u00A0\tИван \n Иванович  "))
        assertEquals("Иванов Иван", IdentityClaimRules.normalizeName("Иванов\u200B Иван"))
        assertEquals("", IdentityClaimRules.normalizeName(null))
        assertEquals("Иванов Иван", IdentityClaimRules.requireFullName("  Иванов \u00A0 Иван "))
    }

    @Test fun `a name of fewer than two words is incomplete, whatever the words are`() {
        assertTrue(IdentityClaimRules.isIncompleteName("Бузурбаева"))
        assertTrue(IdentityClaimRules.isIncompleteName("  Лаура  "))
        assertTrue(IdentityClaimRules.isIncompleteName(""))
        assertTrue(IdentityClaimRules.isIncompleteName(null))
        assertFalse(IdentityClaimRules.isIncompleteName("Бузурбаева Лаура"))
        // Initials still count as words: only the number of words decides what the cabinet offers.
        assertFalse(IdentityClaimRules.isIncompleteName("Иванова А."))
        assertEquals(3, IdentityClaimRules.wordCount("Иванов Иван Иванович"))
    }

    // ── Standard-N user ─────────────────────────────────────────────────────

    @Test fun `the Standard-N user id is a whole positive number without leading zeros`() {
        assertEquals("18", IdentityClaimRules.requireUserId(" 18 "))
        assertEquals("18", IdentityClaimRules.requireUserId("0018"))
        assertEquals("999999999999999", IdentityClaimRules.requireUserId("999999999999999"))
        for (bad in listOf("", "0", "-5", "1.5", "abc", "1234567890123456", null, "１８")) {
            assertNull(IdentityClaimRules.canonicalUserId(bad), "refused: $bad")
            assertThrows(AppException::class.java) { IdentityClaimRules.requireUserId(bad) }
        }
    }

    @Test fun `the user name from Standard-N is tidied and cut, never required`() {
        assertEquals("Бузурбаева Л", IdentityClaimRules.cleanUserName("  Бузурбаева \t Л "))
        assertNull(IdentityClaimRules.cleanUserName("   "))
        assertNull(IdentityClaimRules.cleanUserName(null))
        assertEquals(255, IdentityClaimRules.cleanUserName("я".repeat(400))!!.length)
    }
}
