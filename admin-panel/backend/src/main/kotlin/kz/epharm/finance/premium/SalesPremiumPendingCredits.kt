package kz.epharm.finance.premium

import jakarta.annotation.PreDestroy
import java.time.LocalDate
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kz.epharm.finance.service.PharmacistWalletService
import kz.epharm.pharmacists.entity.PharmacistStatus
import kz.epharm.pharmacists.repository.PharmacistRepository
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/** A pharmacist card that can hold money appeared or turned active; the waiting premium is credited after the commit. */
data class PharmacistCardReady(val pharmacistId: String)

/** [months] waiting figures were applied, [creditedKzt] added and [correctedKzt] taken back by them. */
data class PendingCreditResult(val months: Int, val creditedKzt: Long, val correctedKzt: Long)

/**
 * Credits the premium that waited in `sales_premium_pending` for an IIN without an active card. The pharmacist row is
 * locked first and the waiting rows after it, the same order the import uses, so the two cannot deadlock.
 */
@Service
class SalesPremiumPendingCredits(
    private val jdbc: NamedParameterJdbcTemplate,
    private val pharmacists: PharmacistRepository,
    private val wallet: PharmacistWalletService,
) {
    /** Active cards with a premium waiting, after [afterId] in id order. */
    @Transactional(readOnly = true)
    fun candidates(afterId: String, limit: Int): List<String> = jdbc.queryForList(
        """SELECT ph.id FROM pharmacists ph
            WHERE ph.status = 'active' AND ph.id > :after
              AND EXISTS (SELECT 1 FROM sales_premium_pending p WHERE p.iin = ph.iin)
            ORDER BY ph.id LIMIT :limit""",
        mapOf("after" to afterId, "limit" to limit), String::class.java,
    )

    /**
     * Own transaction: it runs after the commit of whatever made the card ready, or from the sweep, never inside a
     * transaction that already holds this card's row. A card that is not active credits nothing and keeps the rows.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun creditFor(pharmacistId: String): PendingCreditResult {
        val person = pharmacists.findByIdForUpdate(pharmacistId)?.takeIf { it.status == PharmacistStatus.active }
            ?: return PendingCreditResult(0, 0, 0)
        val waiting = jdbc.query(
            "SELECT period, amount_kzt, through FROM sales_premium_pending WHERE iin = :iin ORDER BY period FOR UPDATE",
            mapOf("iin" to person.iin),
        ) { rs, _ -> Waiting(rs.getString("period"), rs.getLong("amount_kzt"), rs.getObject("through", LocalDate::class.java)) }
        var credited = 0L
        var corrected = 0L
        for (row in waiting) {
            val outcome = wallet.applySalesPremium(person.id, row.period, row.through, row.amountKzt)
            credited = Math.addExact(credited, outcome.creditedKzt)
            corrected = Math.addExact(corrected, outcome.correctedKzt)
            jdbc.update("DELETE FROM sales_premium_pending WHERE iin = :iin AND period = :period",
                mapOf("iin" to person.iin, "period" to row.period))
        }
        return PendingCreditResult(waiting.size, credited, corrected)
    }

    private class Waiting(val period: String, val amountKzt: Long, val through: LocalDate)
}

/**
 * The quick path: a card created or activated by a service publishes [PharmacistCardReady]; after that transaction
 * commits, one background thread credits what waits for it. Failures only log: the sweep tries again later.
 */
@Component
class PharmacistCardReadyListener(private val credits: SalesPremiumPendingCredits) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val pool = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(500),
        { task -> Thread(task, "sales-premium-hook").apply { isDaemon = true } },
    ) { _, _ -> log.warn("Sales premium hook queue is full, the sweep will credit the card") }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onCardReady(event: PharmacistCardReady) {
        pool.execute { creditNow(event.pharmacistId) }
    }

    internal fun creditNow(pharmacistId: String) {
        try {
            val result = credits.creditFor(pharmacistId)
            if (result.months > 0) {
                log.info("Waiting sales premium credited to {}: months={} credited={} corrected={}",
                    pharmacistId, result.months, result.creditedKzt, result.correctedKzt)
            }
        } catch (e: Exception) {
            log.warn("Waiting sales premium of {} was not credited, the sweep will retry: {}", pharmacistId, e.javaClass.simpleName)
        }
    }

    @PreDestroy
    fun stop() {
        pool.shutdown()
    }
}

/** The light safety net: credits every active card that still has a waiting premium, a page of cards at a time. */
@Service
class SalesPremiumSweep(private val credits: SalesPremiumPendingCredits) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Returns how many cards got a waiting premium applied. A card that fails is logged and left for the next run. */
    fun sweepOnce(): Int {
        var afterId = ""
        var credited = 0
        for (page in 0 until MAX_PAGES) {
            val ids = credits.candidates(afterId, PAGE)
            if (ids.isEmpty()) break
            for (id in ids) {
                try {
                    if (credits.creditFor(id).months > 0) credited++
                } catch (e: Exception) {
                    log.warn("Waiting sales premium of {} was not credited: {}", id, e.javaClass.simpleName)
                }
            }
            afterId = ids.last()
            if (ids.size < PAGE) break
        }
        if (credited > 0) log.info("Sales premium sweep credited {} cards", credited)
        return credited
    }

    private companion object {
        const val PAGE = 200
        const val MAX_PAGES = 10
    }
}

/** Not part of the `test` profile: the integration tests call [SalesPremiumSweep] directly. */
@Component
@Profile("!test")
class SalesPremiumSweepScheduler(private val sweep: SalesPremiumSweep) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${app.finance.sales-premium.sweep-delay-ms:600000}",
        initialDelayString = "\${app.finance.sales-premium.sweep-initial-delay-ms:180000}",
    )
    fun run() {
        try {
            sweep.sweepOnce()
        } catch (e: Exception) {
            log.error("Sales premium sweep failed: {}", e.javaClass.simpleName)
        }
    }
}
