package kz.epharm.posm.identity

import kz.epharm.auth.access.UnifiedAuthorityLock
import kz.epharm.auth.security.AdminPrincipal
import kz.epharm.finance.premium.PharmacistCardReady
import kz.epharm.pharmacists.entity.PharmacistEntity
import kz.epharm.pharmacists.entity.PharmacistStatus
import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.posm.dto.UpsertPosmPharmacistMappingRequest
import kz.epharm.posm.repository.PosmPharmacistMappingRepository
import kz.epharm.posm.service.PosmPharmacistMappingService
import kz.epharm.posm.service.StandardNDirectoryIdentityLookup
import kz.epharm.posm.staff.SourceStaffBindingService
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * HQ decides what pharmacists typed about themselves. Confirming a person's claim creates the card (account source
 * `self`) or attaches to the card that already holds the IIN, and writes the active HQ mapping (pharmacy + Standard-N
 * USER_ID -> card) with its audit through the same service HQ uses by hand, so receipts of that user belong to the card
 * from then on. Confirming a name claim writes the name on the card. A pending claim does neither: it pays nothing.
 */
@Service
class IdentityClaimAdminService(
    private val jdbc: JdbcTemplate,
    private val store: IdentityClaimStore,
    private val pharmacists: PharmacistRepository,
    private val mappingService: PosmPharmacistMappingService,
    private val mappings: PosmPharmacistMappingRepository,
    private val directory: StandardNDirectoryIdentityLookup,
    /** A card made here can hold money at once: the sales premium waiting for its IIN is credited after the commit. */
    private val events: ApplicationEventPublisher? = null,
) {
    @Transactional(readOnly = true)
    fun list(status: String?): List<IdentityClaimDto> {
        val wanted = status?.trim()?.takeIf { it.isNotEmpty() && it != "all" }
        if (wanted != null && wanted !in STATUSES) invalid("Неизвестный статус заявки")
        return present(store.list(wanted, LIST_LIMIT))
    }

    @Transactional
    fun confirm(id: UUID, req: ConfirmIdentityClaimRequest, actor: AdminPrincipal): IdentityClaimDto {
        UnifiedAuthorityLock.acquire(jdbc)
        val claim = store.find(id, forUpdate = true) ?: notFound()
        if (claim.status != IdentityClaimRules.PENDING) conflict("Заявка уже обработана")
        // The person may have fixed a typo while HQ was looking: confirm only what the reviewer saw.
        if (req.iin.trim() != claim.iin || IdentityClaimRules.normalizeName(req.fullName) != claim.fullName) {
            conflict("Заявка изменилась, пока вы её смотрели. Обновите список и проверьте данные ещё раз")
        }
        if (claim.kind == IdentityClaimRules.KIND_NAME) confirmName(claim, actor) else confirmPerson(claim, actor)
        return present(listOf(store.find(id)!!)).single()
    }

    @Transactional
    fun reject(id: UUID, req: RejectIdentityClaimRequest, actor: AdminPrincipal): IdentityClaimDto {
        UnifiedAuthorityLock.acquire(jdbc)
        val claim = store.find(id, forUpdate = true) ?: notFound()
        if (claim.status != IdentityClaimRules.PENDING) conflict("Заявка уже обработана")
        store.markRejected(id, actor.userId, req.reason.trim())
        return present(listOf(store.find(id)!!)).single()
    }

    private fun confirmPerson(claim: IdentityClaimRow, actor: AdminPrincipal) {
        val pharmacyId = claim.pharmacyId!!
        val userId = claim.standardNUserId!!
        val pharmacy = jdbc.query(
            "SELECT name, city, active FROM pharmacies WHERE id = ?",
            { rs, _ -> Triple(rs.getString(1), rs.getString(2) ?: "", rs.getBoolean(3)) }, pharmacyId,
        ).singleOrNull() ?: conflict("Аптека не найдена")
        if (!pharmacy.third) conflict("Аптека отключена")
        // The directory wins: if it names this user now, the claim is out of date.
        if (directory.find(pharmacyId, userId) != null) {
            conflict("Стандарт-Н уже знает этого пользователя. Заявка устарела, отклоните её")
        }
        SourceStaffBindingService.lockAccount(jdbc, pharmacyId, userId)
        if (mappings.findByPharmacyIdAndExternalUserId(pharmacyId, userId)?.active == true) {
            conflict("Для этого пользователя Стандарт-Н уже есть привязка к карточке. Заявка устарела, отклоните её")
        }
        val card = pharmacists.findByIinForUpdate(claim.iin)
            ?: createCard(claim, pharmacyId, pharmacy.first, pharmacy.second).also { events?.publishEvent(PharmacistCardReady(it.id)) }
        if (card.status != PharmacistStatus.active) {
            conflict("Карточка с этим ИИН не активна (${card.status.name}). Сначала разберитесь с ней в реестре")
        }
        if (card.pharmacyId != pharmacyId) {
            conflict("Карточка с этим ИИН закреплена за другой аптекой (${card.pharmacyName ?: "без аптеки"}). Сначала переведите её в эту аптеку")
        }
        val mapping = mappingService.upsert(
            UpsertPosmPharmacistMappingRequest(pharmacyId, userId, claim.standardNUserName, card.id), actor,
        )
        store.markConfirmed(claim.id, actor.userId, card.id, UUID.fromString(mapping.id))
    }

    /** A card of its own: no phone and no password yet, the person adds them from the till window. */
    private fun createCard(claim: IdentityClaimRow, pharmacyId: String, pharmacyName: String, city: String): PharmacistEntity {
        jdbc.update(
            """
            INSERT INTO pharmacists(id, name, iin, phone, password_hash, tier, status, pharmacy_id, pharmacy_name, city,
                account_source, iin_source, name_source)
            VALUES (?, ?, ?, '', NULL, 'Silver', 'active', ?, ?, ?, 'self', 'self', 'self')
            """.trimIndent(),
            "ph_${UUID.randomUUID()}", claim.fullName, claim.iin, pharmacyId, pharmacyName, city,
        )
        return pharmacists.findByIin(claim.iin)!!
    }

    private fun confirmName(claim: IdentityClaimRow, actor: AdminPrincipal) {
        val card = claim.pharmacistId?.let { pharmacists.findByIdForUpdate(it) } ?: conflict("Карточка не найдена")
        if (card.status != PharmacistStatus.active) conflict("Карточка не активна")
        if (card.iin != claim.iin) conflict("ИИН в карточке изменился. Отклоните заявку")
        if (!IdentityClaimRules.isIncompleteName(card.name)) conflict("В карточке уже полное имя. Отклоните заявку")
        card.name = claim.fullName
        card.nameSource = IdentityClaimRules.SOURCE_SELF
        pharmacists.saveAndFlush(card)
        store.markConfirmed(claim.id, actor.userId, card.id, null)
    }

    // ── what HQ sees ────────────────────────────────────────────────────────

    private fun present(rows: List<IdentityClaimRow>): List<IdentityClaimDto> {
        if (rows.isEmpty()) return emptyList()
        val pharmacyNames = byId("SELECT id, name FROM pharmacies WHERE id IN (:ids)", rows.mapNotNull { it.pharmacyId }.toSet())
        val adminNames = byId("SELECT id::text, name FROM admin_users WHERE id::text IN (:ids)", rows.mapNotNull { it.decidedBy?.toString() }.toSet())
        val cardIds = rows.mapNotNull { it.pharmacistId }.toSet()
        val open = rows.filter { it.status == IdentityClaimRules.PENDING }
        val cardsByIin = pharmacists.findAllByIinIn(open.map { it.iin }.toSet()).groupBy { it.iin }
        val cards = (pharmacists.findAllById(cardIds)).associateBy { it.id }
        val directoryByIin = directoryNames(open.map { it.iin }.toSet())
        return rows.map { row ->
            val conflicts = mutableListOf<IdentityClaimConflictDto>()
            if (row.status == IdentityClaimRules.PENDING) {
                cardsByIin[row.iin].orEmpty().filter { it.id != row.pharmacistId }.forEach { other ->
                    conflicts += IdentityClaimConflictDto(
                        "iin_other_card", pharmacistId = other.id, pharmacistName = other.name,
                        pharmacyName = other.pharmacyName, pharmacistStatus = other.status.name,
                    )
                }
                if (open.any { it.id != row.id && it.iin == row.iin }) conflicts += IdentityClaimConflictDto("iin_other_claim")
                if (row.kind == IdentityClaimRules.KIND_PERSON) {
                    directoryByIin[row.iin].orEmpty().forEach { conflicts += IdentityClaimConflictDto("iin_in_directory", directoryName = it) }
                    val mapped = mappings.findByPharmacyIdAndExternalUserIdAndActiveTrue(row.pharmacyId!!, row.standardNUserId!!)
                    if (mapped != null) conflicts += IdentityClaimConflictDto("mapping_exists", pharmacistId = mapped.pharmacistId)
                }
            }
            if (row.conflict == IdentityClaimRules.CONFLICT_DIRECTORY_IIN_DIFFERS) {
                conflicts += IdentityClaimConflictDto("directory_iin_differs", directoryIin = row.directoryIin)
            }
            IdentityClaimDto(
                id = row.id.toString(), kind = row.kind, status = row.status,
                pharmacyId = row.pharmacyId, pharmacyName = row.pharmacyId?.let { pharmacyNames[it] },
                standardNUserId = row.standardNUserId, standardNUserName = row.standardNUserName,
                iin = row.iin, fullName = row.fullName,
                pharmacistId = row.pharmacistId, pharmacistName = row.pharmacistId?.let { cards[it]?.name },
                conflicts = conflicts, directoryIin = row.directoryIin,
                decidedByName = row.decidedBy?.let { adminNames[it.toString()] },
                decidedAt = row.decidedAt, decisionReason = row.decisionReason, createdAt = row.createdAt,
            )
        }
    }

    private fun byId(sql: String, ids: Set<String>): Map<String, String> =
        queryIn(sql, ids) { rs, _ -> rs.getString(1) to rs.getString(2) }.toMap()

    private fun directoryNames(iins: Set<String>): Map<String, List<String>> =
        queryIn("SELECT iin, full_name FROM standardn_pharmacist_directory WHERE iin IN (:ids)", iins) { rs, _ ->
            rs.getString(1) to rs.getString(2)
        }.groupBy({ it.first }, { it.second })

    private fun <T> queryIn(sql: String, ids: Set<String>, mapper: RowMapper<T>): List<T> =
        if (ids.isEmpty()) emptyList()
        else jdbc.query(sql.replace("(:ids)", ids.joinToString(",", "(", ")") { "?" }), mapper, *ids.toTypedArray())

    private fun invalid(message: String): Nothing = throw AppException(ErrorCode.VALIDATION_FAILED, message, HttpStatus.BAD_REQUEST)
    private fun conflict(message: String): Nothing = throw AppException(ErrorCode.CONFLICT, message, HttpStatus.CONFLICT)
    private fun notFound(): Nothing = throw AppException(ErrorCode.NOT_FOUND, "Заявка не найдена", HttpStatus.NOT_FOUND)

    private companion object {
        const val LIST_LIMIT = 500
        val STATUSES = setOf(IdentityClaimRules.PENDING, IdentityClaimRules.CONFIRMED, IdentityClaimRules.REJECTED, IdentityClaimRules.SUPERSEDED)
    }
}
