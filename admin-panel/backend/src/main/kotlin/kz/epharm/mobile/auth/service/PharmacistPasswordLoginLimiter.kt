package kz.epharm.mobile.auth.service

import kz.epharm.pharmacists.service.PharmacistPhoneOwners
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest

/** Both POSM and /learn authenticate the same password, so they share one attempt budget. */
@Service
class PharmacistPasswordLoginLimiter(private val jdbc: JdbcTemplate, private val phones: PharmacistPhoneOwners) {
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = [AppException::class])
    fun check(iin: String) {
        // The validator and account lookup use trimmed IINs; the attempt identity must agree.
        take(accountScope(iin))
    }

    /**
     * Sign-in by phone (25.11.2026) spends the budget of the card the number belongs to, the same row as its IIN,
     * so switching between IIN and phone adds no attempts. A number of no card, or of several, has its own budget.
     * [phone] is canonical (`+7XXXXXXXXXX`).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = [AppException::class])
    fun checkPhone(phone: String) {
        val ownerIin = phones.owners(phone).singleOrNull()?.iin?.trim()?.takeIf(String::isNotEmpty)
        take(if (ownerIin != null) accountScope(ownerIin) else "login-phone:$phone")
    }

    private fun accountScope(iin: String) = "login-account:${iin.trim()}"

    private fun take(scope: String) {
        val scopeHash = MessageDigest.getInstance("SHA-256").digest(scope.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val attempt = takeFixedWindowAttempt(jdbc, scopeHash, 300)
        if (attempt.attempts > 10) throw AppException(ErrorCode.OTP_TOO_MANY_ATTEMPTS,
            "Слишком много попыток — попробуйте позже", HttpStatus.TOO_MANY_REQUESTS,
            retryAfterSeconds = attempt.retryAfterSeconds)
    }
}
