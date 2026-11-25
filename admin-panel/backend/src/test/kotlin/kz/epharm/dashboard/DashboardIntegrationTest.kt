package kz.epharm.dashboard

import com.fasterxml.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Instant
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.domain.AdminUserStatus
import kz.epharm.auth.dto.LoginRequest
import kz.epharm.auth.dto.LoginResponse
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.catalog.entity.ProductEntity
import kz.epharm.catalog.repository.ProductRepository
import kz.epharm.finance.entity.PayoutBatchEntity
import kz.epharm.finance.entity.PayoutBatchStatus
import kz.epharm.finance.repository.PayoutBatchRepository
import kz.epharm.pharmacies.entity.ChainEntity
import kz.epharm.pharmacies.entity.PharmacyEntity
import kz.epharm.pharmacies.entity.PharmacyGroup
import kz.epharm.pharmacies.repository.ChainRepository
import kz.epharm.pharmacies.repository.PharmacyRepository
import kz.epharm.posm.entity.RecommendationEventEntity
import kz.epharm.posm.entity.RecommendationOutcome
import kz.epharm.posm.entity.PosSaleEntity
import kz.epharm.posm.entity.PosSaleItem
import kz.epharm.posm.repository.PosSaleRepository
import kz.epharm.posm.repository.RecommendationEventRepository
import kz.epharm.rules.entity.RuleEntity
import kz.epharm.rules.entity.RuleStatus
import kz.epharm.rules.entity.RuleTrigger
import kz.epharm.rules.entity.RuleType
import kz.epharm.rules.repository.RuleRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Dashboard на РЕАЛЬНЫХ данных: KPI/топы агрегируются из recommendation_events
 * (а не из денормализованных полей правил/аптек), выплаты — из payout_batches,
 * avgLiftPct — из конверсии pilot/control.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Transactional
class DashboardIntegrationTest {

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
    @Autowired private lateinit var ruleRepository: RuleRepository
    @Autowired private lateinit var productRepository: ProductRepository
    @Autowired private lateinit var pharmacyRepository: PharmacyRepository
    @Autowired private lateinit var chainRepository: ChainRepository
    @Autowired private lateinit var batchRepository: PayoutBatchRepository
    @Autowired private lateinit var eventRepository: RecommendationEventRepository
    @Autowired private lateinit var posSaleRepository: PosSaleRepository
    @Autowired private lateinit var adminUserRepository: AdminUserRepository
    @Autowired private lateinit var passwordEncoder: PasswordEncoder
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    private lateinit var bearer: String

    @BeforeEach
    fun seed() {
        eventRepository.deleteAll()
        posSaleRepository.deleteAll()
        ruleRepository.deleteAll()
        productRepository.deleteAll()
        pharmacyRepository.deleteAll()
        chainRepository.deleteAll()
        batchRepository.deleteAll()
        adminUserRepository.deleteAll()

        productRepository.save(ProductEntity(id = "p_aquamaris", name = "Аквамарис", brand = "Jadran", vendor = "Jadran", mnn = "Sea water", price = 1800))
        productRepository.save(ProductEntity(id = "p_other", name = "Линекс", brand = "Sandoz", vendor = "Sandoz", mnn = "Probiotic", price = 2400))

        // 2 живых правила + 1 архивное (исключается из агрегаций KPI).
        ruleRepository.save(
            RuleEntity(id = "r_active", recommend = "p_aquamaris", trigger = RuleTrigger(kind = "product", value = "p_x"))
                .also { it.type = RuleType.substitution; it.status = RuleStatus.active },
        )
        ruleRepository.save(
            RuleEntity(id = "r_paused", recommend = "p_other", trigger = RuleTrigger(kind = "product", value = "p_y"))
                .also { it.type = RuleType.crosssell; it.status = RuleStatus.paused },
        )
        ruleRepository.save(
            RuleEntity(id = "r_archived", recommend = "p_aquamaris", trigger = RuleTrigger(kind = "product", value = "p_z"))
                .also { it.type = RuleType.substitution; it.status = RuleStatus.archived },
        )

        chainRepository.save(ChainEntity(id = "ch1", name = "Europharma", color = "#2A2BE2", points = 100).also { it.group = PharmacyGroup.pilot })
        pharmacyRepository.save(PharmacyEntity(id = "ph_hi", name = "Аптека Лидер", chainId = "ch1", chainName = "Europharma", city = "Алматы").also { it.group = PharmacyGroup.pilot })
        pharmacyRepository.save(PharmacyEntity(id = "ph_lo", name = "Аптека Тихая", chainId = "ch1", chainName = "Europharma", city = "Астана").also { it.group = PharmacyGroup.control })

        // Реальные события:
        //  r_active (p_aquamaris) в ph_hi (pilot): 50 показов, 20 принятых, 1000₸ → conv 40%, revenue 20000
        //  r_paused (p_other)     в ph_lo (control): 50 показов, 10 принятых, 500₸ → conv 20%, revenue 5000
        events("r_active", "ph_hi", "p_aquamaris", shown = 50, accepted = 20, amount = 1000)
        events("r_paused", "ph_lo", "p_other", shown = 50, accepted = 10, amount = 500)

        batchRepository.save(PayoutBatchEntity(id = "pb_appr", period = "апрель", pharmacists = 10, amount = 800_000, items = 10).also { it.status = PayoutBatchStatus.approved })
        batchRepository.save(PayoutBatchEntity(id = "pb_pend", period = "май", pharmacists = 12, amount = 300_000, items = 12).also { it.status = PayoutBatchStatus.pending })

        adminUserRepository.save(
            AdminUserEntity(email = "damir@jadran.com", passwordHash = passwordEncoder.encode("damir2026"),
                name = "Дамир", company = "Jadran").also { it.role = AdminRole.BRAND_MANAGER; it.status = AdminUserStatus.ACTIVE },
        )
        bearer = "Bearer " + login().tokens.accessToken
    }

    private fun events(ruleId: String, pharmacyId: String, sku: String, shown: Int, accepted: Int, amount: Long) {
        repeat(shown) { i ->
            val e = RecommendationEventEntity(
                id = "rec_${ruleId}_$i",
                sessionId = "s_${ruleId}_$i",
                pharmacistId = "ph_$pharmacyId",
                pharmacyId = pharmacyId,
                ruleId = ruleId,
                recommendSku = sku,
                recommendName = if (sku == "p_aquamaris") "Аквамарис" else "Линекс",
                expectedAmount = amount,
                bonus = 100,
            ).also { if (i < accepted) it.outcome = RecommendationOutcome.accepted }
            eventRepository.save(e)
        }
    }

    @Test
    fun `summary KPI агрегируется только по живым правилам из событий`() {
        mockMvc.perform(get("/api/admin/dashboard/summary").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.impressions").value(100)) // 50 + 50
            .andExpect(jsonPath("$.accepts").value(30)) // 20 + 10
            .andExpect(jsonPath("$.convRate").value(30.0))
            .andExpect(jsonPath("$.activeRules").value(1))
            .andExpect(jsonPath("$.totalRules").value(2))
    }

    @Test
    fun `summary суммы выплат — approved vs pending`() {
        mockMvc.perform(get("/api/admin/dashboard/summary").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.paidOut").value(800_000))
            .andExpect(jsonPath("$.pendingPayout").value(300_000))
    }

    @Test
    fun `summary pilot-аптеки и реальный lift из событий`() {
        mockMvc.perform(get("/api/admin/dashboard/summary").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.pilotPharmacies").value(1))
            // pilot ph_hi conv 0.4 vs control ph_lo conv 0.2 → lift 100%
            .andExpect(jsonPath("$.avgLiftPct").value(100.0))
    }

    @Test
    fun `summary топ-правила по реальной convRate с именем продукта`() {
        mockMvc.perform(get("/api/admin/dashboard/summary").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.topRules.length()").value(2))
            .andExpect(jsonPath("$.topRules[0].id").value("r_active"))
            .andExpect(jsonPath("$.topRules[0].label").value("Аквамарис"))
            .andExpect(jsonPath("$.topRules[0].convRate").value(40.0))
            .andExpect(jsonPath("$.topRules[0].accepts").value(20))
    }

    @Test
    fun `summary топ-аптеки по реальным принятым рекомендациям`() {
        mockMvc.perform(get("/api/admin/dashboard/summary").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.topPharmacies[0].id").value("ph_hi"))
            .andExpect(jsonPath("$.topPharmacies[0].rulesAccepted").value(20))
    }

    @Test
    fun `summary топ-товары по реальной выручке от принятых рекомендаций`() {
        mockMvc.perform(get("/api/admin/dashboard/summary").header("Authorization", bearer))
            .andExpect(status().isOk)
            // p_aquamaris: 20 принятых × 1000 = 20000 (больше, чем p_other 10 × 500 = 5000)
            .andExpect(jsonPath("$.topProducts[0].id").value("p_aquamaris"))
            .andExpect(jsonPath("$.topProducts[0].name").value("Аквамарис"))
            .andExpect(jsonPath("$.topProducts[0].revenue").value(20_000))
    }

    /**
     * Regression for «показано 467, принято 0»: since POSM 1.0.13 the till popup has no accept key and never
     * calls /outcome, so every event stays `shown`. The only evidence of a followed tip is the sale attribution
     * (`sold_at`), and that is what «Принято» must count. A legacy click is still counted, once per row.
     */
    @Test
    fun `accepted counts the product sold in the receipt although the till never reports an outcome`() {
        eventRepository.deleteAll()
        val shownAt = Instant.now().minusSeconds(600)
        fun event(id: String, outcome: RecommendationOutcome = RecommendationOutcome.shown, sold: Boolean = false) =
            RecommendationEventEntity(
                id = id,
                sessionId = "s_$id",
                pharmacistId = "ph_ph_hi",
                pharmacyId = "ph_hi",
                ruleId = "r_active",
                recommendSku = "p_aquamaris",
                recommendName = "Аквамарис",
                expectedAmount = 1_000,
                bonus = 100,
                shownAt = shownAt,
                soldAt = if (sold) shownAt.plusSeconds(40) else null,
                saleId = if (sold) "sale_$id" else null,
                secondsToSale = if (sold) 40 else null,
            ).also { it.outcome = outcome }
        eventRepository.saveAll(
            (1..4).map { event("plain_$it") } + // shown, nothing came of it: not accepted
                (1..3).map { event("sold_$it", sold = true) } + // product in the receipt, outcome still `shown`
                (1..2).map { event("legacy_$it", RecommendationOutcome.accepted) } + // historical click
                event("both", RecommendationOutcome.accepted, sold = true), // click and sale: one row, counted once
        )
        eventRepository.flush()

        mockMvc.perform(get("/api/admin/dashboard/summary").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.impressions").value(10))
            .andExpect(jsonPath("$.accepts").value(6)) // 3 sold + 2 legacy + 1 both
            .andExpect(jsonPath("$.convRate").value(60.0))
            .andExpect(jsonPath("$.topRules[0].id").value("r_active"))
            .andExpect(jsonPath("$.topRules[0].accepts").value(6))
            .andExpect(jsonPath("$.topPharmacies[0].id").value("ph_hi"))
            .andExpect(jsonPath("$.topPharmacies[0].rulesAccepted").value(6))
            .andExpect(jsonPath("$.topProducts[0].id").value("p_aquamaris"))
            .andExpect(jsonPath("$.topProducts[0].revenue").value(6_000))

        // Lift and the pharmacy registry read the per-pharmacy aggregate; the product table reads the per-SKU one.
        val byPharmacy = eventRepository.aggregateByPharmacySince(Instant.EPOCH).single { it.pharmacyId == "ph_hi" }
        assertEquals(10L, byPharmacy.impressions)
        assertEquals(6L, byPharmacy.accepts)
        val bySku = eventRepository.aggregateBySkuSince(Instant.EPOCH).single { it.sku == "p_aquamaris" }
        assertEquals(6L, bySku.accepts)
        assertEquals(6_000L, bySku.revenue)
        // The attribution is analytics only: the stored outcome of a sold row is untouched.
        assertEquals(RecommendationOutcome.shown, eventRepository.findById("sold_1").get().outcome)
    }

    @Test
    fun `GET summary без Bearer → 401`() {
        mockMvc.perform(get("/api/admin/dashboard/summary"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `recommendations пагинирует общий журнал после разворачивания позиций чека`() {
        posSaleRepository.save(
            PosSaleEntity(
                id = "sale_page",
                pharmacistId = "u_cashier",
                pharmacistName = "Кассир",
                pharmacyId = "ph_hi",
                totalAmount = 3_500,
                items = listOf(
                    PosSaleItem(sku = "sku_a", name = "Товар А", qty = 1.0, total = 1_500),
                    PosSaleItem(sku = "sku_b", name = "Товар Б", qty = 2.0, total = 2_000),
                ),
                // Новее seeded-показов: обе позиции должны занять первую страницу.
                printedAt = Instant.now().plusSeconds(60),
            ),
        )

        mockMvc.perform(
            get("/api/admin/dashboard/recommendations")
                .header("Authorization", bearer)
                .param("page", "0")
                .param("size", "2"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.pageSize").value(2))
            .andExpect(jsonPath("$.totalElements").value(102))
            .andExpect(jsonPath("$.totalPages").value(51))
            .andExpect(jsonPath("$.log.length()").value(2))
            .andExpect(jsonPath("$.log[0].saleId").value("sale_page"))
            .andExpect(jsonPath("$.log[1].saleId").value("sale_page"))

        mockMvc.perform(
            get("/api/admin/dashboard/recommendations")
                .header("Authorization", bearer)
                .param("page", "1")
                .param("size", "2"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.page").value(1))
            .andExpect(jsonPath("$.log.length()").value(2))
            .andExpect(jsonPath("$.log[0].type").value("show"))
    }

    @Test
    fun `journal candidate bounds preserve every page across timestamp ties and receipt id prefixes`() {
        eventRepository.deleteAll()
        val at = Instant.now().plusSeconds(60)
        val since = Instant.now().minusSeconds(90L * 24 * 60 * 60)
        fun receipt(id: String, count: Int, time: Instant = at) = PosSaleEntity(
            id = id,
            pharmacyId = "ph_hi",
            pharmacistId = "cashier",
            printedAt = time,
            totalAmount = 4_900,
            items = (0 until count).map { i ->
                PosSaleItem(sku = "sku_$i", name = "Товар $i", qty = i + 0.5, total = 100L + i)
            },
        )
        posSaleRepository.saveAll(listOf(
            receipt("newest", 2, at.plusSeconds(5)),
            receipt("sale", 12),
            receipt("sale!", 2),
            receipt("sale#", 0),
            receipt("sale#9", 2),
            receipt("sale0", 3),
            receipt("older_empty", 0, at.minusSeconds(20)),
            receipt("outside_window", 100, since.minusSeconds(60)),
        ))
        eventRepository.saveAll(listOf(
            RecommendationEventEntity(id = "show_tie", shownAt = at.minusSeconds(1), displayedAt = at),
            // Sorting must use displayed_at, while the period filter still uses shown_at.
            RecommendationEventEntity(id = "show_moved", shownAt = at.plusSeconds(10), displayedAt = at.minusSeconds(25)),
            RecommendationEventEntity(id = "show_outside", shownAt = since.minusSeconds(60), displayedAt = at.plusSeconds(20)),
        ))
        posSaleRepository.flush()
        eventRepository.flush()

        // Unbounded independent reference: expand all matching receipts, then sort computed child ids.
        val expectedIds = jdbcTemplate.queryForList(
            """
                SELECT journal_id FROM (
                    SELECT id AS journal_id, COALESCE(displayed_at, shown_at) AS event_at
                    FROM recommendation_events WHERE shown_at >= ?
                    UNION ALL
                    SELECT CASE WHEN jsonb_array_length(s.items) = 0 THEN s.id
                                ELSE s.id || '#' || (item.ordinality - 1)::text END,
                           s.printed_at
                    FROM pos_sales s
                    CROSS JOIN LATERAL jsonb_array_elements(
                        CASE WHEN jsonb_array_length(s.items) = 0 THEN '[{}]'::jsonb ELSE s.items END
                    ) WITH ORDINALITY item(value, ordinality)
                    WHERE s.printed_at >= ?
                ) journal ORDER BY event_at DESC, journal_id DESC
            """.trimIndent(),
            String::class.java,
            Timestamp.from(since), Timestamp.from(since),
        )
        assertEquals(25, expectedIds.size)
        assertEquals(25L, eventRepository.countJournalRowsSince(since))
        for (size in listOf(1, 2, 3, 7)) {
            val actualIds = mutableListOf<String>()
            val pages = (expectedIds.size + size - 1) / size
            repeat(pages) { page ->
                val result = mockMvc.perform(
                    get("/api/admin/dashboard/recommendations")
                        .header("Authorization", bearer).param("page", "$page").param("size", "$size"),
                ).andExpect(status().isOk).andReturn()
                val json = objectMapper.readTree(result.response.contentAsString)
                assertEquals(page, json["page"].asInt())
                assertEquals(pages, json["totalPages"].asInt())
                assertEquals(25L, json["totalElements"].asLong())
                actualIds += json["log"].map { it["id"].asText() }
            }
            assertEquals(expectedIds, actualIds, "page size $size must preserve the original full journal order")
        }

        val allRows = eventRepository.findJournalRowsSince(since, 100, 0)
        val item = allRows.single { it.id == "sale#11" }
        assertEquals(111L, item.amount)
        assertEquals(11.5, item.units)
        assertEquals("sale", item.saleId)
        val empty = allRows.single { it.id == "older_empty" }
        assertEquals("Чек", empty.title)
        assertEquals(4_900L, empty.amount)
        assertNull(empty.units)
        assertTrue(allRows.none { it.id.startsWith("outside_window") || it.id == "show_outside" })
    }

    @Test
    fun `bounded journal keeps each product attribution and does not guess an unrelated first item`() {
        eventRepository.deleteAll()
        val since = Instant.now().minusSeconds(90L * 24 * 60 * 60)
        val at = Instant.now().plusSeconds(60)
        productRepository.findById("p_aquamaris").get().also { it.barcode = "4601234567890" }
        productRepository.findById("p_other").get().also { it.barcode = "4609876543210" }
        posSaleRepository.saveAll(listOf(
            PosSaleEntity(
                id = "sale_barcode", pharmacyId = "ph_hi", printedAt = at,
                items = listOf(
                    PosSaleItem(name = "Аквамарис", barcode = "4609876543210", qty = 1.0, total = 2_000),
                    PosSaleItem(name = "Другое имя", barcode = "4601234567890", qty = 2.0, total = 3_500),
                ),
            ),
            PosSaleEntity(
                id = "sale_name", pharmacyId = "ph_hi", printedAt = at,
                items = listOf(PosSaleItem(name = "Другой"), PosSaleItem(name = "нужный ТОВАР")),
            ),
            PosSaleEntity(
                id = "sale_fallback", pharmacyId = "ph_hi", printedAt = at,
                items = listOf(PosSaleItem(name = "Первый"), PosSaleItem(name = "Второй")),
            ),
            PosSaleEntity(id = "sale_empty", pharmacyId = "ph_hi", printedAt = at, totalAmount = 700),
        ))
        fun attribution(id: String, saleId: String, sku: String, name: String, seconds: Int) =
            RecommendationEventEntity(
                id = id, saleId = saleId, recommendSku = sku, recommendName = name,
                pharmacyId = "ph_hi",
                shownAt = at.minusSeconds(100), soldAt = at, secondsToSale = seconds,
            )
        eventRepository.saveAll(listOf(
            attribution("attr_slow", "sale_barcode", "p_other", "Аквамарис", 50),
            attribution("attr_z", "sale_barcode", "p_other", "Аквамарис", 8),
            // Two sold products retain independent attribution even at equal times.
            attribution("attr_a", "sale_barcode", "p_aquamaris", "Аквамарис", 8),
            attribution("attr_name", "sale_name", "missing", "  Нужный товар  ", 14),
            attribution("attr_fallback", "sale_fallback", "missing", "Отсутствует", 21),
            attribution("attr_empty", "sale_empty", "missing", "Отсутствует", 25),
        ))
        productRepository.flush()
        posSaleRepository.flush()
        eventRepository.flush()

        val saleRows = eventRepository.findJournalRowsSince(since, 7, 0)
        assertEquals(7, saleRows.size)
        assertTrue(saleRows.all { it.eventType == "sale" })
        val seconds = saleRows.associate { it.id to it.secondsToSale }
        assertEquals(8, seconds["sale_barcode#1"])
        assertEquals(8, seconds["sale_barcode#0"])
        assertEquals(14, seconds["sale_name#1"])
        assertNull(seconds["sale_name#0"])
        assertNull(seconds["sale_fallback#0"])
        assertNull(seconds["sale_fallback#1"])
        assertEquals(25, seconds["sale_empty"])
        assertEquals(13L, eventRepository.countJournalRowsSince(since))
    }

    @Test
    fun `journal attaches sale time to canonical product or a legacy alias despite abbreviated cash register names`() {
        eventRepository.deleteAll()
        val at = Instant.now().plusSeconds(60)
        productRepository.findById("p_aquamaris").get().also {
            it.barcode = "4601234567890"
            it.barcodes = listOf("4601234567890", "4603276013765")
        }
        fun sale(id: String, hasProductId: Boolean) = PosSaleEntity(
            id = id, pharmacyId = "ph_hi", printedAt = at,
            items = listOf(
                PosSaleItem(name = "Аквамарис", productId = "p_other", total = 100),
                PosSaleItem(name = "Аква капс N30", barcode = "4603276013765", productId = if (hasProductId) "p_aquamaris" else null, total = 3_500),
            ),
        )
        posSaleRepository.saveAll(listOf(sale("alias_legacy", false), sale("alias_resolved", true)))
        eventRepository.saveAll(listOf("alias_legacy", "alias_resolved").map { id ->
            RecommendationEventEntity(
                id = "attr_$id", saleId = id, recommendSku = "p_aquamaris", recommendName = "Аквамарис",
                pharmacyId = "ph_hi",
                shownAt = at.minusSeconds(20), soldAt = at, secondsToSale = 20,
            )
        })
        productRepository.flush()
        posSaleRepository.flush()
        eventRepository.flush()

        val rows = eventRepository.findJournalRowsSince(at.minusSeconds(100), 20, 0).filter { it.eventType == "sale" }
        for (id in listOf("alias_legacy", "alias_resolved")) {
            assertNull(rows.single { it.id == "$id#0" }.secondsToSale)
            assertEquals(20, rows.single { it.id == "$id#1" }.secondsToSale)
            assertEquals(3_500L, rows.single { it.id == "$id#1" }.amount)
        }
    }

    @Test
    fun `journal attributes two canonical products independently without duplicates or foreign seller links`() {
        eventRepository.deleteAll()
        val at = Instant.now().plusSeconds(60)
        posSaleRepository.saveAndFlush(PosSaleEntity(
            id = "two_products", pharmacyId = "ph_hi", pharmacistId = "seller", printedAt = at,
            totalAmount = 600,
            items = listOf(
                PosSaleItem(name = "First", productId = "p_aquamaris", total = 100),
                PosSaleItem(name = "Second", productId = "p_other", total = 200),
                PosSaleItem(name = "Repeated first", productId = "p_aquamaris", total = 300),
            ),
        ))
        fun event(id: String, sku: String, seconds: Int, pharmacy: String = "ph_hi", seller: String = "seller") =
            RecommendationEventEntity(
                id = id, saleId = "two_products", pharmacyId = pharmacy, pharmacistId = seller,
                recommendSku = sku, recommendName = "name is not authoritative",
                shownAt = at.minusSeconds(100), soldAt = at, secondsToSale = seconds,
            )
        eventRepository.saveAllAndFlush(listOf(
            event("first", "p_aquamaris", 20),
            event("second", "p_other", 40),
            event("duplicate_first", "p_aquamaris", 30),
            event("foreign_pharmacy", "p_aquamaris", 1, pharmacy = "ph_lo"),
            event("foreign_seller", "p_other", 2, seller = "other_seller"),
            event("invalid_time", "p_other", -5),
        ))

        val rows = eventRepository.findJournalRowsSince(at.minusSeconds(200), 100, 0)
            .filter { it.saleId == "two_products" }
        assertEquals(3, rows.size)
        assertEquals(600L, rows.sumOf { it.amount })
        assertEquals(20, rows.single { it.id == "two_products#0" }.secondsToSale)
        assertEquals(40, rows.single { it.id == "two_products#1" }.secondsToSale)
        assertNull(rows.single { it.id == "two_products#2" }.secondsToSale)
        assertTrue(rows.all { it.receiptStatus == null })
        assertEquals(9L, eventRepository.countJournalRowsSince(at.minusSeconds(200)))
    }

    @Test
    fun `journal exposes only explicit refund status and never attributes a stale refund link`() {
        eventRepository.deleteAll()
        val at = Instant.now().plusSeconds(60)
        val statuses = listOf("refund", "paid", "legacy")
        posSaleRepository.saveAllAndFlush(statuses.map { receipt ->
            PosSaleEntity(
                id = receipt, pharmacyId = "ph_hi", printedAt = at,
                totalAmount = 500,
                items = listOf(PosSaleItem(name = "Аквамарис", productId = "p_aquamaris", total = 500)),
            )
        })
        for (receipt in listOf("refund", "paid")) {
            jdbcTemplate.update(
                "UPDATE pos_sales SET reward_evidence = CAST(? AS jsonb) WHERE id = ?",
                """{"completion":{"status":"$receipt"}}""", receipt,
            )
        }
        eventRepository.saveAllAndFlush(statuses.map { receipt ->
            RecommendationEventEntity(
                id = "event_$receipt", saleId = receipt, pharmacyId = "ph_hi", recommendSku = "p_aquamaris",
                recommendName = "Аквамарис", shownAt = at.minusSeconds(20), soldAt = at, secondsToSale = 20,
            )
        })

        val rows = eventRepository.findJournalRowsSince(at.minusSeconds(200), 100, 0)
        val returned = rows.single { it.saleId == "refund" }
        assertEquals("refund", returned.receiptStatus)
        assertEquals(500L, returned.amount)
        assertNull(returned.secondsToSale)
        for (receipt in listOf("paid", "legacy")) {
            val sold = rows.single { it.saleId == receipt }
            assertNull(sold.receiptStatus)
            assertEquals(20, sold.secondsToSale)
        }
        assertTrue(rows.filter { it.eventType == "show" }.all { it.receiptStatus == null })

        val response = mockMvc.perform(
            get("/api/admin/dashboard/recommendations").header("Authorization", bearer),
        ).andExpect(status().isOk).andReturn()
        val log = objectMapper.readTree(response.response.contentAsString)["log"]
        val refund = log.single { it["saleId"].asText() == "refund" }
        assertEquals("refund", refund["receiptStatus"].asText())
        assertTrue(refund["secondsToSale"].isNull)
    }

    @Test
    fun `recommendations retains legacy limit alias and clamps past the last page`() {
        mockMvc.perform(
            get("/api/admin/dashboard/recommendations").header("Authorization", bearer).param("limit", "3"),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.pageSize").value(3))
            .andExpect(jsonPath("$.totalElements").value(100))
            .andExpect(jsonPath("$.totalPages").value(34))
            .andExpect(jsonPath("$.log.length()").value(3))

        mockMvc.perform(
            get("/api/admin/dashboard/recommendations").header("Authorization", bearer)
                .param("page", "1000").param("size", "3"),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.page").value(33))
            .andExpect(jsonPath("$.totalElements").value(100))
            .andExpect(jsonPath("$.log.length()").value(1))
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
