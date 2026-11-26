package kz.epharm.posm.messages

import kz.epharm.shared.error.AppException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Notices from HQ at the till: what HQ may send, who gets it, what a till shows. Pure rules, no database. */
class PosmMessageRulesTest {
    private val now = Instant.parse("2026-11-25T09:00:00Z")
    private val PROGRAM = "0b7a1f2e-3c4d-4e5f-8a9b-0c1d2e3f4a5b"
    private fun days(n: Long): Instant = now.plus(Duration.ofDays(n))

    private fun request(
        title: String? = "Инвентаризация",
        text: String? = "Завтра в 9:00 инвентаризация, приходите на 15 минут раньше.",
        audience: String? = "all",
        pharmacyIds: List<String>? = null,
        pharmacistIds: List<String>? = null,
        days: Int? = null,
        programId: String? = null,
        onlyAtTill: Boolean? = null,
    ) = CreatePosmMessageRequest(title, text, audience, pharmacyIds, pharmacistIds, days, programId, onlyAtTill)

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

    @Test fun `only the four audiences are accepted`() {
        for (raw in listOf(null, "", "ALL", "pharmacy", "everyone", "Course", "courses")) invalid { PosmMessageRules.draft(request(audience = raw)) }
        assertEquals(PosmMessageAudience.ALL, PosmMessageRules.draft(request(audience = " all ")).audience)
        assertEquals(PosmMessageAudience.COURSE, PosmMessageRules.draft(request(audience = "course", programId = PROGRAM)).audience)
    }

    @Test fun `a course reminder needs a course, written as a UUID, and ignores chosen pharmacies and people`() {
        val draft = PosmMessageRules.draft(
            request(audience = "course", programId = " $PROGRAM ", pharmacyIds = listOf("ph_1"), pharmacistIds = listOf("ph_a")),
        )
        assertEquals(UUID.fromString(PROGRAM), draft.programId)
        assertTrue(draft.pharmacyIds.isEmpty())
        assertTrue(draft.pharmacistIds.isEmpty())
        assertFalse(draft.onlyAtTill)
        assertTrue(draft.choice.frozen)

        assertEquals("Выберите курс", invalid { PosmMessageRules.draft(request(audience = "course")) })
        assertEquals("Выберите курс", invalid { PosmMessageRules.draft(request(audience = "course", programId = "  ")) })
        for (raw in listOf("not-a-uuid", "1-1-1-1-1", "${PROGRAM}0", "{$PROGRAM}", "'; DROP TABLE posm_messages; --")) {
            assertEquals("programId: неверный идентификатор курса", invalid { PosmMessageRules.draft(request(audience = "course", programId = raw)) })
        }
        // A course given to another audience is not read.
        assertNull(PosmMessageRules.draft(request(audience = "all", programId = PROGRAM)).programId)
    }

    @Test fun `the switch only at a till goes with any audience and freezes the people`() {
        for (audience in listOf("all", "pharmacies", "pharmacists", "course")) {
            val choice = PosmMessageRules.choice(
                request(audience = audience, pharmacyIds = listOf("ph_1"), pharmacistIds = listOf("ph_a"), programId = PROGRAM, onlyAtTill = true),
            )
            assertTrue(choice.onlyAtTill)
            assertTrue(choice.frozen)
        }
        val plain = PosmMessageRules.choice(request(audience = "all"))
        assertFalse(plain.onlyAtTill)
        assertFalse(plain.frozen)
        assertFalse(PosmMessageRules.choice(request(audience = "all", onlyAtTill = false)).frozen)
        assertTrue(PosmMessageRules.draft(request(audience = "all", onlyAtTill = true)).onlyAtTill)
    }

    @Test fun `the count before sending needs no title and no text, only the people`() {
        val choice = PosmMessageRules.choice(request(title = null, text = null, audience = "pharmacists", pharmacistIds = listOf("ph_a")))
        assertEquals(listOf("ph_a"), choice.pharmacistIds)
        // The same checks as for the send.
        assertEquals("Выберите хотя бы одного сотрудника", invalid { PosmMessageRules.choice(request(title = null, text = null, audience = "pharmacists")) })
        invalid { PosmMessageRules.choice(request(audience = "nobody")) }
    }

    @Test fun `the search shows 50 people by default and at most 100`() {
        assertEquals(50, PosmMessageRules.searchLimit(null))
        assertEquals(1, PosmMessageRules.searchLimit(0))
        assertEquals(100, PosmMessageRules.searchLimit(10_000))
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

    @Test fun `a Kazakh title and text go together and are cleaned like the Russian ones`() {
        val none = PosmMessageRules.draft(request())
        assertNull(none.titleKk)
        assertNull(none.textKk)
        assertNull(PosmMessageRules.draft(request().copy(titleKk = "  ", textKk = "")).titleKk)

        val both = PosmMessageRules.draft(request().copy(titleKk = "  Түгендеу\u0000 ", textKk = "Ертең  9:00-де.\r\n\r\n\r\nКеліңіз. "))
        assertEquals("Түгендеу", both.titleKk)
        assertEquals("Ертең 9:00-де.\n\nКеліңіз.", both.textKk)
        // The Russian ones are what they were.
        assertEquals("Инвентаризация", both.title)

        assertEquals(
            "Введите текст на казахском или очистите казахский заголовок",
            invalid { PosmMessageRules.draft(request().copy(titleKk = "Түгендеу")) },
        )
        assertEquals(
            "Введите заголовок на казахском или очистите казахский текст",
            invalid { PosmMessageRules.draft(request().copy(textKk = "Мәтін")) },
        )
        assertEquals(
            "Заголовок на казахском до 120 знаков",
            invalid { PosmMessageRules.draft(request().copy(titleKk = "ә".repeat(121), textKk = "Мәтін")) },
        )
        assertEquals(
            "Текст на казахском до 1000 знаков",
            invalid { PosmMessageRules.draft(request().copy(titleKk = "Тақырып", textKk = "ә".repeat(1001))) },
        )
        // Only invisible characters: as empty as no title at all.
        assertEquals(
            "Введите заголовок на казахском",
            invalid { PosmMessageRules.draft(request().copy(titleKk = "\u200B\u200B", textKk = "Мәтін")) },
        )
    }

    @Test fun `a person who chose Kazakh reads the Kazakh text, everybody else the Russian one`() {
        val ru = "Инвентаризация" to "Завтра в 9:00."
        val kk = "Түгендеу" to "Ертең сағат 9:00-де."
        assertEquals(kk, PosmMessageRules.localized("kk", ru.first, ru.second, kk.first, kk.second))
        assertEquals(ru, PosmMessageRules.localized("ru", ru.first, ru.second, kk.first, kk.second))
        // Not chosen yet: Russian.
        assertEquals(ru, PosmMessageRules.localized(null, ru.first, ru.second, kk.first, kk.second))
        // HQ wrote no Kazakh text: Russian for everybody.
        assertEquals(ru, PosmMessageRules.localized("kk", ru.first, ru.second, null, null))
        assertEquals(ru, PosmMessageRules.localized("kk", ru.first, ru.second, " ", kk.second))
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
