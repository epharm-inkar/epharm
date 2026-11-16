package kz.epharm.posm.staff

import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate

class StaffAuthCleanupContractTest {
    @Test fun `retention is bounded and cannot silently become an indefinite archive`() {
        val jdbc = mockk<JdbcTemplate>()
        for (days in listOf(0, -1, 366)) assertThrows(IllegalArgumentException::class.java) { StaffAuthCleanup(jdbc, days) }
        assertDoesNotThrow { StaffAuthCleanup(jdbc, 1) }
        assertDoesNotThrow { StaffAuthCleanup(jdbc, 365) }
    }

    @Test fun `cleanup moves expired proof atomically with explicit bounded retention`() {
        val jdbc = mockk<JdbcTemplate>(relaxed=true)
        StaffAuthCleanup(jdbc, 30).cleanExpired()
        verify(exactly=1) { jdbc.update(StaffAuthCleanup.ARCHIVE_EXPIRED_SOURCE_PROOFS, 30, 30) }
        verify { jdbc.update(match { it.startsWith("DELETE FROM posm_staff_source_proofs") }, 30) }
        val sql = StaffAuthCleanup.ARCHIVE_EXPIRED_SOURCE_PROOFS
        assertTrue(sql.contains("DELETE FROM posm_device_staff_sessions WHERE expires_at<now()-interval '24 hours' RETURNING *"))
        assertTrue(sql.contains("INSERT INTO posm_staff_source_proofs"))
        assertTrue(sql.contains("source_verified_at<expires_at"))
        val archiveProjection = sql.substringAfter("INSERT INTO posm_staff_source_proofs")
        assertFalse(archiveProjection.contains("token_hash"))
        assertFalse(archiveProjection.contains("standard_n_iin"))
        assertFalse(archiveProjection.contains("standard_n_full_name"))
    }
}
