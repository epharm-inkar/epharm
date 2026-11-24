package kz.epharm.appupdate

import kz.epharm.appupdate.dto.RegisterReleaseRequest
import kz.epharm.appupdate.repository.AppReleaseRepository
import kz.epharm.appupdate.service.AppReleaseService
import kz.epharm.posm.entity.PosSaleEntity
import kz.epharm.posm.repository.PosSaleRepository
import kz.epharm.posm.service.DevicePresenceService
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant

/**
 * Owner's rule 24.11.2026: a till build reaches every till only after a checked pilot. The service must
 * refuse a stable registration until the same build ran on a pilot till and a pilot receipt arrived.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Transactional
class StableReleaseGateIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("epharm_test").withUsername("epharm").withPassword("epharm_test")
            .apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun props(reg: DynamicPropertyRegistry) {
            reg.add("spring.datasource.url") { postgres.jdbcUrl }
            reg.add("spring.datasource.username") { postgres.username }
            reg.add("spring.datasource.password") { postgres.password }
            reg.add("app.posm.update-pilot-devices") { "ph_gate|KASSA1" }
            reg.add("app.posm.require-pilot-before-stable") { "true" }
            reg.add("app.posm.stable-after-pilot-minutes") { "0" }
        }
    }

    @Autowired private lateinit var service: AppReleaseService
    @Autowired private lateinit var releases: AppReleaseRepository
    @Autowired private lateinit var presence: DevicePresenceService
    @Autowired private lateinit var sales: PosSaleRepository

    @BeforeEach
    fun clean() = releases.deleteAll()

    @Test
    fun `stable is refused until the pilot build runs on a pilot till and a pilot receipt arrives`() {
        val refused = assertThrows(AppException::class.java) { service.register(build("stable")) }
        assertEquals(ErrorCode.CONFLICT, refused.code)
        assertTrue(refused.message!!.contains("пилотом"), refused.message)

        service.register(build("pilot"))
        try {
            presence.heartbeat("KASSA1", "ph_gate", appVersion = "1.0.91.0")
            val noReceipt = assertThrows(AppException::class.java) { service.register(build("stable")) }
            assertTrue(noReceipt.message!!.contains("чека"), noReceipt.message)

            sales.saveAndFlush(
                PosSaleEntity(id = "gate-sale-1", pharmacyId = "ph_gate", pharmacistId = "cashier",
                    printedAt = Instant.now(), totalAmount = 1_000).also { it.deviceId = "KASSA1" },
            )
            val stable = service.register(build("stable"))
            assertEquals("stable", stable.channel)
            assertEquals("1.0.91", service.currentFor("win-x64").version)
        } finally {
            // Presence outlives the test transaction: expire the pulse for other tests.
            presence.heartbeat("KASSA1", "ph_gate", now = Instant.now().minusSeconds(3600))
        }
    }

    @Test
    fun `a different build of the same version needs its own pilot`() {
        service.register(build("pilot"))
        val refused = assertThrows(AppException::class.java) {
            service.register(build("stable", sha256 = "b".repeat(64)))
        }
        assertTrue(refused.message!!.contains("пилотом"), refused.message)
    }

    private fun build(channel: String, sha256: String = "a".repeat(64)) = RegisterReleaseRequest(
        version = "1.0.91",
        url = "https://cdn.example/epharm-posm-1.0.91.zip",
        sha256 = sha256,
        manifestSignature = "AQID",
        channel = channel,
    )
}
