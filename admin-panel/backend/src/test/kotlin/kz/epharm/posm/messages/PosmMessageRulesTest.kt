package kz.epharm.posm.messages

import kz.epharm.shared.error.AppException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Notices from HQ at the till: what HQ may send, who gets it, what a till shows. Pure rules, no database. */
class PosmMessageRulesTest {
    private val now = Instant.parse("2026-11-25T09:00:00Z")
    private fun days(n: Long): Instant = now.plus(Duration.ofDays(n))

    private fun request(
        title: String? = "Инвентаризация",
        text: String? = "Завтра в 9:00 инвентаризация, приходите на 15 минут раньше.",
        audience: String? = "all",
        pharmacyIds: List<String>? = null,
        pharmacistIds: List<String>? = null,
        days: Int? = null,
    ) = CreatePosmMessageRequest(title, text, audience, pharmacyIds, pharmacistIds, days)

    private fun invalid(block: () -> Unit): String = assertThrows<AppException> { block() }.let {
        assertEquals(400, it.status.value())
        it.message.orEmpty()
    }

    @Test fun `a notice for everybody needs only a title and a text and lives seven days`() {
        val draft = PosmMessageRules.draft(request(pharmacyIds = listOf("ignored")))
        assertEquals(PosmMessageAudience.ALL, draft.audience)
        assertEquals("Инвентаризация", draft.title)
        assertTrue(draft.pharmacyIds.isEmpty())
        assertTrue(draft.pharmacistIds.isEmpty())
        assertEquals(7, draft.days)
        assertEquals(days(7), PosmMessageRules.expiresAt(now, draft.days))
    }

    @Test fun `pharmacies and pharmacists need a choice, trimmed and without repeats`() {
        val pharmacies = PosmMessageRules.draft(request(audience = "pharmacies", pharmacyIds = listOf(" ph_2 ", "ph_1", "ph_2", " ")))
        assertEquals(listOf("ph_2", "ph_1"), pharmacies.pharmacyIds)
        assertTrue(pharmacies.pharmacistIds.isEmpty())
        val people = PosmMessageRules.draft(request(audience = "pharmacists", pharmacistIds = listOf("ph_a"), pharmacyIds = listOf("ph_1")))
        assertEquals(listOf("ph_a"), people.pharmacistIds)
        assertTrue(people.pharmacyIds.isEmpty())

        assertEquals("Выберите хотя бы одну аптеку", invalid { PosmMessageRules.draft(request(audience = "pharmacies")) })
        assertEquals("Выберите хотя бы одного сотрудника", invalid { PosmMessageRules.draft(request(audience = "pharmacists", pharmacistIds = listOf(" "))) })
        invalid { PosmMessageRules.draft(request(audience = "pharmacies", pharmacyIds = listOf("x".repeat(65)))) }
        invalid { PosmMessageRules.draft(request(audience = "pharmacists", pharmacistIds = (1..2001).map { "p$it" })) }
    }

    @Test fun `only the three audiences are accepted`() {
        for (raw in listOf(null, "", "ALL", "pharmacy", "everyone")) invalid { PosmMessageRules.draft(request(audience = raw)) }
        assertEquals(PosmMessageAudience.ALL, PosmMessageRules.draft(request(audience = " all ")).audience)
    }

    @Test fun `the title is one clean line of at most 120 characters`() {
        assertEquals("Новый курс по витаминам", PosmMessageRules.title("  Новый\tкурс \n по\u0000 витаминам​  "))
        assertEquals("Введите заголовок", invalid { PosmMessageRules.title(" \n ") })
        assertEquals(120, PosmMessageRules.title("я".repeat(120)).length)
        invalid { PosmMessageRules.title("я".repeat(121)) }
    }

    @Test fun `the text keeps paragraphs, drops control characters and is limited to 1000`() {
        val text = PosmMessageRules.text("  Коллеги!\r\n\r\n\r\n\r\nЗавтра   проверка.\u0007\n  В 9:00.  \n\n")
        assertEquals("Коллеги!\n\nЗавтра проверка.\nВ 9:00.", text)
        assertEquals("Введите текст уведомления", invalid { PosmMessageRules.text("\n\t \n") })
        assertEquals(1000, PosmMessageRules.text("а".repeat(1000)).length)
        invalid { PosmMessageRules.text("а".repeat(1001)) }
        // Kazakh letters and emoji count as one character each.
        assertEquals("Сәлем 👋", PosmMessageRules.text("Сәлем 👋"))
    }

    @Test fun `days are between 1 and 30`() {
        assertEquals(1, PosmMessageRules.days(1))
        assertEquals(30, PosmMessageRules.days(30))
        assertEquals(7, PosmMessageRules.days(null))
        invalid { PosmMessageRules.days(0) }
        invalid { PosmMessageRules.days(31) }
    }

    @Test fun `who gets a notice`() {
        val all = PosmMessageTarget(PosmMessageAudience.ALL, emptySet(), emptySet())
        val pharmacies = PosmMessageTarget(PosmMessageAudience.PHARMACIES, setOf("abay"), emptySet())
        val people = PosmMessageTarget(PosmMessageAudience.PHARMACISTS, emptySet(), setOf("ph_aigerim"))

        assertTrue(PosmMessageRules.isFor(all, "ph_any", null, "any-till"))
        // At a till of the chosen pharmacy, whoever signs in.
        assertTrue(PosmMessageRules.isFor(pharmacies, "ph_floater", "tole-bi", "abay"))
        // Staff of the chosen pharmacy working elsewhere today.
        assertTrue(PosmMessageRules.isFor(pharmacies, "ph_staff", "abay", "tole-bi"))
        assertFalse(PosmMessageRules.isFor(pharmacies, "ph_other", "tole-bi", "tole-bi"))
        assertFalse(PosmMessageRules.isFor(pharmacies, "ph_new", null, "tole-bi"))
        // Chosen people at any till, nobody else at the same till.
        assertTrue(PosmMessageRules.isFor(people, "ph_aigerim", "tole-bi", "abay"))
        assertFalse(PosmMessageRules.isFor(people, "ph_colleague", "abay", "abay"))
    }

    @Test fun `a notice is active until its last day or until HQ withdraws it`() {
        assertTrue(PosmMessageRules.isActive(days(1), null, now))
        assertFalse(PosmMessageRules.isActive(now, null, now))
        assertFalse(PosmMessageRules.isActive(days(1), now, now))
    }

    private fun row(id: Int, sentDaysAgo: Long, expiresIn: Long = 5, closedAt: Instant? = null) = PosmPersonMessageRow(
        id = UUID(0, id.toLong()),
        title = "Уведомление $id",
        text = "Текст $id",
        createdAt = days(-sentDaysAgo),
        expiresAt = days(expiresIn),
        shownAt = closedAt,
        closedAt = closedAt,
    )

    @Test fun `the till gets open notices and the history, newest first`() {
        val view = PosmMessageRules.forPerson(
            listOf(
                row(1, sentDaysAgo = 3, closedAt = days(-2)),
                row(2, sentDaysAgo = 1),
                row(3, sentDaysAgo = 10, expiresIn = -3),
                row(4, sentDaysAgo = 40, expiresIn = 1),
                row(5, sentDaysAgo = 2),
            ),
            now,
        )
        assertEquals(listOf(2, 5, 1, 3), view.messages.map { it.id.leastSignificantBits.toInt() })
        assertEquals(2, view.pendingCount)
        assertEquals(listOf(true, true, false, false), view.messages.map { it.pending })
        // Past its last day and never closed: history only, not shown again.
        val expired = view.messages.single { it.id.leastSignificantBits == 3L }
        assertFalse(expired.pending)
        assertEquals(null, expired.closedAt)
        assertEquals(days(-2), view.messages.single { it.id.leastSignificantBits == 1L }.closedAt)
    }

    @Test fun `the history keeps at most 20, but never drops an open notice`() {
        val closed = (1..30).map { row(it, sentDaysAgo = 1, closedAt = days(-1)) }
        val open = (31..35).map { row(it, sentDaysAgo = 25) }
        val view = PosmMessageRules.forPerson(closed + open, now)
        assertEquals(20, view.messages.size)
        assertEquals(5, view.pendingCount)
        assertEquals(5, view.messages.count { it.pending })

        val many = (1..25).map { row(it, sentDaysAgo = 2) }
        val all = PosmMessageRules.forPerson(many, now)
        assertEquals(25, all.messages.size)
        assertEquals(25, all.pendingCount)
    }

    @Test fun `nothing sent means an empty list`() {
        val view = PosmMessageRules.forPerson(emptyList(), now)
        assertTrue(view.messages.isEmpty())
        assertEquals(0, view.pendingCount)
    }

    @Test fun `list limit is bounded`() {
        assertEquals(50, PosmMessageRules.listLimit(null))
        assertEquals(1, PosmMessageRules.listLimit(0))
        assertEquals(200, PosmMessageRules.listLimit(5000))
    }
}
