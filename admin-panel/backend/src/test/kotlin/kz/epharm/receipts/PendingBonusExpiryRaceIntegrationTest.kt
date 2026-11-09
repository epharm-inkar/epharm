package kz.epharm.receipts

import java.sql.Connection
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kz.epharm.receipts.repository.PendingBonusRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.jpa.repository.Query
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/** Only the exact repository SQL and PostgreSQL row-lock semantics; no app/SMS/external service. */
@Testcontainers
class PendingBonusExpiryRaceIntegrationTest {
    companion object {
        @Container @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("epharm_pending_expiry_test").withUsername("expiry_test").withPassword("isolated-expiry-test")
        private val cutoff = Instant.parse("2026-11-09T00:00:00Z")
    }

    private fun connection(): Connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
    private val expirySql get() = PendingBonusRepository::class.java.getMethod("expireAwaitingBefore", Instant::class.java)
        .getAnnotation(Query::class.java).value.replace(":cutoff", "?")

    @BeforeEach
    fun schema() {
        connection().use { c -> c.createStatement().use { s ->
            s.execute("DROP TABLE IF EXISTS pending_bonuses")
            s.execute("""CREATE TABLE pending_bonuses(id text PRIMARY KEY,status text NOT NULL,created_at timestamptz NOT NULL,
                source_sale_id text,updated_at timestamptz NOT NULL DEFAULT now())""")
        } }
    }

    private fun seed(id: String, state: String, createdAt: Instant, source: String? = null) {
        connection().use { c -> c.prepareStatement("INSERT INTO pending_bonuses(id,status,created_at,source_sale_id) VALUES (?,?,?,?)").use { s ->
            s.setString(1,id); s.setString(2,state); s.setTimestamp(3,Timestamp.from(createdAt)); s.setString(4,source); s.executeUpdate()
        } }
    }
    private fun expire(c: Connection): Int = c.prepareStatement(expirySql).use { s ->
        s.setTimestamp(1,Timestamp.from(cutoff)); s.executeUpdate()
    }

    @Test
    fun `only old awaiting rows expire and physical sale binding is never overwritten`() {
        seed("old","awaiting_receipt",cutoff.minusSeconds(1),"physical-sale")
        seed("boundary","awaiting_receipt",cutoff)
        seed("fresh","awaiting_receipt",cutoff.plusSeconds(1))
        seed("credited","matched",cutoff.minusSeconds(1),"paid-sale")
        seed("expired","expired",cutoff.minusSeconds(1))
        connection().use { c ->
            assertEquals(1,expire(c))
            c.createStatement().use { s -> s.executeQuery("SELECT id,status,source_sale_id FROM pending_bonuses ORDER BY id").use { rows ->
                val states = mutableMapOf<String,String>()
                while (rows.next()) {
                    states[rows.getString("id")] = rows.getString("status")
                    if (rows.getString("id") == "old") assertEquals("physical-sale",rows.getString("source_sale_id"))
                    if (rows.getString("id") == "credited") assertEquals("paid-sale",rows.getString("source_sale_id"))
                }
                assertEquals(mapOf("old" to "expired","boundary" to "awaiting_receipt","fresh" to "awaiting_receipt","credited" to "matched","expired" to "expired"),states)
            } }
            assertEquals(0,expire(c))
        }
    }

    @Test
    fun `expiry waiting on credit rechecks matched status after commit and cannot revive its old snapshot`() {
        seed("race","awaiting_receipt",cutoff.minusSeconds(1))
        val executor = Executors.newSingleThreadExecutor()
        val attempted = CountDownLatch(1)
        val backendPid = AtomicInteger()
        connection().use { credit ->
            credit.autoCommit = false
            credit.createStatement().use { it.executeUpdate("UPDATE pending_bonuses SET status='matched',source_sale_id='completed-paid-sale' WHERE id='race'") }
            val expiry = executor.submit<Int> {
                connection().use { c ->
                    c.createStatement().use { s -> s.executeQuery("SELECT pg_backend_pid()").use { rows -> rows.next(); backendPid.set(rows.getInt(1)) } }
                    attempted.countDown()
                    expire(c)
                }
            }
            try {
                assertTrue(attempted.await(3,TimeUnit.SECONDS))
                var lockObserved = false
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                connection().use { observer ->
                    while (!lockObserved && System.nanoTime() < deadline) {
                        observer.prepareStatement("SELECT wait_event_type='Lock' FROM pg_stat_activity WHERE pid=?").use { s ->
                            s.setInt(1,backendPid.get()); s.executeQuery().use { r -> lockObserved = r.next() && r.getBoolean(1) }
                        }
                        if (!lockObserved) Thread.sleep(10)
                    }
                }
                assertTrue(lockObserved,"The actual expiry UPDATE must be blocked on the credit's PostgreSQL row lock")
                assertFalse(expiry.isDone)
                credit.commit()
                assertEquals(0,expiry.get(3,TimeUnit.SECONDS))
                connection().use { c -> c.createStatement().use { s ->
                    s.executeQuery("SELECT status,source_sale_id FROM pending_bonuses WHERE id='race'").use { row ->
                        assertTrue(row.next()); assertEquals("matched",row.getString(1)); assertEquals("completed-paid-sale",row.getString(2))
                    }
                } }
            } finally {
                credit.rollback()
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(3,TimeUnit.SECONDS))
            }
        }
    }
}
