package kz.epharm.posm.identity

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

/** The till names the Standard-N user signed in at it; the device key proves the till. */
data class PosmIdentityRequest(
    @field:NotBlank @field:Size(max = 64) val pharmacyId: String,
    @field:NotBlank @field:Size(max = 32) val standardNUserId: String,
    /** The user's name as Standard-N shows it at the till; HQ sees it next to what the person typed. */
    @field:Size(max = 255) val standardNUserName: String? = null,
    @field:Size(max = 32) val iin: String? = null,
    @field:Size(max = 400) val fullName: String? = null,
)

/**
 * Where the Standard-N user at a till stands: `known` (the directory or a confirmed claim names them, the window works
 * as usual), `unknown` (the till shows the form), `pending` (sent, the office has not decided) or `rejected` (the
 * office said no, [reason] says why, the till shows the form again).
 */
data class PosmIdentityDto(
    val state: String,
    val iinMasked: String? = null,
    val fullName: String? = null,
    val reason: String? = null,
)

data class NameClaimRequest(@field:NotBlank @field:Size(max = 400) val fullName: String)

data class MeIdentityClaimDto(val status: String, val fullName: String, val reason: String?, val createdAt: Instant)

/** The cabinet profile: the name on the card, where it came from, and what the pharmacist may still do about it. */
data class MeIdentityDto(
    val name: String,
    val nameSource: String,
    val iinSource: String,
    val nameIncomplete: Boolean,
    val canPropose: Boolean,
    val claim: MeIdentityClaimDto?,
)

data class ConfirmIdentityClaimRequest(
    /** What the reviewer saw: the claim is confirmed only if it still holds exactly these values. */
    @field:NotBlank @field:Size(max = 32) val iin: String,
    @field:NotBlank @field:Size(max = 400) val fullName: String,
)

data class RejectIdentityClaimRequest(@field:NotBlank @field:Size(min = 3, max = 500) val reason: String)

/** One reason to look closer at a claim; the admin draws the text from [code] and these fields. */
data class IdentityClaimConflictDto(
    val code: String,
    val pharmacistId: String? = null,
    val pharmacistName: String? = null,
    val pharmacyName: String? = null,
    val pharmacistStatus: String? = null,
    val directoryIin: String? = null,
    val directoryName: String? = null,
)

data class IdentityClaimDto(
    val id: String,
    val kind: String,
    val status: String,
    val pharmacyId: String?,
    val pharmacyName: String?,
    val standardNUserId: String?,
    /** The Standard-N user name as the till reported it. */
    val standardNUserName: String?,
    val iin: String,
    val fullName: String,
    val pharmacistId: String?,
    val pharmacistName: String?,
    val conflicts: List<IdentityClaimConflictDto>,
    val directoryIin: String?,
    val decidedByName: String?,
    val decidedAt: Instant?,
    val decisionReason: String?,
    val createdAt: Instant,
)

/** A row of `pharmacist_identity_claims`. */
data class IdentityClaimRow(
    val id: UUID,
    val kind: String,
    val pharmacyId: String?,
    val standardNProfileId: Long?,
    val standardNUserId: String?,
    val standardNUserName: String?,
    val deviceId: String?,
    val pharmacistId: String?,
    val iin: String,
    val fullName: String,
    val status: String,
    val conflict: String?,
    val conflictPharmacistId: String?,
    val directoryIin: String?,
    val decidedBy: UUID?,
    val decidedAt: Instant?,
    val decisionReason: String?,
    val mappingId: UUID?,
    val createdAt: Instant,
    val updatedAt: Instant,
)
