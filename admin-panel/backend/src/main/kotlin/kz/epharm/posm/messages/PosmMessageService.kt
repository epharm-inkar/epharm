package kz.epharm.posm.messages

import kz.epharm.pharmacists.entity.PharmacistEntity
import kz.epharm.posm.attill.TillPersonPresence
import kz.epharm.posm.self.PosmSelfPersonResolver
import kz.epharm.posm.self.PosmSelfRequest
import kz.epharm.posm.service.PosmDeviceIdentity
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Notices from HQ to pharmacists at the till (25.11.2026). HQ writes and sends them under /api/admin; the till asks
 * for the notices of the person signed in to Standard-N at it, reports when one appeared on screen and when the person
 * closed it. The targeting rule is PosmMessageRules.isFor; the SQL here follows it.
 *
 * Since 26.11.2026 a notice can also go to the pharmacists who have not finished a course, and any notice can go only to
 * the people at a till when it is sent. Those two are chosen once, at sending, and kept in `posm_message_recipients`
 * (PosmMessageAudiences, PosmRecipientRules); a course notice stops reaching a person who finishes the course after
 * that (COURSE_STILL_OPEN).
 */
@Service
class PosmMessageService(
    private val jdbc: JdbcTemplate,
    private val named: NamedParameterJdbcTemplate,
    private val people: PosmSelfPersonResolver,
    private val audiences: PosmMessageAudiences,
    private val presence: TillPersonPresence,
) {
    private val log = LoggerFactory.getLogger(PosmMessageService::class.java)
    private val stamp = AtomicReference<CachedStamp?>(null)

    // ── HQ ──────────────────────────────────────────────────────────────

    @Transactional
    fun create(request: CreatePosmMessageRequest, createdBy: String): PosmMessageAdminDto {
        val draft = PosmMessageRules.draft(request)
        val actor = createdBy.trim().take(255).ifEmpty { unauthorized() }
        val program = audiences.validate(draft.choice)
        // The people of a course notice, or of one sent only to people at a till, are chosen here and kept.
        val recipients = audiences.resolve(draft.choice)
        recipients.frozenIds?.let { if (it.isEmpty()) noRecipients(draft) }
        val id = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO posm_messages (id, title, body, audience, recipients_total, created_by, created_at, expires_at,
                                       program_id, program_name, only_at_till)
            VALUES (?, ?, ?, ?, ?, ?, now(), now() + (? * interval '1 day'), ?, ?, ?)
            """.trimIndent(),
            id, draft.title, draft.text, draft.audience.wire, recipients.total, actor, draft.days,
            program?.id, program?.name, draft.onlyAtTill,
        )
        if (draft.pharmacyIds.isNotEmpty()) {
            jdbc.batchUpdate(
                "INSERT INTO posm_message_targets (message_id, pharmacy_id) VALUES (?, ?)",
                draft.pharmacyIds.map { arrayOf<Any>(id, it) },
            )
        }
        if (draft.pharmacistIds.isNotEmpty()) {
            jdbc.batchUpdate(
                "INSERT INTO posm_message_targets (message_id, pharmacist_id) VALUES (?, ?)",
                draft.pharmacistIds.map { arrayOf<Any>(id, it) },
            )
        }
        recipients.frozenIds?.let { ids ->
            jdbc.batchUpdate(
                "INSERT INTO posm_message_recipients (message_id, pharmacist_id) VALUES (?, ?)",
                ids.map { arrayOf<Any>(id, it) },
            )
        }
        forgetStampAfterCommit()
        log.info(
            "POSM message {} for {} ({} targets, {} recipients{}, {} days) sent by {}",
            id, draft.audience.wire, draft.pharmacyIds.size + draft.pharmacistIds.size, recipients.total,
            if (draft.onlyAtTill) ", only at a till" else "", draft.days, actor,
        )
        return find(id) ?: notFound()
    }

    /** POST /api/admin/posm-messages/preview: who a notice would reach, before HQ sends it. */
    fun preview(request: CreatePosmMessageRequest): PosmMessagePreviewDto = audiences.preview(request)

    @Transactional(readOnly = true)
    fun list(limitRaw: Int?): List<PosmMessageAdminDto> {
        val rows = jdbc.query("$SELECT_ADMIN ORDER BY m.created_at DESC, m.id DESC LIMIT ?", { rs, _ -> adminRow(rs) }, PosmMessageRules.listLimit(limitRaw))
        return withTargets(rows)
    }

    /** Stops showing a notice on the tills; people who already closed it keep it in their history only until now. */
    @Transactional
    fun withdraw(id: UUID, withdrawnBy: String): PosmMessageAdminDto {
        val actor = withdrawnBy.trim().take(255).ifEmpty { unauthorized() }
        val changed = jdbc.update(
            "UPDATE posm_messages SET withdrawn_at = now(), withdrawn_by = ? WHERE id = ? AND withdrawn_at IS NULL",
            actor, id,
        )
        if (changed == 1) {
            forgetStampAfterCommit()
            log.info("POSM message {} withdrawn by {}", id, actor)
        }
        return find(id) ?: notFound()
    }

    // ── Till ────────────────────────────────────────────────────────────

    /** The notices of the person signed in to Standard-N at this till: open ones and the last 30 days. */
    fun forPerson(device: PosmDeviceIdentity, req: PosmSelfRequest): PosmSelfMessagesDto {
        val person = people.person(device, req)
        // The till names the person signed in to Standard-N: that is who is at the till now (AtTillService).
        presence.record(device, person.id)
        val till = device.pharmacyId!!
        val rows = named.query(
            """
            SELECT m.id, m.title, m.body, m.created_at, m.expires_at, r.shown_at, r.closed_at
            FROM posm_messages m
            LEFT JOIN posm_message_receipts r ON r.message_id = m.id AND r.pharmacist_id = :person
            WHERE m.withdrawn_at IS NULL
              AND m.created_at >= now() - (:days * interval '1 day')
              AND $FOR_PERSON
              AND $COURSE_STILL_OPEN
            ORDER BY (r.closed_at IS NULL AND m.expires_at > now()) DESC, m.created_at DESC, m.id DESC
            LIMIT :limit
            """.trimIndent(),
            personParams(person, till)
                .addValue("days", PosmMessageRules.HISTORY_DAYS)
                .addValue("limit", PosmMessageRules.PERSON_READ_LIMIT),
        ) { rs, _ ->
            PosmPersonMessageRow(
                id = rs.getObject("id", UUID::class.java),
                title = rs.getString("title"),
                text = rs.getString("body"),
                createdAt = rs.getTimestamp("created_at").toInstant(),
                expiresAt = rs.getTimestamp("expires_at").toInstant(),
                shownAt = rs.getTimestamp("shown_at")?.toInstant(),
                closedAt = rs.getTimestamp("closed_at")?.toInstant(),
            )
        }
        return PosmMessageRules.forPerson(rows, Instant.now())
    }

    /** The notice appeared on this till for this person. The first report counts; a repeat changes nothing. */
    fun markShown(device: PosmDeviceIdentity, req: PosmSelfRequest, id: UUID): PosmSelfMessageAckDto {
        val person = people.person(device, req)
        requireForPerson(id, person, device.pharmacyId!!, allowWithdrawn = false)
        return jdbc.query(
            """
            INSERT INTO posm_message_receipts (message_id, pharmacist_id, shown_at, pharmacy_id, device_id)
            VALUES (?, ?, now(), ?, ?)
            ON CONFLICT (message_id, pharmacist_id) DO UPDATE SET shown_at = posm_message_receipts.shown_at
            RETURNING shown_at, closed_at
            """.trimIndent(),
            { rs, _ -> ack(id, rs) },
            id, person.id, device.pharmacyId, device.deviceId?.take(128),
        ).single()
    }

    /** The person closed the notice with ×. Also counts as shown when that report was lost. */
    fun markClosed(device: PosmDeviceIdentity, req: PosmSelfRequest, id: UUID): PosmSelfMessageAckDto {
        val person = people.person(device, req)
        // A notice withdrawn after it was shown may still be closed: the report only completes the receipt.
        requireForPerson(id, person, device.pharmacyId!!, allowWithdrawn = true)
        return jdbc.query(
            """
            INSERT INTO posm_message_receipts (message_id, pharmacist_id, shown_at, closed_at, pharmacy_id, device_id)
            VALUES (?, ?, now(), now(), ?, ?)
            ON CONFLICT (message_id, pharmacist_id)
                DO UPDATE SET closed_at = coalesce(posm_message_receipts.closed_at, EXCLUDED.closed_at)
            RETURNING shown_at, closed_at
            """.trimIndent(),
            { rs, _ -> ack(id, rs) },
            id, person.id, device.pharmacyId, device.deviceId?.take(128),
        ).single()
    }

    /**
     * When the notices last changed (sent or withdrawn), for the till's minute check of /api/posm/notice: a till that
     * sees a newer time asks for its person's notices at once instead of waiting for its 15-minute check.
     * Cached for 30 seconds; a failed read keeps the previous value.
     */
    fun changeStamp(): Instant? {
        val cached = stamp.get()
        val now = System.nanoTime()
        if (cached != null && now - cached.atNanos < STAMP_TTL_NANOS) return cached.value
        val value = try {
            jdbc.queryForObject(
                "SELECT max(greatest(created_at, coalesce(withdrawn_at, created_at))) FROM posm_messages",
                Timestamp::class.java,
            )?.toInstant()
        } catch (error: RuntimeException) {
            log.warn("POSM message stamp was not read: {}", error.message)
            return cached?.value
        }
        stamp.set(CachedStamp(value, now))
        return value
    }

    // ── internals ───────────────────────────────────────────────────────

    /** The next till check reads the new time; only after the commit, so no till caches the old one meanwhile. */
    private fun forgetStampAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            stamp.set(null)
            return
        }
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() = stamp.set(null)
        })
    }

    private fun find(id: UUID): PosmMessageAdminDto? =
        withTargets(jdbc.query("$SELECT_ADMIN WHERE m.id = ?", { rs, _ -> adminRow(rs) }, id)).singleOrNull()

    private fun requireForPerson(id: UUID, person: PharmacistEntity, tillPharmacyId: String, allowWithdrawn: Boolean) {
        val withdrawn = named.query(
            "SELECT m.withdrawn_at IS NOT NULL AS withdrawn FROM posm_messages m WHERE m.id = :id AND $FOR_PERSON",
            personParams(person, tillPharmacyId).addValue("id", id),
        ) { rs, _ -> rs.getBoolean("withdrawn") }.singleOrNull()
        if (withdrawn == null || (withdrawn && !allowWithdrawn)) notFound()
    }

    private fun personParams(person: PharmacistEntity, tillPharmacyId: String) = MapSqlParameterSource()
        .addValue("person", person.id)
        .addValue("till", tillPharmacyId)
        // No home pharmacy yet: the till's pharmacy stands in, so the parameter is never null.
        .addValue("home", person.pharmacyId?.takeIf { it.isNotBlank() } ?: tillPharmacyId)

    private fun withTargets(rows: List<AdminRow>): List<PosmMessageAdminDto> {
        if (rows.isEmpty()) return emptyList()
        val names = mutableMapOf<UUID, MutableList<String>>()
        val counts = mutableMapOf<UUID, Int>()
        named.query(
            """
            SELECT message_id, pharmacy_name, pharmacy_addr, pharmacist_name, target_id, total
            FROM (
                SELECT t.message_id, p.name AS pharmacy_name, p.addr AS pharmacy_addr, ph.name AS pharmacist_name,
                       coalesce(t.pharmacy_id, t.pharmacist_id) AS target_id,
                       count(*) OVER (PARTITION BY t.message_id) AS total,
                       row_number() OVER (
                           PARTITION BY t.message_id
                           ORDER BY coalesce(p.name, ph.name, t.pharmacy_id, t.pharmacist_id), coalesce(t.pharmacy_id, t.pharmacist_id)
                       ) AS n
                FROM posm_message_targets t
                LEFT JOIN pharmacies p ON p.id = t.pharmacy_id
                LEFT JOIN pharmacists ph ON ph.id = t.pharmacist_id
                WHERE t.message_id IN (:ids)
            ) x
            WHERE n <= :names
            ORDER BY message_id, n
            """.trimIndent(),
            mapOf("ids" to rows.map { it.id }, "names" to PosmMessageRules.TARGET_NAMES),
            RowCallbackHandler { rs ->
                val id = rs.getObject("message_id", UUID::class.java)
                counts[id] = rs.getInt("total")
                names.getOrPut(id) { mutableListOf() } += targetName(
                    rs.getString("pharmacy_name"), rs.getString("pharmacy_addr"), rs.getString("pharmacist_name"), rs.getString("target_id"),
                )
            },
        )
        return rows.map { row ->
            PosmMessageAdminDto(
                id = row.id,
                title = row.title,
                text = row.text,
                audience = row.audience,
                targetCount = counts[row.id] ?: 0,
                targetNames = names[row.id].orEmpty(),
                recipientsTotal = row.recipientsTotal,
                shownCount = row.shownCount,
                closedCount = row.closedCount,
                createdAt = row.createdAt,
                createdBy = row.createdBy,
                expiresAt = row.expiresAt,
                withdrawnAt = row.withdrawnAt,
                withdrawnBy = row.withdrawnBy,
                active = row.active,
                onlyAtTill = row.onlyAtTill,
                programId = row.programId,
                programName = row.programName,
            )
        }
    }

    private fun adminRow(rs: ResultSet) = AdminRow(
        id = rs.getObject("id", UUID::class.java),
        title = rs.getString("title"),
        text = rs.getString("body"),
        audience = rs.getString("audience"),
        recipientsTotal = rs.getInt("recipients_total"),
        shownCount = rs.getInt("shown_count"),
        closedCount = rs.getInt("closed_count"),
        createdAt = rs.getTimestamp("created_at").toInstant(),
        createdBy = rs.getString("created_by"),
        expiresAt = rs.getTimestamp("expires_at").toInstant(),
        withdrawnAt = rs.getTimestamp("withdrawn_at")?.toInstant(),
        withdrawnBy = rs.getString("withdrawn_by"),
        active = rs.getBoolean("active"),
        onlyAtTill = rs.getBoolean("only_at_till"),
        programId = rs.getObject("program_id", UUID::class.java),
        programName = rs.getString("program_name"),
    )

    private fun ack(id: UUID, rs: ResultSet) = PosmSelfMessageAckDto(
        id = id,
        shownAt = rs.getTimestamp("shown_at").toInstant(),
        closedAt = rs.getTimestamp("closed_at")?.toInstant(),
    )

    private data class AdminRow(
        val id: UUID,
        val title: String,
        val text: String,
        val audience: String,
        val recipientsTotal: Int,
        val shownCount: Int,
        val closedCount: Int,
        val createdAt: Instant,
        val createdBy: String,
        val expiresAt: Instant,
        val withdrawnAt: Instant?,
        val withdrawnBy: String?,
        val active: Boolean,
        val onlyAtTill: Boolean,
        val programId: UUID?,
        val programName: String?,
    )

    private data class CachedStamp(val value: Instant?, val atNanos: Long)

    private companion object {
        const val STAMP_TTL_NANOS = 30_000_000_000L

        const val SELECT_ADMIN = """
            SELECT m.id, m.title, m.body, m.audience, m.recipients_total, m.created_by, m.created_at, m.expires_at,
                   m.withdrawn_at, m.withdrawn_by, coalesce(r.shown, 0) AS shown_count, coalesce(r.closed, 0) AS closed_count,
                   (m.withdrawn_at IS NULL AND m.expires_at > now()) AS active,
                   m.only_at_till, m.program_id, m.program_name
            FROM posm_messages m
            LEFT JOIN LATERAL (
                SELECT count(*) AS shown, count(closed_at) AS closed FROM posm_message_receipts x WHERE x.message_id = m.id
            ) r ON true
        """

        /**
         * PosmMessageRules.isFor in SQL; :person, :till and :home are bound by personParams. A frozen notice (a course,
         * or «only at a till») goes to the people kept with it (V127); the others are judged live.
         */
        const val FOR_PERSON = """
            ((m.frozen AND EXISTS (
                    SELECT 1 FROM posm_message_recipients rc WHERE rc.message_id = m.id AND rc.pharmacist_id = :person))
             OR (NOT m.frozen AND (m.audience = 'all'
                 OR (m.audience = 'pharmacies' AND EXISTS (
                        SELECT 1 FROM posm_message_targets t WHERE t.message_id = m.id AND t.pharmacy_id IN (:till, :home)))
                 OR (m.audience = 'pharmacists' AND EXISTS (
                        SELECT 1 FROM posm_message_targets t WHERE t.message_id = m.id AND t.pharmacist_id = :person)))))
        """

        /**
         * A course notice reaches a person only while they still have an open assignment of the course: one who finished
         * it (or whom HQ paused or released from it) after the notice was sent is not nagged. The reports «shown» and
         * «closed» do not use it: a notice already on screen can still be closed.
         */
        const val COURSE_STILL_OPEN = """
            (m.audience <> 'course' OR EXISTS (
                SELECT 1 FROM training_assignments a
                JOIN training_program_versions v ON v.id = a.program_version_id
                WHERE v.program_id = m.program_id AND a.pharmacist_id = :person
                  AND ${PosmMessageAudiences.OPEN_ASSIGNMENT}))
        """

        fun targetName(pharmacy: String?, address: String?, pharmacist: String?, id: String): String = when {
            !pharmacy.isNullOrBlank() ->
                pharmacy.trim() + (address?.trim()?.takeIf { it.isNotEmpty() && !pharmacy.contains(it) }?.let { ", $it" } ?: "")
            !pharmacist.isNullOrBlank() -> pharmacist.trim()
            else -> id
        }

        fun notFound(): Nothing = throw AppException(ErrorCode.NOT_FOUND, "Уведомление не найдено", HttpStatus.NOT_FOUND)

        /** A notice whose people are chosen at sending, with nobody chosen: nothing to send, and HQ is told why. */
        fun noRecipients(draft: PosmMessageDraft): Nothing = throw AppException(
            ErrorCode.VALIDATION_FAILED,
            when {
                draft.onlyAtTill -> "Сейчас за кассой нет никого из адресатов. Отправьте позже или снимите «Только тем, кто сейчас за кассой»"
                draft.audience == PosmMessageAudience.COURSE -> "Некому напоминать: все уже прошли этот курс"
                else -> "Нет адресатов"
            },
        )

        fun unauthorized(): Nothing = throw AppException(ErrorCode.UNAUTHORIZED, "Admin identity is required", HttpStatus.UNAUTHORIZED)
    }
}
