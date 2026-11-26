package kz.epharm.finance.premium

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.persistence.EntityManager
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import kz.epharm.auth.security.AdminPrincipal
import kz.epharm.finance.service.PharmacistWalletService
import kz.epharm.finance.service.SalesPremiumOutcome
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * The monthly sales premium of sellers, computed outside ePharm from the central Standard-N database, arrives as
 * cumulative month-to-date figures per IIN and is credited to the pharmacist wallet by the difference to the ledger.
 * Sellers without an active card wait in `sales_premium_pending` until one appears (see [SalesPremiumPendingCredits]).
 */
@Service
class SalesPremiumService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val wallet: PharmacistWalletService,
    private val entityManager: EntityManager,
    private val json: ObjectMapper,
    private val clock: Clock = Clock.system(SalesPremiumRules.ALMATY),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Same figures as a real import would give now. The transaction is read-only: PostgreSQL rejects any write. */
    @Transactional(readOnly = true)
    fun preview(request: SalesPremiumImportRequest): SalesPremiumImportResult {
        val input = SalesPremiumRules.validate(request, LocalDate.now(clock))
        requireNotStale(input)
        val plan = plan(input)
        val ids = plan.matched.map { it.pharmacistId }
        val balances = balances(ids)
        val credited = creditedSums(ids, input.period)
        val tally = Tally(input)
        plan.matched.forEach { match ->
            val decision = SalesPremiumRules.decide(
                match.row.amountKzt, credited[match.pharmacistId] ?: 0L, balances[match.pharmacistId] ?: 0L,
            )
            tally.matched(match.row, SalesPremiumOutcome(decision.creditKzt, decision.correctionKzt, decision.uncoveredKzt, 0))
        }
        plan.held.forEach(tally::held)
        plan.ambiguous.forEach(tally::ambiguous)
        return tally.result(dryRun = true, auditId = null)
    }

    /**
     * Credits what the file adds, writes what waits for an absent card to the pending table and one audit row, all in
     * one transaction. Imports of the same month are serialised; pharmacist rows are locked in id order, like the
     * payout generation does, so the two never wait for each other in a circle.
     */
    @Transactional
    fun apply(request: SalesPremiumImportRequest, actor: AdminPrincipal): SalesPremiumImportResult {
        val input = SalesPremiumRules.validate(request, LocalDate.now(clock))
        jdbc.jdbcTemplate.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", "sales-premium:import:${input.period}")
        requireNotStale(input)
        val plan = plan(input)
        val tally = Tally(input)
        plan.matched.sortedBy { it.pharmacistId }.forEach { match ->
            val outcome = wallet.applySalesPremium(match.pharmacistId, input.period, input.through, match.row.amountKzt)
            // The figure is now in the ledger; a waiting row of the same month would only repeat it.
            jdbc.update("DELETE FROM sales_premium_pending WHERE iin = :iin AND period = :period",
                mapOf("iin" to match.row.iin, "period" to input.period))
            // Everything is written with JDBC; dropping the loaded cards keeps every flush cheap for 5000 rows.
            entityManager.clear()
            tally.matched(match.row, outcome)
        }
        plan.held.forEach { held ->
            storePending(held.row, input)
            tally.held(held)
        }
        plan.ambiguous.forEach(tally::ambiguous)
        val summary = tally.result(dryRun = false, auditId = null)
        val auditId = writeAudit(input, summary, actor)
        log.info(
            "Sales premium import {} period={} through={} rows={} matched={} credited={} corrections={} pending={} ambiguous={}",
            auditId, input.period, input.through, summary.rows.count, summary.matched.count, summary.credited.sumKzt,
            summary.corrections.appliedKzt, summary.pending.count, summary.ambiguous.count,
        )
        return summary.copy(auditId = auditId.toString())
    }

    /** A cut-off older than one already loaded for the month would take back valid credits: refuse the whole file. */
    private fun requireNotStale(input: ValidatedImport) {
        val latest = jdbc.queryForObject(
            "SELECT max(through) FROM sales_premium_imports WHERE period = :period",
            mapOf("period" to input.period),
        ) { rs, _ -> rs.getObject(1, LocalDate::class.java) }
        if (latest != null && input.through.isBefore(latest)) {
            throw AppException(
                ErrorCode.CONFLICT,
                "Срез на ${input.through} старше уже загруженного ($latest). Пришлите срез не раньше этой даты",
                HttpStatus.CONFLICT,
            )
        }
    }

    private fun plan(input: ValidatedImport): Plan {
        val cards = input.rows.map { it.iin }.chunked(IN_CHUNK).flatMap { chunk ->
            jdbc.query("SELECT iin, id, status FROM pharmacists WHERE iin IN (:iins)", mapOf("iins" to chunk)) { rs, _ ->
                Triple(rs.getString("iin"), rs.getString("id"), rs.getString("status"))
            }
        }.groupBy({ it.first }, { PremiumCandidate(it.second, it.third == "active") })
        val matched = ArrayList<Match>()
        val held = ArrayList<Held>()
        val ambiguous = ArrayList<PremiumRow>()
        for (row in input.rows) {
            val candidates = cards[row.iin].orEmpty()
            when (val resolution = SalesPremiumRules.resolve(candidates)) {
                is IinResolution.Matched -> matched += Match(row, resolution.pharmacistId)
                IinResolution.NoPharmacist -> held += Held(row, notActive = false)
                IinResolution.NotActive -> held += Held(row, notActive = true)
                IinResolution.Ambiguous -> ambiguous += row
            }
        }
        return Plan(matched, held, ambiguous)
    }

    private fun balances(ids: List<String>): Map<String, Long> = ids.chunked(IN_CHUNK).flatMap { chunk ->
        jdbc.query("SELECT id, balance FROM pharmacists WHERE id IN (:ids)", mapOf("ids" to chunk)) { rs, _ ->
            rs.getString("id") to rs.getLong("balance")
        }
    }.toMap()

    private fun creditedSums(ids: List<String>, period: String): Map<String, Long> = ids.chunked(IN_CHUNK).flatMap { chunk ->
        jdbc.query(
            """SELECT pharmacist_id, COALESCE(SUM(amount_kzt),0) AS credited FROM pharmacist_wallet_entries
                WHERE pharmacist_id IN (:ids) AND context_key = :context
                  AND source IN ('sales_premium','sales_premium_correction') GROUP BY pharmacist_id""",
            mapOf("ids" to chunk, "context" to SalesPremiumRules.contextKey(period)),
        ) { rs, _ -> rs.getString("pharmacist_id") to rs.getLong("credited") }
    }.toMap()

    /** The latest figure of a seller without an active card; a zero figure owes nothing and clears the row. */
    private fun storePending(row: PremiumRow, input: ValidatedImport) {
        if (row.amountKzt == 0L) {
            jdbc.update("DELETE FROM sales_premium_pending WHERE iin = :iin AND period = :period",
                mapOf("iin" to row.iin, "period" to input.period))
            return
        }
        jdbc.update(
            """INSERT INTO sales_premium_pending(iin, period, amount_kzt, through, updated_at)
                VALUES (:iin, :period, :amount, :through, now())
                ON CONFLICT (iin, period) DO UPDATE
                SET amount_kzt = EXCLUDED.amount_kzt, through = EXCLUDED.through, updated_at = now()""",
            MapSqlParameterSource().addValue("iin", row.iin).addValue("period", input.period)
                .addValue("amount", row.amountKzt).addValue("through", input.through),
        )
    }

    /** No IIN, name or phone: the hash recognises a file, the ledger entries hold the per-person amounts. */
    private fun writeAudit(input: ValidatedImport, summary: SalesPremiumImportResult, actor: AdminPrincipal): UUID {
        val id = UUID.randomUUID()
        val withoutIins = summary.copy(ambiguous = summary.ambiguous.copy(iins = emptyList()))
        jdbc.update(
            """INSERT INTO sales_premium_imports(id, period, through, actor_id, actor_role, payload_sha256, rows_count, summary)
                VALUES (:id, :period, :through, :actor, :role, :hash, :rows, CAST(:summary AS jsonb))""",
            MapSqlParameterSource().addValue("id", id).addValue("period", input.period)
                .addValue("through", input.through).addValue("actor", actor.userId)
                .addValue("role", actor.role.name).addValue("hash", input.payloadSha256)
                .addValue("rows", input.rows.size).addValue("summary", json.writeValueAsString(withoutIins)),
        )
        return id
    }

    private class Match(val row: PremiumRow, val pharmacistId: String)
    private class Held(val row: PremiumRow, val notActive: Boolean)
    private class Plan(val matched: List<Match>, val held: List<Held>, val ambiguous: List<PremiumRow>)

    /** Adds the rows up in the shape of the response. */
    private class Tally(private val input: ValidatedImport) {
        private var matchedCount = 0
        private var matchedSum = 0L
        private var creditedCount = 0
        private var creditedSum = 0L
        private var correctionCount = 0
        private var correctionApplied = 0L
        private var correctionUncovered = 0L
        private var unchanged = 0
        private var pendingCount = 0
        private var pendingSum = 0L
        private var notActive = 0
        private val ambiguousIins = ArrayList<String>()
        private var ambiguousSum = 0L

        fun matched(row: PremiumRow, outcome: SalesPremiumOutcome) {
            matchedCount++
            matchedSum = Math.addExact(matchedSum, row.amountKzt)
            if (outcome.creditedKzt > 0) {
                creditedCount++
                creditedSum = Math.addExact(creditedSum, outcome.creditedKzt)
            }
            if (outcome.correctedKzt > 0 || outcome.uncoveredKzt > 0) {
                correctionCount++
                correctionApplied = Math.addExact(correctionApplied, outcome.correctedKzt)
                correctionUncovered = Math.addExact(correctionUncovered, outcome.uncoveredKzt)
            }
            if (outcome.creditedKzt == 0L && outcome.correctedKzt == 0L && outcome.uncoveredKzt == 0L) unchanged++
        }

        fun held(held: Held) {
            pendingCount++
            pendingSum = Math.addExact(pendingSum, held.row.amountKzt)
            if (held.notActive) notActive++
        }

        fun ambiguous(row: PremiumRow) {
            ambiguousIins += row.iin
            ambiguousSum = Math.addExact(ambiguousSum, row.amountKzt)
        }

        fun result(dryRun: Boolean, auditId: String?) = SalesPremiumImportResult(
            period = input.period, through = input.through, dryRun = dryRun,
            rows = PremiumTotals(input.rows.size, input.rows.fold(0L) { sum, row -> Math.addExact(sum, row.amountKzt) }),
            matched = PremiumTotals(matchedCount, matchedSum),
            credited = PremiumTotals(creditedCount, creditedSum),
            corrections = PremiumCorrections(correctionCount, correctionApplied, correctionUncovered),
            unchanged = unchanged,
            pending = PremiumPending(pendingCount, pendingSum, notActive),
            ambiguous = PremiumAmbiguous(ambiguousIins.size, ambiguousSum, ambiguousIins.sorted()),
            packsTotal = input.packsTotal.setScale(2, RoundingMode.HALF_UP),
            auditId = auditId,
        )
    }

    private companion object {
        const val IN_CHUNK = 2000
    }
}
