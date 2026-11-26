package kz.epharm.posm.identity

import kz.epharm.auth.access.UnifiedAuthorityLock
import kz.epharm.pharmacists.entity.PharmacistEntity
import kz.epharm.pharmacists.entity.PharmacistStatus
import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.posm.repository.PosmPharmacistMappingRepository
import kz.epharm.posm.service.PosmDeviceIdentity
import kz.epharm.posm.service.StandardNDirectoryIdentityLookup
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * What a pharmacist types about themselves: at the till (a Standard-N user without a card: IIN and full name) and in the
 * cabinet (a card whose name is cut short: the full name). A till claim with nothing for HQ to look at is confirmed at
 * once ([IdentityClaimAdminService.autoConfirm], owner 26.11.2026); any other claim waits for HQ. Every claim yields to
 * the Standard-N directory ([IdentityClaimDirectoryReconciler]).
 */
@Service
class IdentityClaimSubmissionService(
    private val store: IdentityClaimStore,
    private val directory: StandardNDirectoryIdentityLookup,
    private val pharmacists: PharmacistRepository,
    private val mappings: PosmPharmacistMappingRepository,
    private val limiter: IdentityClaimLimiter,
    private val jdbc: JdbcTemplate,
    private val admin: IdentityClaimAdminService,
    /** Off: every till claim waits for HQ, as before 26.11.2026. */
    @Value("\${epharm.identity-claims.auto-confirm:true}") private val autoConfirm: Boolean,
) {
    // ── till ────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    fun tillState(device: PosmDeviceIdentity, req: PosmIdentityRequest): PosmIdentityDto {
        val pharmacyId = ownPharmacy(device, req.pharmacyId)
        val userId = IdentityClaimRules.requireUserId(req.standardNUserId)
        if (isKnown(pharmacyId, userId)) return PosmIdentityDto(STATE_KNOWN)
        val latest = store.latestPerson(pharmacyId, userId)
        return when (latest?.status) {
            IdentityClaimRules.PENDING -> PosmIdentityDto(STATE_PENDING, IdentityClaimRules.maskIin(latest.iin), latest.fullName)
            IdentityClaimRules.REJECTED -> PosmIdentityDto(STATE_REJECTED, reason = latest.decisionReason)
            else -> PosmIdentityDto(STATE_UNKNOWN)
        }
    }

    @Transactional
    fun tillSubmit(device: PosmDeviceIdentity, req: PosmIdentityRequest): PosmIdentityDto {
        val pharmacyId = ownPharmacy(device, req.pharmacyId)
        // A fleet-key till has no device of its own: nothing to count the attempts against.
        val deviceId = device.deviceId?.takeUnless { device.legacy }
            ?: throw AppException(ErrorCode.FORBIDDEN, "Отправить данные можно только с зарегистрированной кассы", HttpStatus.FORBIDDEN)
        val userId = IdentityClaimRules.requireUserId(req.standardNUserId)
        limiter.take("device:$deviceId", IdentityClaimLimiter.PER_DEVICE)
        limiter.take("person:$pharmacyId:$userId", IdentityClaimLimiter.PER_PERSON)
        val iin = IdentityClaimRules.requireIin(req.iin)
        val name = IdentityClaimRules.requireFullName(req.fullName)

        // HQ takes this lock before it locks a claim; taking it first here keeps the till from deadlocking with HQ.
        if (autoConfirm) UnifiedAuthorityLock.acquire(jdbc)
        if (isKnown(pharmacyId, userId)) conflict("ePharm уже знает вас. Закройте окно и откройте его снова")
        if (mappings.findByPharmacyIdAndExternalUserIdAndActiveTrue(pharmacyId, userId) != null) {
            conflict("Ваш пользователь Стандарт-Н уже привязан к карточке ePharm. Если что-то не так, обратитесь к менеджеру сети")
        }
        // The IIN of someone else's card is not refused (HQ decides, it may be one person at two pharmacies), but it is
        // flagged so that HQ looks at it first.
        val other = pharmacists.findByIin(iin)
        val claimId = store.upsertPerson(
            pharmacyId = pharmacyId, profileId = store.profileIdOf(pharmacyId), userId = userId,
            userName = IdentityClaimRules.cleanUserName(req.standardNUserName), deviceId = deviceId,
            iin = iin, fullName = name,
            conflict = other?.let { IdentityClaimRules.CONFLICT_IIN_OTHER_CARD }, conflictPharmacistId = other?.id,
        )
        if (autoConfirm && other == null && admin.autoConfirm(claimId) != null) return PosmIdentityDto(STATE_KNOWN)
        return PosmIdentityDto(STATE_PENDING, IdentityClaimRules.maskIin(iin), name)
    }

    /** The directory names them, or HQ confirmed their claim and the mapping made then is still active. */
    private fun isKnown(pharmacyId: String, userId: String) =
        directory.find(pharmacyId, userId) != null || store.confirmedCardId(pharmacyId, userId) != null

    private fun ownPharmacy(device: PosmDeviceIdentity, claimed: String): String =
        device.pharmacyId?.takeIf { it == claimed.trim() }
            ?: throw AppException(ErrorCode.FORBIDDEN, "Касса не относится к этой аптеке", HttpStatus.FORBIDDEN)

    // ── cabinet ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    fun cabinetState(pharmacistId: String): MeIdentityDto = stateOf(card(pharmacistId))

    @Transactional
    fun cabinetPropose(pharmacistId: String, rawName: String): MeIdentityDto {
        limiter.take("card:$pharmacistId", IdentityClaimLimiter.PER_PERSON)
        val name = IdentityClaimRules.requireFullName(rawName)
        val card = pharmacists.findByIdForUpdate(pharmacistId) ?: notFound()
        if (card.status != PharmacistStatus.active) conflict("Карточка не активна")
        if (!IdentityClaimRules.isIncompleteName(card.name)) conflict("В вашей карточке уже указано полное имя")
        if (IdentityClaimRules.iinProblem(card.iin) != null) conflict("В карточке не заполнен ИИН. Обратитесь к менеджеру сети")
        // The directory wins: when it already has the full name of this IIN, the card takes it and nothing is asked.
        val fromDirectory = store.directoryNamesOf(card.iin).map { IdentityClaimRules.normalizeName(it) }
            .firstOrNull { !IdentityClaimRules.isIncompleteName(it) }
        if (fromDirectory != null && card.nameSource != IdentityClaimRules.SOURCE_HQ) {
            card.name = fromDirectory
            card.nameSource = IdentityClaimRules.SOURCE_STANDARDN
            return stateOf(pharmacists.saveAndFlush(card))
        }
        store.upsertName(card.id, card.pharmacyId, card.iin, name)
        return stateOf(card)
    }

    private fun stateOf(card: PharmacistEntity): MeIdentityDto {
        val latest = store.latestName(card.id)
        val incomplete = IdentityClaimRules.isIncompleteName(card.name)
        val shown = latest?.takeIf { it.status == IdentityClaimRules.PENDING || it.status == IdentityClaimRules.REJECTED }
        return MeIdentityDto(
            name = card.name,
            nameSource = card.nameSource,
            iinSource = card.iinSource,
            nameIncomplete = incomplete,
            canPropose = card.status == PharmacistStatus.active && incomplete && latest?.status != IdentityClaimRules.PENDING,
            claim = shown?.let { MeIdentityClaimDto(it.status, it.fullName, it.decisionReason, it.createdAt) },
        )
    }

    private fun card(id: String): PharmacistEntity = pharmacists.findById(id).orElse(null) ?: notFound()

    private fun conflict(message: String): Nothing = throw AppException(ErrorCode.CONFLICT, message, HttpStatus.CONFLICT)
    private fun notFound(): Nothing = throw AppException(ErrorCode.USER_NOT_FOUND, "Сотрудник не найден", HttpStatus.NOT_FOUND)

    companion object {
        const val STATE_KNOWN = "known"
        const val STATE_UNKNOWN = "unknown"
        const val STATE_PENDING = "pending"
        const val STATE_REJECTED = "rejected"
    }
}
