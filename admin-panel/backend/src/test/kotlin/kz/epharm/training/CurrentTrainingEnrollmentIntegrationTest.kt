package kz.epharm.training

import com.fasterxml.jackson.databind.ObjectMapper
import kz.epharm.auth.access.StoredGrant
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.auth.service.JwtService
import kz.epharm.training.repository.TrainingAssignmentRepository
import kz.epharm.training.service.TrainingAutoAssignmentService
import kz.epharm.training.service.TrainingService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Disposable database only: verify committed enrollment, not mocked permission flags. */
@SpringBootTest(properties = [
    "app.merch.automation.enabled=false",
    "app.training.auto-assignment.enabled=true",
    "app.training.auto-assignment.program-id=11111111-1111-4111-8111-111111111111",
    "app.training.auto-assignment.actor-id=22222222-2222-4222-8222-222222222222",
    "app.training.auto-assignment.initial-delay-ms=86400000",
])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class CurrentTrainingEnrollmentIntegrationTest {
    companion object {
        private val PROGRAM = UUID.fromString("11111111-1111-4111-8111-111111111111")
        private val ACTOR = UUID.fromString("22222222-2222-4222-8222-222222222222")
        private const val COURSE = "qa-current-training-course"

        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("current_training_enrollment_disposable")
            .withUsername("epharm").withPassword("synthetic-local-test-only").apply { start() }

        @JvmStatic @DynamicPropertySource fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
        }
    }

    @Autowired lateinit var enrollment: TrainingAutoAssignmentService
    @Autowired lateinit var assignments: TrainingAssignmentRepository
    @Autowired lateinit var training: TrainingService
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var users: AdminUserRepository
    @Autowired lateinit var jwt: JwtService
    private lateinit var actorToken: String
    private lateinit var version: UUID
    private var learnerSequence = 0

    @BeforeEach fun seed() {
        jdbc.execute("DROP TRIGGER IF EXISTS qa_fail_enrollment ON training_assignments")
        jdbc.execute("DROP FUNCTION IF EXISTS qa_fail_enrollment()")
        jdbc.execute("TRUNCATE chains,admin_users,unified_persons,pharmacists,courses,training_programs,standardn_pharmacist_directory CASCADE")
        learnerSequence = 0
        val actor = users.saveAndFlush(AdminUserEntity(id = ACTOR, email = "training-automation@example.invalid",
            name = "Synthetic training manager", passwordHash = "synthetic-unused-hash", roleRaw = AdminRole.TRAINING_MANAGER.name))
        val person = UUID.randomUUID()
        jdbc.update("INSERT INTO unified_persons(id,verification_reference) VALUES(?,'synthetic-current-training-proof')", person)
        jdbc.update("INSERT INTO unified_admin_profiles(account_id,person_id,grants_json) VALUES(?,?,?)",
            ACTOR, person, mapper.writeValueAsString(listOf(StoredGrant("training", "assignments", "assign", allPharmacies = true))))
        jdbc.update("UPDATE admin_users SET access_mode='MANAGED' WHERE id=?", ACTOR)
        actorToken = jwt.issueAccessToken(actor)
        jdbc.update("INSERT INTO chains(id,name,color,\"group\",points) VALUES('enrollment-chain','Synthetic','#000000','pilot',0)")
        jdbc.update("""INSERT INTO pharmacies(id,name,chain_id,chain_name,city,district,addr,"group")
            VALUES('enrollment-pharmacy','Synthetic pharmacy','enrollment-chain','Synthetic','Synthetic city','','Synthetic','pilot')""")
        jdbc.update("""INSERT INTO courses(id,title,status,lessons,duration_min,bonus,created_by)
            VALUES(?,'Synthetic current course','published',1,2,0,?)""", COURSE, ACTOR.toString())
        jdbc.update("""INSERT INTO course_lessons(id,course_id,title,kind,content,order_no)
            VALUES('enrollment-material',?,'Synthetic material','text','Read this synthetic material.',0)""", COURSE)
        jdbc.update("""INSERT INTO training_programs(id,name,allowed_formats,status,created_by,normative_days)
            VALUES(?,'Synthetic selected program','online','published',?,14)""", PROGRAM, ACTOR)
        version = insertVersion(1)
    }

    @Test fun `backfill and subsequent registrations include pending without credentials but never blocked or directory-only staff`() {
        learner("active", "active")
        learner("pending", "pending", assignedPharmacy = false, password = null)
        learner("blocked", "blocked")
        jdbc.update("""INSERT INTO standardn_pharmacist_directory(directory_key,source_profile_id,source_user_id,iin,full_name,source_status)
            VALUES('qa-directory-only',1,1,'000000009999','Synthetic unregistered Standard-N user',0)""")
        val beforeMoney = moneySnapshot()

        val first = enrollment.reconcile()
        assertThat(first.created).isEqualTo(2)
        assertThat(first.failed).isZero()
        assertThat(jdbc.queryForList("SELECT pharmacist_id FROM training_assignments ORDER BY pharmacist_id", String::class.java))
            .containsExactly("active", "pending")
        assertEnrollmentSideEffects(2)
        val beforeRepeat = enrollmentSnapshot()
        assertThat(enrollment.reconcile().created).isZero()
        assertThat(enrollmentSnapshot()).isEqualTo(beforeRepeat)

        learner("future-otp-registration", "pending", assignedPharmacy = false, password = null)
        assertThat(enrollment.reconcile().created).isEqualTo(1)
        assertEnrollmentSideEffects(3)
        assertThat(moneySnapshot().filterKeys { it != "balances" }).isEqualTo(beforeMoney.filterKeys { it != "balances" })
        assertThat(jdbc.queryForList("SELECT balance FROM pharmacists", Long::class.java)).allMatch { it == 37L }
        assertThat(jdbc.queryForObject("SELECT status FROM pharmacists WHERE id='pending'", String::class.java)).isEqualTo("pending")
        assertThat(jdbc.queryForObject("SELECT password_hash FROM pharmacists WHERE id='pending'", String::class.java)).isNull()
        val pendingToken = jwt.issuePharmacistToken("pending", "Synthetic pending",
            jdbc.queryForObject("SELECT phone FROM pharmacists WHERE id='pending'", String::class.java)!!)
        val result = mvc.perform(get("/api/mobile/training").header("Authorization", "Bearer $pendingToken")).andReturn()
        assertThat(result.response.status).describedAs(result.response.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(result.response.contentAsByteArray)["assignments"].single()["programId"].asText())
            .isEqualTo(PROGRAM.toString())
    }

    @Test fun `disabled or malformed configuration never falls back to another target`() {
        learner("eligible", "active")
        val disabled = TrainingAutoAssignmentService(assignments, training, false, "not-a-program", "not-an-actor")
        assertThat(disabled.reconcile().scanned).isZero()
        for ((program, actor) in listOf("" to ACTOR.toString(), "not-a-uuid" to ACTOR.toString(),
            PROGRAM.toString() to "not-a-uuid", UUID.randomUUID().toString() to ACTOR.toString())) {
            val invalid = TrainingAutoAssignmentService(assignments, training, true, program, actor)
            assertThatThrownBy { invalid.reconcile() }.isInstanceOf(RuntimeException::class.java)
        }
        assertThat(count("training_assignments")).isZero()
        assertThat(count("training_notifications")).isZero()
    }

    @Test fun `keyset reconciliation reaches later batches while earlier enrollments leave the candidate set`() {
        jdbc.update("""INSERT INTO pharmacists(id,name,iin,phone,status,balance,tier)
            SELECT 'batch-'||lpad(g::text,3,'0'),'Synthetic batch '||g,lpad(g::text,12,'0'),'+7'||lpad(g::text,10,'0'),'pending',37,'Silver'
            FROM generate_series(1,105) g""")
        assertThat(enrollment.reconcile().created).isEqualTo(105)
        assertEnrollmentSideEffects(105)
        assertThat(enrollment.reconcile().created).isZero()
        assertEnrollmentSideEffects(105)
    }

    @ParameterizedTest
    @ValueSource(strings = ["completed", "overdue", "cancelled", "in_progress", "paused"])
    fun `any historical assignment across program versions is preserved without re-enrollment`(status: String) {
        learner("existing", "active")
        val assignment = UUID.randomUUID()
        jdbc.update("""INSERT INTO training_assignments(id,program_version_id,pharmacist_id,status,assigned_by,due_at,progress_pct,score,repeat_no,format)
            VALUES(?,?,'existing',?,?,now()-interval '2 days',67,85,1,'online')""", assignment, version, status, ACTOR)
        jdbc.update("UPDATE pharmacists SET courses_total=1 WHERE id='existing'")
        jdbc.update("UPDATE courses SET enrolled=1 WHERE id=?", COURSE)
        val definition = jdbc.queryForObject("SELECT id FROM training_program_stages WHERE version_id=? AND stage_key='online'", UUID::class.java, version)!!
        val stage = UUID.randomUUID()
        jdbc.update("""INSERT INTO training_assignment_stages(id,assignment_id,program_stage_id,status,progress_pct,score,attempts_used)
            VALUES(?,?,?,'in_progress',67,85,2)""", stage, assignment, definition)
        jdbc.update("""INSERT INTO training_lesson_progress(id,assignment_id,assignment_stage_id,lesson_id,progress_pct)
            VALUES(?,?,?,'enrollment-material',67)""", UUID.randomUUID(), assignment, stage)
        insertVersion(2)
        jdbc.update("UPDATE training_programs SET current_version=2 WHERE id=?", PROGRAM)
        val before = enrollmentSnapshot()

        assertThat(enrollment.reconcile().created).isZero()
        assertThat(enrollment.reconcile().created).isZero()
        assertThat(enrollmentSnapshot()).isEqualTo(before)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM training_assignments", Int::class.java)).isEqualTo(1)
    }

    @ParameterizedTest
    @ValueSource(strings = ["draft-program", "archived-program", "draft-course", "archived-course", "no-course", "no-lessons", "no-online-stage", "offline-only", "expired-program"])
    fun `invalid configured target fails closed without enrolling or changing another published program`(invalid: String) {
        learner("eligible", "active")
        when (invalid) {
            "draft-program" -> jdbc.update("UPDATE training_programs SET status='draft' WHERE id=?", PROGRAM)
            "archived-program" -> jdbc.update("UPDATE training_programs SET status='archived' WHERE id=?", PROGRAM)
            "draft-course" -> jdbc.update("UPDATE courses SET status='draft' WHERE id=?", COURSE)
            "archived-course" -> jdbc.update("UPDATE courses SET status='archived' WHERE id=?", COURSE)
            "no-course" -> jdbc.update("UPDATE training_program_versions SET online_course_id=NULL WHERE id=?", version)
            "no-lessons" -> jdbc.update("DELETE FROM course_lessons WHERE course_id=?", COURSE)
            "no-online-stage" -> jdbc.update("UPDATE training_program_stages SET type='material' WHERE version_id=? AND stage_key='online'", version)
            "offline-only" -> jdbc.update("UPDATE training_program_versions SET allowed_formats='offline' WHERE id=?", version)
            "expired-program" -> jdbc.update("UPDATE training_programs SET ends_at=now()-interval '1 day' WHERE id=?", PROGRAM)
        }
        jdbc.update("""INSERT INTO training_programs(id,name,allowed_formats,status,created_by)
            VALUES(?,'Unrelated newest published program','online','published',?)""", UUID.randomUUID(), ACTOR)
        val before = enrollmentSnapshot()
        assertThatThrownBy { enrollment.reconcile() }.isInstanceOf(RuntimeException::class.java)
        assertThat(enrollmentSnapshot()).isEqualTo(before)
        assertThat(count("training_assignments")).isZero()
    }

    @ParameterizedTest
    @ValueSource(strings = ["blocked", "wrong-role", "revoked-grant", "assigned-scope", "missing-profile"])
    fun `current actor authority is checked again after revocation`(revocation: String) {
        learner("first", "active")
        assertThat(enrollment.reconcile().created).isEqualTo(1)
        learner("later", "pending", assignedPharmacy = false, password = null)
        when (revocation) {
            "blocked" -> jdbc.update("UPDATE admin_users SET status='blocked' WHERE id=?", ACTOR)
            "wrong-role" -> jdbc.update("UPDATE admin_users SET role='HQ_HEAD' WHERE id=?", ACTOR)
            "revoked-grant" -> jdbc.update("UPDATE unified_admin_profiles SET grants_json='[]',version=version+1 WHERE account_id=?", ACTOR)
            "assigned-scope" -> jdbc.update("UPDATE unified_admin_profiles SET grants_json=?,version=version+1 WHERE account_id=?",
                mapper.writeValueAsString(listOf(StoredGrant("training", "assignments", "assign", pharmacyIds = setOf("enrollment-pharmacy")))), ACTOR)
            "missing-profile" -> jdbc.update("DELETE FROM unified_admin_profiles WHERE account_id=?", ACTOR)
        }
        val before = enrollmentSnapshot()
        assertThatThrownBy { enrollment.reconcile() }.isInstanceOf(RuntimeException::class.java)
        assertThat(enrollmentSnapshot()).isEqualTo(before)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM training_assignments WHERE pharmacist_id='later'", Int::class.java)).isZero()
    }

    @Test fun `manual assignment still rejects pending while automatic enrollment accepts the registered account`() {
        learner("pending", "pending", assignedPharmacy = false, password = null)
        val response = manualAssignment("pending")
        assertThat(response.first).describedAs(response.second).isEqualTo(400)
        assertThat(count("training_assignments")).isZero()
        assertThat(enrollment.reconcile().created).isEqualTo(1)
        assertEnrollmentSideEffects(1)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `concurrent enrollment and manual assignment converge to one complete assignment`(manual: Boolean) {
        learner("concurrent", "active")
        val pool = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        try {
            val first = pool.submit {
                ready.countDown(); check(start.await(10, TimeUnit.SECONDS))
                enrollment.reconcile()
            }
            val second = pool.submit {
                ready.countDown(); check(start.await(10, TimeUnit.SECONDS))
                if (manual) {
                    val response = manualAssignment("concurrent")
                    assertThat(response.first).describedAs(response.second).isEqualTo(200)
                } else enrollment.reconcile()
            }
            check(ready.await(10, TimeUnit.SECONDS)); start.countDown()
            first.get(30, TimeUnit.SECONDS); second.get(30, TimeUnit.SECONDS)
            assertEnrollmentSideEffects(1)
            assertThat(enrollment.reconcile().created).isZero()
            assertEnrollmentSideEffects(1)
        } finally {
            start.countDown(); pool.shutdownNow()
            check(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test fun `one failed recipient does not starve later users and retry has no duplicated side effects`() {
        learner("a-fails-once", "active")
        learner("z-succeeds", "pending", assignedPharmacy = false, password = null)
        jdbc.execute("""CREATE FUNCTION qa_fail_enrollment() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN IF NEW.pharmacist_id='a-fails-once' THEN RAISE EXCEPTION 'Synthetic recipient failure'; END IF; RETURN NEW; END ${'$'}${'$'}""")
        jdbc.execute("CREATE TRIGGER qa_fail_enrollment BEFORE INSERT ON training_assignments FOR EACH ROW EXECUTE FUNCTION qa_fail_enrollment()")
        try {
            val result = enrollment.reconcile()
            assertThat(result.created).isEqualTo(1)
            assertThat(result.failed).isEqualTo(1)
            assertThat(jdbc.queryForList("SELECT pharmacist_id FROM training_assignments", String::class.java)).containsExactly("z-succeeds")
            assertEnrollmentSideEffects(1)
        } finally {
            jdbc.execute("DROP TRIGGER qa_fail_enrollment ON training_assignments")
            jdbc.execute("DROP FUNCTION qa_fail_enrollment()")
        }
        assertThat(enrollment.reconcile().created).isEqualTo(1)
        assertEnrollmentSideEffects(2)
        assertThat(enrollment.reconcile().created).isZero()
        assertEnrollmentSideEffects(2)
    }

    @Test fun `future start and program end bound new enrollment without changing existing bonus configuration`() {
        learner("scheduled", "pending", assignedPharmacy = false, password = null)
        val start = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(86_400)
        val end = start.plusSeconds(2 * 86_400)
        jdbc.update("UPDATE training_programs SET starts_at=?,ends_at=? WHERE id=?", Timestamp.from(start), Timestamp.from(end), PROGRAM)
        jdbc.update("UPDATE courses SET bonus=15 WHERE id=?", COURSE)
        jdbc.update("UPDATE training_program_versions SET completion_bonus=80 WHERE id=?", version)
        jdbc.update("UPDATE training_program_stages SET bonus=20 WHERE version_id=? AND stage_key='online'", version)
        val money = moneySnapshot()
        assertThat(enrollment.reconcile().created).isEqualTo(1)
        val assignment = jdbc.queryForMap("SELECT status,starts_at,due_at FROM training_assignments")
        assertThat(assignment["status"]).isEqualTo("scheduled")
        assertThat((assignment["starts_at"] as Timestamp).toInstant()).isEqualTo(start)
        assertThat((assignment["due_at"] as Timestamp).toInstant()).isEqualTo(end)
        assertThat(moneySnapshot()).isEqualTo(money)
    }

    private fun insertVersion(number: Int): UUID {
        val id = UUID.randomUUID()
        jdbc.update("""INSERT INTO training_program_versions(id,program_id,version_no,allowed_formats,online_course_id,snapshot_json,created_by)
            VALUES(?,?,?,'online',?,'{}',?)""", id, PROGRAM, number, COURSE, ACTOR)
        jdbc.update("""INSERT INTO training_program_stages(id,version_id,stage_key,type,title,order_no,applicable_formats)
            VALUES(?,?,'online','online_course','Synthetic online stage',0,'online')""", UUID.randomUUID(), id)
        jdbc.update("""INSERT INTO training_program_stages(id,version_id,stage_key,type,title,order_no,unlock_after_key,applicable_formats)
            VALUES(?,?,'reading','material','Synthetic reading stage',1,'online','online')""", UUID.randomUUID(), id)
        return id
    }

    private fun learner(id: String, status: String, assignedPharmacy: Boolean = true, password: String? = "synthetic-unused-hash") {
        learnerSequence++
        jdbc.update("""INSERT INTO pharmacists(id,name,iin,phone,password_hash,pharmacy_id,pharmacy_name,city,status,balance,tier)
            VALUES(?,?,?,?,?,?,?,?,?,37,'Silver')""", id, "Synthetic $id", learnerSequence.toString().padStart(12, '0'),
            "+7" + learnerSequence.toString().padStart(10, '0'), password,
            if (assignedPharmacy) "enrollment-pharmacy" else null,
            if (assignedPharmacy) "Synthetic pharmacy" else null, "Synthetic city", status)
    }

    private fun manualAssignment(id: String): Pair<Int, String> {
        val response = mvc.perform(post("/api/admin/training/assignments")
            .header("Authorization", "Bearer $actorToken").contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsBytes(mapOf("programId" to PROGRAM, "pharmacistIds" to listOf(id), "format" to "online"))))
            .andReturn().response
        return response.status to response.contentAsString
    }

    private fun assertEnrollmentSideEffects(assignments: Int) {
        assertThat(count("training_assignments")).isEqualTo(assignments.toLong())
        assertThat(count("training_assignment_stages")).isEqualTo(assignments * 2L)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM training_notifications WHERE event_type='training_assigned'", Long::class.java))
            .isEqualTo(assignments.toLong())
        assertThat(count("training_notifications")).isEqualTo(assignments * 3L)
        assertThat(jdbc.queryForObject("SELECT enrolled FROM courses WHERE id=?", Int::class.java, COURSE)).isEqualTo(assignments)
        assertThat(jdbc.queryForObject("SELECT sum(courses_total) FROM pharmacists", Long::class.java)).isEqualTo(assignments.toLong())
        assertThat(count("training_rewards")).isZero()
        assertThat(count("pharmacist_wallet_entries")).isZero()
        assertThat(count("training_certificates")).isZero()
    }

    private fun count(table: String): Long = jdbc.queryForObject("SELECT count(*) FROM $table", Long::class.java)!!

    private fun enrollmentSnapshot(): Map<String, Any> = listOf("training_assignments", "training_assignment_stages", "training_lesson_progress",
        "training_notifications", "training_audit_log", "training_rewards", "training_certificates").associateWith { table ->
        jdbc.queryForList("SELECT * FROM $table ORDER BY id")
    } + mapOf("courseCounters" to jdbc.queryForList("SELECT id,enrolled,completed FROM courses ORDER BY id"),
        "pharmacistCounters" to jdbc.queryForList("SELECT id,courses_total,courses_done FROM pharmacists ORDER BY id"))

    private fun moneySnapshot(): Map<String, Any> = mapOf(
        "balances" to jdbc.queryForList("SELECT id,balance FROM pharmacists ORDER BY id"),
        "wallet" to jdbc.queryForList("SELECT * FROM pharmacist_wallet_entries ORDER BY id"),
        "rewards" to jdbc.queryForList("SELECT * FROM training_rewards ORDER BY id"),
        "courseBonuses" to jdbc.queryForList("SELECT id,bonus FROM courses ORDER BY id"),
        "versionBonuses" to jdbc.queryForList("SELECT id,completion_bonus FROM training_program_versions ORDER BY id"),
        "stageBonuses" to jdbc.queryForList("SELECT id,bonus FROM training_program_stages ORDER BY id"),
    )
}
