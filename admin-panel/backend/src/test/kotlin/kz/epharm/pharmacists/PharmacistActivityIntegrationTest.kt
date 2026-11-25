package kz.epharm.pharmacists

import kz.epharm.pharmacists.service.PharmacistActivityService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * «Активные» = worked with ePharm in the last seven days (Grigoriy, 25.11.2026): a sale the till
 * attributed, a tip it showed, a cabinet sign-in. Coverage is that number over pharmacists in the system.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Transactional
class PharmacistActivityIntegrationTest {
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
        }
    }

    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var activity: PharmacistActivityService

    private fun pharmacist(id: String, iin: String, created: Instant = Instant.now().minus(40, ChronoUnit.DAYS)) {
        jdbc.update(
            """
            insert into pharmacists (id, name, iin, phone, city, tier, status, joined_at, created_at, updated_at, account_source)
            values (?, ?, ?, ?, 'Алматы', 'Silver', 'active', current_date, ?, now(), 'registration')
            """.trimIndent(),
            id, "Тест $id", iin, "+7701${iin.takeLast(7)}", Timestamp.from(created),
        )
    }

    @Test
    fun `a sale, a tip and a cabinet sign-in count as work with ePharm, old activity and silence do not`() {
        val now = Instant.now()
        pharmacist("act_sale", "990101300001")
        pharmacist("act_tip", "990101300002")
        pharmacist("act_cabinet_old", "990101300003")
        pharmacist("act_silent", "990101300004")
        pharmacist("act_new_today", "990101300005", created = now)
        jdbc.update(
            "insert into pos_sales (id, pharmacist_id, pharmacy_id, total_amount, items, printed_at, pharmacist_source) values (?, 'act_sale', 'ph_x', 100, '[]'::jsonb, ?, 'posm_internal')",
            "sale_${UUID.randomUUID()}", Timestamp.from(now.minus(2, ChronoUnit.HOURS)),
        )
        jdbc.update(
            "insert into recommendation_events (id, session_id, pharmacist_id, pharmacy_id, rule_id, kind, recommend_sku, shown_at) values (?, 's', 'act_tip', 'ph_x', 'r', 'crosssell', 'sku', ?)",
            "rec_${UUID.randomUUID()}", Timestamp.from(now.minus(3, ChronoUnit.DAYS)),
        )
        jdbc.update(
            "insert into mobile_refresh_tokens (id, pharmacist_id, token_hash, expires_at, created_at) values (?, 'act_cabinet_old', ?, ?, ?)",
            UUID.randomUUID(), "hash_${UUID.randomUUID()}", Timestamp.from(now.plus(30, ChronoUnit.DAYS)),
            Timestamp.from(now.minus(10, ChronoUnit.DAYS)),
        )

        val last = activity.lastActiveSince(now.minus(30, ChronoUnit.DAYS))
        assertThat(last).containsKeys("act_sale", "act_tip", "act_cabinet_old").doesNotContainKey("act_silent")
        assertThat(last.getValue("act_sale")).isAfter(now.minus(3, ChronoUnit.HOURS))
        assertThat(activity.lastActiveSince(now.minus(7, ChronoUnit.DAYS))).doesNotContainKey("act_cabinet_old")

        val coverage = activity.coverage(30)
        val today = LocalDate.now(ZoneId.of("Asia/Almaty"))
        assertThat(coverage.daily).hasSize(30)
        assertThat(coverage.daily.last().date).isEqualTo(today)
        assertThat(coverage.total).isGreaterThanOrEqualTo(5)
        assertThat(coverage.active7d).isGreaterThanOrEqualTo(2)
        assertThat(coverage.sellers7d).isGreaterThanOrEqualTo(1)
        assertThat(coverage.coveragePct).isBetween(0.0, 100.0)
        // The pharmacist created today joins the system today, not before.
        assertThat(coverage.daily.last().total - coverage.daily[coverage.daily.size - 2].total).isGreaterThanOrEqualTo(1)
    }
}
