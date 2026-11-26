package kz.epharm.posm.messages

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Who a notice from HQ is for. */
enum class PosmMessageAudience(val wire: String) {
    /** Every pharmacist at every till. */
    ALL("all"),

    /** Whoever signs in at a till of the chosen pharmacies, and the staff of those pharmacies at any till. */
    PHARMACIES("pharmacies"),

    /** Only the chosen pharmacists, at any till. */
    PHARMACISTS("pharmacists"),

    /**
     * The pharmacists who have not finished a chosen course (owner, 26.11.2026). The people are chosen when HQ sends
     * the notice and kept; a person who finishes the course before the till shows it is not shown it.
     */
    COURSE("course"),
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
    /** The training program of audience «course». */
    val programId: String? = null,
    /** Any audience: only the people who sit at a till when the notice is sent; false when absent. */
    val onlyAtTill: Boolean? = null,
    /** The Kazakh title and text (V128): both or neither. People who chose Kazakh read them; the rest read Russian. */
    val titleKk: String? = null,
    val textKk: String? = null,
)

/** One notice as HQ sees it in the admin, with how many people saw it at a till and how many closed it. */
data class PosmMessageAdminDto(
    val id: UUID,
    val title: String,
    val text: String,
    val audience: String,
    /** Chosen pharmacies or pharmacists; 0 for «all» and «course». */
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
    /** Sent only to the people at a till at that moment; the people were chosen then and kept. */
    val onlyAtTill: Boolean = false,
    /** The course of audience «course», with its name as it was when the notice was sent. */
    val programId: UUID? = null,
    val programName: String? = null,
    /** The Kazakh title and text, when HQ wrote them (V128). */
    val titleKk: String? = null,
    val textKk: String? = null,
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

/** A pharmacist in the notice picker and in the count preview: enough to tell namesakes apart, nothing private. */
data class PosmPersonDto(
    val id: String,
    val name: String,
    val pharmacyName: String,
    val city: String,
    val address: String,
    /** Last four digits of the phone, or null. */
    val phoneTail: String?,
    val status: String,
    /** At a till right now. */
    val atTillNow: Boolean,
    /** The last time they were seen at a till; null when never. */
    val lastTillSeenAt: Instant?,
)

/** GET /api/admin/posm-messages/pharmacists: [total] matches, the first [items] of them. */
data class PosmPharmacistSearchDto(
    val total: Int,
    val items: List<PosmPersonDto>,
)

/** A course HQ can remind about: it still has people who have not finished it. */
data class PosmCourseOptionDto(
    val id: UUID,
    val name: String,
    /** Pharmacists with an open assignment of the course (not finished, not paused, not cancelled, not blocked). */
    val openCount: Int,
    /** The one due day (Almaty) of all open assignments; null when they differ or some have none. */
    val dueOn: LocalDate?,
)

/** POST /api/admin/posm-messages/preview: who a notice would reach, before it is sent. */
data class PosmMessagePreviewDto(
    val audience: String,
    /** The people it goes to. A frozen notice: the people chosen now. Otherwise the active cards in scope. */
    val recipients: Int,
    /** People at a till right now who are in the audience: they see it within a minute or two. */
    val atTillNow: Int,
    /** True when the people are chosen at sending and kept (a course, or «only at a till»). */
    val frozen: Boolean,
    /** The first few of them by name; empty for «all» and «pharmacies». */
    val sample: List<PosmPersonDto>,
    /** How many more there are than [sample] shows. */
    val sampleMore: Int,
    /** Minutes after a till's last check during which a person counts as at the till. */
    val windowMinutes: Long,
)

/** The people a notice goes to, worked out when HQ sends it or asks for the count. */
data class PosmRecipients(
    /** Stored as `recipients_total`. */
    val total: Int,
    /** The people kept with the notice; null when its audience is judged live at every till check. */
    val frozenIds: List<String>?,
    val atTillNow: Int,
    /** Minutes after a till's last check during which a person counts as at the till. */
    val windowMinutes: Long,
)
