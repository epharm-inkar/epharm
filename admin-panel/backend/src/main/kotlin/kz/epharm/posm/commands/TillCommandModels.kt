package kz.epharm.posm.commands

import java.time.Instant
import java.util.UUID

/** The fixed list of what a till can be asked to do. No parameters travel with an action. */
enum class TillCommandAction(val wire: String) {
    DIAGNOSTICS("diagnostics"),
    RESTART("restart"),
    ;

    companion object {
        fun fromWire(value: String?): TillCommandAction? = entries.firstOrNull { it.wire == value?.trim() }
    }
}

enum class TillCommandStatus(val wire: String) {
    PENDING("pending"),
    DELIVERED("delivered"),
    DONE("done"),
    FAILED("failed"),
    EXPIRED("expired"),
    ;

    val active: Boolean get() = this == PENDING || this == DELIVERED

    companion object {
        fun fromWire(value: String?): TillCommandStatus? = entries.firstOrNull { it.wire == value?.trim() }
    }
}

/** HQ request: which till and which action. Nullable so that missing fields get our own 400 message. */
data class CreateTillCommandRequest(
    val pharmacyId: String? = null,
    val deviceId: String? = null,
    val action: String? = null,
)

/** One command as HQ sees it; status already accounts for an elapsed deadline. */
data class TillCommandDto(
    val id: UUID,
    val pharmacyId: String,
    val deviceId: String,
    val action: String,
    val status: String,
    val requestedBy: String,
    val createdAt: Instant,
    /** Waiting: until when the till may take it. Taken: until when the till may answer. */
    val expiresAt: Instant,
    val deliveredAt: Instant?,
    val finishedAt: Instant?,
    val resultMessage: String?,
    /** true while the diagnostics archive can be downloaded (kept 14 days). */
    val artifactAvailable: Boolean,
    /** Archive size; stays after the archive is removed. */
    val artifactBytes: Long?,
)

/** What the till receives from POST /api/posm/commands/next. */
data class TillCommandDeliveryDto(
    val id: UUID,
    val action: String,
    /** Deadline for the result; a later answer gets 409. */
    val expiresAt: Instant,
)

/** Answer to the till's POST /api/posm/commands/{id}/result. */
data class TillCommandResultDto(
    val id: UUID,
    val status: String,
    val finishedAt: Instant,
)

/** A diagnostics archive ready to be downloaded by HQ. */
class TillCommandArtifact(val fileName: String, val bytes: ByteArray)
