package kz.epharm.posm.service

import jakarta.persistence.EntityManager
import java.time.Instant
import java.util.UUID
import kz.epharm.auth.access.AdministrativeScope
import kz.epharm.pharmacists.entity.PharmacistStatus
import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.posm.repository.PosSaleRepository
import kz.epharm.posm.repository.RecommendationEventRepository
import kz.epharm.receipts.entity.PendingBonusStatus
import kz.epharm.receipts.repository.PendingBonusRepository
import kz.epharm.receipts.service.PendingBonusService
import kz.epharm.receipts.service.ReconcileService
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class RewardReviewDecision(val action: String, val reason: String,
    val pharmacistId: String? = null, val eligiblePackages: Long? = null,
    val confirmedPayment: Boolean = false, val confirmedPackages: Boolean = false)

@Service
class RecommendationRewardReviewService(
    private val jdbc: JdbcTemplate,
    private val scope: AdministrativeScope,
    private val sales: PosSaleRepository,
    private val pharmacists: PharmacistRepository,
    private val identities: PosmPharmacistIdentityService,
    private val events: RecommendationEventRepository,
    private val pending: PendingBonusRepository,
    private val pendingService: PendingBonusService,
    private val reconcile: ReconcileService,
    private val em: EntityManager,
) {
    @Transactional(readOnly = true)
    fun list(state: String?, from: Instant?, to: Instant?, offset: Int, limit: Int): Map<String, Any> {
        if (offset < 0 || limit !in 1..10000 || state != null && state !in STATES ||
            from != null && to != null && !from.isBefore(to)) invalid("Некорректные фильтры")
        val values = mutableListOf<Any?>(); val conditions = mutableListOf("1=1")
        scope.currentPharmacyIds()?.let { ids ->
            if (ids.isEmpty()) conditions += "false" else {
                conditions += "w.pharmacy_id IN (${ids.joinToString(",") { "?" }})"; values.addAll(ids.sorted())
            }
        }
        state?.let { conditions += "w.state=?"; values += it }
        from?.let { conditions += "w.created_at>=?"; values += java.sql.Timestamp.from(it) }
        to?.let { conditions += "w.created_at<?"; values += java.sql.Timestamp.from(it) }
        val where = conditions.joinToString(" AND ")
        val total = jdbc.queryForObject("SELECT count(*) FROM recommendation_sale_rewards w WHERE $where", Long::class.java, *values.toTypedArray())!!
        val rows = jdbc.queryForList("""SELECT w.*,p.name AS pharmacy_name,f.name AS pharmacist_name,e.recommend_name AS product_name
            FROM recommendation_sale_rewards w LEFT JOIN pharmacies p ON p.id=w.pharmacy_id
            LEFT JOIN pharmacists f ON f.id=w.pharmacist_id LEFT JOIN recommendation_events e ON e.id=w.event_id
            WHERE $where ORDER BY w.created_at DESC,w.id LIMIT ? OFFSET ?""", *(values + listOf(limit, offset)).toTypedArray())
        return mapOf("items" to rows.map(::present), "total" to total, "offset" to offset, "limit" to limit)
    }

    @Transactional(readOnly = true)
    fun detail(id: String): Map<String, Any?> {
        val row = load(id); permitted(row["pharmacy_id"].toString())
        val sale = sales.findById(row["source_sale_id"].toString()).orElse(null)
        val people = jdbc.queryForList("SELECT id,name FROM pharmacists WHERE pharmacy_id=? AND status='active' ORDER BY name,id", row["pharmacy_id"])
        val source = sale?.let { mapOf("id" to it.id, "sessionId" to it.sessionId, "pharmacyId" to it.pharmacyId,
            "pharmacistId" to it.pharmacistId, "pharmacistSource" to it.pharmacistSource,
            "reportedPharmacistId" to it.reportedPharmacistId, "reportedPharmacistName" to it.reportedPharmacistName,
            "sourceDocumentId" to it.sourceDocumentId, "captureSource" to it.captureSource,
            "totalAmount" to it.totalAmount, "items" to it.items, "printedAt" to it.printedAt) }
        return present(row) + mapOf("sale" to source, "rewardEvidence" to sale?.rewardEvidence, "eligiblePharmacists" to people)
    }

    @Transactional
    fun decide(id: String, request: RewardReviewDecision, actor: String): Map<String, Any?> {
        if (request.reason.trim().length !in 3..2000 || actor.isBlank()) invalid("Укажите причину решения")
        scope.lockCurrentActor()
        var row = load(id); permitted(row["pharmacy_id"].toString())
        val session = jdbc.queryForObject("SELECT session_id FROM recommendation_reward_receipts WHERE id=?", String::class.java, row["canonical_receipt_id"])
        session?.let { lock("recommendation:$it") }
        val recipient = request.pharmacistId ?: row["pharmacist_id"]?.toString()
        recipient?.let { lock("reward-pharmacist:$it") }
        row["pending_bonus_id"]?.let { jdbc.queryForList("SELECT id FROM pending_bonuses WHERE id=? FOR UPDATE", it) }
        row["receipt_id"]?.let { jdbc.queryForList("SELECT id FROM receipts WHERE id=? FOR UPDATE", it) }
        jdbc.queryForList("SELECT id FROM recommendation_sale_rewards WHERE id=? FOR UPDATE", id)
        row = load(id); permitted(row["pharmacy_id"].toString())
        val reserved = row["budget_reserved"] == true
        when (request.action) {
            "reject" -> {
                if (reserved || row["state"] == "credited") conflict("Нельзя отменить уже зачисленный бонус этим действием")
                if (row["state"] != "rejected") jdbc.update("UPDATE recommendation_sale_rewards SET state='rejected',reason_code='hq_rejected',updated_at=now() WHERE id=?", id)
            }
            "acknowledge_refund" -> {
                if (row["state"] != "refund_review") conflict("Возврат уже рассмотрен или не зарегистрирован")
                jdbc.update("UPDATE recommendation_sale_rewards SET state=?,reason_code='refund_reviewed',updated_at=now() WHERE id=?",
                    if (reserved) "credited" else "rejected", id)
            }
            "approve" -> {
                if (row["state"] != "review_required" || !request.confirmedPayment || !request.confirmedPackages)
                    conflict("Нужны проверка оплаты и количества упаковок")
                val canonical = jdbc.queryForMap("SELECT physical_key,fiscal_key FROM recommendation_reward_receipts WHERE id=?", row["canonical_receipt_id"])
                if (canonical["physical_key"] == null || canonical["fiscal_key"] == null)
                    conflict("Сначала подтвердите уникальные исходный документ и фискальный чек")
                if (row["reason_code"] in setOf("reservation_conflict", "receipt_payload_conflict", "shown_payload_conflict"))
                    conflict("Сначала требуется отдельное устранение конфликта исходных данных")
                val count = request.eligiblePackages ?: 0
                if (count !in 1..1000000 || recipient.isNullOrBlank()) invalid("Укажите получателя и число полных упаковок")
                val owner = pharmacists.findById(recipient).orElse(null) ?: invalid("Фармацевт не найден")
                if (owner.status != PharmacistStatus.active || owner.pharmacyId != row["pharmacy_id"]) conflict("Получатель должен быть активен в этой аптеке")
                val sale = sales.findById(row["source_sale_id"].toString()).orElseThrow()
                val identity = identities.resolve(sale.pharmacyId, sale.reportedPharmacistId.orEmpty(), sale.reportedPharmacistName,
                    deviceId=sale.deviceId,sourceNamespace=sale.rewardEvidence?.completion?.identity?.sourceNamespace,
                    at=sale.rewardEvidence?.completion?.completedAt ?: sale.printedAt,
                    nativeSession=sale.rewardEvidence?.completion?.identity?.nativeSessionId?.toString(),
                    workstation=sale.rewardEvidence?.completion?.identity?.workstationId)
                if (identity.source.wireValue != "standardn_explicit_mapping" || identity.pharmacistId != recipient)
                    conflict("Сначала подтвердите связь пользователя Стандарт-Н с фармацевтом")
                if (sale.pharmacistId.isNotBlank() && sale.pharmacistId != recipient) conflict("Нельзя перенести начисление другому получателю")
                val event = row["event_id"]?.let { events.findById(it.toString()).orElse(null) }
                    ?: conflict("Нет подтверждённого исходного предложения")
                if (event.displayedAt == null || event.recommendSku != row["product_id"] || event.bonus <= 0 || event.outcomeRaw in setOf("rejected", "expired"))
                    conflict("Нет подходящего фактического показа")
                val lines = sale.items.filter { it.productId == row["product_id"] }
                if (lines.isEmpty() || lines.any { !it.qty.isFinite() || it.qty <= 0 || it.total <= 0 } ||
                    lines.sumOf { it.qty } < count.toDouble()) conflict("Количество превышает подтверждённую продажу")
                sale.pharmacistId = recipient; sale.pharmacistSource = identity.source.wireValue; sales.save(sale)
                val current = row["pending_bonus_id"]?.let { pending.findById(it.toString()).orElse(null) }
                if (current == null && event.pendingBonusId != null) conflict("Сначала рассмотрите существующую бронь этого предложения")
                val reservation = current ?: pendingService.register(recipient, event.recommendSku, event.recommendName,
                    event.expectedAmount, event.bonus, event.ruleId, sale.id, event.displayedAt, sale.pharmacyId)
                if (reservation.status != PendingBonusStatus.awaiting_receipt) conflict("Бронь уже закрыта")
                em.flush()
                val promo = jdbc.queryForObject("SELECT promo_id FROM recommendation_reward_shows WHERE event_id=?", String::class.java, event.id)
                jdbc.update("""UPDATE recommendation_sale_rewards SET pharmacist_id=?,pending_bonus_id=?,unit_bonus=?,
                    eligible_packages=?,total_bonus=?,promo_id=?,state='ready',reason_code='hq_verified',updated_at=now() WHERE id=?""",
                    recipient, reservation.id, event.bonus, count, Math.multiplyExact(event.bonus, count), promo, id)
                event.pendingBonusId = reservation.id; events.save(event); em.flush()
                val receipt = reconcile.ingestVerifiedRecommendationReward(reservation.id, sale.id, lines.sumOf { it.total }, autoCredit = false)
                reconcile.approve(receipt, actor)
            }
            else -> invalid("Неизвестное действие")
        }
        jdbc.update("""INSERT INTO recommendation_reward_audit(id,reward_id,pharmacy_id,actor_id,action,reason,detail)
            VALUES (?,?,?,?,?,?,jsonb_build_object('confirmedPayment',?::boolean,'confirmedPackages',?::boolean))""",
            "ra_${UUID.randomUUID().toString().replace("-", "")}", id, row["pharmacy_id"], actor, request.action,
            request.reason.trim(), request.confirmedPayment, request.confirmedPackages)
        return detail(id)
    }

    private fun load(id: String) = jdbc.queryForList("SELECT * FROM recommendation_sale_rewards WHERE id=?", id).singleOrNull()
        ?: throw AppException(ErrorCode.NOT_FOUND, "Начисление не найдено", HttpStatus.NOT_FOUND)
    private fun permitted(pharmacy: String) { scope.currentPharmacyIds()?.let { if (pharmacy !in it) AdministrativeScope.deny() } }
    private fun lock(value: String) { jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", value) }
    private fun present(row: Map<String, Any?>): Map<String, Any?> = row.mapKeys { (key, _) ->
        key.split('_').mapIndexed { index, part -> if (index == 0) part else part.replaceFirstChar(Char::uppercaseChar) }.joinToString("")
    }.mapValues { (_, value) -> if (value is java.sql.Timestamp) value.toInstant().toString() else value }
    private fun invalid(message: String): Nothing = throw AppException(ErrorCode.VALIDATION_FAILED, message)
    private fun conflict(message: String): Nothing = throw AppException(ErrorCode.CONFLICT, message, HttpStatus.CONFLICT)
    private companion object { val STATES = setOf("waiting_evidence", "not_eligible", "review_required", "ready", "credited", "rejected", "refund_review") }
}
