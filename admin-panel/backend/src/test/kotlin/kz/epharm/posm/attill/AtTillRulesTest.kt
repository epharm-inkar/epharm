package kz.epharm.posm.attill

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/** Who is at a till right now: the presence window, one person per till, the stored form. Pure rules, no database. */
class AtTillRulesTest {
    private val window = Duration.ofMinutes(20)
    private val now = Instant.parse("2026-11-26T09:00:00Z")
    private fun minutes(n: Long): Instant = now.plus(Duration.ofMinutes(n))

    private fun person(seen: Instant, id: String = "ph_a", pharmacy: String = "abay", device: String = "KASSA1", since: Instant = seen) =
        TillPerson(id, pharmacy, device, since, seen)

    @Test fun `the window setting is kept between five minutes and three hours`() {
        assertEquals(Duration.ofMinutes(20), AtTillRules.window(20))
        assertEquals(Duration.ofMinutes(5), AtTillRules.window(1))
        assertEquals(Duration.ofMinutes(5), AtTillRules.window(-3))
        assertEquals(Duration.ofMinutes(180), AtTillRules.window(100_000))
        assertEquals(20, AtTillRules.DEFAULT_WINDOW_MINUTES)
    }

    @Test fun `a till is keyed by pharmacy and computer name without regard to case`() {
        assertEquals(AtTillRules.tillKey("abay", "KASSA1"), AtTillRules.tillKey("abay", "kassa1"))
        assertNotEquals(AtTillRules.tillKey("abay", "KASSA1"), AtTillRules.tillKey("tole", "KASSA1"))
        // The same name in two pharmacies is two tills (KASSA1 is everywhere).
        assertNotEquals(AtTillRules.tillKey("abay", "KASSA1"), AtTillRules.tillKey("abay", "KASSA2"))
        assertEquals("\u001Fkassa1", AtTillRules.tillKey(null, "KASSA1"))
    }

    @Test fun `a person named at once is at the till from now`() {
        val first = AtTillRules.next(null, "ph_a", "abay", "KASSA1", now, window)
        assertEquals(now, first.since)
        assertEquals(now, first.seenAt)
    }

    @Test fun `the same person keeps since while they keep being named, even across the 15 minute check`() {
        val first = AtTillRules.next(null, "ph_a", "abay", "KASSA1", now, window)
        val second = AtTillRules.next(first, "ph_a", "abay", "KASSA1", minutes(15), window)
        assertEquals(now, second.since)
        assertEquals(minutes(15), second.seenAt)
        val third = AtTillRules.next(second, "ph_a", "abay", "KASSA1", minutes(30), window)
        assertEquals(now, third.since)
    }

    @Test fun `after a gap longer than the window the same person starts a new stretch`() {
        val first = AtTillRules.next(null, "ph_a", "abay", "KASSA1", now, window)
        val later = AtTillRules.next(first, "ph_a", "abay", "KASSA1", minutes(21), window)
        assertEquals(minutes(21), later.since)
    }

    @Test fun `a colleague who signs in takes the till over and starts their own stretch`() {
        val first = AtTillRules.next(null, "ph_a", "abay", "KASSA1", now, window)
        val colleague = AtTillRules.next(first, "ph_b", "abay", "KASSA1", minutes(5), window)
        assertEquals("ph_b", colleague.pharmacistId)
        assertEquals(minutes(5), colleague.since)
    }

    @Test fun `the time never goes back when two server instances disagree`() {
        val first = AtTillRules.next(null, "ph_a", "abay", "KASSA1", minutes(10), window)
        val behind = AtTillRules.next(first, "ph_a", "abay", "KASSA1", minutes(9), window)
        assertEquals(minutes(10), behind.seenAt)
        assertEquals(minutes(10), behind.since)
    }

    @Test fun `at a till means the till is online and its person was named within the window`() {
        val online = setOf(AtTillRules.tillKey("abay", "KASSA1"))
        assertTrue(AtTillRules.isAtTill(person(minutes(-19)), online, now, window))
        // Exactly on the edge still counts; a minute past it does not.
        assertTrue(AtTillRules.isAtTill(person(minutes(-20)), online, now, window))
        assertFalse(AtTillRules.isAtTill(person(minutes(-21)), online, now, window))
        // Named a moment ago, but the till has gone offline (shift over, computer off).
        assertFalse(AtTillRules.isAtTill(person(minutes(-1)), emptySet(), now, window))
        assertFalse(AtTillRules.isAtTill(person(minutes(-1), pharmacy = "tole"), online, now, window))
    }

    @Test fun `the computer name is matched without regard to case when the till is online`() {
        val online = setOf(AtTillRules.tillKey("abay", "kassa1"))
        assertTrue(AtTillRules.isAtTill(person(minutes(-1), device = "KASSA1"), online, now, window))
    }

    @Test fun `the newer of two records of one till stands`() {
        val old = person(minutes(-10))
        val fresh = person(minutes(-2), id = "ph_b")
        assertEquals(fresh, AtTillRules.newest(old, fresh))
        assertEquals(fresh, AtTillRules.newest(fresh, old))
        assertEquals(old, AtTillRules.newest(old, null))
        assertEquals(old, AtTillRules.newest(null, old))
        assertNull(AtTillRules.newest(null, null))
    }

    @Test fun `a record survives being stored and read back, a damaged one is no record`() {
        val record = person(minutes(-3), since = minutes(-40))
        assertEquals(record, AtTillRules.decode(AtTillRules.encode(record)))
        assertNull(AtTillRules.decode(null))
        assertNull(AtTillRules.decode(""))
        assertNull(AtTillRules.decode("ph_a\u001Fabay\u001FKASSA1\u001F1\u001F"))
        assertNull(AtTillRules.decode("ph_a\u001Fabay\u001FKASSA1\u001Fx\u001F2"))
        assertNull(AtTillRules.decode("ph_a\u001F\u001FKASSA1\u001F1\u001F2"))
        assertNull(AtTillRules.decode("ph_a\u001Fabay\u001FKASSA1\u001F1\u001F2\u001Fextra"))
    }

    @Test fun `the phone tail is the last four digits or nothing`() {
        assertEquals("4567", AtTillPerson.phoneTail("+7 (701) 123-45-67"))
        assertEquals("4567", AtTillPerson.phoneTail("87011234567"))
        assertNull(AtTillPerson.phoneTail(""))
        assertNull(AtTillPerson.phoneTail("12"))
        assertNull(AtTillPerson.phoneTail(null))
    }
}
