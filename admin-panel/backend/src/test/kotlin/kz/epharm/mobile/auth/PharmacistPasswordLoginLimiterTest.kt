package kz.epharm.mobile.auth

import io.mockk.every
import io.mockk.mockk
import kz.epharm.mobile.auth.service.FixedWindowAttempt
import kz.epharm.mobile.auth.service.PharmacistPasswordLoginLimiter
import kz.epharm.pharmacists.repository.PharmacistPhoneRow
import kz.epharm.pharmacists.service.PharmacistPhoneOwners
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper

class PharmacistPasswordLoginLimiterTest {
    private fun recordingJdbc(observed: MutableList<String>): JdbcTemplate {
        val jdbc = mockk<JdbcTemplate>()
        every { jdbc.query(any<String>(), any<RowMapper<FixedWindowAttempt>>(), any<String>(), any<Long>(), any<Long>(), any<Long>()) } answers {
            observed += arg<Array<Any?>>(2)[0] as String
            listOf(FixedWindowAttempt(observed.count { it == observed.last() }, 299))
        }
        return jdbc
    }

    private fun owner(ownerId: String, ownerIin: String) = object : PharmacistPhoneRow {
        override val id = ownerId
        override val iin = ownerIin
        override val phone = "+77015551234"
    }

    @Test fun `account attempt keys canonicalize exactly as the validated account lookup`() {
        val observed = mutableListOf<String>()
        val limiter = PharmacistPasswordLoginLimiter(recordingJdbc(observed), mockk())
        repeat(10) { index -> limiter.check(" ".repeat(index) + "000101500011" + "\t".repeat(index)) }
        val failure = assertThrows(AppException::class.java) { limiter.check("000101500011") }
        assertEquals(ErrorCode.OTP_TOO_MANY_ATTEMPTS, failure.code)
        assertEquals(299L, failure.retryAfterSeconds)
        assertEquals(1, observed.toSet().size, "Equivalent IIN inputs must use one durable attempt row")
    }

    @Test fun `a phone of one card spends the budget of that card's IIN`() {
        val observed = mutableListOf<String>()
        val phones = mockk<PharmacistPhoneOwners>()
        every { phones.owners("+77015551234") } returns listOf(owner("ph-1", "000101500011"))
        val limiter = PharmacistPasswordLoginLimiter(recordingJdbc(observed), phones)
        repeat(5) { limiter.checkPhone("+77015551234") }
        repeat(5) { limiter.check("000101500011") }
        assertEquals(ErrorCode.OTP_TOO_MANY_ATTEMPTS, assertThrows(AppException::class.java) { limiter.checkPhone("+77015551234") }.code)
        assertEquals(1, observed.toSet().size, "Switching between IIN and phone must not add attempts")
    }

    @Test fun `an unknown or shared phone has a budget of its own`() {
        val observed = mutableListOf<String>()
        val phones = mockk<PharmacistPhoneOwners>()
        every { phones.owners("+77015550000") } returns emptyList()
        every { phones.owners("+77015559999") } returns listOf(owner("ph-1", "000101500011"), owner("ph-2", "000101500022"))
        val limiter = PharmacistPasswordLoginLimiter(recordingJdbc(observed), phones)
        limiter.checkPhone("+77015550000")
        limiter.checkPhone("+77015559999")
        limiter.check("000101500011")
        limiter.check("000101500022")
        assertEquals(4, observed.toSet().size, "A phone of no card or of several cards must not spend a card's budget")
    }
}
