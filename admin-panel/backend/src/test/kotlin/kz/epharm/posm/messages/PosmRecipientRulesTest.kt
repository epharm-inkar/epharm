package kz.epharm.posm.messages

import kz.epharm.posm.attill.AtTillPerson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Who a notice goes to when the people are chosen at sending: the pharmacists who have not finished a course, and any
 * audience narrowed to the people at a till now. Pure rules, no database.
 */
class PosmRecipientRulesTest {
    private val program = UUID.fromString("11111111-2222-3333-4444-555555555555")
    private val at = Instant.parse("2026-11-26T09:00:00Z")

    private fun here(id: String, till: String, home: String? = till) = AtTillPerson(
        pharmacistId = id, name = "Имя $id", phone = "", iin = "", homePharmacyId = home, homePharmacyName = "Аптека $home",
        tillPharmacyId = till, tillPharmacyName = "Аптека $till", tillCity = "Алматы", tillAddress = "Абая 1",
        deviceId = "KASSA-$id", since = at, seenAt = at,
    )

    private fun choice(
        audience: PosmMessageAudience,
        onlyAtTill: Boolean = false,
        pharmacies: List<String> = emptyList(),
        pharmacists: List<String> = emptyList(),
    ) = PosmAudienceChoice(audience, pharmacies, pharmacists, program.takeIf { audience == PosmMessageAudience.COURSE }, onlyAtTill)

    private val atTill = listOf(here("ph_a", "abay"), here("ph_b", "abay"), here("ph_c", "tole", home = "abay"), here("ph_d", "tole"))

    @Test fun `without a course and without the switch the audience is judged live and nothing is kept`() {
        for (audience in listOf(PosmMessageAudience.ALL, PosmMessageAudience.PHARMACIES, PosmMessageAudience.PHARMACISTS)) {
            val c = choice(audience, pharmacies = listOf("abay"), pharmacists = listOf("ph_a"))
            assertFalse(c.frozen)
            assertNull(PosmRecipientRules.frozenIds(c, emptySet(), atTill))
        }
    }

    @Test fun `a course goes to the people who have not finished it, sorted, whether or not they are at a till`() {
        val c = choice(PosmMessageAudience.COURSE)
        assertTrue(c.frozen)
        assertEquals(listOf("ph_a", "ph_x", "ph_z"), PosmRecipientRules.frozenIds(c, setOf("ph_z", "ph_a", "ph_x"), atTill))
        assertEquals(emptyList<String>(), PosmRecipientRules.frozenIds(c, emptySet(), atTill))
    }

    @Test fun `a course with the switch goes only to those of them who are at a till now`() {
        val c = choice(PosmMessageAudience.COURSE, onlyAtTill = true)
        // ph_x and ph_z have not finished the course but are not at a till; ph_b finished it.
        val open = setOf("ph_a", "ph_c", "ph_x", "ph_z")
        assertEquals(listOf("ph_a", "ph_c"), PosmRecipientRules.frozenIds(c, open, atTill))
    }

    @Test fun `everybody with the switch is everybody at a till now`() {
        val c = choice(PosmMessageAudience.ALL, onlyAtTill = true)
        assertTrue(c.frozen)
        assertEquals(listOf("ph_a", "ph_b", "ph_c", "ph_d"), PosmRecipientRules.frozenIds(c, emptySet(), atTill))
    }

    @Test fun `pharmacies with the switch take who sits at a chosen till and the staff of a chosen pharmacy wherever they sit`() {
        val c = choice(PosmMessageAudience.PHARMACIES, onlyAtTill = true, pharmacies = listOf("abay"))
        // ph_a and ph_b sit at the till of abay; ph_c is abay staff at the till of tole; ph_d is tole staff at tole.
        assertEquals(listOf("ph_a", "ph_b", "ph_c"), PosmRecipientRules.frozenIds(c, emptySet(), atTill))
        val tole = choice(PosmMessageAudience.PHARMACIES, onlyAtTill = true, pharmacies = listOf("tole"))
        assertEquals(listOf("ph_c", "ph_d"), PosmRecipientRules.frozenIds(tole, emptySet(), atTill))
    }

    @Test fun `a person without a home pharmacy is found only by the till they sit at`() {
        val visitor = here("ph_v", "abay", home = null)
        val c = choice(PosmMessageAudience.PHARMACIES, onlyAtTill = true, pharmacies = listOf("abay"))
        assertEquals(listOf("ph_v"), PosmRecipientRules.frozenIds(c, emptySet(), listOf(visitor)))
        val other = choice(PosmMessageAudience.PHARMACIES, onlyAtTill = true, pharmacies = listOf("tole"))
        assertEquals(emptyList<String>(), PosmRecipientRules.frozenIds(other, emptySet(), listOf(visitor)))
    }

    @Test fun `chosen people with the switch are those of them who are at a till now`() {
        val c = choice(PosmMessageAudience.PHARMACISTS, onlyAtTill = true, pharmacists = listOf("ph_b", "ph_d", "ph_gone"))
        assertEquals(listOf("ph_b", "ph_d"), PosmRecipientRules.frozenIds(c, emptySet(), atTill))
    }

    @Test fun `nobody at a till is an empty list, not a missing one`() {
        val c = choice(PosmMessageAudience.ALL, onlyAtTill = true)
        assertEquals(emptyList<String>(), PosmRecipientRules.frozenIds(c, emptySet(), emptyList()))
    }

    @Test fun `the count of people at a till counts the audience, with or without the switch`() {
        assertEquals(4, PosmRecipientRules.atTillNow(choice(PosmMessageAudience.ALL), emptySet(), atTill))
        assertEquals(3, PosmRecipientRules.atTillNow(choice(PosmMessageAudience.PHARMACIES, pharmacies = listOf("abay")), emptySet(), atTill))
        assertEquals(1, PosmRecipientRules.atTillNow(choice(PosmMessageAudience.PHARMACISTS, pharmacists = listOf("ph_a", "ph_zz")), emptySet(), atTill))
        assertEquals(2, PosmRecipientRules.atTillNow(choice(PosmMessageAudience.COURSE), setOf("ph_a", "ph_d", "ph_x"), atTill))
        assertEquals(0, PosmRecipientRules.atTillNow(choice(PosmMessageAudience.ALL), emptySet(), emptyList()))
    }

    @Test fun `a frozen notice goes to the people kept with it and to nobody else`() {
        val kept = PosmMessageTarget(PosmMessageAudience.ALL, emptySet(), emptySet(), recipientIds = setOf("ph_a"))
        assertTrue(PosmMessageRules.isFor(kept, "ph_a", "abay", "abay"))
        // A notice sent to everybody at a till now does not reach someone who sits down later.
        assertFalse(PosmMessageRules.isFor(kept, "ph_b", "abay", "abay"))
        val course = PosmMessageTarget(PosmMessageAudience.COURSE, emptySet(), emptySet(), recipientIds = setOf("ph_a"))
        assertTrue(PosmMessageRules.isFor(course, "ph_a", null, "abay"))
        assertFalse(PosmMessageRules.isFor(course, "ph_b", null, "abay"))
        // A course notice without its list reaches nobody: no list, no audience.
        assertFalse(PosmMessageRules.isFor(PosmMessageTarget(PosmMessageAudience.COURSE, emptySet(), emptySet()), "ph_a", null, "abay"))
    }
}
