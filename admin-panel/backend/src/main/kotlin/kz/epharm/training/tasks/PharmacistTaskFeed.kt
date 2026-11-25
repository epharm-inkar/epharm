package kz.epharm.training.tasks

import com.fasterxml.jackson.annotation.JsonValue
import kz.epharm.training.domain.TrainingAssignmentStatus
import kz.epharm.training.domain.TrainingPriority
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** What the pharmacist sees on a task: four states instead of the fifteen assignment statuses. */
enum class PharmacistTaskStatus(@get:JsonValue val wire: String) {
    NEW("new"),
    IN_PROGRESS("in_progress"),
    DONE("done"),
    OVERDUE("overdue"),
}

data class PharmacistTaskDto(
    /** The training assignment id. */
    val id: UUID,
    val kind: String,
    val title: String,
    val instructions: String,
    /** The completion bonus credited to the wallet when the course is done; null when the program pays none. */
    val rewardKzt: Long?,
    val required: Boolean,
    val status: PharmacistTaskStatus,
    val progressPct: Int,
    val dueAt: Instant?,
    val completedAt: Instant?,
)

data class PharmacistTaskFeedDto(
    val tasks: List<PharmacistTaskDto>,
    /** Tasks the person has not opened yet. */
    val newCount: Int,
    /** Tasks not done yet: new, in progress and overdue. */
    val openCount: Int,
)

/** One training assignment with its program, as the feed reads it from the database. */
data class PharmacistTaskRow(
    val assignmentId: UUID,
    val status: TrainingAssignmentStatus,
    val required: Boolean,
    val priority: TrainingPriority,
    val progressPct: Int,
    val startsAt: Instant?,
    val dueAt: Instant?,
    val startedAt: Instant?,
    val completedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val programName: String,
    val programShortDescription: String,
    val programDescription: String,
    val completionBonusKzt: Long,
)

/**
 * A pharmacist's «задание» is an active training assignment (owner's decision 25.11.2026: no parallel task
 * system). Pure rules, no database: which assignments are shown, their state, order and the text of the task.
 */
object PharmacistTaskFeed {
    const val KIND_COURSE = "course"

    /** A finished task stays in the feed this long, so the person sees «выполнено». */
    val DONE_VISIBLE_FOR: Duration = Duration.ofDays(14)

    private const val DESCRIPTION_LIMIT = 300

    private val NOTHING_DONE_YET = setOf(
        TrainingAssignmentStatus.scheduled,
        TrainingAssignmentStatus.not_started,
        TrainingAssignmentStatus.waiting_event_selection,
    )

    private val GROUP_ORDER = listOf(
        PharmacistTaskStatus.OVERDUE,
        PharmacistTaskStatus.NEW,
        PharmacistTaskStatus.IN_PROGRESS,
    )

    /**
     * The state of the task, or null when it is not shown: cancelled, finished more than 14 days ago, or not
     * started by HQ yet (the cabinet refuses to start it before `startsAt`). Overdue follows the training
     * module: a due date in the past on anything but done, cancelled or paused.
     */
    fun status(row: PharmacistTaskRow, now: Instant): PharmacistTaskStatus? {
        when (row.status) {
            TrainingAssignmentStatus.cancelled -> return null
            TrainingAssignmentStatus.completed ->
                return if (finishedAt(row) >= now.minus(DONE_VISIBLE_FOR)) PharmacistTaskStatus.DONE else null
            else -> Unit
        }
        if (row.startsAt?.isAfter(now) == true) return null
        if (row.status == TrainingAssignmentStatus.overdue) return PharmacistTaskStatus.OVERDUE
        if (row.status != TrainingAssignmentStatus.paused && row.dueAt?.isBefore(now) == true) {
            return PharmacistTaskStatus.OVERDUE
        }
        return when (row.status) {
            in NOTHING_DONE_YET ->
                if (row.startedAt == null && row.progressPct <= 0) PharmacistTaskStatus.NEW else PharmacistTaskStatus.IN_PROGRESS
            else -> PharmacistTaskStatus.IN_PROGRESS
        }
    }

    /** Overdue, new, in progress (each by due date), then done (latest first). */
    fun build(rows: List<PharmacistTaskRow>, now: Instant): PharmacistTaskFeedDto {
        val shown = rows.mapNotNull { row -> status(row, now)?.let { row to it } }
        val open = shown.filter { it.second != PharmacistTaskStatus.DONE }.sortedWith(
            compareBy<Pair<PharmacistTaskRow, PharmacistTaskStatus>> { GROUP_ORDER.indexOf(it.second) }
                .thenBy(nullsLast()) { it.first.dueAt }
                .thenByDescending { it.first.required }
                .thenByDescending { it.first.priority.ordinal }
                .thenBy { it.first.createdAt }
                .thenBy { it.first.assignmentId },
        )
        val done = shown.filter { it.second == PharmacistTaskStatus.DONE }.sortedWith(
            compareByDescending<Pair<PharmacistTaskRow, PharmacistTaskStatus>> { finishedAt(it.first) }
                .thenBy { it.first.assignmentId },
        )
        val tasks = (open + done).map { (row, status) -> task(row, status) }
        return PharmacistTaskFeedDto(
            tasks = tasks,
            newCount = tasks.count { it.status == PharmacistTaskStatus.NEW },
            openCount = tasks.count { it.status != PharmacistTaskStatus.DONE },
        )
    }

    /** How to take the course, in the words of the till and the cabinet, plus what the program is about. */
    fun instructions(programName: String, shortDescription: String, description: String): String {
        val how = "Откройте «Мой кабинет», раздел «Обучение», курс ${quoted(programName)}."
        val about = summary(shortDescription.ifBlank { description })
        return if (about.isEmpty()) how else "$how $about"
    }

    private fun task(row: PharmacistTaskRow, status: PharmacistTaskStatus) = PharmacistTaskDto(
        id = row.assignmentId,
        kind = KIND_COURSE,
        title = row.programName.trim(),
        instructions = instructions(row.programName, row.programShortDescription, row.programDescription),
        rewardKzt = row.completionBonusKzt.takeIf { it > 0 },
        required = row.required,
        status = status,
        progressPct = if (status == PharmacistTaskStatus.DONE) 100 else row.progressPct.coerceIn(0, 100),
        dueAt = row.dueAt,
        completedAt = if (status == PharmacistTaskStatus.DONE) row.completedAt else null,
    )

    private fun finishedAt(row: PharmacistTaskRow): Instant = row.completedAt ?: row.updatedAt

    private fun quoted(name: String): String {
        val trimmed = name.trim()
        return if (trimmed.startsWith("«") && trimmed.endsWith("»")) trimmed else "«$trimmed»"
    }

    private fun summary(text: String): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        if (flat.length <= DESCRIPTION_LIMIT) return flat
        val cut = flat.take(DESCRIPTION_LIMIT)
        val boundary = cut.lastIndexOf(' ').takeIf { it >= DESCRIPTION_LIMIT / 2 } ?: cut.length
        return cut.take(boundary).trimEnd(' ', ',', ';', ':', '.', '-') + "…"
    }
}
