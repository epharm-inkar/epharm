package kz.epharm.posm.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.persistence.EntityManager
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kz.epharm.pharmacists.entity.PharmacistStatus
import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.posm.dto.*
import kz.epharm.posm.entity.PosSaleEntity
import kz.epharm.posm.repository.PosSaleRepository
import kz.epharm.posm.repository.RecommendationEventRepository
import kz.epharm.receipts.entity.PendingBonusStatus
import kz.epharm.receipts.repository.PendingBonusRepository
import kz.epharm.receipts.service.PendingBonusService
import kz.epharm.receipts.service.ReconcileService
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Opt-in v2 path: immutable evidence -> canonical receipt -> existing receipt/wallet writer. */
@Service
class RecommendationRewardService(
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
    private val events: RecommendationEventRepository,
    private val sales: PosSaleRepository,
    private val products: RulesEngineService,
    private val pharmacists: PharmacistRepository,
    private val pending: PendingBonusRepository,
    private val pendingService: PendingBonusService,
    private val reconcile: ReconcileService,
    private val em: EntityManager,
    @Value("\${app.posm.sale-bonus.enabled:false}") private val enabled: Boolean,
    @Value("\${app.posm.sale-bonus.auto-credit-enabled:false}") private val autoCredit: Boolean,
    @Value("\${app.posm.sale-bonus.eligible-after:}") eligibleAfterRaw: String,
) {
    private val cutoff = eligibleAfterRaw.trim().takeIf(String::isNotBlank)?.let(Instant::parse)
    private val policy = RecommendationRewardEvidencePolicy()
    init { require(!autoCredit || enabled && cutoff != null) { "Automatic rewards require opt-in and an activation cutoff" } }

    fun protocol(): Int? = if (enabled) 1 else null
    fun sameEvidence(a: RewardSaleEvidence?, b: RewardSaleEvidence?): Boolean =
        a == null && b == null || a != null && b != null && hash(a) == hash(b)

    @Transactional
    fun shown(eventId: String, at: Instant, evidence: RewardShownEvidence?, device: PosmDeviceIdentity) {
        if (evidence == null) return
        val event = events.findById(eventId).orElse(null) ?: return
        if (event.pharmacyId != device.pharmacyId && !device.legacy) return
        lockSession(event.sessionId)
        em.flush(); em.refresh(event)
        val fingerprint = hash(listOf(at.toString(), evidence, device.deviceId))
        val previous = rows("SELECT payload_hash FROM recommendation_reward_shows WHERE event_id=?", eventId).singleOrNull()
        if (previous != null) {
            if (previous["payload_hash"] != fingerprint) {
                audit(null, event.pharmacyId, "device:${device.deviceId ?: "legacy"}", "shown_conflict",
                    "The first display snapshot is immutable", "shown:${hash(listOf(eventId, fingerprint))}")
                jdbc.update("UPDATE recommendation_reward_shows SET auto_eligible=false WHERE event_id=?", eventId)
                flagEventCases(eventId, "shown_payload_conflict")
                return // Do not take recipient locks after locking a conflicting case.
            }
        } else {
            val promo = jdbc.query("SELECT promo_id FROM rules WHERE id=?", { rs, _ -> rs.getString(1) }, event.ruleId).singleOrNull()
            val eligible = enabled && autoCredit && !device.legacy && cutoff != null &&
                !at.isBefore(cutoff) && !event.shownAt.isBefore(cutoff) &&
                event.sessionId == evidence.checkoutSessionId && evidence.version == 1
            jdbc.update("""INSERT INTO recommendation_reward_shows
                (event_id,pharmacy_id,device_id,shown_at,shown_at_exact,evidence,payload_hash,auto_eligible,promo_id)
                VALUES (?,?,?,?,?,?::jsonb,?,?,?)""", eventId, event.pharmacyId, device.deviceId,
                java.sql.Timestamp.from(at), at.toString(), json(evidence), fingerprint, eligible, promo)
        }
        if (enabled) {
            val replay = sales.findByPharmacyIdAndSessionIdOrderByCreatedAtAsc(event.pharmacyId, event.sessionId)
                .filter { it.rewardEvidence != null }
            if (replay.isEmpty()) return
            // A checkout can contain several physical sales/cashiers: take the whole lock set first.
            replay.map { it.pharmacistId }.filter(String::isNotBlank).distinct().sorted().forEach {
                jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "reward-pharmacist:$it")
            }
            replay.flatMap { receiptKeys(it, it.rewardEvidence!!) }.distinct().sorted().forEach(::lockReceipt)
            val promos = jdbc.queryForList("""SELECT DISTINCT s.promo_id FROM recommendation_reward_shows s
                JOIN recommendation_events e ON e.id=s.event_id WHERE e.pharmacy_id=? AND e.session_id=?
                AND s.promo_id IS NOT NULL ORDER BY s.promo_id""", event.pharmacyId, event.sessionId)
            promos.forEach { jdbc.queryForList("SELECT id FROM promos WHERE id=? FOR NO KEY UPDATE", it["promo_id"]) }
            replay.forEach(::sale)
        }
    }

    @Transactional
    fun sale(sale: PosSaleEntity) {
        val evidence = sale.rewardEvidence ?: return
        if (!enabled) return
        lockSession(sale.sessionId ?: return)
        if (sale.pharmacistId.isNotBlank()) jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "reward-pharmacist:${sale.pharmacistId}")
        em.flush()
        if (evidence.completion.status == "refund") { refund(sale, evidence); return }
        val canonical = canonical(sale, evidence) ?: return
        val returned = rows("""SELECT h.refund_sale_id FROM recommendation_reward_refund_holds h
            JOIN recommendation_reward_receipts r ON r.id=? AND r.pharmacy_id=h.pharmacy_id
            WHERE h.physical_key=r.physical_key OR h.fiscal_key=r.fiscal_key LIMIT 1""", canonical).isNotEmpty()
        val sessionEvents = events.findAllBySessionId(sale.sessionId!!).filter { it.pharmacyId == sale.pharmacyId }
        val candidates = sessionEvents.map { it.recommendSku }.toSet()
        if (candidates.isEmpty()) return
        val snapshots = rows("""SELECT s.* FROM recommendation_reward_shows s
            JOIN recommendation_events e ON e.id=s.event_id WHERE e.pharmacy_id=? AND e.session_id=?""",
            sale.pharmacyId, sale.sessionId).associate { row ->
            val event = sessionEvents.first { it.id == row["event_id"] }
            event.id to RewardShowRecord(event, mapper.readValue(row["evidence"].toString(), RewardShownEvidence::class.java),
                Instant.parse(row["shown_at_exact"].toString()), row["device_id"]?.toString(), row["auto_eligible"] == true,
                row["promo_id"]?.toString())
        }
        // Lock the complete funding set in a deterministic order before the first wallet mutation.
        snapshots.values.mapNotNull { it.promoId }.distinct().sorted().forEach {
            jdbc.queryForList("SELECT id FROM promos WHERE id=? FOR NO KEY UPDATE", it)
        }
        val decisions = policy.evaluate(sale, evidence, snapshots, products::resolveStrongProductIds)
        val bodyProducts = products.resolveStrongProductIds(sale.items.map { CartItemDto(it.sku, it.barcode, it.name, it.qty) })
        for (decision in decisions.filter { it.productId in candidates }) {
            val caseId = "rw_${hash(listOf(canonical, decision.productId)).take(56)}"
            val existing = rows("SELECT * FROM recommendation_sale_rewards WHERE id=?", caseId).singleOrNull()
            if (existing != null && existing["state"] != "waiting_evidence") continue
            val show = decision.eventId?.let(snapshots::get)
            val actorValid = sale.pharmacistSource == "standardn_explicit_mapping" &&
                sale.reportedPharmacistId == evidence.completion.identity.rawUserId &&
                pharmacists.findById(sale.pharmacistId).orElse(null)?.let {
                    it.status == PharmacistStatus.active && it.pharmacyId == sale.pharmacyId
                } == true
            val reason = when {
                returned -> "refund_reported"
                decision.eligible && !actorValid -> "employee_unverified"
                decision.eligible && !autoCredit -> "automatic_credit_disabled"
                else -> decision.reason
            }
            val ready = decision.eligible && actorValid && autoCredit && !returned
            val state = when {
                returned -> "refund_review"
                ready -> "ready"
                reason == "shown_evidence_missing" -> "waiting_evidence"
                reason in setOf("no_timely_addition", "addition_outside_window", "product_preexisting", "offer_not_eligible", "before_activation") -> "not_eligible"
                else -> "review_required"
            }
            val beneficiary = sale.pharmacistId.takeIf { it.isNotBlank() && pharmacists.existsById(it) }
            if (existing == null) jdbc.update("""INSERT INTO recommendation_sale_rewards
                (id,canonical_receipt_id,pharmacy_id,pharmacist_id,device_id,source_sale_id,product_id,event_id,promo_id,
                 state,reason_code,unit_bonus,eligible_packages,total_bonus)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", caseId, canonical, sale.pharmacyId, beneficiary, sale.deviceId,
                sale.id, decision.productId, decision.eventId, show?.promoId, state, reason, decision.unitBonus,
                decision.packages, if (ready) Math.multiplyExact(decision.packages, decision.unitBonus) else 0L)
            if (!ready || show == null) {
                if (existing != null) jdbc.update("UPDATE recommendation_sale_rewards SET state=?,reason_code=?,updated_at=now() WHERE id=?", state, reason, caseId)
                continue
            }
            // Reuse an accepted reservation, but never revive a matched/rejected/expired one.
            val linked = sessionEvents.filter { it.recommendSku == decision.productId }.mapNotNull { it.pendingBonusId }.distinct()
            val sourceBound = pending.findFirstBySourceSaleIdAndSku(sale.id, decision.productId)
            val selected = if (sourceBound != null) sourceBound else if (linked.size == 1) pending.findById(linked.single()).orElse(null) else null
            if (linked.size > 1 || selected != null && (selected.status != PendingBonusStatus.awaiting_receipt ||
                    selected.pharmacistId != sale.pharmacistId || selected.pharmacyId != sale.pharmacyId ||
                    selected.sku != decision.productId || selected.bonus != decision.unitBonus ||
                    selected.sourceSaleId != null && selected.sourceSaleId != sale.id)) {
                hold(caseId, "reservation_conflict"); continue
            }
            val reservation = selected?.also {
                jdbc.queryForList("SELECT id FROM pending_bonuses WHERE id=? FOR UPDATE", it.id); em.refresh(it)
                if (it.status != PendingBonusStatus.awaiting_receipt) { hold(caseId, "reservation_conflict"); return@also }
                it.sourceSaleId = sale.id; pending.save(it)
            } ?: pendingService.register(sale.pharmacistId, decision.productId, show.event.recommendName,
                show.event.expectedAmount, decision.unitBonus, show.event.ruleId, sale.id, show.shownAt, sale.pharmacyId)
            if (reservation.status != PendingBonusStatus.awaiting_receipt) continue
            em.flush() // The JDBC foreign key must see a newly created JPA reservation.
            jdbc.update("""UPDATE recommendation_sale_rewards SET pharmacist_id=?,event_id=?,promo_id=?,state='ready',
                reason_code='qualified',unit_bonus=?,eligible_packages=?,total_bonus=?,pending_bonus_id=?,updated_at=now() WHERE id=?""",
                sale.pharmacistId, show.event.id, show.promoId, decision.unitBonus, decision.packages,
                Math.multiplyExact(decision.unitBonus, decision.packages), reservation.id, caseId)
            show.event.pendingBonusId = reservation.id; events.save(show.event); em.flush()
            val paidAmount = sale.items.indices.filter { bodyProducts[it] == decision.productId }
                .fold(0L) { total, index -> Math.addExact(total, sale.items[index].total) }
            reconcile.ingestVerifiedRecommendationReward(reservation.id, sale.id, paidAmount, autoCredit = true)
        }
    }

    private fun canonical(sale: PosSaleEntity, evidence: RewardSaleEvidence): String? {
        val complete = evidence.completion
        val namespace = complete.identity.sourceNamespace
        val physical = if (namespace.matches(Regex("[a-f0-9]{64}")) && sale.sourceDocumentId != null)
            hash(listOf("physical", sale.pharmacyId, namespace, sale.sourceDocumentId)) else null
        val fiscal = if (complete.status == "paid" && complete.deviceNumber.isNotBlank() && complete.fiscalNumber > 0 && complete.fiscalShift > 0)
            hash(listOf("fiscal", sale.pharmacyId, complete.deviceNumber.trim().uppercase(), complete.fiscalShift, complete.fiscalNumber)) else null
        receiptKeys(sale, evidence).sorted().forEach(::lockReceipt)
        val fingerprint = hash(listOf(sale.pharmacyId, sale.sourceDocumentId, sale.sessionId, sale.reportedPharmacistId,
            complete.completedAt.toString(), sale.totalAmount, sale.items.map { listOf(it.sku, it.barcode, it.qty, it.price, it.total) },
            complete.status, complete.deviceNumber, complete.fiscalShift, complete.fiscalNumber))
        val found = rows("""SELECT * FROM recommendation_reward_receipts WHERE source_sale_id=?
            OR physical_key=? OR fiscal_key=? ORDER BY id""", sale.id, physical, fiscal)
        if (found.isNotEmpty()) {
            val conflict = found.size > 1 || found.single()["payload_hash"] != fingerprint
            if (conflict) {
                found.forEach { row ->
                    jdbc.update("""UPDATE recommendation_sale_rewards SET
                        state=CASE WHEN budget_reserved THEN 'refund_review' ELSE 'review_required' END,
                        reason_code='receipt_payload_conflict',updated_at=now() WHERE canonical_receipt_id=?""", row["id"])
                }
                audit(null, sale.pharmacyId, "device:${sale.deviceId}", "receipt_conflict",
                    "Canonical physical/fiscal receipt has conflicting claims", "alias:${hash(listOf(sale.id, fingerprint))}")
                return null
            }
            // A different transport id is an alias, never another source of money.
            if (found.single()["source_sale_id"] != sale.id) return null
            return found.single()["id"].toString()
        }
        val id = hash(listOf("receipt", sale.pharmacyId, physical ?: sale.id))
        jdbc.update("""INSERT INTO recommendation_reward_receipts
            (id,pharmacy_id,source_sale_id,physical_key,fiscal_key,payload_hash,device_id,session_id)
            VALUES (?,?,?,?,?,?,?,?)""", id, sale.pharmacyId, sale.id, physical, fiscal, fingerprint, sale.deviceId, sale.sessionId)
        return id
    }

    private fun refund(sale: PosSaleEntity, evidence: RewardSaleEvidence) {
        val complete = evidence.completion
        val physical = complete.originalSourceDocumentId?.let {
            hash(listOf("physical", sale.pharmacyId, complete.identity.sourceNamespace, it))
        }
        val fiscal = if (complete.originalFiscalNumber != null && complete.originalFiscalShift != null)
            hash(listOf("fiscal", sale.pharmacyId, complete.deviceNumber.trim().uppercase(), complete.originalFiscalShift, complete.originalFiscalNumber)) else null
        // Serialize refund-first against the original receipt, including different cashiers.
        receiptKeys(sale, evidence).sorted().forEach(::lockReceipt)
        jdbc.update("""INSERT INTO recommendation_reward_refund_holds(refund_sale_id,pharmacy_id,physical_key,fiscal_key)
            VALUES (?,?,?,?) ON CONFLICT (refund_sale_id) DO NOTHING""", sale.id, sale.pharmacyId, physical, fiscal)
        val targets = rows("""SELECT w.id FROM recommendation_sale_rewards w JOIN recommendation_reward_receipts r
            ON r.id=w.canonical_receipt_id WHERE w.pharmacy_id=? AND (r.physical_key=? OR r.fiscal_key=?)""", sale.pharmacyId, physical, fiscal)
        if (targets.isEmpty()) {
            audit(null, sale.pharmacyId, "device:${sale.deviceId}", "unlinked_refund", "Refund needs an original verified receipt", "refund:${sale.id}")
            val canonical = canonical(sale, evidence)
            if (canonical != null) jdbc.update("""INSERT INTO recommendation_sale_rewards
                (id,canonical_receipt_id,pharmacy_id,device_id,source_sale_id,product_id,state,reason_code)
                VALUES (?,?,?,?,?,'__refund__','refund_review','original_receipt_pending')
                ON CONFLICT (canonical_receipt_id,product_id) DO NOTHING""",
                "rw_${hash(listOf(canonical, "__refund__")).take(56)}", canonical, sale.pharmacyId, sale.deviceId, sale.id)
        } else targets.forEach { row ->
            val id = row["id"].toString()
            jdbc.update("UPDATE recommendation_sale_rewards SET state='refund_review',reason_code='refund_reported',updated_at=now() WHERE id=?", id)
            audit(id, sale.pharmacyId, "device:${sale.deviceId}", "refund_reported", "HQ review; original credit is retained", "refund:${hash(listOf(sale.id, id))}")
        }
    }

    private fun flagEventCases(eventId: String, reason: String) {
        jdbc.update("""UPDATE recommendation_sale_rewards SET state=CASE WHEN budget_reserved THEN 'refund_review'
            ELSE 'review_required' END,reason_code=?,updated_at=now() WHERE event_id=?""", reason, eventId)
    }
    private fun hold(id: String, reason: String) { jdbc.update("UPDATE recommendation_sale_rewards SET state='review_required',reason_code=?,updated_at=now() WHERE id=?", reason, id) }
    private fun receiptKeys(sale: PosSaleEntity, evidence: RewardSaleEvidence): Set<String> {
        val c = evidence.completion
        val keys = mutableSetOf<String>()
        if (c.identity.sourceNamespace.matches(Regex("[a-f0-9]{64}"))) {
            sale.sourceDocumentId?.let { keys += hash(listOf("physical", sale.pharmacyId, c.identity.sourceNamespace, it)) }
            if (c.status == "refund") c.originalSourceDocumentId?.let {
                keys += hash(listOf("physical", sale.pharmacyId, c.identity.sourceNamespace, it))
            }
        }
        if (c.status == "paid" && c.deviceNumber.isNotBlank() && c.fiscalNumber > 0 && c.fiscalShift > 0)
            keys += hash(listOf("fiscal", sale.pharmacyId, c.deviceNumber.trim().uppercase(), c.fiscalShift, c.fiscalNumber))
        if (c.status == "refund" && c.originalFiscalNumber != null && c.originalFiscalShift != null)
            keys += hash(listOf("fiscal", sale.pharmacyId, c.deviceNumber.trim().uppercase(), c.originalFiscalShift, c.originalFiscalNumber))
        return keys
    }
    private fun lockReceipt(key: String) { jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "reward-receipt:$key") }
    private fun lockSession(id: String) { jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "recommendation:$id") }
    private fun rows(sql: String, vararg values: Any?): List<Map<String, Any?>> = jdbc.queryForList(sql, *values)
    private fun json(value: Any): String = mapper.writeValueAsString(value)
    private fun hash(value: Any): String = MessageDigest.getInstance("SHA-256").digest(canonicalJson(mapper.valueToTree(value)).toByteArray()).joinToString("") { "%02x".format(it) }
    private fun canonicalJson(node: JsonNode): String = when {
        node.isObject -> node.fieldNames().asSequence().sorted().joinToString(",", "{", "}") { json(it) + ":" + canonicalJson(node[it]) }
        node.isArray -> node.joinToString(",", "[", "]") { canonicalJson(it) }
        node.isNumber -> node.decimalValue().stripTrailingZeros().toPlainString()
        else -> node.toString()
    }
    private fun audit(id: String?, pharmacy: String, actor: String, action: String, reason: String, key: String) {
        jdbc.update("""INSERT INTO recommendation_reward_audit(id,reward_id,pharmacy_id,actor_id,action,reason,source_key)
            VALUES (?,?,?,?,?,?,?) ON CONFLICT (source_key) DO NOTHING""", "ra_${UUID.randomUUID().toString().replace("-", "")}", id, pharmacy, actor, action, reason, key)
    }
}
