package kz.epharm.posm.messages

import kz.epharm.pharmacists.service.PharmacistSearch
import kz.epharm.pharmacists.service.PharmacistSearchRow
import kz.epharm.posm.attill.AtTillPerson
import kz.epharm.posm.attill.AtTillService
import kz.epharm.posm.attill.PharmacistTillFlag
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import java.sql.ResultSet
import java.time.LocalDate
import java.util.UUID

/**
 * The people a notice from HQ goes to (owner, 26.11.2026): the count before sending, the course to remind about, the
 * search for chosen people, and the list kept with a notice that is sent to a course or only to people at a till. The
 * rules are pure (PosmRecipientRules, PharmacistSearch); this class reads the database and the people at tills.
 */
@Service
class PosmMessageAudiences(
    private val jdbc: JdbcTemplate,
    private val named: NamedParameterJdbcTemplate,
    private val atTill: AtTillService,
) {
    /** The program a course notice is about, with its name as it is now. */
    data class ProgramRef(val id: UUID, val name: String)

    /** Checks that what the audience names exists. For a course returns the program. */
    fun validate(choice: PosmAudienceChoice): ProgramRef? = when (choice.audience) {
        PosmMessageAudience.ALL -> null
        PosmMessageAudience.PHARMACIES -> requireKnown("pharmacies", choice.pharmacyIds, "Не найдены аптеки")
        PosmMessageAudience.PHARMACISTS -> requireKnown("pharmacists", choice.pharmacistIds, "Не найдены сотрудники")
        PosmMessageAudience.COURSE -> program(choice.programId ?: invalid("Выберите курс"))
    }

    /** The people the notice goes to at this moment, the people at tills read afresh. */
    fun resolve(choice: PosmAudienceChoice): PosmRecipients {
        val snapshot = atTill.snapshot()
        val courseOpen = if (choice.audience == PosmMessageAudience.COURSE) courseOpenIds(choice.programId ?: invalid("Выберите курс")) else emptySet()
        val frozen = PosmRecipientRules.frozenIds(choice, courseOpen, snapshot.people)
        if (frozen != null && frozen.size > PosmMessageRules.MAX_RECIPIENTS) {
            invalid("Слишком много адресатов (${frozen.size}): сузьте выбор")
        }
        return PosmRecipients(
            total = frozen?.size ?: liveTotal(choice),
            frozenIds = frozen,
            atTillNow = PosmRecipientRules.atTillNow(choice, courseOpen, snapshot.people),
            windowMinutes = snapshot.windowMinutes,
        )
    }

    /** POST /api/admin/posm-messages/preview: who the notice would reach, before HQ sends it. */
    fun preview(request: CreatePosmMessageRequest): PosmMessagePreviewDto {
        val choice = PosmMessageRules.choice(request)
        validate(choice)
        val recipients = resolve(choice)
        val sampleIds = when {
            recipients.frozenIds != null -> recipients.frozenIds
            choice.audience == PosmMessageAudience.PHARMACISTS -> choice.pharmacistIds
            else -> emptyList()
        }
        val sample = people(sampleIds, PosmMessageRules.PREVIEW_SAMPLE)
        return PosmMessagePreviewDto(
            audience = choice.audience.wire,
            recipients = recipients.total,
            atTillNow = recipients.atTillNow,
            frozen = choice.frozen,
            sample = sample,
            sampleMore = (sampleIds.size - sample.size).coerceAtLeast(0),
            windowMinutes = recipients.windowMinutes,
        )
    }

    /**
     * The courses with people who have not finished them, most people first. A course in the archive is not offered;
     * `dueOn` is set only when every open assignment has the same due day (Almaty), so that the reminder can name it.
     */
    fun courses(): List<PosmCourseOptionDto> = jdbc.query(
        """
        SELECT p.id, p.name, count(DISTINCT a.pharmacist_id) AS open_count,
               count(DISTINCT (a.due_at AT TIME ZONE 'Asia/Almaty')::date) AS due_days,
               min((a.due_at AT TIME ZONE 'Asia/Almaty')::date) AS due_on,
               bool_or(a.due_at IS NULL) AS some_without_due
        FROM training_programs p
        JOIN training_program_versions v ON v.program_id = p.id
        JOIN training_assignments a ON a.program_version_id = v.id
        JOIN pharmacists ph ON ph.id = a.pharmacist_id
        WHERE p.status <> 'archived' AND p.archived_at IS NULL AND ph.status <> 'blocked' AND $OPEN_ASSIGNMENT
        GROUP BY p.id, p.name
        ORDER BY open_count DESC, p.name, p.id
        """.trimIndent(),
    ) { rs, _ ->
        PosmCourseOptionDto(
            id = rs.getObject("id", UUID::class.java),
            name = rs.getString("name"),
            openCount = rs.getInt("open_count"),
            dueOn = if (rs.getInt("due_days") == 1 && !rs.getBoolean("some_without_due")) rs.getObject("due_on", LocalDate::class.java) else null,
        )
    }

    /** GET /api/admin/posm-messages/pharmacists: the picker's search, by what HQ remembers about a person. */
    fun search(query: String?, limitRaw: Int?): PosmPharmacistSearchDto {
        val limit = PosmMessageRules.searchLimit(limitRaw)
        val parsed = PharmacistSearch.parse(query)
        val matched = candidates().filter { PharmacistSearch.matches(parsed, it.searchRow()) }
        val flags = atTill.flags()
        return PosmPharmacistSearchDto(total = matched.size, items = matched.take(limit).map { it.toDto(flags[it.id]) })
    }

    // ── internals ───────────────────────────────────────────────────────

    /** Everyone who is not blocked, by name: about two thousand cards, read for each search. */
    private fun candidates(): List<Candidate> = jdbc.query(
        """
        SELECT ph.id, ph.name, ph.phone, ph.iin, ph.status,
               coalesce(nullif(ph.pharmacy_name, ''), p.name, '') AS pharmacy_name,
               coalesce(nullif(ph.city, ''), p.city, '') AS city, coalesce(p.addr, '') AS addr
        FROM pharmacists ph
        LEFT JOIN pharmacies p ON p.id = ph.pharmacy_id
        WHERE ph.status <> 'blocked'
        ORDER BY ph.name, ph.id
        """.trimIndent(),
    ) { rs, _ -> candidate(rs) }

    /** The first [limit] of these people by name, with the flag «at a till now». */
    private fun people(ids: List<String>, limit: Int): List<PosmPersonDto> {
        if (ids.isEmpty()) return emptyList()
        val flags = atTill.flags()
        return named.query(
            """
            SELECT ph.id, ph.name, ph.phone, ph.iin, ph.status,
                   coalesce(nullif(ph.pharmacy_name, ''), p.name, '') AS pharmacy_name,
                   coalesce(nullif(ph.city, ''), p.city, '') AS city, coalesce(p.addr, '') AS addr
            FROM pharmacists ph
            LEFT JOIN pharmacies p ON p.id = ph.pharmacy_id
            WHERE ph.id IN (:ids)
            ORDER BY ph.name, ph.id
            LIMIT :limit
            """.trimIndent(),
            mapOf("ids" to ids, "limit" to limit),
        ) { rs, _ -> candidate(rs).let { it.toDto(flags[it.id]) } }
    }

    private fun candidate(rs: ResultSet) = Candidate(
        id = rs.getString("id"),
        name = rs.getString("name"),
        phone = rs.getString("phone").orEmpty(),
        iin = rs.getString("iin").orEmpty(),
        status = rs.getString("status"),
        pharmacyName = rs.getString("pharmacy_name"),
        city = rs.getString("city"),
        address = rs.getString("addr"),
    )

    /** The people with an open assignment of the course: the ones a reminder is for. */
    private fun courseOpenIds(programId: UUID): Set<String> = named.queryForList(
        """
        SELECT DISTINCT a.pharmacist_id
        FROM training_assignments a
        JOIN training_program_versions v ON v.id = a.program_version_id
        JOIN pharmacists ph ON ph.id = a.pharmacist_id
        WHERE v.program_id = :program AND ph.status <> 'blocked' AND $OPEN_ASSIGNMENT
        """.trimIndent(),
        mapOf("program" to programId),
        String::class.java,
    ).toSet()

    /** The notice's «all», «pharmacies» and «pharmacists» audiences, judged live: active cards in scope when it is sent. */
    private fun liveTotal(choice: PosmAudienceChoice): Int = when (choice.audience) {
        PosmMessageAudience.ALL ->
            jdbc.queryForObject("SELECT count(*) FROM pharmacists WHERE status = 'active'", Int::class.java)
        PosmMessageAudience.PHARMACIES -> named.queryForObject(
            "SELECT count(*) FROM pharmacists WHERE status = 'active' AND pharmacy_id IN (:ids)",
            mapOf("ids" to choice.pharmacyIds),
            Int::class.java,
        )
        PosmMessageAudience.PHARMACISTS -> named.queryForObject(
            "SELECT count(*) FROM pharmacists WHERE status <> 'blocked' AND id IN (:ids)",
            mapOf("ids" to choice.pharmacistIds),
            Int::class.java,
        )
        PosmMessageAudience.COURSE -> 0
    } ?: 0

    private fun requireKnown(table: String, ids: List<String>, message: String): ProgramRef? {
        val known = named.queryForList(
            "SELECT id FROM $table WHERE id IN (:ids)",
            mapOf("ids" to ids),
            String::class.java,
        ).toSet()
        val unknown = ids.filterNot { it in known }
        if (unknown.isNotEmpty()) invalid("$message: " + unknown.take(5).joinToString(", "))
        return null
    }

    private fun program(id: UUID): ProgramRef {
        val row = jdbc.query(
            "SELECT name, status, archived_at IS NOT NULL AS archived FROM training_programs WHERE id = ?",
            { rs, _ -> Triple(rs.getString("name"), rs.getString("status"), rs.getBoolean("archived")) },
            id,
        ).singleOrNull() ?: invalid("Курс не найден")
        if (row.third || row.second == "archived") invalid("Курс в архиве, напомнить о нём нельзя")
        return ProgramRef(id, row.first)
    }

    private data class Candidate(
        val id: String,
        val name: String,
        val phone: String,
        val iin: String,
        val status: String,
        val pharmacyName: String,
        val city: String,
        val address: String,
    ) {
        fun searchRow() = PharmacistSearchRow(id, name, phone, iin, listOf(pharmacyName, city, address))

        fun toDto(flag: PharmacistTillFlag?) = PosmPersonDto(
            id = id,
            name = name,
            pharmacyName = pharmacyName,
            city = city,
            address = address,
            phoneTail = AtTillPerson.phoneTail(phone),
            status = status,
            atTillNow = flag?.atTillNow ?: false,
            lastTillSeenAt = flag?.lastSeenAt,
        )
    }

    private fun invalid(message: String): Nothing = throw AppException(ErrorCode.VALIDATION_FAILED, message)

    companion object {
        /** An assignment HQ still waits for: not finished, not paused, not cancelled, and already started. Alias `a`. */
        const val OPEN_ASSIGNMENT = "a.status NOT IN ('completed', 'cancelled', 'paused') AND (a.starts_at IS NULL OR a.starts_at <= now())"
    }
}
