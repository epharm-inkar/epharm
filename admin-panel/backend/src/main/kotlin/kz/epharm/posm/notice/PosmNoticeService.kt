package kz.epharm.posm.notice

import kz.epharm.appupdate.service.AppReleaseService
import kz.epharm.posm.service.PosmDeviceIdentity
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** HQ switch «ePharm на технических работах» (one row, `posm_notice`). */
data class PosmNotice(
    val maintenance: Boolean,
    val pilotLive: Boolean,
    val title: String,
    val message: String,
    val updatedAt: Instant? = null,
    val updatedBy: String? = null,
)

/** What one till shows: a pilot till stays live while `pilotLive` is on. */
data class PosmTillNotice(
    val maintenance: Boolean,
    val title: String,
    val message: String,
    val changedAt: Instant?,
)

@Service
class PosmNoticeService(
    private val jdbc: JdbcTemplate,
    private val releases: AppReleaseService,
) {
    @Transactional(readOnly = true)
    fun current(): PosmNotice = jdbc.query(
        "SELECT maintenance, pilot_live, title, message, updated_at, updated_by FROM posm_notice WHERE id = 1",
    ) { rs, _ ->
        PosmNotice(
            maintenance = rs.getBoolean(1),
            pilotLive = rs.getBoolean(2),
            title = rs.getString(3),
            message = rs.getString(4),
            updatedAt = rs.getTimestamp(5)?.toInstant(),
            updatedBy = rs.getString(6),
        )
    }.singleOrNull() ?: DEFAULT

    @Transactional(readOnly = true)
    fun forTill(device: PosmDeviceIdentity): PosmTillNotice =
        tillView(current(), releases.isPilotTill(device.pharmacyId, device.deviceId, device.legacy))

    @Transactional
    fun update(maintenance: Boolean, pilotLive: Boolean, title: String?, message: String?, updatedBy: String?): PosmNotice {
        val before = current()
        val cleanTitle = title?.trim()?.takeIf { it.isNotEmpty() } ?: before.title
        val cleanMessage = message?.trim()?.takeIf { it.isNotEmpty() } ?: before.message
        if (cleanTitle.length > 120 || cleanMessage.length > 600) {
            throw AppException(ErrorCode.VALIDATION_FAILED, "Заголовок до 120 знаков, текст до 600", HttpStatus.BAD_REQUEST)
        }
        jdbc.update(
            """
            INSERT INTO posm_notice (id, maintenance, pilot_live, title, message, updated_at, updated_by)
            VALUES (1, ?, ?, ?, ?, now(), ?)
            ON CONFLICT (id) DO UPDATE SET maintenance = EXCLUDED.maintenance, pilot_live = EXCLUDED.pilot_live,
                title = EXCLUDED.title, message = EXCLUDED.message, updated_at = now(), updated_by = EXCLUDED.updated_by
            """.trimIndent(),
            maintenance, pilotLive, cleanTitle, cleanMessage, updatedBy?.take(255),
        )
        return current()
    }

    companion object {
        val DEFAULT = PosmNotice(
            maintenance = true,
            pilotLive = true,
            title = "ePharm на технических работах",
            message = "Сейчас ePharm недоступен: идут технические работы. Пожалуйста, подождите. " +
                "Когда ePharm снова заработает, на этом компьютере появится уведомление.",
        )

        fun tillView(notice: PosmNotice, pilotTill: Boolean): PosmTillNotice = PosmTillNotice(
            maintenance = notice.maintenance && !(pilotTill && notice.pilotLive),
            title = notice.title,
            message = notice.message,
            changedAt = notice.updatedAt,
        )
    }
}
