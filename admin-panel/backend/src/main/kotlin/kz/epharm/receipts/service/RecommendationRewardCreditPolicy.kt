package kz.epharm.receipts.service

import java.math.BigDecimal
import kz.epharm.posm.repository.PosSaleRepository
import kz.epharm.receipts.entity.PendingBonusEntity
import kz.epharm.receipts.entity.PendingBonusStatus
import kz.epharm.receipts.entity.ReceiptEntity
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

data class RecommendationRewardCreditDecision(val allowed: Boolean, val amountKzt: Long, val reasonCode: String? = null)

/** Caller holds pending -> receipt locks; this policy adds case -> promo, then the shared wallet writer runs. */
@Service
class RecommendationRewardCreditPolicy(private val jdbc: JdbcTemplate, private val sales: PosSaleRepository) {
    fun attachReceipt(pending: PendingBonusEntity, receipt: ReceiptEntity, sourceSaleId: String): String? {
        val reward = reward(pending.id, receipt.id) ?: conflict("reward_case_missing")
        validateBinding(reward, pending, receipt)
        if (reward.sourceSaleId != sourceSaleId) conflict("reward_sale_conflict")
        bindReceipt(reward, receipt.id)
        return reward.reason
    }

    fun beforeCredit(pending: PendingBonusEntity, receipt: ReceiptEntity): RecommendationRewardCreditDecision {
        // The absence of a v2 case is not permission to revive a closed legacy reservation.
        // Already-approved receipt retries return before this new-credit decision.
        if (pending.status != PendingBonusStatus.awaiting_receipt) conflict("reward_not_ready")
        val reward = reward(pending.id, receipt.id)
            ?: return RecommendationRewardCreditDecision(true, pending.bonus) // Preserve legacy receipt decisions.
        validateBinding(reward, pending, receipt)
        if (reward.state != "ready" || pending.status != PendingBonusStatus.awaiting_receipt) conflict("reward_not_ready")
        activeBeneficiary(reward)
        val total = try { Math.multiplyExact(reward.unitBonus, reward.packages) }
            catch (_: ArithmeticException) { conflict("reward_amount_overflow") }
        if (reward.unitBonus <= 0 || reward.packages <= 0 || total != reward.total || pending.bonus != reward.unitBonus) {
            conflict("reward_amount_conflict")
        }
        val sale = sales.findById(reward.sourceSaleId).orElse(null) ?: conflict("reward_sale_missing")
        if (sale.pharmacyId != reward.pharmacy || sale.pharmacistId != reward.pharmacist || sale.totalAmount <= 0) {
            conflict("reward_sale_identity_conflict")
        }
        val lines = sale.items.filter { it.productId == reward.product }
        if (lines.isEmpty() || lines.any { !it.qty.isFinite() || it.qty <= 0 || it.price <= 0 || it.total <= 0 }) {
            conflict("reward_sale_product_conflict")
        }
        val paidPackages = lines.fold(BigDecimal.ZERO) { sum, line -> sum.add(BigDecimal.valueOf(line.qty)) }
        val paidAmount = try { lines.fold(0L) { sum, line -> Math.addExact(sum, line.total) } }
            catch (_: ArithmeticException) { conflict("reward_sale_amount_overflow") }
        if (paidPackages < BigDecimal.valueOf(reward.packages) || receipt.parsedAmount != paidAmount ||
            receipt.parsedAt != sale.printedAt) conflict("reward_sale_quantity_conflict")
        val promo = reward.promo ?: return budgetReview(reward, "reward_budget_missing")
        // Funding changes no key: allow concurrent FK references without lock-upgrade deadlocks.
        val budget = jdbc.queryForList("SELECT budget,spent FROM promos WHERE id=? FOR NO KEY UPDATE", promo).singleOrNull()
            ?: return budgetReview(reward, "reward_budget_missing")
        val limit = number(budget, "budget"); val spent = number(budget, "spent")
        if (limit <= 0 || spent < 0 || spent > limit) return budgetReview(reward, "reward_budget_invalid")
        if (reward.reserved) {
            if (spent < total) conflict("reward_budget_reservation_conflict")
        } else {
            if (total > limit - spent) return budgetReview(reward, "reward_budget_exhausted")
            if (jdbc.update("UPDATE promos SET spent=spent+? WHERE id=? AND spent=?", total, promo, spent) != 1) {
                conflict("reward_budget_changed")
            }
            if (jdbc.update("UPDATE recommendation_sale_rewards SET budget_reserved=true,updated_at=now() WHERE id=? AND budget_reserved=false", reward.id) != 1) {
                conflict("reward_budget_reservation_conflict")
            }
        }
        bindReceipt(reward, receipt.id)
        return RecommendationRewardCreditDecision(true, total)
    }

    fun afterCredit(pending: PendingBonusEntity, receipt: ReceiptEntity, credited: Long) {
        val reward = reward(pending.id, receipt.id) ?: return
        validateBinding(reward, pending, receipt)
        // The wallet now holds the beneficiary row lock: a concurrent move/block cannot slip between check and credit.
        activeBeneficiary(reward)
        if (reward.state != "ready" || reward.total != credited || !reward.reserved) conflict("reward_credit_conflict")
        if (jdbc.update("""UPDATE recommendation_sale_rewards SET state='credited',reason_code='credited',receipt_id=?,updated_at=now()
            WHERE id=? AND state='ready' AND budget_reserved=true""", receipt.id, reward.id) != 1) conflict("reward_credit_conflict")
    }

    private fun validateBinding(r: Reward, p: PendingBonusEntity, receipt: ReceiptEntity) {
        if (r.pending != p.id || receipt.pendingBonusId != p.id || r.pharmacist.isNullOrBlank() ||
            r.pharmacist != p.pharmacistId || r.pharmacist != receipt.pharmacistId ||
            r.pharmacy != p.pharmacyId || r.pharmacy != receipt.pharmacyId || r.product != p.sku ||
            r.product != receipt.parsedSku || r.sourceSaleId != p.sourceSaleId ||
            (r.receipt != null && r.receipt != receipt.id)) conflict("reward_identity_conflict")
    }

    private fun activeBeneficiary(r: Reward) {
        val person = jdbc.queryForList("SELECT status,pharmacy_id FROM pharmacists WHERE id=?", r.pharmacist).singleOrNull()
        if (person?.get("status") != "active" || person["pharmacy_id"] != r.pharmacy) conflict("reward_beneficiary_changed")
    }

    private fun bindReceipt(r: Reward, receipt: String) {
        if (jdbc.update("""UPDATE recommendation_sale_rewards SET receipt_id=?,updated_at=now()
            WHERE id=? AND (receipt_id IS NULL OR receipt_id=?)""", receipt, r.id, receipt) != 1) conflict("reward_receipt_conflict")
    }

    private fun budgetReview(r: Reward, reason: String): RecommendationRewardCreditDecision {
        if (jdbc.update("""UPDATE recommendation_sale_rewards SET state='review_required',reason_code=?,updated_at=now()
            WHERE id=? AND state='ready'""", reason, r.id) != 1) conflict("reward_state_changed")
        return RecommendationRewardCreditDecision(false, 0, reason)
    }

    private fun reward(pending: String, receipt: String): Reward? {
        val rows = jdbc.queryForList("""SELECT * FROM recommendation_sale_rewards
            WHERE pending_bonus_id=? OR receipt_id=? ORDER BY id FOR UPDATE""", pending, receipt)
        if (rows.size > 1) conflict("reward_receipt_conflict")
        return rows.singleOrNull()?.let { r -> Reward(
            r["id"] as String, r["pharmacy_id"] as String, r["pharmacist_id"] as? String,
            r["source_sale_id"] as String, r["product_id"] as String, r["promo_id"] as? String,
            r["state"] as String, r["reason_code"] as? String, number(r, "unit_bonus"), number(r, "eligible_packages"),
            number(r, "total_bonus"), r["pending_bonus_id"] as? String, r["receipt_id"] as? String, r["budget_reserved"] as Boolean,
        ) }
    }

    private fun number(row: Map<String, Any>, key: String): Long = (row[key] as? Number)?.toLong() ?: conflict("reward_amount_missing")
    private data class Reward(val id: String, val pharmacy: String, val pharmacist: String?, val sourceSaleId: String,
        val product: String, val promo: String?, val state: String, val reason: String?, val unitBonus: Long,
        val packages: Long, val total: Long, val pending: String?, val receipt: String?, val reserved: Boolean)
    private fun conflict(reason: String): Nothing = throw AppException(ErrorCode.CONFLICT, reason, HttpStatus.CONFLICT)
}
