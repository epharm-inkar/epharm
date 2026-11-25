package kz.epharm.posm.self

import kz.epharm.pharmacists.entity.PharmacistEntity
import kz.epharm.pharmacists.entity.PharmacistStatus
import kz.epharm.posm.service.PosmDeviceIdentity
import kz.epharm.posm.service.StandardNDirectoryIdentityLookup
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import kz.epharm.training.tasks.PharmacistTaskFeedDto
import kz.epharm.training.tasks.PharmacistTaskFeedService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service

/**
 * The till's task list for the person signed in to Standard-N at it: their training assignments as «задания».
 * The person is found exactly as the personal window finds them (PosmSelfService): the till's pharmacy, the
 * Standard-N user -> the central directory -> the card by IIN, created on first sight. Nothing typed at the
 * till is saved here; the request may carry the window's other fields, they are ignored.
 */
@Service
class PosmSelfTasksService(
    private val directory: StandardNDirectoryIdentityLookup,
    private val accounts: StandardNAccountService,
    private val feed: PharmacistTaskFeedService,
) {
    fun tasks(device: PosmDeviceIdentity, req: PosmSelfRequest): PharmacistTaskFeedDto = feed.feed(person(device, req).id)

    // Same rules and messages as PosmSelfService.person; PosmSelfTasksIntegrationTest keeps them equal.
    private fun person(device: PosmDeviceIdentity, req: PosmSelfRequest): PharmacistEntity {
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
