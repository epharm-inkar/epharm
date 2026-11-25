package kz.epharm.pharmacists.service

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * When pharmacists worked with ePharm, and how coverage of the network grows (Grigoriy, 25.11.2026:
 * «Активные: сколько человек открывали ePharm за последнюю неделю»; a coverage page with a chart by date).
 *
 * «Worked with ePharm» is any of: a sale the till attributed to the pharmacist, a recommendation the till
 * showed them, a sign-in to the cabinet. Every source is read per pharmacist through its own
 * (pharmacist_id, time) index, never by scanning the two-million-row sales table.
 */
@Service
class PharmacistActivityService(
    private val jdbc: JdbcTemplate,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** The last activity of each pharmacist since [since]; pharmacists without activity are absent. */
    @Transactional(readOnly = true)
    fun lastActiveSince(since: Instant): Map<String, Instant> {
        val at = Timestamp.from(since)
        val result = HashMap<String, Instant>()
        // Measured on production data (2 million sales, 25.11.2026): correlated max() per source took 20-77 s,
        // this lateral read through each (pharmacist_id, time) index takes about 0.15 s. The sales index is
        // partial («pharmacist_id <> ''»), so the condition must be spelled out for the planner to use it.
        jdbc.query(
            """
            select a.pharmacist_id, max(a.at) as last_active from (
                select p.id as pharmacist_id, s.printed_at as at
                  from pharmacists p
                  join lateral (select printed_at from pos_sales
                                 where pharmacist_id = p.id and pharmacist_id <> '' and printed_at >= ?) s on true
                union all
                select p.id, r.shown_at
                  from pharmacists p
                  join lateral (select shown_at from recommendation_events
                                 where pharmacist_id = p.id and shown_at >= ?) r on true
                union all
                select pharmacist_id, created_at from mobile_refresh_tokens where created_at >= ?
            ) a
            group by a.pharmacist_id
            """.trimIndent(),
            { rs ->
                rs.getTimestamp("last_active")?.let { result[rs.getString("pharmacist_id")] = it.toInstant() }
            },
            at, at, at,
        )
        return result
    }

    /**
     * Coverage by day for the last [days] days (Almaty dates): pharmacists in the system that day, how
     * many worked with ePharm in the seven days up to it, and the share. Today's row is the headline.
     */
    @Transactional(readOnly = true)
    fun coverage(days: Int): PharmacistCoverageDto {
        val span = days.coerceIn(7, 120)
        val today = LocalDate.now(clock.withZone(ALMATY))
        val first = today.minusDays((span - 1).toLong())
        // Activity window starts six days before the first chart day, so its seven-day count is complete.
        val since = first.minusDays(6).atStartOfDay(ALMATY).toInstant()
        val at = Timestamp.from(since)

        val activeDays = HashMap<String, MutableSet<LocalDate>>()
        val saleDays = HashMap<String, MutableSet<LocalDate>>()
        val cabinetDays = HashMap<String, MutableSet<LocalDate>>()
        jdbc.query(
            """
            select a.pharmacist_id, a.day, a.source from (
                select p.id as pharmacist_id, (s.printed_at at time zone 'Asia/Almaty')::date as day, 'sale' as source
                  from pharmacists p
                  join lateral (select printed_at from pos_sales
                                 where pharmacist_id = p.id and pharmacist_id <> '' and printed_at >= ?) s on true
                union
                select p.id, (r.shown_at at time zone 'Asia/Almaty')::date, 'tip'
                  from pharmacists p
                  join lateral (select shown_at from recommendation_events where pharmacist_id = p.id and shown_at >= ?) r on true
                union
                select t.pharmacist_id, (t.created_at at time zone 'Asia/Almaty')::date, 'cabinet'
                  from mobile_refresh_tokens t
                 where t.created_at >= ?
            ) a
            """.trimIndent(),
            { rs ->
                val id = rs.getString("pharmacist_id")
                val day = rs.getObject("day", LocalDate::class.java)
                activeDays.getOrPut(id) { HashSet() }.add(day)
                when (rs.getString("source")) {
                    "sale" -> saleDays.getOrPut(id) { HashSet() }.add(day)
                    "cabinet" -> cabinetDays.getOrPut(id) { HashSet() }.add(day)
                }
            },
            at, at, at,
        )

        data class Person(val joined: LocalDate, val source: String, val blocked: Boolean)
        val people = jdbc.query(
            """
            select id, (created_at at time zone 'Asia/Almaty')::date as joined, account_source, status
              from pharmacists
            """.trimIndent(),
        ) { rs, _ ->
            rs.getString("id") to Person(
                joined = rs.getObject("joined", LocalDate::class.java),
                source = rs.getString("account_source"),
                blocked = rs.getString("status") == "blocked",
            )
        }.toMap()

        fun activeIn(set: Map<String, Set<LocalDate>>, id: String, from: LocalDate, to: LocalDate) =
            set[id]?.any { !it.isBefore(from) && !it.isAfter(to) } == true

        val daily = (0 until span).map { offset ->
            val day = first.plusDays(offset.toLong())
            val weekStart = day.minusDays(6)
            val inSystem = people.filter { (_, person) -> !person.joined.isAfter(day) && !person.blocked }.keys
            val active = inSystem.count { activeIn(activeDays, it, weekStart, day) }
            val sellers = inSystem.count { activeIn(saleDays, it, weekStart, day) }
            PharmacistCoverageDayDto(
                date = day,
                total = inSystem.size,
                active7d = active,
                sellers7d = sellers,
                coveragePct = percent(active, inSystem.size),
                joined = people.values.count { it.joined == day },
            )
        }
        val now = daily.last()
        val weekStart = today.minusDays(6)
        val inSystem = people.filter { (_, person) -> !person.blocked }
        return PharmacistCoverageDto(
            date = today,
            total = inSystem.size,
            fromStandardN = inSystem.values.count { it.source == "standardn" },
            registered = inSystem.values.count { it.source != "standardn" },
            active7d = now.active7d,
            sellers7d = now.sellers7d,
            cabinet7d = inSystem.keys.count { activeIn(cabinetDays, it, weekStart, today) },
            coveragePct = now.coveragePct,
            daily = daily,
            generatedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS),
        )
    }

    private fun percent(part: Int, whole: Int): Double =
        if (whole == 0) 0.0 else Math.round(part * 1000.0 / whole) / 10.0

    companion object {
        val ALMATY: ZoneId = ZoneId.of("Asia/Almaty")
    }
}

data class PharmacistCoverageDayDto(
    val date: LocalDate,
    /** Pharmacists in the system that day (not blocked). */
    val total: Int,
    /** Worked with ePharm in the seven days up to this day: a sale, a tip shown, a cabinet sign-in. */
    val active7d: Int,
    /** Sold through ePharm (the till knew who sold) in the same seven days. */
    val sellers7d: Int,
    val coveragePct: Double,
    /** New in the system that day (registered or brought from Standard-N). */
    val joined: Int,
)

data class PharmacistCoverageDto(
    val date: LocalDate,
    val total: Int,
    val fromStandardN: Int,
    val registered: Int,
    val active7d: Int,
    val sellers7d: Int,
    val cabinet7d: Int,
    val coveragePct: Double,
    val daily: List<PharmacistCoverageDayDto>,
    val generatedAt: Instant,
)
