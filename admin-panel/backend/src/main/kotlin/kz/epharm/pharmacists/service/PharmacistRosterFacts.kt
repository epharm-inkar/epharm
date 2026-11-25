package kz.epharm.pharmacists.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import kz.epharm.pharmacists.dto.PharmacistPharmacyRefDto
import kz.epharm.pharmacists.entity.PharmacistEntity
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Instant
import java.time.ZoneId

/** Per-person facts for the HQ roster; read-only, never written back to the stale profile counters. */
data class PharmacistRosterFact(
    val lastSaleAt: Instant?,
    val earnedMonthKzt: Long,
    val openAssignments: Int,
    val standardNPharmacies: List<PharmacistPharmacyRefDto>,
)

/**
 * One statement per roster response (no per-row round trips). Every correlated subquery is an index probe:
 * - last sale: `ix_pos_sales_seller_iin_printed` (V112, by IIN: a person may sell in several pharmacies) and
 *   `idx_pos_sales_pharmacist_printed` (V109, saved seller ID of older receipts), backward scan, first non-refund;
 * - month earnings: `ix_wallet_pharmacist_created` (V062), the same sum as the till's personal window;
 * - open training: `ix_training_assignment_pharmacist` (V035);
 * - Standard-N pharmacies: `ix_standardn_directory_active_iin` (V055) and `ix_pharmacies_standardn_profile` (V112).
 * The literal `pharmacist_id <> ''` and `seller_iin IS NOT NULL` keep the planner on the partial indexes.
 */
@Component
class PharmacistRosterFacts(
    private val jdbc: NamedParameterJdbcTemplate,
    private val mapper: ObjectMapper,
) {
    fun load(people: Collection<PharmacistEntity>, observedAt: Instant = Instant.now()): Map<String, PharmacistRosterFact> {
        val ids = people.map { it.id }.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return emptyMap()
        val monthStart = observedAt.atZone(ALMATY).toLocalDate().withDayOfMonth(1).atStartOfDay(ALMATY).toInstant()
        return ids.chunked(CHUNK).flatMap { chunk ->
            val params = MapSqlParameterSource()
                .addValue("ids", chunk)
                .addValue("observedAt", Timestamp.from(observedAt))
                .addValue("monthStart", Timestamp.from(monthStart))
            jdbc.query(SQL, params) { rs, _ ->
                rs.getString("pharmacist_id") to PharmacistRosterFact(
                    lastSaleAt = rs.getTimestamp("last_sale_at")?.toInstant(),
                    earnedMonthKzt = rs.getLong("earned_month_kzt").coerceAtLeast(0),
                    openAssignments = rs.getLong("open_assignments").coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
                    standardNPharmacies = pharmacies(rs.getString("standardn_pharmacies")),
                )
            }
        }.toMap()
    }

    private fun pharmacies(json: String?): List<PharmacistPharmacyRefDto> =
        if (json.isNullOrBlank()) emptyList() else mapper.readValue(json, PHARMACY_LIST)

    private companion object {
        val ALMATY: ZoneId = ZoneId.of("Asia/Almaty")
        const val CHUNK = 5_000
        val PHARMACY_LIST = object : TypeReference<List<PharmacistPharmacyRefDto>>() {}
        const val NOT_REFUND = "COALESCE(s.reward_evidence #>> '{completion,status}', '') <> 'refund'"
        val SQL = """
            SELECT p.id AS pharmacist_id,
                   GREATEST(
                       (SELECT MAX(s.printed_at) FROM pos_sales s
                         WHERE s.seller_iin = p.iin AND s.seller_iin IS NOT NULL
                           AND s.printed_at <= :observedAt AND $NOT_REFUND),
                       (SELECT MAX(s.printed_at) FROM pos_sales s
                         WHERE s.pharmacist_id = p.id AND s.pharmacist_id <> ''
                           AND s.printed_at <= :observedAt AND $NOT_REFUND)
                   ) AS last_sale_at,
                   (SELECT COALESCE(SUM(w.earned_delta_kzt), 0) FROM pharmacist_wallet_entries w
                     WHERE w.pharmacist_id = p.id AND w.created_at >= :monthStart) AS earned_month_kzt,
                   (SELECT COUNT(*) FROM training_assignments a
                     WHERE a.pharmacist_id = p.id AND a.status NOT IN ('completed', 'cancelled')) AS open_assignments,
                   (SELECT COALESCE(json_agg(json_build_object('id', ph.id, 'name', ph.name, 'city', COALESCE(ph.city, ''))
                                             ORDER BY ph.name, ph.id), '[]'::json)::text
                      FROM pharmacies ph
                     WHERE ph.standardn_profile_id IN (
                               SELECT d.source_profile_id FROM standardn_pharmacist_directory d
                                WHERE d.iin = p.iin AND d.source_status = 0)) AS standardn_pharmacies
              FROM pharmacists p
             WHERE p.id IN (:ids)
        """.trimIndent()
    }
}
