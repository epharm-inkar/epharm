package kz.epharm.training.tasks

import kz.epharm.training.domain.TrainingAssignmentStatus
import kz.epharm.training.domain.TrainingAssignmentStatus.cancelled
import kz.epharm.training.domain.TrainingAssignmentStatus.completed
import kz.epharm.training.domain.TrainingAssignmentStatus.in_progress
import kz.epharm.training.domain.TrainingAssignmentStatus.not_started
import kz.epharm.training.domain.TrainingAssignmentStatus.overdue
import kz.epharm.training.domain.TrainingAssignmentStatus.paused
import kz.epharm.training.domain.TrainingAssignmentStatus.retake_required
import kz.epharm.training.domain.TrainingAssignmentStatus.scheduled
import kz.epharm.training.domain.TrainingAssignmentStatus.waiting_event_selection
import kz.epharm.training.domain.TrainingAssignmentStatus.waiting_online
import kz.epharm.training.domain.TrainingPriority
import kz.epharm.training.tasks.PharmacistTaskStatus.DONE
import kz.epharm.training.tasks.PharmacistTaskStatus.IN_PROGRESS
import kz.epharm.training.tasks.PharmacistTaskStatus.NEW
import kz.epharm.training.tasks.PharmacistTaskStatus.OVERDUE
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

class PharmacistTaskFeedTest {
    private val now = Instant.parse("2026-11-25T08:00:00Z")
    private fun days(n: Long): Instant = now.plus(Duration.ofDays(n))

    private fun row(
        status: TrainingAssignmentStatus,
        id: Int = 1,
        startedAt: Instant? = null,
        progress: Int = 0,
        startsAt: Instant? = null,
        dueAt: Instant? = null,
        completedAt: Instant? = null,
        updatedAt: Instant = days(-1),
        required: Boolean = true,
        priority: TrainingPriority = TrainingPriority.normal,
        name: String = "Курс $id",
        shortDescription: String = "",
        description: String = "",
        bonus: Long = 0,
    ) = PharmacistTaskRow(
        assignmentId = UUID(0, id.toLong()),
        status = status,
        required = required,
        priority = priority,
        progressPct = progress,
        startsAt = startsAt,
        dueAt = dueAt,
        startedAt = startedAt,
        completedAt = completedAt,
        createdAt = days(-30),
        updatedAt = updatedAt,
        programName = name,
        programShortDescription = shortDescription,
        programDescription = description,
        completionBonusKzt = bonus,
    )

    private fun status(row: PharmacistTaskRow) = PharmacistTaskFeed.status(row, now)

    @Test fun `not started and never opened is new, whatever waiting it starts with`() {
        assertEquals(NEW, status(row(not_started)))
        assertEquals(NEW, status(row(scheduled, startsAt = days(-1))))
        assertEquals(NEW, status(row(waiting_event_selection)))
    }

    @Test fun `opened or with any progress is in progress`() {
        assertEquals(IN_PROGRESS, status(row(not_started, startedAt = days(-2))))
        assertEquals(IN_PROGRESS, status(row(not_started, progress = 10)))
        assertEquals(IN_PROGRESS, status(row(waiting_event_selection, startedAt = days(-2))))
    }

    @Test fun `waiting, retake and paused statuses are in progress`() {
        val inProgress = TrainingAssignmentStatus.entries.filter { it.name.startsWith("waiting_") && it != waiting_event_selection } +
            listOf(in_progress, retake_required, paused)
        inProgress.forEach { assertEquals(IN_PROGRESS, status(row(it, startedAt = days(-3))), it.name) }
        // Never opened, but an explicit in-progress status is not «new».
        assertEquals(IN_PROGRESS, status(row(waiting_online)))
        assertEquals(IN_PROGRESS, status(row(paused)))
    }

    @Test fun `overdue status or a due date in the past is overdue, except for paused`() {
        assertEquals(OVERDUE, status(row(overdue)))
        assertEquals(OVERDUE, status(row(not_started, dueAt = days(-1))))
        assertEquals(OVERDUE, status(row(waiting_online, startedAt = days(-5), dueAt = days(-1))))
        assertEquals(OVERDUE, status(row(retake_required, dueAt = now.minusSeconds(1))))
        assertEquals(IN_PROGRESS, status(row(paused, dueAt = days(-1))))
        assertEquals(NEW, status(row(not_started, dueAt = days(1))))
    }

    @Test fun `completed shows as done for 14 days, then leaves the feed`() {
        assertEquals(DONE, status(row(completed, completedAt = days(-13))))
        assertEquals(DONE, status(row(completed, completedAt = days(-14))))
        assertNull(status(row(completed, completedAt = days(-14).minusSeconds(1))))
        assertNull(status(row(completed, completedAt = days(-40))))
        // A past due date does not make a finished task overdue.
        assertEquals(DONE, status(row(completed, completedAt = days(-2), dueAt = days(-5))))
        // Legacy rows without completed_at fall back to their last update.
        assertEquals(DONE, status(row(completed, completedAt = null, updatedAt = days(-3))))
        assertNull(status(row(completed, completedAt = null, updatedAt = days(-20))))
    }

    @Test fun `cancelled and not yet started by HQ are hidden`() {
        assertNull(status(row(cancelled)))
        assertNull(status(row(cancelled, dueAt = days(-3))))
        assertNull(status(row(scheduled, startsAt = days(2))))
        assertNull(status(row(not_started, startsAt = now.plusSeconds(60), dueAt = days(-1))))
        assertEquals(NEW, status(row(scheduled, startsAt = now)))
    }

    @Test fun `every assignment status maps without an error`() {
        TrainingAssignmentStatus.entries.forEach { PharmacistTaskFeed.status(row(it, completedAt = days(-1)), now) }
    }

    @Test fun `order is overdue, new, in progress by due date, then done latest first`() {
        val feed = PharmacistTaskFeed.build(
            listOf(
                row(completed, id = 1, completedAt = days(-10)),
                row(waiting_online, id = 2, startedAt = days(-4), dueAt = days(9)),
                row(not_started, id = 3),
                row(overdue, id = 4, dueAt = days(-1)),
                row(completed, id = 5, completedAt = days(-1)),
                row(not_started, id = 6, dueAt = days(3)),
                row(in_progress, id = 7, startedAt = days(-4), dueAt = days(2)),
                row(not_started, id = 8, dueAt = days(-7)),
                row(cancelled, id = 9),
                row(completed, id = 10, completedAt = days(-30)),
                row(scheduled, id = 11, startsAt = days(5)),
            ),
            now,
        )
        assertEquals(
            listOf(8, 4, 6, 3, 7, 2, 5, 1),
            feed.tasks.map { it.id.leastSignificantBits.toInt() },
        )
        assertEquals(listOf(OVERDUE, OVERDUE, NEW, NEW, IN_PROGRESS, IN_PROGRESS, DONE, DONE), feed.tasks.map { it.status })
        assertEquals(2, feed.newCount)
        assertEquals(6, feed.openCount)
    }

    @Test fun `same due date puts required and higher priority first`() {
        val feed = PharmacistTaskFeed.build(
            listOf(
                row(not_started, id = 1, dueAt = days(5), required = false, priority = TrainingPriority.critical),
                row(not_started, id = 2, dueAt = days(5), priority = TrainingPriority.low),
                row(not_started, id = 3, dueAt = days(5), priority = TrainingPriority.critical),
            ),
            now,
        )
        assertEquals(listOf(3, 2, 1), feed.tasks.map { it.id.leastSignificantBits.toInt() })
    }

    @Test fun `an empty feed has zero counts`() {
        val feed = PharmacistTaskFeed.build(emptyList(), now)
        assertTrue(feed.tasks.isEmpty())
        assertEquals(0, feed.newCount)
        assertEquals(0, feed.openCount)
        assertEquals(0, feed.requiredOpenCount)
    }

    @Test fun `the till's red dot counts required courses not finished yet`() {
        val feed = PharmacistTaskFeed.build(
            listOf(
                row(not_started, id = 1),
                row(waiting_online, id = 2, startedAt = days(-1), progress = 30),
                row(not_started, id = 3, dueAt = days(-2)),
                row(in_progress, id = 4, startedAt = days(-1), required = false),
                row(paused, id = 5, startedAt = days(-3)),
                row(completed, id = 6, completedAt = days(-1)),
                row(cancelled, id = 7),
                row(scheduled, id = 8, startsAt = days(3)),
            ),
            now,
        )
        // New, in progress and overdue required courses; not the optional one, the paused one, a finished,
        // a cancelled one, nor one HQ has not started yet.
        assertEquals(3, feed.requiredOpenCount)
        assertEquals(5, feed.openCount)
    }

    @Test fun `finishing the last required course turns the red dot off`() {
        val before = PharmacistTaskFeed.build(listOf(row(in_progress, id = 1, startedAt = days(-1), progress = 90)), now)
        assertEquals(1, before.requiredOpenCount)
        val after = PharmacistTaskFeed.build(listOf(row(completed, id = 1, completedAt = now, progress = 100)), now)
        assertEquals(0, after.requiredOpenCount)
        assertEquals(DONE, after.tasks.single().status)
    }

    @Test fun `task fields come from the assignment and its program version`() {
        val feed = PharmacistTaskFeed.build(
            listOf(
                row(completed, id = 1, name = "  Витамины  ", completedAt = days(-2), progress = 80, bonus = 1500, required = false),
                row(waiting_online, id = 2, startedAt = days(-1), progress = 140, dueAt = days(4), completedAt = days(-1)),
            ),
            now,
        )
        val active = feed.tasks[0]
        assertEquals("course", active.kind)
        assertEquals(100, active.progressPct)
        assertEquals(days(4), active.dueAt)
        assertNull(active.completedAt)
        assertNull(active.rewardKzt)
        val done = feed.tasks[1]
        assertEquals("Витамины", done.title)
        assertEquals(1500L, done.rewardKzt)
        assertEquals(100, done.progressPct)
        assertEquals(days(-2), done.completedAt)
        assertFalse(done.required)
    }

    @Test fun `instructions say where to take the course and add the program description`() {
        assertEquals(
            "Откройте «Мой кабинет», раздел «Обучение», курс «Витамины». Как продавать витамины группы B.",
            PharmacistTaskFeed.instructions("Витамины", "Как продавать витамины\n группы B.", "Длинное описание"),
        )
        assertEquals(
            "Откройте «Мой кабинет», раздел «Обучение», курс «Витамины». Длинное описание",
            PharmacistTaskFeed.instructions("Витамины", "  ", " Длинное   описание "),
        )
        assertEquals(
            "Откройте «Мой кабинет», раздел «Обучение», курс «Витамины».",
            PharmacistTaskFeed.instructions(" Витамины ", "", ""),
        )
        assertEquals(
            "Откройте «Мой кабинет», раздел «Обучение», курс «Аптека года».",
            PharmacistTaskFeed.instructions("«Аптека года»", "", ""),
        )
    }

    @Test fun `a long description is cut at a word with an ellipsis`() {
        val text = PharmacistTaskFeed.instructions("Курс", "", "слово ".repeat(100))
        val about = text.substringAfter("курс «Курс». ")
        assertTrue(about.length <= 301, "length ${about.length}")
        assertTrue(about.endsWith("слово…"), about.takeLast(20))
        assertFalse(text.contains('—') || text.contains('–'))
    }
}
