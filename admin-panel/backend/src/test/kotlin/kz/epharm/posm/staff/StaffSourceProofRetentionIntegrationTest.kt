package kz.epharm.posm.staff

import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
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

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Transactional
class StaffSourceProofRetentionIntegrationTest {
    companion object {
        @Container @JvmStatic val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("source_proof_retention").withUsername("epharm").withPassword("epharm_test").apply { start() }
        @JvmStatic @DynamicPropertySource fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
        }
    }
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var bindings: SourceStaffBindingService
    @Autowired lateinit var cleanup: StaffAuthCleanup
    private val namespace = "a".repeat(64)
    private val created = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS).minusSeconds(72 * 3600)
    private val paid = created.plusSeconds(3600)
    private val expires = created.plusSeconds(8 * 3600)
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun stamp(value: Instant) = Timestamp.from(value)
    private fun resolve(at: Instant = paid, device: String = "proof-device", ns: String = namespace,
                        user: String = "17", session: String = "41", workstation: String = "ws", pharmacy: String = "proof-ph") =
        bindings.resolve(pharmacy, device, ns, user, at, session, workstation)

    @BeforeEach fun seed() {
        jdbc.update("""INSERT INTO chains(id,name,"group") VALUES ('proof-chain','Synthetic','pilot')""")
        jdbc.update("""INSERT INTO pharmacies(id,name,chain_id,chain_name,city,"group")
            VALUES ('proof-ph','Synthetic','proof-chain','Synthetic','Synthetic','pilot')""")
        jdbc.update("""INSERT INTO pharmacists(id,name,iin,phone,pharmacy_id,pharmacy_name,tier,status,password_hash)
            VALUES ('proof-person','Synthetic Person','900115300013','+77000000177','proof-ph','Synthetic','Silver','active','synthetic-password-hash')""")
        jdbc.update("""INSERT INTO posm_source_staff_bindings(id,pharmacy_id,device_id,source_namespace,native_user_id,
            pharmacist_id,proof_method,proof_hash,state,reason_code,created_at)
            VALUES (?,'proof-ph','proof-device',?,'17','proof-person','IIN',?,'active','verified_iin',?)""",
            UUID.randomUUID(), namespace, hash("IIN:900115300013"), stamp(created))
        grant("expired", created, expires)
    }

    private fun grant(id: String, start: Instant, end: Instant) {
        jdbc.update("""INSERT INTO posm_device_staff_sessions(token_hash,pharmacist_id,device_id,pharmacy_id,
            password_fingerprint,process_instance_id,standard_n_user_id,standard_n_session_id,workstation_id,
            source_namespace,created_at,source_verified_at,expires_at,source_proof_method,source_proof_hash)
            VALUES (?,'proof-person','proof-device','proof-ph',?,'synthetic-process','17','41','ws',?,?,?,?,'IIN',?)""",
            hash(id), hash("synthetic-password-hash"), namespace, stamp(start), stamp(start.plusSeconds(1)), stamp(end), hash("IIN:900115300013"))
    }

    @Test fun `cleanup retains exact historical attribution but never current authentication`() {
        assertEquals("proof-person", resolve().recipient?.id)
        grant("current", Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600))
        cleanup.cleanExpired(); cleanup.cleanExpired()
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM posm_staff_source_proofs", Int::class.java))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM posm_device_staff_sessions WHERE token_hash=?", Int::class.java, hash("expired")))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM posm_device_staff_sessions WHERE token_hash=? AND expires_at>now()", Int::class.java, hash("current")))
        assertEquals("proof-person", resolve().recipient?.id)
        // No archive lookup exists in authentication: old token remains absent, not a renewed grant.
        jdbc.update("DELETE FROM posm_device_staff_sessions WHERE token_hash=?", hash("current"))
        assertNull(resolve(Instant.now()).recipient)
        val columns = jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_name='posm_staff_source_proofs'", String::class.java)
        assertTrue(columns.none { it in setOf("token_hash", "process_instance_id", "standard_n_iin", "standard_n_full_name", "phone", "password_hash") })
    }

    @Test fun `historical proof cannot cross its expiry verification time or native scope`() {
        cleanup.cleanExpired()
        assertNull(resolve(expires).recipient)
        assertNull(resolve(created).recipient)
        assertNull(resolve(device="other").recipient)
        assertNull(resolve(ns="b".repeat(64)).recipient)
        assertNull(resolve(user="18").recipient)
        assertNull(resolve(session="42").recipient)
        assertNull(resolve(workstation="other").recipient)
        assertNull(resolve(pharmacy="other").recipient)
        assertEquals("proof-person", resolve().recipient?.id)
    }

    @Test fun `revocation before receipt remains a denial after cleanup while earlier receipts stay valid`() {
        jdbc.update("UPDATE posm_device_staff_sessions SET revoked_at=? WHERE token_hash=?", stamp(paid), hash("expired"))
        cleanup.cleanExpired()
        assertNull(resolve().recipient)
        assertEquals("proof-person", resolve(paid.minusSeconds(1)).recipient?.id)
        assertEquals(stamp(paid), jdbc.queryForObject("SELECT revoked_at FROM posm_staff_source_proofs", Timestamp::class.java))
    }

    @Test fun `password or HQ revocation still blocks an archived grant`() {
        cleanup.cleanExpired()
        jdbc.update("UPDATE pharmacists SET password_hash='different-password-hash' WHERE id='proof-person'")
        assertNull(resolve().recipient)
        jdbc.update("UPDATE pharmacists SET password_hash='synthetic-password-hash' WHERE id='proof-person'")
        jdbc.update("UPDATE posm_source_staff_bindings SET state='conflict',conflict_at=now(),reason_code='hq_mapping_revoked'")
        assertNull(resolve().recipient)
    }

    @Test fun `retention boundary denies proof before hourly cleanup and cleanup removes it`() {
        cleanup.cleanExpired()
        jdbc.update("UPDATE posm_staff_source_proofs SET retained_until=now()")
        assertNull(resolve().recipient)
        cleanup.cleanExpired()
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM posm_staff_source_proofs", Int::class.java))
    }

    @Test fun `unverified and older than retention grants are deleted without archival`() {
        jdbc.update("UPDATE posm_device_staff_sessions SET source_verified_at=NULL WHERE token_hash=?", hash("expired"))
        grant("too-old", Instant.now().minusSeconds(92 * 86400), Instant.now().minusSeconds(91 * 86400))
        cleanup.cleanExpired()
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM posm_staff_source_proofs", Int::class.java))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM posm_device_staff_sessions", Int::class.java))
        assertNull(resolve().recipient)
    }
}
