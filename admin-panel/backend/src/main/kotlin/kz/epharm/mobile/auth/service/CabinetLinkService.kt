package kz.epharm.mobile.auth.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Timestamp
import java.time.Instant
import java.util.Base64

/**
 * One-time links that open the pharmacist web cabinet already signed in (no password, no SMS). A till asks for
 * one for the person currently signed in to Standard-N there; the person is identified by IIN from the central
 * directory. The token is 256 random bits, lives minutes, works once, and only its SHA-256 is stored. It travels
 * in the URL fragment (`#t=`), so it never reaches server logs or a Referer header.
 */
@Service
class CabinetLinkService(
    private val jdbc: JdbcTemplate,
    @Value("\${app.public-base-url:https://epharm.inkar.kz}") private val publicBaseUrl: String,
    @Value("\${app.posm.cabinet-link-ttl-seconds:600}") private val ttlSeconds: Long,
) {
    private val random = SecureRandom()

    data class Issued(val url: String, val expiresAt: Instant)

    fun issue(pharmacistId: String, pharmacyId: String, deviceId: String, now: Instant = Instant.now()): Issued {
        val raw = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        val expiresAt = now.plusSeconds(ttlSeconds.coerceIn(60, 3600))
        jdbc.update(
            "INSERT INTO posm_cabinet_links(token_hash, pharmacist_id, pharmacy_id, device_id, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?)",
            hash(raw), pharmacistId, pharmacyId, deviceId.take(128), Timestamp.from(now), Timestamp.from(expiresAt),
        )
        jdbc.update("DELETE FROM posm_cabinet_links WHERE expires_at < ?", Timestamp.from(now.minusSeconds(86_400)))
        return Issued("${publicBaseUrl.trimEnd('/')}/learn/enter#t=$raw", expiresAt)
    }

    /** Uses the token once. Returns the pharmacist id, or null when unknown, used or expired. */
    fun consume(raw: String, now: Instant = Instant.now()): String? {
        val token = raw.trim()
        if (!TOKEN.matches(token)) return null
        return jdbc.query(
            "UPDATE posm_cabinet_links SET used_at = ? WHERE token_hash = ? AND used_at IS NULL AND expires_at > ? RETURNING pharmacist_id",
            { rs, _ -> rs.getString(1) },
            Timestamp.from(now), hash(token), Timestamp.from(now),
        ).singleOrNull()
    }

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private companion object {
        val TOKEN = Regex("^[A-Za-z0-9_-]{43}$")
    }
}
