package kz.epharm.mobile.auth.service

import kz.epharm.auth.service.RefreshTokenService
import kz.epharm.mobile.auth.entity.MobileRefreshTokenEntity
import kz.epharm.mobile.auth.repository.MobileRefreshTokenRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Refresh-токены фармацевтов. Зеркало admin RefreshTokenService, но subject — строковый
 * pharmacistId. Хеширование переиспользуем (RefreshTokenService.hash — SHA-256).
 */
@Service
class MobileRefreshTokenService(
    private val repository: MobileRefreshTokenRepository,
    @Value("\${app.jwt.refresh-ttl-days:30}") private val refreshTtlDays: Long,
) {
    val refreshTtl: Duration = Duration.ofDays(refreshTtlDays)

    /** Scalar lookup does not cache a token entity before the caller locks its pharmacist. */
    @Transactional(readOnly = true)
    fun pharmacistIdOf(rawToken: String): String? = repository.pharmacistIdByTokenHash(RefreshTokenService.hash(rawToken))

    /** Возвращает (rawToken, expiresAt). Raw отдаём клиенту, в БД — только хеш. */
    @Transactional
    fun issue(pharmacistId: String, now: Instant = Instant.now(), sessionEndsAt: Instant? = null): IssuedRefresh {
        val raw = UUID.randomUUID().toString().replace("-", "") +
            UUID.randomUUID().toString().replace("-", "")
        val usual = now.plus(refreshTtl)
        val entity = MobileRefreshTokenEntity(
            id = UUID.randomUUID(),
            pharmacistId = pharmacistId,
            tokenHash = RefreshTokenService.hash(raw),
            expiresAt = sessionEndsAt?.takeIf { it.isBefore(usual) } ?: usual,
            createdAt = now,
            sessionEndsAt = sessionEndsAt,
        )
        repository.save(entity)
        return IssuedRefresh(raw = raw, expiresAt = entity.expiresAt)
    }

    /** Находит активный refresh, revoke + возвращает pharmacistId. null — если невалиден. */
    @Transactional
    fun rotate(rawToken: String, now: Instant = Instant.now()): String? = rotateSession(rawToken, now)?.pharmacistId

    /** Same as [rotate], and tells the end of a shift-long session so the next token keeps it. */
    @Transactional
    fun rotateSession(rawToken: String, now: Instant = Instant.now()): Rotated? {
        val entity = repository.findByTokenHash(RefreshTokenService.hash(rawToken)).orElse(null) ?: return null
        if (!entity.isActive(now)) return null
        entity.revokedAt = now
        repository.save(entity)
        return Rotated(entity.pharmacistId, entity.sessionEndsAt)
    }

    @Transactional
    fun revokeAllForPharmacist(pharmacistId: String) {
        repository.revokeAllForPharmacist(pharmacistId)
    }

    data class IssuedRefresh(val raw: String, val expiresAt: Instant)
    data class Rotated(val pharmacistId: String, val sessionEndsAt: Instant?)
}
