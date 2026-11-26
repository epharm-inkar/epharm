package kz.epharm.posm

import kz.epharm.posm.service.DevicePresenceService
import kz.epharm.posm.service.StaffSourceStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Источник «кто продаёт» в heartbeat кассы: только фиксированные коды, ничего личного.
 * POSM 1.0.100 добавил `manager_only`: на компьютере работает только «Менеджер» Стандарт-Н, ePharm поставили не на кассу.
 */
class StaffSourceStatusTest {

    private val everyCodeATillReports = listOf(
        "initializing", "unknown", "local_disabled", "service_disabled", "resolved",
        "no_process", "manager_only", "process_unavailable", "ambiguous_process", "process_changing",
        "database_disabled", "database_unavailable", "no_session", "ambiguous_session",
        "unknown_workstation", "unsupported_session_time", "future_session_time", "stale_session",
        "invalid_session_time", "unsupported_process_time", "session_predates_process",
    )

    @Test
    fun `manager_only from a till with only the Standard-N manager program is kept`() {
        assertEquals("manager_only", StaffSourceStatus.normalize("manager_only"))
    }

    @Test
    fun `every fixed code a till reports is kept as is`() {
        for (code in everyCodeATillReports) {
            assertEquals(code, StaffSourceStatus.normalize(code), "code $code")
        }
    }

    @Test
    fun `a heartbeat with manager_only reaches the connected tills unchanged`() {
        val presence = DevicePresenceService(ttlSeconds = 90)
        val now = Instant.parse("2026-11-26T10:00:00Z")

        presence.heartbeat("MANAGER-PC", "ph_a", now, appVersion = "1.0.100",
            staffAuthStatus = "local_disabled", staffSourceStatus = "manager_only")

        val till = presence.connected(now.plusSeconds(1)).single()
        assertEquals("manager_only", till.staffSourceStatus)
        assertEquals("local_disabled", till.staffAuthStatus)
    }

    @Test
    fun `anything that is not a fixed code is dropped`() {
        val rejected = listOf(
            null, "", " ", "MANAGER_ONLY", "manager_only ", "manager_only\nprivate",
            "manager", "authenticated", "iin=000101500011",
        )
        for (value in rejected) {
            assertNull(StaffSourceStatus.normalize(value), "value $value")
        }
    }
}
