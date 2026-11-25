package kz.epharm.posm.self

import kz.epharm.pharmacists.entity.PharmacistEntity
import kz.epharm.pharmacists.entity.PharmacistStatus
import kz.epharm.posm.service.PosmDeviceIdentity
import kz.epharm.posm.service.StandardNDirectoryIdentityLookup
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component

/**
 * The person signed in to Standard-N at the till, for the till's read-only feeds (tasks, notices): the till's
 * pharmacy, the Standard-N user -> the central directory -> the card by IIN, created on first sight. Same rules and
 * messages as PosmSelfService.person; PosmSelfTasksIntegrationTest keeps them equal.
 */
@Component
class PosmSelfPersonResolver(
    private val directory: StandardNDirectoryIdentityLookup,
    private val accounts: StandardNAccountService,
) {
    fun person(device: PosmDeviceIdentity, req: PosmSelfRequest): PharmacistEntity {
        val pharmacyId = device.pharmacyId?.takeIf { it == req.pharmacyId }
            ?: throw AppException(ErrorCode.FORBIDDEN, "Касса не относится к этой аптеке", HttpStatus.FORBIDDEN)
        val found = directory.find(pharmacyId, req.standardNUserId)
            ?: throw AppException(
                ErrorCode.USER_NOT_FOUND,
                "Не нашли вас в справочнике Стандарт-Н: попросите МАС заполнить ИИН в вашей карточке пользователя",
                HttpStatus.NOT_FOUND,
            )
        val card = accounts.ensure(found, pharmacyId)
            ?: throw AppException(ErrorCode.USER_NOT_FOUND, "Сотрудник не найден", HttpStatus.NOT_FOUND)
        if (card.status == PharmacistStatus.blocked) {
            throw AppException(ErrorCode.PHARMACIST_BLOCKED, "Учётная запись заблокирована", HttpStatus.FORBIDDEN)
        }
        return card
    }
}
