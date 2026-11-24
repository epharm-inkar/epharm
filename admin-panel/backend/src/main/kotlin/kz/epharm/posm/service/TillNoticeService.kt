package kz.epharm.posm.service

import kz.epharm.pharmacies.repository.PharmacyRepository
import kz.epharm.posm.dto.TillNoticeDto
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

/**
 * What the till status window («Состояние ePharm», POSM 1.0.92+) learns from the heartbeat answer: the HQ notice
 * (`POSM_TILL_NOTICE_*`, for example «идут технические работы») and the name of the pharmacy the till key belongs
 * to, so a pharmacist sees at once that the till is bound to the right pharmacy. Tills poll nothing new for it.
 */
@Service
class TillNoticeService(
    private val pharmacies: PharmacyRepository,
    @Value("\${app.posm.till-notice.id:}") id: String,
    @Value("\${app.posm.till-notice.ru:}") ru: String,
    @Value("\${app.posm.till-notice.kk:}") kk: String,
    @Value("\${app.posm.till-notice.level:info}") level: String,
) {
    val notice: TillNoticeDto? = notice(id, ru, kk, level)

    private val names = ConcurrentHashMap<String, Cached>()

    /** Pharmacy name for a heartbeat; cached for ten minutes, so 500 tills cost about one query a second. */
    fun pharmacyName(pharmacyId: String?, nowMillis: Long = System.currentTimeMillis()): String? {
        val id = pharmacyId?.trim()?.takeIf { it.isNotEmpty() && it.length <= 128 } ?: return null
        names[id]?.takeIf { nowMillis - it.atMillis < TTL_MILLIS }?.let { return it.name }
        val name = runCatching {
            pharmacies.findById(id).map { it.name.trim().take(120) }.orElse(null)?.takeIf { it.isNotEmpty() }
        }.getOrNull()
        if (names.size >= MAX_ENTRIES) names.clear()
        names[id] = Cached(name, nowMillis)
        return name
    }

    private data class Cached(val name: String?, val atMillis: Long)

    companion object {
        private const val TTL_MILLIS = 10 * 60 * 1000L
        private const val MAX_ENTRIES = 5_000

        /** A notice needs an id and Russian text; Kazakh falls back to Russian on the till. */
        internal fun notice(id: String, ru: String, kk: String, level: String): TillNoticeDto? {
            val cleanId = id.trim().take(64)
            val cleanRu = ru.trim().take(600)
            if (cleanId.isEmpty() || cleanRu.isEmpty()) return null
            val cleanLevel = if (level.trim().equals("warning", ignoreCase = true)) "warning" else "info"
            return TillNoticeDto(cleanId, cleanRu, kk.trim().take(600), cleanLevel)
        }
    }
}
