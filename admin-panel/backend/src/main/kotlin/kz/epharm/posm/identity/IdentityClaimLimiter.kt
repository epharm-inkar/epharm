package kz.epharm.posm.identity

import kz.epharm.mobile.auth.service.takeFixedWindowAttempt
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest

/**
 * How often one till, one Standard-N user or one card may send an identity claim. An attempt counts even when the
 * request is then refused, so a script that guesses IINs is stopped by its own failures. Own transaction: the count
 * stays when the request rolls back.
 */
@Service
class IdentityClaimLimiter(private val jdbc: JdbcTemplate) {
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = [AppException::class])
    fun take(scope: String, limit: Int, windowSeconds: Long = WINDOW_SECONDS) {
        val hash = MessageDigest.getInstance("SHA-256").digest(("identity-claim:" + scope).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val attempt = takeFixedWindowAttempt(jdbc, hash, windowSeconds)
        if (attempt.attempts > limit) {
            throw AppException(
                ErrorCode.OTP_TOO_MANY_ATTEMPTS,
                "Слишком много попыток. Попробуйте позже",
                HttpStatus.TOO_MANY_REQUESTS,
                retryAfterSeconds = attempt.retryAfterSeconds,
            )
        }
    }

    companion object {
        const val WINDOW_SECONDS = 3600L
        const val PER_DEVICE = 20
        const val PER_PERSON = 5
    }
}
