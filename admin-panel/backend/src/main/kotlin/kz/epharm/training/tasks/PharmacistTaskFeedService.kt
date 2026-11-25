package kz.epharm.training.tasks

import kz.epharm.training.domain.TrainingAssignmentStatus
import kz.epharm.training.domain.TrainingPriority
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * Reads one pharmacist's training assignments for the task feed. Read-only: the status shown is derived here,
 * the assignment itself changes only through the training module (a completed course makes the task «done»).
 */
@Service
class PharmacistTaskFeedService(private val jdbc: JdbcTemplate) {
    fun feed(pharmacistId: String, now: Instant = Instant.now()): PharmacistTaskFeedDto {
        val doneSince = Timestamp.from(now.minus(PharmacistTaskFeed.DONE_VISIBLE_FOR))
        val rows = jdbc.query(SQL, { rs, _ -> row(rs) }, pharmacistId, doneSince)
        return PharmacistTaskFeed.build(rows, now)
    }

    private fun row(rs: ResultSet) = PharmacistTaskRow(
        assignmentId = rs.getObject("id", UUID::class.java),
        status = TrainingAssignmentStatus.valueOf(rs.getString("status")),
        required = rs.getBoolean("required"),
        priority = TrainingPriority.entries.firstOrNull { it.name == rs.getString("priority") } ?: TrainingPriority.normal,
        progressPct = rs.getInt("progress_pct"),
        startsAt = rs.instant("starts_at"),
        dueAt = rs.instant("due_at"),
        startedAt = rs.instant("started_at"),
        completedAt = rs.instant("completed_at"),
        createdAt = rs.instant("created_at")!!,
        updatedAt = rs.instant("updated_at")!!,
        programName = rs.getString("program_name"),
        programShortDescription = rs.getString("short_description").orEmpty(),
        programDescription = rs.getString("description").orEmpty(),
        completionBonusKzt = rs.getLong("completion_bonus"),
    )

    private fun ResultSet.instant(column: String): Instant? = getTimestamp(column)?.toInstant()

    private companion object {
        // Cancelled never shows; a completed one only while it is recent. The rest of the rules live in
        // PharmacistTaskFeed so that they are tested without a database.
        val SQL = """
            SELECT a.id, a.status, a.required, a.priority, a.progress_pct, a.starts_at, a.due_at, a.started_at,
                   a.completed_at, a.created_at, a.updated_at,
                   p.name AS program_name, p.short_description, p.description, v.completion_bonus
            FROM training_assignments a
            JOIN training_program_versions v ON v.id = a.program_version_id
            JOIN training_programs p ON p.id = v.program_id
            WHERE a.pharmacist_id = ?
              AND a.status <> 'cancelled'
              AND (a.status <> 'completed' OR coalesce(a.completed_at, a.updated_at) >= ?)
        """.trimIndent()
    }
}
