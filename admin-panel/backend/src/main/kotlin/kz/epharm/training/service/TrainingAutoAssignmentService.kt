package kz.epharm.training.service

import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import kz.epharm.training.repository.TrainingAssignmentRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.util.UUID

data class TrainingAutoAssignmentRun(
    val scanned: Int = 0,
    val created: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
)

@Service
class TrainingAutoAssignmentService(
    private val assignments: TrainingAssignmentRepository,
    private val training: TrainingService,
    @Value("\${app.training.auto-assignment.enabled:false}") private val enabled: Boolean,
    @Value("\${app.training.auto-assignment.program-id:}") private val configuredProgramId: String,
    @Value("\${app.training.auto-assignment.actor-id:}") private val configuredActorId: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${app.training.auto-assignment.delay-ms:60000}",
        initialDelayString = "\${app.training.auto-assignment.initial-delay-ms:5000}",
    )
    fun scheduledReconcile() {
        runCatching { reconcile() }
            .onFailure { log.error("Automatic training assignment failed: type={} code={}",
                it.javaClass.simpleName, (it as? AppException)?.code?.name ?: "UNEXPECTED") }
    }

    // No outer transaction: every learner commits independently and releases the authority lock.
    fun reconcile(): TrainingAutoAssignmentRun {
        if (!enabled) return TrainingAutoAssignmentRun()
        val programId = UUID.fromString(configuredProgramId)
        val actorId = UUID.fromString(configuredActorId)
        training.validateAutomaticAssignmentTarget(programId, actorId)
        var afterId = ""
        var scanned = 0
        var created = 0
        var skipped = 0
        var failed = 0
        for (batch in 0 until 20) {
            val ids = assignments.findAutomaticAssignmentCandidates(programId, afterId, 100)
            if (ids.isEmpty()) break
            ids.forEach { pharmacistId ->
                scanned++
                try {
                    if (training.ensureAutomaticAssignment(programId, actorId, pharmacistId)) created++ else skipped++
                } catch (e: Exception) {
                    // Revoked authority or a paused/expired target stops the whole run immediately.
                    if (e is AppException && e.code in setOf(ErrorCode.FORBIDDEN, ErrorCode.VALIDATION_FAILED, ErrorCode.NOT_FOUND)) throw e
                    failed++
                    log.error("Automatic training assignment failed for program={} pharmacist={} type={} code={}",
                        programId, pharmacistId, e.javaClass.simpleName, (e as? AppException)?.code?.name ?: "UNEXPECTED")
                }
            }
            afterId = ids.last()
            if (ids.size < 100) break
        }
        return TrainingAutoAssignmentRun(scanned, created, skipped, failed).also {
            if (it.created > 0 || it.failed > 0) log.info("Automatic training assignment program={} scanned={} created={} skipped={} failed={}",
                programId, it.scanned, it.created, it.skipped, it.failed)
        }
    }
}
