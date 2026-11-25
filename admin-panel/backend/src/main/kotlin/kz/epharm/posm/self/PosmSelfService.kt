package kz.epharm.posm.self

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import kz.epharm.learnercredentials.LearnerPasswordVault
import kz.epharm.mobile.auth.service.CabinetLinkService
import kz.epharm.mobile.auth.service.MobileRefreshTokenService
import kz.epharm.pharmacists.entity.PharmacistEntity
import kz.epharm.pharmacists.entity.PharmacistStatus
import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.pharmacists.service.PharmacistPhoneOwners
import kz.epharm.posm.service.PosmDeviceIdentity
import kz.epharm.posm.service.StandardNDirectoryIdentityLookup
import kz.epharm.shared.PhoneUtil
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import kz.epharm.shared.validation.PharmacistPasswordPolicy
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** The till names the person signed in to Standard-N at it; the device key proves the till. */
data class PosmSelfRequest(
    @field:NotBlank @field:Size(max = 64) val pharmacyId: String,
    @field:NotBlank @field:Size(max = 32) val standardNUserId: String,
    @field:Size(max = 32) val phone: String? = null,
    @field:Size(max = 128) val password: String? = null,
    /** false for the till's background check of the red dot: no one-time link is issued. */
    val link: Boolean = true,
)

data class PosmSelfRewardDto(val kind: String, val label: String, val bonusKzt: Long, val at: Instant)

data class PosmSelfDto(
    val fullName: String,
    val iinMasked: String,
    val pharmacy: String,
    val earnedMonthKzt: Long,
    val balanceKzt: Long,
    val recent: List<PosmSelfRewardDto>,
    val phoneBound: Boolean,
    val passwordSet: Boolean,
    /** One-time cabinet link for the QR in the window (minutes-lived, single use); null when not asked for. */
    val cabinetUrl: String?,
    val cabinetUrlExpiresAt: Instant?,
)

data class PosmCabinetLinkDto(val url: String, val expiresAt: Instant)

/**
 * The till's personal ePharm window (no login): who the pharmacist is, what they earned, and their optional
 * phone and password. The person is the Standard-N user signed in at this till, found by IIN in the central
 * directory; the card is created on first sight (StandardNAccountService).
 */
@Service
class PosmSelfService(
    private val directory: StandardNDirectoryIdentityLookup,
    private val accounts: StandardNAccountService,
    private val pharmacists: PharmacistRepository,
    private val cabinetLinks: CabinetLinkService,
    private val jdbc: JdbcTemplate,
    private val passwordEncoder: PasswordEncoder,
    private val credentialVault: LearnerPasswordVault,
    private val phoneOwners: PharmacistPhoneOwners,
    private val refreshTokens: MobileRefreshTokenService,
) {
    fun view(device: PosmDeviceIdentity, req: PosmSelfRequest): PosmSelfDto {
        val person = person(device, req)
        return dto(person, device, issueLink = req.link)
    }

    fun cabinetLink(device: PosmDeviceIdentity, req: PosmSelfRequest): PosmCabinetLinkDto {
        val person = person(device, req)
        val link = cabinetLinks.issue(person.id, device.pharmacyId!!, device.deviceId ?: "legacy")
        return PosmCabinetLinkDto(link.url, link.expiresAt)
    }

    /**
     * The till proves who is signed in to Standard-N at it. It may fill an empty phone and set or change the
     * password (owner's decision 25.11.2026: the cabinet has no SMS reset any more; the till asks for the new
     * password twice and sends the final one). A bound phone is never replaced from the till: a colleague signed
     * in as the person could move the account to their own number, so that goes through support.
     * A changed password ends every cabinet session of the person (refresh tokens); the database triggers on
     * `password_hash` (V061, V097) already revoke their POSM staff sessions.
     */
    @Transactional
    fun saveContact(device: PosmDeviceIdentity, req: PosmSelfRequest): PosmSelfDto {
        val person = person(device, req)
        val card = pharmacists.findByIdForUpdate(person.id) ?: notFound()
        val typedPhone = req.phone?.trim()?.takeIf { it.isNotEmpty() }
        val typedPassword = req.password?.takeIf { it.isNotEmpty() }
        if (typedPhone == null && typedPassword == null) invalid("Введите номер телефона или пароль")
        val phone = typedPhone?.let(PhoneUtil::normalize)
        if (phone != null && !PHONE.matches(phone)) invalid("Номер в формате +7 7XX XXX XX XX")
        if (phone != null && card.phone.isNotBlank() && PhoneUtil.normalize(card.phone) != phone) {
            conflict("Номер уже привязан. Сменить его можно через поддержку ePharm")
        }
        typedPassword?.let { PharmacistPasswordPolicy.requireValid(it, TILL_PASSWORD_MINIMUM) }
        val phoneIsNew = phone != null && card.phone.isBlank()
        if (phoneIsNew) {
            if (phoneOwners.owners(phone!!).any { it.id != card.id }) conflict("Этот номер уже привязан к другому сотруднику")
            card.phone = phone
        }
        val oldHash = card.passwordHash?.takeIf { it.isNotBlank() }
        // The same password sent again (a retry) keeps the hash and the sessions.
        val passwordIsNew = typedPassword != null && (oldHash == null || !passwordEncoder.matches(typedPassword, oldHash))
        if (passwordIsNew) card.passwordHash = passwordEncoder.encode(typedPassword)
        card.updatedAt = Instant.now()
        // Flushed first: the vault seals the password only against the hash already in the table.
        val saved = pharmacists.saveAndFlush(card)
        if (phoneIsNew) {
            // Saved from the till without SMS (decision 24.11): the person typed it, nobody verified it.
            jdbc.update("UPDATE pharmacists SET phone_verification_status = 'self_declared' WHERE id = ?", saved.id)
        }
        if (typedPassword != null) credentialVault.capture(saved.id, saved.passwordHash!!, typedPassword)
        if (passwordIsNew && oldHash != null) refreshTokens.revokeAllForPharmacist(saved.id)
        return dto(saved, device, issueLink = req.link)
    }

    private fun person(device: PosmDeviceIdentity, req: PosmSelfRequest): PharmacistEntity {
        val pharmacyId = device.pharmacyId?.takeIf { it == req.pharmacyId } ?: forbidden()
        val found = directory.find(pharmacyId, req.standardNUserId)
            ?: throw AppException(
                ErrorCode.USER_NOT_FOUND,
                "Не нашли вас в справочнике Стандарт-Н: попросите МАС заполнить ИИН в вашей карточке пользователя",
                HttpStatus.NOT_FOUND,
            )
        val card = accounts.ensure(found, pharmacyId) ?: notFound()
        if (card.status == PharmacistStatus.blocked) {
            throw AppException(ErrorCode.PHARMACIST_BLOCKED, "Учётная запись заблокирована", HttpStatus.FORBIDDEN)
        }
        return card
    }

    private fun dto(card: PharmacistEntity, device: PosmDeviceIdentity, issueLink: Boolean): PosmSelfDto {
        val earned = jdbc.queryForObject(
            """
            SELECT coalesce(sum(earned_delta_kzt), 0) FROM pharmacist_wallet_entries
            WHERE pharmacist_id = ? AND created_at >= date_trunc('month', now() AT TIME ZONE 'Asia/Almaty') AT TIME ZONE 'Asia/Almaty'
            """.trimIndent(),
            Long::class.java, card.id,
        ) ?: 0L
        val recent = jdbc.query(
            """
            SELECT e.source, e.amount_kzt, e.created_at, pb.product_name, r.type
            FROM pharmacist_wallet_entries e
            LEFT JOIN pending_bonuses pb ON e.source = 'receipt_bonus' AND pb.id = e.source_id
            LEFT JOIN rules r ON r.id = pb.rule_id
            WHERE e.pharmacist_id = ? AND e.amount_kzt > 0
            ORDER BY e.created_at DESC
            LIMIT 5
            """.trimIndent(),
            { rs, _ ->
                val source = rs.getString(1)
                val ruleType = rs.getString(5)
                PosmSelfRewardDto(
                    kind = ruleType ?: source,
                    label = rs.getString(4)?.takeIf { it.isNotBlank() } ?: LABELS[source] ?: "Начисление",
                    bonusKzt = rs.getLong(2),
                    at = rs.getTimestamp(3).toInstant(),
                )
            },
            card.id,
        )
        val link = if (issueLink) cabinetLinks.issue(card.id, device.pharmacyId!!, device.deviceId ?: "legacy") else null
        return PosmSelfDto(
            fullName = card.name,
            iinMasked = mask(card.iin),
            pharmacy = card.pharmacyName ?: "",
            earnedMonthKzt = earned,
            balanceKzt = card.balance,
            recent = recent,
            phoneBound = card.phone.isNotBlank(),
            passwordSet = !card.passwordHash.isNullOrBlank(),
            cabinetUrl = link?.url,
            cabinetUrlExpiresAt = link?.expiresAt,
        )
    }

    private fun mask(iin: String): String = if (iin.length == 12) iin.take(6) + "•••••" + iin.takeLast(1) else ""
    private fun notFound(): Nothing = throw AppException(ErrorCode.USER_NOT_FOUND, "Сотрудник не найден", HttpStatus.NOT_FOUND)
    private fun forbidden(): Nothing = throw AppException(ErrorCode.FORBIDDEN, "Касса не относится к этой аптеке", HttpStatus.FORBIDDEN)
    private fun invalid(message: String): Nothing = throw AppException(ErrorCode.VALIDATION_FAILED, message, HttpStatus.BAD_REQUEST)
    private fun conflict(message: String): Nothing = throw AppException(ErrorCode.CONFLICT, message, HttpStatus.CONFLICT)

    private companion object {
        val PHONE = Regex("^\\+7[0-9]{10}$")
        /** The pharmacist password minimum at the till, as for POSM sign-in since #148 (StaffAuthService). */
        const val TILL_PASSWORD_MINIMUM = 4
        val LABELS = mapOf(
            "receipt_bonus" to "Бонус за рекомендацию",
            "training_bonus" to "Обучение",
            "merch_task" to "Задание мерча",
        )
    }
}
