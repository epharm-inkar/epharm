package kz.epharm.pharmacies

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.persistence.EntityManager
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.domain.AdminUserStatus
import kz.epharm.auth.dto.LoginRequest
import kz.epharm.auth.dto.LoginResponse
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.pharmacies.entity.ChainEntity
import kz.epharm.pharmacies.entity.PharmacyEntity
import kz.epharm.pharmacies.entity.PharmacyGroup
import kz.epharm.pharmacies.repository.ChainRepository
import kz.epharm.pharmacies.repository.PharmacyRepository
import kz.epharm.pharmacies.service.PharmacyMetricsScheduler
import kz.epharm.pharmacists.entity.PharmacistEntity
import kz.epharm.pharmacists.repository.PharmacistRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Transactional
class PharmaciesIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("epharm_test")
            .withUsername("epharm")
            .withPassword("epharm_test")
            .apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun props(reg: DynamicPropertyRegistry) {
            reg.add("spring.datasource.url") { postgres.jdbcUrl }
            reg.add("spring.datasource.username") { postgres.username }
            reg.add("spring.datasource.password") { postgres.password }
        }
    }

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var pharmacyRepository: PharmacyRepository
    @Autowired private lateinit var chainRepository: ChainRepository
    @Autowired private lateinit var pharmacistRepository: PharmacistRepository
    @Autowired private lateinit var adminUserRepository: AdminUserRepository
    @Autowired private lateinit var passwordEncoder: PasswordEncoder
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var entityManager: EntityManager
    @Autowired private lateinit var metricsScheduler: PharmacyMetricsScheduler

    private lateinit var bearer: String

    @BeforeEach
    fun seed() {
        pharmacistRepository.deleteAll()
        pharmacyRepository.deleteAll()
        chainRepository.deleteAll()
        adminUserRepository.deleteAll()

        chainRepository.save(
            ChainEntity(id = "europharma", name = "Europharma", color = "#2A2BE2", points = 100)
                .also { it.group = PharmacyGroup.pilot },
        )
        chainRepository.save(
            ChainEntity(id = "biosfera", name = "Биосфера", color = "#8B5CF6", points = 50)
                .also { it.group = PharmacyGroup.control },
        )

        pharmacyRepository.save(
            PharmacyEntity(
                id = "europharma_0", name = "Europharma №100",
                chainId = "europharma", chainName = "Europharma",
                city = "Алматы", district = "Бостандыкский", addr = "ул. Абая, 10",
                pharmacists = 3, receipts30d = 500, gmv30d = 1_500_000,
            ).also { it.group = PharmacyGroup.pilot },
        )

        adminUserRepository.save(
            AdminUserEntity(
                email = "damir@jadran.com",
                passwordHash = passwordEncoder.encode("damir2026"),
                name = "Дамир", company = "Jadran",
            ).also { it.role = AdminRole.BRAND_MANAGER; it.status = AdminUserStatus.ACTIVE },
        )
        bearer = "Bearer " + login().tokens.accessToken
    }

    @Test
    fun `GET chains returns 2 seeded`() {
        mockMvc.perform(get("/api/admin/pharmacies/chains").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].id").value("europharma")) // sorted by points DESC
    }

    @Test
    fun `GET pharmacies — список с правильными полями`() {
        mockMvc.perform(get("/api/admin/pharmacies").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].chainName").value("Europharma"))
            .andExpect(jsonPath("$[0].city").value("Алматы"))
            .andExpect(jsonPath("$[0].registeredDeviceCount").value(0))
            .andExpect(jsonPath("$[0].onlineDeviceCount").value(0))
    }

    @Test
    fun `pharmacist column counts live profiles instead of the stale stored counter`() {
        pharmacyRepository.saveAndFlush(PharmacyEntity(
            id = "europharma_1", name = "Europharma №101", chainId = "europharma", chainName = "Europharma",
            city = "Алматы", district = "", addr = "",
        ).also { it.group = PharmacyGroup.pilot })
        listOf("one" to "europharma_0", "two" to "europharma_0").forEachIndexed { i, (id, pharmacy) ->
            pharmacistRepository.saveAndFlush(PharmacistEntity(
                id = "u_$id", name = "Synthetic $id", iin = "99000000000$i", phone = "+7 (700) 000-00-0$i",
                pharmacyId = pharmacy, pharmacyName = "Europharma №100", city = "Алматы",
            ))
        }

        mockMvc.perform(get("/api/admin/pharmacies").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[?(@.id == 'europharma_0')].pharmacists").value(2))
            .andExpect(jsonPath("$[?(@.id == 'europharma_1')].pharmacists").value(0))
        mockMvc.perform(get("/api/admin/pharmacies/europharma_0").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.pharmacists").value(2))
    }

    @Test
    fun `roster receipt index exists valid and partial on resolved sellers`() {
        val definition = jdbc.queryForObject("""
            SELECT pg_get_indexdef(i.indexrelid) FROM pg_index i
            JOIN pg_class c ON c.oid = i.indexrelid
            WHERE c.relname = 'idx_pos_sales_pharmacist_printed' AND i.indisvalid
        """.trimIndent(), String::class.java)
        assertThat(definition).contains("(pharmacist_id, printed_at)").contains("WHERE ((pharmacist_id)::text <> ''::text)")
    }

    @Test
    fun `device counts keep offline registrations exclude revoked and separate same device name across pharmacies`() {
        pharmacyRepository.saveAndFlush(PharmacyEntity(
            id = "ph_other", name = "Other pharmacy", chainId = "biosfera", chainName = "Биосфера",
        ))
        val now = Instant.now()
        fun device(pharmacy: String, device: String, seen: Instant, active: Boolean = true, revoked: Boolean = false) {
            jdbc.update("""
                INSERT INTO fulfillment_devices
                  (id, device_id, pharmacy_id, secret_sha256, secret_hint, active, last_seen_at, revoked_at)
                VALUES (?, ?, ?, ?, 'synthetic', ?, ?, ?)
            """.trimIndent(), UUID.randomUUID(), device, pharmacy,
                UUID.randomUUID().toString().replace("-", "").repeat(2), active,
                Timestamp.from(seen), if (revoked) Timestamp.from(now) else null)
        }
        device("europharma_0", "KASSA1", now.minusSeconds(10))
        device("europharma_0", "KASSA2", now.minusSeconds(3600))
        device("europharma_0", "FUTURE", now.plusSeconds(3600))
        device("europharma_0", "REVOKED", now.minusSeconds(10), active = false, revoked = true)
        device("europharma_0", "REVOKED_FLAG", now.minusSeconds(10), revoked = true)
        device("ph_other", "KASSA1", now.minusSeconds(10))

        mockMvc.perform(get("/api/admin/pharmacies?chainId=europharma").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].registeredDeviceCount").value(3))
            .andExpect(jsonPath("$[0].onlineDeviceCount").value(1))
            .andExpect(jsonPath("$[0].deviceCountsObservedAt").isNotEmpty)
            // app.posm.heartbeat-ttl-seconds: 150 since tills beat every 60 s (INK-150).
            .andExpect(jsonPath("$[0].deviceOnlineWindowSeconds").value(150))
        mockMvc.perform(get("/api/admin/pharmacies/ph_other").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.registeredDeviceCount").value(1))
            .andExpect(jsonPath("$.onlineDeviceCount").value(1))

        jdbc.update("UPDATE pharmacies SET active = false WHERE id = 'ph_other'")
        mockMvc.perform(get("/api/admin/pharmacies/ph_other").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.registeredDeviceCount").value(1))
            .andExpect(jsonPath("$.onlineDeviceCount").value(0))
    }

    @Test
    fun `GET pharmacies filter by group=pilot`() {
        mockMvc.perform(get("/api/admin/pharmacies?group=pilot").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
    }

    @Test
    fun `metrics refresh preserves newer inventory and passport fields despite a stale entity snapshot`() {
        entityManager.flush()
        val stale = pharmacyRepository.findById("europharma_0").orElseThrow()
        assertThat(stale.inventoryDeviceCount).isNull()
        val inventoryAt = Instant.parse("2026-11-16T08:00:00Z")
        // Deterministic database-level interleaving: persistence context still holds
        // the old snapshot, while inventory/passport columns already have newer values.
        jdbc.update("""
            UPDATE pharmacies SET inventory_device_count = 5, inventory_source = 'Synthetic inventory',
                inventory_updated_at = ?, name = 'Updated passport', active = false
            WHERE id = 'europharma_0'
        """.trimIndent(), Timestamp.from(inventoryAt))

        assertThat(metricsScheduler.refreshNow()).isEqualTo(1)
        entityManager.flush()
        entityManager.clear()
        val actual = pharmacyRepository.findById("europharma_0").orElseThrow()
        assertThat(actual.inventoryDeviceCount).isEqualTo(5)
        assertThat(actual.inventorySource).isEqualTo("Synthetic inventory")
        assertThat(actual.inventoryUpdatedAt).isEqualTo(inventoryAt)
        assertThat(actual.name).isEqualTo("Updated passport")
        assertThat(actual.active).isFalse()
        assertThat(actual.receipts30d).isZero()
        assertThat(actual.gmv30d).isZero()
        assertThat(actual.rulesAccepted).isZero()
        assertThat(metricsScheduler.refreshNow()).isZero()

        assertThat(pharmacyRepository.updateMetricsIfChanged("europharma_0", 7, 12_345L, 4)).isEqualTo(1)
        assertThat(pharmacyRepository.updateMetricsIfChanged("europharma_0", 7, 12_345L, 4)).isZero()
        assertThat(pharmacyRepository.updateMetricsIfChanged("absent", 7, 12_345L, 4)).isZero()
        entityManager.clear()
        val changed = pharmacyRepository.findById("europharma_0").orElseThrow()
        assertThat(changed.receipts30d).isEqualTo(7)
        assertThat(changed.gmv30d).isEqualTo(12_345L)
        assertThat(changed.rulesAccepted).isEqualTo(4)
        assertThat(changed.inventoryDeviceCount).isEqualTo(5)
        assertThat(changed.inventoryUpdatedAt).isEqualTo(inventoryAt)
        assertThat(changed.name).isEqualTo("Updated passport")
    }

    @Test
    fun `inventory is unknown until explicitly recorded and zero does not mean unknown`() {
        mockMvc.perform(get("/api/admin/pharmacies/europharma_0").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.inventoryDeviceCount").isEmpty)
            .andExpect(jsonPath("$.inventorySource").isEmpty)
            .andExpect(jsonPath("$.inventoryUpdatedAt").isEmpty)
        val before = Instant.now()
        val response = mockMvc.perform(patch("/api/admin/pharmacies/europharma_0")
            .header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
            .content("""{"inventoryDeviceCount":0,"inventorySource":" Synthetic inventory "}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.inventoryDeviceCount").value(0))
            .andExpect(jsonPath("$.inventorySource").value("Synthetic inventory"))
            .andExpect(jsonPath("$.registeredDeviceCount").value(0))
            .andReturn().response.contentAsString
        val timestamp = Instant.parse(objectMapper.readTree(response)["inventoryUpdatedAt"].asText())
        assertThat(timestamp).isBetween(before, Instant.now())

        mockMvc.perform(patch("/api/admin/pharmacies/europharma_0")
            .header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
            .content("""{"inventoryDeviceCount":null,"inventorySource":null,"district":"Updated"}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.inventoryDeviceCount").value(0))
            .andExpect(jsonPath("$.inventorySource").value("Synthetic inventory"))
            .andExpect(jsonPath("$.inventoryUpdatedAt").value(timestamp.toString()))
    }

    @Test
    fun `inventory patch rejects negative partial blank overlong and control character observations`() {
        val invalid = listOf(
            mapOf("inventoryDeviceCount" to -1, "inventorySource" to "Synthetic"),
            mapOf("inventoryDeviceCount" to 1),
            mapOf("inventorySource" to "Synthetic"),
            mapOf("inventoryDeviceCount" to 1, "inventorySource" to "   "),
            mapOf("inventoryDeviceCount" to 1, "inventorySource" to "x".repeat(256)),
            mapOf("inventoryDeviceCount" to 1, "inventorySource" to "source\nforged"),
        )
        invalid.forEach { payload ->
            mockMvc.perform(patch("/api/admin/pharmacies/europharma_0")
                .header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest)
        }
    }

    @Test
    fun `GET pharmacy by id 404 на unknown`() {
        mockMvc.perform(get("/api/admin/pharmacies/unknown").header("Authorization", bearer))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
    }

    @Test
    fun `POST create pharmacy — успешный сценарий`() {
        val payload = """
            {"name":"Новая аптека","chainId":"europharma","city":"Астана","group":"pilot"}
        """.trimIndent()
        mockMvc.perform(
            post("/api/admin/pharmacies")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("Новая аптека"))
            .andExpect(jsonPath("$.chainName").value("Europharma"))
    }

    @Test
    fun `POST create under the umbrella chain takes the network from the name`() {
        chainRepository.save(
            ChainEntity(id = RealPharmacySeeder.UMBRELLA_CHAIN_ID, name = "Inkar", color = "#16C97A", points = 0)
                .also { it.group = PharmacyGroup.rolled },
        )
        fun create(name: String) = mockMvc.perform(
            post("/api/admin/pharmacies")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    mapOf("name" to name, "chainId" to "inkar", "city" to "Павлодар", "group" to "rolled"),
                )),
        ).andExpect(status().isOk)

        create("Jnk-фарм г.Павлодар Кутузова 1").andExpect(jsonPath("$.chainName").value("JNK-фарм"))
        create("Аптека г.Алматы Жетысу 2, 85").andExpect(jsonPath("$.chainName").value("Inkar"))
    }

    @Test
    fun `V108 cleanup fixes networks cities and switches off closed pharmacies without a till`() {
        chainRepository.save(
            ChainEntity(id = RealPharmacySeeder.UMBRELLA_CHAIN_ID, name = "Inkar", color = "#16C97A", points = 0)
                .also { it.group = PharmacyGroup.rolled },
        )
        fun pharmacy(id: String, name: String, chain: String, city: String) = pharmacyRepository.save(
            PharmacyEntity(id = id, name = name, chainId = "inkar", chainName = chain, city = city)
                .also { it.group = PharmacyGroup.rolled },
        )
        pharmacy("sloc_01KSAHYDSB2X042YH51YM2S37E", "JNK-фарм с.Жетиген, ул Валиханова 92Г", "JNK-фарм с.Жети", "ен")
        pharmacy("sloc_01KSAHYDSCAY9EA18EAX3XHF59", "ИРБИС Жер г.Талдыкорган Шевченко 128", "ИРБИС Жер", "Талдыкорган")
        pharmacy("sloc_01KSAHYDSDXHF1V3EXPV10VYDJ", "Ирбис Транс Систем Конаев мкр 3 д 8/3", "Inkar", "Конаев")
        pharmacy("ph_tengri", "ТОО \"Tengripharm\" г.Астана Абая 63", "ТОО \"Tengripharm", "Астана")
        pharmacy("ph_store", "Склад ТОО Курмет-Фарм", "Inkar", "—")
        pharmacy("ph_typo", "Курмет-Фарм г.Усть-Каменагорск ул.Ауезова 20", "Курмет-Фарм", "Усть-Каменагорск")
        pharmacy("sloc_01KSBH8M075A0WRTF1SXT6XPFY", "Ирбис Транс Систем г.Актау ТРЦ Байтерек закрыта", "Ирбис Транс Систем", "Актау")
        pharmacy("sloc_01KSBRMS3ACBVBTYKHGTTZ2R5F", "Ирбис Транс Систем г.Алматы Саина 16 Магнум закрыта", "Ирбис Транс Систем", "Алматы")
        entityManager.flush()
        jdbc.update(
            "INSERT INTO fulfillment_devices(id, device_id, pharmacy_id, secret_sha256, secret_hint) VALUES (?, 'KASSA1', ?, ?, 'hint')",
            UUID.randomUUID(), "sloc_01KSBRMS3ACBVBTYKHGTTZ2R5F", "a".repeat(64),
        )

        val script = javaClass.classLoader.getResource("db/migration/V108__pharmacy_directory_cleanup.sql")!!.readText()
        // The test transaction's own connection, so the script sees the rows saved above.
        org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(
            org.springframework.jdbc.datasource.DataSourceUtils.getConnection(jdbc.dataSource!!),
            org.springframework.core.io.ByteArrayResource(script.toByteArray()),
        )
        entityManager.clear()

        fun row(id: String) = jdbc.queryForMap("SELECT chain_name, city, active FROM pharmacies WHERE id = ?", id)
        assertThat(row("sloc_01KSAHYDSB2X042YH51YM2S37E")).containsEntry("chain_name", "JNK-фарм").containsEntry("city", "Жетиген")
        assertThat(row("sloc_01KSAHYDSCAY9EA18EAX3XHF59")).containsEntry("chain_name", "Ирбис-Жер")
        // City was already corrected by hand: the guard keeps it.
        assertThat(row("sloc_01KSAHYDSDXHF1V3EXPV10VYDJ")).containsEntry("chain_name", "Ирбис Транс Систем").containsEntry("city", "Конаев")
        assertThat(row("ph_tengri")).containsEntry("chain_name", "ТОО \"Tengripharm\"")
        assertThat(row("ph_store")).containsEntry("chain_name", "Inkar").containsEntry("city", "—")
        assertThat(row("ph_typo")).containsEntry("city", "Усть-Каменогорск")
        assertThat(row("sloc_01KSBH8M075A0WRTF1SXT6XPFY")).containsEntry("active", false)
        // Closed in the name but a till key is registered: left on for a check with the network.
        assertThat(row("sloc_01KSBRMS3ACBVBTYKHGTTZ2R5F")).containsEntry("active", true)
    }

    @Test
    fun `POST create с unknown chain → 400`() {
        val payload = """
            {"name":"X","chainId":"nope","city":"X","group":"pilot"}
        """.trimIndent()
        mockMvc.perform(
            post("/api/admin/pharmacies")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
    }

    @Test
    fun `PATCH pharmacy обновляет метрики`() {
        mockMvc.perform(
            patch("/api/admin/pharmacies/europharma_0")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"receipts30d":1000}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.receipts30d").value(1000))
    }

    @Test
    fun `GET без Bearer → 401`() {
        mockMvc.perform(get("/api/admin/pharmacies"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `GET с недопустимым enum-параметром → 400 VALIDATION_FAILED`() {
        // group=bogus не входит в PharmacyGroup → MethodArgumentTypeMismatchException
        // → GlobalExceptionHandler.handleTypeMismatch (раньше Spring-дефолт без кода).
        mockMvc.perform(get("/api/admin/pharmacies?group=bogus").header("Authorization", bearer))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
    }

    // ── Chains CRUD (Блок 2) ─────────────────────────────────────────────

    @Test
    fun `POST chain creates — 201`() {
        val payload = """{"id":"newchain","name":"Новая сеть","color":"#16C97A","points":10,"group":"pilot"}"""
        mockMvc.perform(
            post("/api/admin/pharmacies/chains")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").value("newchain"))
            .andExpect(jsonPath("$.color").value("#16C97A"))
    }

    @Test
    fun `POST chain duplicate id → 409`() {
        val payload = """{"id":"europharma","name":"X","color":"#000000","group":"pilot"}"""
        mockMvc.perform(
            post("/api/admin/pharmacies/chains")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("CONFLICT"))
    }

    @Test
    fun `POST chain bad color → 400`() {
        val payload = """{"id":"badcol","name":"X","color":"red","group":"pilot"}"""
        mockMvc.perform(
            post("/api/admin/pharmacies/chains")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
    }

    @Test
    fun `PATCH chain updates points`() {
        mockMvc.perform(
            patch("/api/admin/pharmacies/chains/biosfera")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"points":999}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.points").value(999))
            .andExpect(jsonPath("$.name").value("Биосфера"))
    }

    @Test
    fun `DELETE chain with pharmacies → 409`() {
        // europharma имеет аптеку europharma_0
        mockMvc.perform(delete("/api/admin/pharmacies/chains/europharma").header("Authorization", bearer))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("CONFLICT"))
    }

    @Test
    fun `DELETE empty chain → 204`() {
        // biosfera без аптек — удаляется
        mockMvc.perform(delete("/api/admin/pharmacies/chains/biosfera").header("Authorization", bearer))
            .andExpect(status().isNoContent)
        mockMvc.perform(get("/api/admin/pharmacies/chains").header("Authorization", bearer))
            .andExpect(jsonPath("$.length()").value(1))
    }

    // ── Pharmacy DELETE (Блок 2) ──────────────────────────────────────────

    @Test
    fun `DELETE pharmacy without pharmacists → 204`() {
        mockMvc.perform(delete("/api/admin/pharmacies/europharma_0").header("Authorization", bearer))
            .andExpect(status().isNoContent)
        mockMvc.perform(get("/api/admin/pharmacies/europharma_0").header("Authorization", bearer))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `DELETE pharmacy with pharmacists → 409`() {
        pharmacistRepository.save(
            PharmacistEntity(
                id = "phc_test01", name = "Тест Фармацевт", iin = "900115300013",
                phone = "+77001234567", pharmacyId = "europharma_0",
                pharmacyName = "Europharma №100", city = "Алматы",
            ),
        )
        mockMvc.perform(delete("/api/admin/pharmacies/europharma_0").header("Authorization", bearer))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("CONFLICT"))
        // аптека на месте
        mockMvc.perform(get("/api/admin/pharmacies/europharma_0").header("Authorization", bearer))
            .andExpect(status().isOk)
    }

    @Test
    fun `DELETE pharmacy unknown → 404`() {
        mockMvc.perform(delete("/api/admin/pharmacies/unknown").header("Authorization", bearer))
            .andExpect(status().isNotFound)
    }

    private fun login(): LoginResponse {
        val req = LoginRequest(email = "damir@jadran.com", password = "damir2026")
        val result = mockMvc.perform(
            post("/api/admin/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)),
        ).andExpect(status().isOk).andReturn()
        return objectMapper.readValue(result.response.contentAsString, LoginResponse::class.java)
    }
}
