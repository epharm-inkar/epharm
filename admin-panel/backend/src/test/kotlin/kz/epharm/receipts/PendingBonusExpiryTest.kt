package kz.epharm.receipts

import io.mockk.confirmVerified
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Duration
import java.time.Instant
import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.receipts.repository.PendingBonusRepository
import kz.epharm.receipts.service.PendingBonusService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

class PendingBonusExpiryTest {
    @Test
    fun `expiry uses one conditional update and returns actual affected rows without stale entity saves`() {
        val pending = mockk<PendingBonusRepository>()
        val pharmacists = mockk<PharmacistRepository>()
        val service = PendingBonusService(pending, pharmacists)
        val age = Duration.ofDays(14)
        val lower = Instant.now().minus(age)
        every { pending.expireAwaitingBefore(any()) } returns 3

        assertEquals(3, service.expireStale(age))
        val upper = Instant.now().minus(age)
        verify(exactly = 1) { pending.expireAwaitingBefore(match { !it.isBefore(lower) && !it.isAfter(upper) }) }
        confirmVerified(pending)
        verify { pharmacists wasNot Called }
    }

    @Test
    fun `native expiry flushes pending edits then clears stale managed projections`() {
        val method = PendingBonusRepository::class.java.getMethod("expireAwaitingBefore", Instant::class.java)
        val modifying = method.getAnnotation(Modifying::class.java)
        assertTrue(modifying.flushAutomatically)
        assertTrue(modifying.clearAutomatically)
        assertTrue(method.getAnnotation(Query::class.java).nativeQuery)
    }
}
