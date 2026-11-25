package kz.epharm.posm.commands

import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import kz.epharm.posm.service.PosmDeviceIdentity
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile

/**
 * Remote till commands (25.11.2026). HQ asks a till for one action from a fixed list; the till takes it with its own
 * device key, runs it and answers. Deadlines are checked against the database clock; an elapsed command is shown as
 * expired at once and marked so by the next write (lazy expiry).
 */
@Service
class TillCommandService(
    private val jdbc: JdbcTemplate,
    private val storage: TillArtifactStorage,
) {
    private val log = LoggerFactory.getLogger(TillCommandService::class.java)
    private val commandMapper = RowMapper<TillCommandDto> { rs, _ -> mapCommand(rs) }

    // ── HQ ──────────────────────────────────────────────────────────────

    @Transactional
    fun create(request: CreateTillCommandRequest, requestedBy: String): TillCommandDto {
        val pharmacyId = TillCommandRules.requiredId(request.pharmacyId, "pharmacyId", 64)
        val deviceId = TillCommandRules.requiredId(request.deviceId, "deviceId", 128)
        val action = TillCommandRules.action(request.action)
        val actor = requestedBy.trim().take(255).ifEmpty { unauthorized("Admin identity is required") }
        val tills = jdbc.queryForObject(
            """
            SELECT count(*) FROM fulfillment_devices d
            JOIN pharmacies p ON p.id = d.pharmacy_id AND p.active = true
            WHERE d.pharmacy_id = ? AND d.device_id = ? AND d.active = true
            """.trimIndent(),
            Long::class.java,
            pharmacyId,
            deviceId,
        ) ?: 0L
        if (tills != 1L) notFound("Касса не найдена или её ключ отозван")
        expireOverdue(pharmacyId, deviceId)
        val id = UUID.randomUUID()
        val inserted = jdbc.update(
            """
            INSERT INTO till_commands (id, pharmacy_id, device_id, action, status, requested_by, created_at, expires_at)
            VALUES (?, ?, ?, ?, 'pending', ?, now(), now() + (? * interval '1 minute'))
            ON CONFLICT (pharmacy_id, device_id) WHERE status IN ('pending', 'delivered') DO NOTHING
            """.trimIndent(),
            id,
            pharmacyId,
            deviceId,
            action.wire,
            actor,
            TillCommandRules.DELIVERY_MINUTES,
        )
        if (inserted != 1) conflict("У этой кассы уже есть задание в работе. Дождитесь ответа кассы или конца срока")
        log.info("Till command {} {} for {}/{} requested by {}", id, action.wire, pharmacyId, deviceId, actor)
        return find(id) ?: notFound("Задание не найдено")
    }

    @Transactional(readOnly = true)
    fun list(pharmacyIdRaw: String?, deviceIdRaw: String?, limitRaw: Int?): List<TillCommandDto> {
        val pharmacyId = TillCommandRules.optionalId(pharmacyIdRaw, "pharmacyId", 64)
        val deviceId = TillCommandRules.optionalId(deviceIdRaw, "deviceId", 128)
        val filters = mutableListOf<String>()
        val args = mutableListOf<Any>()
        pharmacyId?.let { filters += "pharmacy_id = ?"; args += it }
        deviceId?.let { filters += "device_id = ?"; args += it }
        val where = if (filters.isEmpty()) "" else "WHERE " + filters.joinToString(" AND ")
        args += TillCommandRules.listLimit(limitRaw)
        return jdbc.query("$SELECT_COMMAND $where ORDER BY created_at DESC, id DESC LIMIT ?", commandMapper, *args.toTypedArray())
    }

    @Transactional(readOnly = true)
    fun artifact(id: UUID): TillCommandArtifact {
        val row = jdbc.query(
            "SELECT pharmacy_id, device_id, finished_at, artifact_key FROM till_commands WHERE id = ?",
            { rs, _ ->
                ArchiveRow(
                    pharmacyId = rs.getString("pharmacy_id"),
                    deviceId = rs.getString("device_id"),
                    finishedAt = rs.getTimestamp("finished_at")?.toInstant(),
                    key = rs.getString("artifact_key"),
                )
            },
            id,
        ).singleOrNull() ?: notFound("Задание не найдено")
        val key = row.key ?: notFound("Архива нет: касса его не прислала или он удалён через 14 дней")
        val bytes = storage.get(key) ?: notFound("Архив не найден в хранилище")
        return TillCommandArtifact(TillCommandRules.archiveFileName(row.pharmacyId, row.deviceId, row.finishedAt), bytes)
    }

    // ── Till ────────────────────────────────────────────────────────────

    /** The oldest waiting command of this till, now taken; null when there is none. */
    @Transactional
    fun next(device: PosmDeviceIdentity): TillCommandDeliveryDto? {
        val till = requireTill(device)
        expireOverdue(till.pharmacyId, till.deviceId)
        return jdbc.query(
            """
            UPDATE till_commands
            SET status = 'delivered', delivered_at = now(), expires_at = now() + (? * interval '1 minute')
            WHERE id = (
                SELECT id FROM till_commands
                WHERE pharmacy_id = ? AND device_id = ? AND status = 'pending' AND expires_at > now()
                ORDER BY created_at, id
                LIMIT 1
                FOR UPDATE SKIP LOCKED
            )
            RETURNING id, action, expires_at
            """.trimIndent(),
            { rs, _ ->
                TillCommandDeliveryDto(
                    id = rs.getObject("id", UUID::class.java),
                    action = rs.getString("action"),
                    expiresAt = rs.getTimestamp("expires_at").toInstant(),
                )
            },
            TillCommandRules.RESULT_MINUTES,
            till.pharmacyId,
            till.deviceId,
        ).singleOrNull()
    }

    /** The till's answer for a command it took. Only the command of this till, only while it is taken. */
    fun result(
        device: PosmDeviceIdentity,
        id: UUID,
        statusRaw: String?,
        messageRaw: String?,
        file: MultipartFile?,
    ): TillCommandResultDto {
        val till = requireTill(device)
        val status = TillCommandRules.resultStatus(statusRaw)
        val message = TillCommandRules.message(messageRaw)
        val stored = jdbc.query(
            """
            SELECT action, status, expires_at <= now() AS overdue
            FROM till_commands WHERE id = ? AND pharmacy_id = ? AND device_id = ?
            """.trimIndent(),
            { rs, _ ->
                StoredState(
                    action = TillCommandAction.fromWire(rs.getString("action")) ?: TillCommandAction.DIAGNOSTICS,
                    status = TillCommandStatus.fromWire(rs.getString("status")) ?: TillCommandStatus.EXPIRED,
                    overdue = rs.getBoolean("overdue"),
                )
            },
            id,
            till.pharmacyId,
            till.deviceId,
        ).singleOrNull()
        when (TillCommandRules.resultDecision(stored?.status, stored?.overdue == true)) {
            TillCommandRules.ResultDecision.ACCEPT -> Unit
            TillCommandRules.ResultDecision.NOT_FOUND -> notFound("Задание не найдено")
            TillCommandRules.ResultDecision.NOT_TAKEN -> conflict("Касса ещё не брала это задание")
            TillCommandRules.ResultDecision.ALREADY_FINISHED -> conflict("Ответ на это задание уже получен")
            TillCommandRules.ResultDecision.EXPIRED -> {
                expireOverdue(till.pharmacyId, till.deviceId)
                conflict("Срок задания истёк")
            }
        }
        val action = stored!!.action
        val archive = file?.takeIf { !it.isEmpty }?.let { readArchive(action, it) }
        val key = archive?.let { storeArchive(id, it) }
        val updated = jdbc.query(
            """
            UPDATE till_commands
            SET status = ?, finished_at = now(), result_message = ?, artifact_key = ?, artifact_bytes = ?
            WHERE id = ? AND pharmacy_id = ? AND device_id = ? AND status = 'delivered' AND expires_at > now()
            RETURNING finished_at
            """.trimIndent(),
            { rs, _ -> rs.getTimestamp("finished_at").toInstant() },
            status.wire,
            message,
            key,
            archive?.size?.toLong(),
            id,
            till.pharmacyId,
            till.deviceId,
        ).singleOrNull()
        if (updated == null) {
            // Lost a race with expiry or a repeated answer: our archive must not stay without its row.
            key?.let(storage::delete)
            conflict("Ответ на это задание уже получен или его срок истёк")
        }
        log.info("Till command {} finished by {}/{}: {}", id, till.pharmacyId, till.deviceId, status.wire)
        return TillCommandResultDto(id = id, status = status.wire, finishedAt = updated)
    }

    // ── Housekeeping ────────────────────────────────────────────────────

    /** Marks every elapsed waiting or taken command expired; returns how many. */
    fun expireAllOverdue(): Int = jdbc.update(
        "UPDATE till_commands SET status = 'expired', finished_at = now() " +
            "WHERE status IN ('pending', 'delivered') AND expires_at <= now()",
    )

    /** Removes archives older than 14 days; the row keeps artifact_bytes. Returns how many were removed. */
    fun removeOldArchives(batch: Int = 200): Int {
        val old = jdbc.query(
            """
            SELECT id, artifact_key FROM till_commands
            WHERE artifact_key IS NOT NULL AND finished_at < now() - (? * interval '1 day')
            ORDER BY finished_at
            LIMIT ?
            """.trimIndent(),
            { rs, _ -> rs.getObject("id", UUID::class.java) to rs.getString("artifact_key") },
            TillCommandRules.ARCHIVE_RETENTION_DAYS,
            batch,
        )
        var removed = 0
        for ((id, key) in old) {
            if (!storage.delete(key)) continue
            removed += jdbc.update("UPDATE till_commands SET artifact_key = NULL WHERE id = ? AND artifact_key = ?", id, key)
        }
        return removed
    }

    private fun find(id: UUID): TillCommandDto? =
        jdbc.query("$SELECT_COMMAND WHERE id = ?", commandMapper, id).singleOrNull()

    private fun expireOverdue(pharmacyId: String, deviceId: String) {
        jdbc.update(
            """
            UPDATE till_commands SET status = 'expired', finished_at = now()
            WHERE pharmacy_id = ? AND device_id = ? AND status IN ('pending', 'delivered') AND expires_at <= now()
            """.trimIndent(),
            pharmacyId,
            deviceId,
        )
    }

    private fun readArchive(action: TillCommandAction, file: MultipartFile): ByteArray {
        TillCommandRules.checkArchive(action, file.size, file.inputStream.use { it.readNBytes(4) })
        val bytes = file.inputStream.use { it.readNBytes(TillCommandRules.MAX_ARCHIVE_BYTES.toInt() + 1) }
        TillCommandRules.checkArchive(action, bytes.size.toLong(), bytes)
        return bytes
    }

    private fun storeArchive(id: UUID, bytes: ByteArray): String {
        val key = TillCommandRules.archiveKey(id)
        try {
            storage.put(key, bytes)
        } catch (error: RuntimeException) {
            log.warn("Till diagnostics archive for {} was not stored: {}", id, error.message)
            throw AppException(
                ErrorCode.UPSTREAM_UNAVAILABLE,
                "Хранилище архивов недоступно, повторите позже",
                HttpStatus.SERVICE_UNAVAILABLE,
            )
        }
        return key
    }

    private fun requireTill(device: PosmDeviceIdentity): Till {
        val pharmacyId = device.pharmacyId?.takeIf { it.isNotBlank() }
        val deviceId = device.deviceId?.takeIf { it.isNotBlank() }
        if (device.legacy || pharmacyId == null || deviceId == null) unauthorized("Требуется индивидуальный ключ кассы")
        return Till(pharmacyId, deviceId)
    }

    private fun mapCommand(rs: ResultSet): TillCommandDto {
        val stored = TillCommandStatus.fromWire(rs.getString("status")) ?: TillCommandStatus.EXPIRED
        val overdue = rs.getBoolean("overdue")
        val expiresAt = rs.getTimestamp("expires_at").toInstant()
        return TillCommandDto(
            id = rs.getObject("id", UUID::class.java),
            pharmacyId = rs.getString("pharmacy_id"),
            deviceId = rs.getString("device_id"),
            action = rs.getString("action"),
            status = TillCommandRules.visibleStatus(stored, overdue).wire,
            requestedBy = rs.getString("requested_by"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            expiresAt = expiresAt,
            deliveredAt = rs.getTimestamp("delivered_at")?.toInstant(),
            finishedAt = TillCommandRules.visibleFinishedAt(
                stored,
                overdue,
                rs.getTimestamp("finished_at")?.toInstant(),
                expiresAt,
            ),
            resultMessage = rs.getString("result_message"),
            artifactAvailable = rs.getString("artifact_key") != null,
            artifactBytes = rs.getLong("artifact_bytes").takeUnless { rs.wasNull() },
        )
    }

    private data class Till(val pharmacyId: String, val deviceId: String)

    private data class StoredState(val action: TillCommandAction, val status: TillCommandStatus, val overdue: Boolean)

    private data class ArchiveRow(val pharmacyId: String, val deviceId: String, val finishedAt: Instant?, val key: String?)

    private companion object {
        const val SELECT_COMMAND = """
            SELECT id, pharmacy_id, device_id, action, status, requested_by, created_at, expires_at, delivered_at,
                   finished_at, result_message, artifact_key, artifact_bytes, expires_at <= now() AS overdue
            FROM till_commands
        """

        fun notFound(message: String): Nothing = throw AppException(ErrorCode.NOT_FOUND, message, HttpStatus.NOT_FOUND)

        fun conflict(message: String): Nothing = throw AppException(ErrorCode.CONFLICT, message, HttpStatus.CONFLICT)

        fun unauthorized(message: String): Nothing =
            throw AppException(ErrorCode.UNAUTHORIZED, message, HttpStatus.UNAUTHORIZED)
    }
}
