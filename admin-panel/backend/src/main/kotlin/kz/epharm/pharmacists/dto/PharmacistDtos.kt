package kz.epharm.pharmacists.dto

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import kz.epharm.shared.validation.Iin
import kz.epharm.pharmacists.entity.PharmacistEntity
import kz.epharm.pharmacists.entity.PharmacistStatus
import kz.epharm.pharmacists.entity.PharmacistTier
import java.time.Instant
import java.time.LocalDate

data class PharmacistDto(
    val id: String,
    val name: String,
    val iin: String?,
    val phone: String?,
    val pharmacyId: String,
    val pharmacyName: String,
    val city: String,
    val tier: PharmacistTier,
    val receipts30d: Int?,
    val rulesAccepted30d: Int?,
    val earned30d: Long?,
    val balance: Long?,
    val coursesDone: Int,
    val coursesTotal: Int,
    val status: PharmacistStatus,
    val hasPassword: Boolean,
    val joinedAt: LocalDate,
    val createdAt: Instant,
    val updatedAt: Instant,
    val standardNIinMatchStatus: String? = "UNKNOWN",
    val standardNIinCheckedAt: Instant? = null,
    val standardNIdentityMatchMethod: String? = "NONE",
    val standardNIdentityMatchStatus: String? = "UNKNOWN",
    val standardNIdentityCheckedAt: Instant? = null,
    /** Who created the card (V114): `standardn` = the server, from the central Standard-N directory. */
    val accountSource: String = "registration",
    /** Presence only: the number itself stays behind the personal-data permission. */
    val hasPhone: Boolean = false,
    /** Latest non-refund receipt of the person, by IIN or by the saved seller ID, in any pharmacy. */
    val lastSaleAt: Instant? = null,
    /** Wallet credits since the 1st of the current month in Asia/Almaty, as the till's personal window shows. */
    val earnedMonthKzt: Long? = null,
    /** Training assignments that are neither completed nor cancelled; null when not computed. */
    val openAssignments: Int? = null,
    /** ePharm pharmacies linked (V112) to the person's active Standard-N users; the main one may be among them. */
    val standardNPharmacies: List<PharmacistPharmacyRefDto> = emptyList(),
) {
    companion object {
        fun of(e: PharmacistEntity): PharmacistDto = PharmacistDto(
            id = e.id, name = e.name, iin = e.iin, phone = e.phone,
            // Admin-контракт ждёт String — для pending-фармацевта без аптеки отдаём "".
            pharmacyId = e.pharmacyId ?: "", pharmacyName = e.pharmacyName ?: "", city = e.city,
            tier = e.tier,
            receipts30d = e.receipts30d, rulesAccepted30d = e.rulesAccepted30d,
            earned30d = e.earned30d, balance = e.balance,
            coursesDone = e.coursesDone, coursesTotal = e.coursesTotal,
            status = e.status, joinedAt = e.joinedAt,
            hasPassword = !e.passwordHash.isNullOrBlank(),
            createdAt = e.createdAt, updatedAt = e.updatedAt,
            standardNIinMatchStatus = e.standardNIinMatchStatus,
            standardNIinCheckedAt = e.standardNIinCheckedAt,
            standardNIdentityMatchMethod = e.standardNIdentityMatchMethod,
            standardNIdentityMatchStatus = e.standardNIdentityMatchStatus,
            standardNIdentityCheckedAt = e.standardNIdentityCheckedAt,
            accountSource = e.accountSource,
            hasPhone = e.phone.isNotBlank(),
        )
    }
}

data class PharmacistPharmacyRefDto(val id: String, val name: String, val city: String)

data class CreatePharmacistRequest(
    @field:NotBlank @field:Size(max = 255)
    val name: String,
    @field:NotBlank @field:Iin
    val iin: String,
    @field:NotBlank @field:Size(max = 32)
    val phone: String,
    @field:NotBlank @field:Size(min = 6, max = 128)
    val password: String,
    @field:NotBlank @field:Size(max = 64)
    val pharmacyId: String,
    val tier: PharmacistTier = PharmacistTier.Silver,
    val status: PharmacistStatus = PharmacistStatus.pending,
)

data class UpdatePharmacistRequest(
    @field:Size(max = 255)
    val name: String? = null,
    @field:Size(max = 32)
    val phone: String? = null,
    @field:Size(min = 6, max = 128)
    val password: String? = null,
    val tier: PharmacistTier? = null,
    val status: PharmacistStatus? = null,
    @field:Min(0) val balance: Long? = null,
    @field:Min(0) val coursesDone: Int? = null,
    @field:Min(0) val coursesTotal: Int? = null,
)

data class ActivatePharmacistRequest(
    @field:NotBlank
    @field:Size(max = 64)
    val pharmacyId: String,
)

data class ChangeStatusRequest(
    @field:NotNull
    val status: PharmacistStatus,
)
