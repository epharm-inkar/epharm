package kz.epharm.pharmacists

import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.pharmacists.service.PharmacistMoney
import kz.epharm.pharmacists.service.PharmacistService
import kz.epharm.pharmacists.service.currentMoney
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
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * «Продажи и заработок каждого в админке» (Grigoriy, 25.11.2026): the roster shows what each pharmacist sold in
 * 30 days at the current pharmacy (refunds, older sales and other pharmacies excluded) and what the wallet ledger
 * credited in 30 days, whatever the stored running counter says.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Transactional
class PharmacistMoneyIntegrationTest {
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
    @Autowired private lateinit var pharmacists: PharmacistRepository
    @Autowired private lateinit var service: PharmacistService

    private val now: Instant = Instant.now()

    private fun pharmacy(id: String) {
        // pharmacies.chain_id references chains: the chain goes first (as in MerchRewardsIntegrationTest).
        jdbc.update("""INSERT INTO chains(id,name,"group") VALUES ('money-chain','TEST','pilot') ON CONFLICT DO NOTHING""")
        jdbc.update(
            """INSERT INTO pharmacies(id,name,chain_id,chain_name,city,"group") VALUES (?,?,'money-chain','TEST','TEST','pilot')
                ON CONFLICT DO NOTHING""",
            id, id,
        )
    }

    private fun pharmacist(id: String, iin: String, pharmacyId: String) = jdbc.update(
        """INSERT INTO pharmacists(id,name,iin,phone,pharmacy_id,pharmacy_name,tier,status,balance,earned_30d)
            VALUES (?,?,?,?,?,?,'Silver','active',?,?)""",
        id, "Тест $id", iin, "+7701${iin.takeLast(7)}", pharmacyId, pharmacyId, 700L, 9_999L,
    )

    private fun sale(pharmacistId: String, pharmacyId: String, amount: Long, at: Instant, refund: Boolean = false) =
        jdbc.update(
            """INSERT INTO pos_sales (id, pharmacist_id, pharmacy_id, total_amount, items, printed_at, pharmacist_source, reward_evidence)
                VALUES (?, ?, ?, ?, '[]'::jsonb, ?, 'posm_internal', ?::jsonb)""",
            "sale_${UUID.randomUUID()}", pharmacistId, pharmacyId, amount, Timestamp.from(at),
            if (refund) """{"completion":{"status":"refund"}}""" else null,
        )

    private fun credit(pharmacistId: String, earned: Long, at: Instant, source: String = "training_bonus") = jdbc.update(
        """INSERT INTO pharmacist_wallet_entries
            (id,pharmacist_id,source,source_id,amount_kzt,earned_delta_kzt,context_key,balance_after_kzt,created_at)
            VALUES (?,?,?,?,?,?,?,?,?)""",
        UUID.randomUUID(), pharmacistId, source, "src_${UUID.randomUUID()}",
        if (source == "payout_reservation") -earned else earned, if (source == "payout_reservation") 0L else earned,
        "test", 1_000L, Timestamp.from(at),
    )

    @Test
    fun `sales are the current pharmacy's receipts of 30 days and earnings are the ledger's credits of 30 days`() {
        pharmacy("money_ph_a")
        pharmacy("money_ph_b")
        pharmacist("money_seller", "980101300001", "money_ph_a")
        pharmacist("money_trainee", "980101300002", "money_ph_a")
        pharmacist("money_silent", "980101300003", "money_ph_a")
        listOf(100L, 200L, 300L).forEach { sale("money_seller", "money_ph_a", it, now.minus(2, ChronoUnit.DAYS)) }
        sale("money_seller", "money_ph_a", 5_000, now.minus(1, ChronoUnit.DAYS), refund = true) // a refund is not a sale
        sale("money_seller", "money_ph_a", 7_000, now.minus(40, ChronoUnit.DAYS)) // older than 30 days
        sale("money_seller", "money_ph_b", 9_000, now.minus(1, ChronoUnit.DAYS)) // another pharmacy, not attached
        credit("money_seller", 500, now.minus(5, ChronoUnit.DAYS))
        credit("money_seller", 500, now.minus(4, ChronoUnit.DAYS), source = "payout_reservation") // not earnings
        credit("money_seller", 900, now.minus(40, ChronoUnit.DAYS)) // older than 30 days
        credit("money_trainee", 50, now.minus(1, ChronoUnit.DAYS))

        val money = pharmacists.currentMoney(
            mapOf("money_seller" to "money_ph_a", "money_trainee" to "money_ph_a", "money_silent" to "money_ph_a"),
            observedAt = now,
        )
        assertThat(money["money_seller"]).isEqualTo(PharmacistMoney(receipts = 3, sales = 600, earned = 500))
        assertThat(money["money_trainee"]).isEqualTo(PharmacistMoney(receipts = 0, sales = 0, earned = 50))
        assertThat(money).doesNotContainKey("money_silent")

        // The roster carries the same numbers; the stored running counter (9 999) is not shown as 30 days.
        val roster = service.list(pharmacyId = "money_ph_a").associateBy { it.id }
        assertThat(roster.getValue("money_seller").receipts30d).isEqualTo(3)
        assertThat(roster.getValue("money_seller").sales30d).isEqualTo(600)
        assertThat(roster.getValue("money_seller").earned30d).isEqualTo(500)
        assertThat(roster.getValue("money_seller").balance).isEqualTo(700)
        assertThat(roster.getValue("money_silent").sales30d).isEqualTo(0)
        assertThat(roster.getValue("money_silent").earned30d).isEqualTo(0)
    }
}
