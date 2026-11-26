package kz.epharm.finance.service

import jakarta.persistence.EntityManager
import java.time.LocalDate
import java.util.UUID
import kz.epharm.auth.security.AdminPrincipal
import kz.epharm.finance.premium.SalesPremiumRules
import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

enum class WalletCreditSource(val value: String) {
    RECEIPT_BONUS("receipt_bonus"), TRAINING_BONUS("training_bonus"), MERCH_TASK("merch_task"),
}

data class WalletMutation(val entryId: UUID?, val balanceKzt: Long, val amountKzt: Long, val applied: Boolean)

/**
 * What bringing one pharmacist's premium of a month to the cumulative figure of the source did: [creditedKzt] added,
 * [correctedKzt] taken back, [uncoveredKzt] that a return wanted to take back but the balance did not hold.
 */
data class SalesPremiumOutcome(val creditedKzt: Long, val correctedKzt: Long, val uncoveredKzt: Long, val balanceKzt: Long)

/** One row lock covers every monetary writer; the ledger and the existing projection commit together. */
@Service
class PharmacistWalletService(
    private val jdbc: JdbcTemplate,
    private val pharmacists: PharmacistRepository,
    private val entityManager: EntityManager,
) {
    @Transactional
    fun credit(
        source: WalletCreditSource,
        sourceId: String,
        pharmacistId: String,
        amountKzt: Long,
        contextKey: String,
    ): WalletMutation {
        if (amountKzt <= 0) invalid("Сумма начисления должна быть положительной")
        return mutate(source.value, sourceId, pharmacistId, amountKzt, amountKzt, contextKey)
    }

    @Transactional
    fun adjustTo(pharmacistId: String, requestedBalanceKzt: Long): WalletMutation {
        if (requestedBalanceKzt < 0) invalid("Баланс не может быть отрицательным")
        val current = lockBalance(pharmacistId)
        val delta = requestedBalanceKzt - current
        if (delta == 0L) return WalletMutation(null, current, 0, false)
        val actor = (SecurityContextHolder.getContext().authentication?.principal as? AdminPrincipal)
            ?.userId?.toString() ?: "internal"
        return mutate("admin_adjustment", UUID.randomUUID().toString(), pharmacistId, delta, 0, "actor:$actor")
    }

    /** Reservation removes spendable funds; it is not evidence of a bank transfer. */
    @Transactional
    fun reserveForPayout(pharmacistId: String, payoutItemId: String): WalletMutation {
        val current = lockBalance(pharmacistId)
        val existing = existing("payout_reservation", payoutItemId)
        if (existing != null) {
            if (existing.pharmacistId != pharmacistId) conflict()
            return WalletMutation(existing.id, current, existing.amount, false)
        }
        if (current == 0L) return WalletMutation(null, current, 0, false)
        return mutate("payout_reservation", payoutItemId, pharmacistId, -current, 0, "payout-item:$payoutItemId")
    }

    /**
     * Sales premium: brings the net premium of [period] in the ledger (credits minus corrections) to [cumulativeKzt],
     * the month-to-date figure of the source. The difference is computed under the pharmacist row lock, so a repeated
     * or a concurrent import of the same figure writes nothing the second time. A rise is one `sales_premium` credit
     * (earned and balance up); a fall is one `sales_premium_correction` that lowers earned and balance by the same
     * amount but never below a zero balance, the rest is returned as uncovered and is tried again by the next import.
     */
    @Transactional
    fun applySalesPremium(pharmacistId: String, period: String, through: LocalDate, cumulativeKzt: Long): SalesPremiumOutcome {
        if (cumulativeKzt < 0) invalid("Премия не может быть отрицательной")
        val balance = lockBalance(pharmacistId)
        val context = SalesPremiumRules.contextKey(period)
        val credited = jdbc.queryForObject(
            """SELECT COALESCE(SUM(amount_kzt),0) FROM pharmacist_wallet_entries
                WHERE pharmacist_id=? AND context_key=? AND source IN (?,?)""",
            Long::class.java, pharmacistId, context, SALES_PREMIUM, SALES_PREMIUM_CORRECTION,
        ) ?: 0L
        val decision = SalesPremiumRules.decide(cumulativeKzt, credited, balance)
        var current = balance
        if (decision.creditKzt > 0) {
            val sourceId = nextSourceId(SALES_PREMIUM, SalesPremiumRules.sourceIdBase(period, pharmacistId, through))
            current = mutate(SALES_PREMIUM, sourceId, pharmacistId, decision.creditKzt, decision.creditKzt, context).balanceKzt
        }
        if (decision.correctionKzt > 0) {
            val sourceId = nextSourceId(SALES_PREMIUM_CORRECTION, SalesPremiumRules.sourceIdBase(period, pharmacistId, through))
            current = mutate(
                SALES_PREMIUM_CORRECTION, sourceId, pharmacistId,
                -decision.correctionKzt, -decision.correctionKzt, context,
            ).balanceKzt
        }
        return SalesPremiumOutcome(decision.creditKzt, decision.correctionKzt, decision.uncoveredKzt, current)
    }

    @Transactional
    fun lockPayoutGeneration() {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended('wallet:payout-generation', 0))")
    }

    private fun mutate(
        source: String, sourceId: String, pharmacistId: String,
        amount: Long, earnedDelta: Long, context: String,
    ): WalletMutation {
        if (sourceId.isBlank() || sourceId.length > 128 || context.length > 255 ||
            sourceId.any(Char::isISOControl) || context.any(Char::isISOControl)) invalid("Некорректный источник начисления")
        val current = lockBalance(pharmacistId)
        val previous = existing(source, sourceId)
        if (previous != null) {
            if (previous.pharmacistId != pharmacistId || previous.amount != amount ||
                previous.earnedDelta != earnedDelta || previous.context != context) conflict()
            return WalletMutation(previous.id, current, amount, false)
        }
        val next = try { Math.addExact(current, amount) } catch (_: ArithmeticException) {
            invalid("Сумма выходит за допустимые пределы")
        }
        if (next < 0) invalid("Недостаточно средств")
        val id = UUID.randomUUID()
        val inserted = jdbc.query(
            """INSERT INTO pharmacist_wallet_entries
                (id,pharmacist_id,source,source_id,amount_kzt,earned_delta_kzt,context_key,balance_after_kzt)
                VALUES (?,?,?,?,?,?,?,?) ON CONFLICT (source,source_id) DO NOTHING RETURNING id""",
            { rs, _ -> rs.getObject("id", UUID::class.java) },
            id, pharmacistId, source, sourceId, amount, earnedDelta, context, next,
        ).singleOrNull()
        // A concurrent request for the same source but another recipient must never credit both.
        if (inserted == null) conflict()
        // GREATEST: a premium correction lowers earned; the stored counter must never leave its CHECK (>= 0).
        jdbc.update(
            "UPDATE pharmacists SET balance=?, earned_30d=GREATEST(earned_30d+?,0), updated_at=now() WHERE id=?",
            next, earnedDelta, pharmacistId,
        )
        entityManager.refresh(pharmacists.findById(pharmacistId).orElseThrow())
        return WalletMutation(id, next, amount, true)
    }

    private fun lockBalance(pharmacistId: String): Long {
        // Callers can have pending JPA edits, including a new reward/receipt and profile fields.
        entityManager.flush()
        val balance = jdbc.query("SELECT balance FROM pharmacists WHERE id=? FOR UPDATE", { rs, _ -> rs.getLong(1) }, pharmacistId)
            .singleOrNull() ?: throw AppException(ErrorCode.NOT_FOUND, "Фармацевт не найден", HttpStatus.NOT_FOUND)
        jdbc.update("""INSERT INTO pharmacist_wallet_checkpoints(pharmacist_id,balance_kzt,earned_30d_kzt)
            SELECT id,balance,earned_30d FROM pharmacists WHERE id=? ON CONFLICT DO NOTHING""", pharmacistId)
        return balance
    }

    /** The first entry of a cut-off keeps the plain id; a repeated run of the same cut-off with other figures adds `#n`. */
    private fun nextSourceId(source: String, base: String): String {
        val taken = jdbc.queryForObject(
            "SELECT count(*) FROM pharmacist_wallet_entries WHERE source=? AND (source_id=? OR starts_with(source_id,?))",
            Long::class.java, source, base, "$base#",
        ) ?: 0L
        return if (taken == 0L) base else "$base#${taken + 1}"
    }

    private fun existing(source: String, sourceId: String): Existing? = jdbc.query(
        """SELECT id,pharmacist_id,amount_kzt,earned_delta_kzt,context_key
            FROM pharmacist_wallet_entries WHERE source=? AND source_id=?""",
        { rs, _ -> Existing(rs.getObject("id", UUID::class.java), rs.getString("pharmacist_id"),
            rs.getLong("amount_kzt"), rs.getLong("earned_delta_kzt"), rs.getString("context_key")) }, source, sourceId,
    ).singleOrNull()

    private data class Existing(val id: UUID, val pharmacistId: String, val amount: Long, val earnedDelta: Long, val context: String)
    private fun conflict(): Nothing = throw AppException(ErrorCode.CONFLICT,
        "Финансовое событие уже обработано с другими данными", HttpStatus.CONFLICT)
    private fun invalid(message: String): Nothing = throw AppException(ErrorCode.VALIDATION_FAILED, message)

    private companion object {
        const val SALES_PREMIUM = "sales_premium"
        const val SALES_PREMIUM_CORRECTION = "sales_premium_correction"
    }
}
