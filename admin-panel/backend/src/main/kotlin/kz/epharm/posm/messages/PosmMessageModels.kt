package kz.epharm.posm.messages

import java.time.Instant
import java.util.UUID

/** Who a notice from HQ is for. */
enum class PosmMessageAudience(val wire: String) {
    /** Every pharmacist at every till. */
    ALL("all"),

    /** Whoever signs in at a till of the chosen pharmacies, and the staff of those pharmacies at any till. */
    PHARMACIES("pharmacies"),

    /** Only the chosen pharmacists, at any till. */
    PHARMACISTS("pharmacists"),
    ;

    companion object {
        fun fromWire(value: String?): PosmMessageAudience? = entries.firstOrNull { it.wire == value?.trim() }
    }
}

/** HQ request from «Уведомления на кассах». Nullable fields so that a missing one gets our own 400 message. */
data class CreatePosmMessageRequest(
    val title: String? = null,
    val text: String? = null,
    val audience: String? = null,
    val pharmacyIds: List<String>? = null,
    val pharmacistIds: List<String>? = null,
    /** How many days tills show the notice; 7 when absent. */
    val days: Int? = null,
)

/** One notice as HQ sees it in the admin, with how many people saw it at a till and how many closed it. */
data class PosmMessageAdminDto(
    val id: UUID,
    val title: String,
    val text: String,
    val audience: String,
    /** Chosen pharmacies or pharmacists; 0 for «all». */
    val targetCount: Int,
    /** Names of the first chosen pharmacies or pharmacists (at most five), for the list. */
    val targetNames: List<String>,
    /** Active pharmacist cards in scope when the notice was sent. */
    val recipientsTotal: Int,
    /** People who saw it on a till (the window appeared). */
    val shownCount: Int,
    /** People who closed it with ×. */
    val closedCount: Int,
    val createdAt: Instant,
    val createdBy: String,
    val expiresAt: Instant,
    val withdrawnAt: Instant?,
    val withdrawnBy: String?,
    /** Still delivered to tills: not withdrawn and not expired. */
    val active: Boolean,
)

/**
 * A notice for the person signed in to Standard-N at the till. pending = the till shows it until the person closes
 * it with ×; an unclosed notice past its last day stays in the history only.
 */
data class PosmSelfMessageDto(
    val id: UUID,
    val title: String,
    val text: String,
    val sentAt: Instant,
    val closedAt: Instant?,
    val pending: Boolean,
)

/** POST /api/posm/self/messages: newest first, open ones included; pendingCount = still to be shown and closed. */
data class PosmSelfMessagesDto(
    val messages: List<PosmSelfMessageDto>,
    val pendingCount: Int,
)

/** Answer to the till's «shown» and «closed» reports. */
data class PosmSelfMessageAckDto(
    val id: UUID,
    val shownAt: Instant,
    val closedAt: Instant?,
)

/** A notice as the till query reads it, with this person's receipt. */
data class PosmPersonMessageRow(
    val id: UUID,
    val title: String,
    val text: String,
    val createdAt: Instant,
    val expiresAt: Instant,
    val shownAt: Instant?,
    val closedAt: Instant?,
)
