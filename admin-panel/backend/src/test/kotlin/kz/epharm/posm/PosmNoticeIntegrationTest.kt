package kz.epharm.posm

import kz.epharm.posm.notice.PosmNoticeService
import kz.epharm.posm.service.PosmDeviceIdentity
import kz.epharm.shared.error.AppException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/** Real PostgreSQL: the migration starts in maintenance, HQ switches it, a till reads its view. */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class PosmNoticeIntegrationTest {
    companion object {
        @Container @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine").apply { start() }
        @JvmStatic @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
        }
    }

    @Autowired private lateinit var notices: PosmNoticeService

    private val till = PosmDeviceIdentity("notice-device", "notice-pharmacy", legacy = false)

    @AfterEach fun restore() {
        notices.update(true, true, PosmNoticeService.DEFAULT.title, PosmNoticeService.DEFAULT.message, "test")
    }

    @Test fun `the network starts in maintenance with the default text`() {
        val view = notices.forTill(till)
        assertTrue(view.maintenance)
        assertEquals(PosmNoticeService.DEFAULT.title, view.title)
        assertEquals(PosmNoticeService.DEFAULT.message, view.message)
    }

    @Test fun `HQ switches the works off and the till sees it with the change time`() {
        val saved = notices.update(false, true, null, null, "hq@example.test")
        assertFalse(saved.maintenance)
        assertEquals("hq@example.test", saved.updatedBy)
        val view = notices.forTill(till)
        assertFalse(view.maintenance)
        assertEquals(saved.updatedAt, view.changedAt)
        // Blank texts keep the previous ones.
        assertEquals(PosmNoticeService.DEFAULT.title, view.title)
    }

    @Test fun `texts are trimmed and limited`() {
        val saved = notices.update(true, false, "  Плановые работы  ", "  Вернёмся к 15:00  ", null)
        assertEquals("Плановые работы", saved.title)
        assertEquals("Вернёмся к 15:00", saved.message)
        assertFalse(saved.pilotLive)
        assertThrows(AppException::class.java) { notices.update(true, true, "x".repeat(121), null, null) }
    }
}
