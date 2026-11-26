package kz.epharm.posm.self

import kz.epharm.pharmacists.entity.PharmacistEntity
import kz.epharm.pharmacists.entity.PharmacistStatus
import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.posm.identity.IdentityClaimRules
import kz.epharm.posm.identity.IdentityClaimStore
import kz.epharm.posm.service.PosmDeviceIdentity
import kz.epharm.posm.service.StandardNDirectoryIdentityLookup
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component

/**
 * The person signed in to Standard-N at the till, for the personal window and the till's read-only feeds (tasks,
 * notices): the till's pharmacy, the Standard-N user -> the central directory -> the card by IIN, created on first
 * sight. A user the directory does not know is still a known person when HQ confirmed what they typed about themselves
 * and the mapping that confirmation made is active (POSM 1.0.101). Anyone else gets `STANDARDN_USER_UNKNOWN`: the
 * till answers with the IIN form, an older till shows the message. One place for all callers.
 */
@Component
class PosmSelfPersonResolver(
    private val directory: StandardNDirectoryIdentityLookup,
    private val accounts: StandardNAccountService,
    private val claims: IdentityClaimStore,
    private val pharmacists: PharmacistRepository,
) {
    fun person(device: PosmDeviceIdentity, req: PosmSelfRequest): PharmacistEntity {
        val pharmacyId = device.pharmacyId?.takeIf { it == req.pharmacyId }
            ?: throw AppException(ErrorCode.FORBIDDEN, "Касса не относится к этой аптеке", HttpStatus.FORBIDDEN)
        val found = directory.find(pharmacyId, req.standardNUserId)
        val card = (if (found != null) accounts.ensure(found, pharmacyId) else confirmedCard(pharmacyId, req.standardNUserId))
            ?: throw AppException(ErrorCode.USER_NOT_FOUND, "Сотрудник не найден", HttpStatus.NOT_FOUND)
        if (card.status == PharmacistStatus.blocked) {
            throw AppException(ErrorCode.PHARMACIST_BLOCKED, "Учётная запись заблокирована", HttpStatus.FORBIDDEN)
        }
        return card
    }

    /** The directory does not know this user: only a confirmed claim in force names them, otherwise the till is told so. */
    private fun confirmedCard(pharmacyId: String, standardNUserId: String): PharmacistEntity =
        IdentityClaimRules.canonicalUserId(standardNUserId)
            ?.let { claims.confirmedCardId(pharmacyId, it) }
            ?.let { pharmacists.findById(it).orElse(null) }
            ?: throw AppException(
                ErrorCode.STANDARDN_USER_UNKNOWN,
                "Не нашли вас в справочнике Стандарт-Н: попросите МАС заполнить ИИН в вашей карточке пользователя",
                HttpStatus.NOT_FOUND,
            )
}
