package kz.epharm.posm

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kz.epharm.auth.access.StoredGrant
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.auth.service.JwtService
import kz.epharm.catalog.entity.ProductEntity
import kz.epharm.catalog.repository.ProductRepository
import kz.epharm.pharmacies.entity.ChainEntity
import kz.epharm.pharmacies.entity.PharmacyEntity
import kz.epharm.pharmacies.repository.ChainRepository
import kz.epharm.pharmacies.repository.PharmacyRepository
import kz.epharm.pharmacists.entity.PharmacistEntity
import kz.epharm.pharmacists.entity.PharmacistStatus
import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.posm.dto.*
import kz.epharm.posm.service.RewardReviewDecision
import kz.epharm.promo.entity.PromoEntity
import kz.epharm.promo.entity.PromoStatus
import kz.epharm.promo.entity.PromoTier
import kz.epharm.promo.repository.PromoRepository
import kz.epharm.rules.entity.RuleEntity
import kz.epharm.rules.entity.RuleStatus
import kz.epharm.rules.entity.RuleTrigger
import kz.epharm.rules.repository.RuleRepository
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/** Actual device-authenticated HTTP flow. Only catalogue, funding and identities are fixtures;
 * reward cases, pending bonuses, receipts and wallet entries are produced by application endpoints. */
@SpringBootTest(properties = [
    "app.posm.sale-bonus.enabled=true", "app.posm.sale-bonus.auto-credit-enabled=true",
    "app.posm.sale-bonus.eligible-after=2026-01-01T00:00:00Z", "app.posm.legacy-device-key-enabled=false",
])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class RecommendationRewardFlowIntegrationTest {
    companion object {
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("reward_http_flow_test").withUsername("epharm").withPassword("test-only").apply { start() }
        private val sequence = AtomicLong(860_000_000_000)
        private val documents = AtomicLong(20_000)
        @JvmStatic @DynamicPropertySource fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
        }
    }
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var admins: AdminUserRepository
    @Autowired lateinit var jwt: JwtService
    @Autowired lateinit var chains: ChainRepository
    @Autowired lateinit var pharmacies: PharmacyRepository
    @Autowired lateinit var people: PharmacistRepository
    @Autowired lateinit var products: ProductRepository
    @Autowired lateinit var promos: PromoRepository
    @Autowired lateinit var rules: RuleRepository
    private var bearer: String? = null

    @Test fun `actual recommend shown and paid two package sale credit the shared wallet without fake Excel`() {
        val f = flow()
        acknowledge(f)
        deliver(f, payment(f))
        assertCredit(f, 400)
        assertThat(balance(f.employee)).isEqualTo(400)
        assertThat(number("SELECT earned_30d FROM pharmacists WHERE id=?", f.employee.person)).isEqualTo(400)
        assertThat(spent(f.world.offers.single())).isEqualTo(400)
        val receipt = rows("""SELECT r.* FROM receipts r JOIN recommendation_sale_rewards w ON w.receipt_id=r.id
            WHERE w.source_sale_id=?""", f.sale).single()
        assertThat(receipt["status"]).isEqualTo("approved")
        assertThat(receipt["auto_approved"]).isEqualTo(true)
        assertThat(receipt["confirmed_by_log"]).isEqualTo(true)
        assertThat(receipt["confirmed_by_excel"]).isEqualTo(false)
        assertThat((receipt["parsed_amount"] as Number).toLong()).isEqualTo(2000)
        assertThat(number("SELECT bonus FROM pending_bonuses WHERE id=(SELECT pending_bonus_id FROM recommendation_sale_rewards WHERE source_sale_id=?)", f.sale)).isEqualTo(200)
        assertThat(number("SELECT count(*) FROM posm_pharmacist_mapping_audit WHERE pharmacist_id=?", f.employee.person)).isEqualTo(1)
        // These are the existing web /learn readers, not a separate mobile application test.
        val owner = people.findById(f.employee.person).orElseThrow()
        val learner = "Bearer ${jwt.issuePharmacistToken(owner.id, owner.name, owner.phone)}"
        val profile = mvc.perform(get("/api/mobile/auth/me").header("Authorization", learner))
            .andExpect(status().isOk).andReturn().response.contentAsByteArray
        assertThat(mapper.readTree(profile)["balance"].asLong()).isEqualTo(400)
        assertThat(mapper.readTree(profile)["earned30d"].asLong()).isEqualTo(400)
        val portalBalance = mvc.perform(get("/api/mobile/me/balance").header("Authorization", learner))
            .andExpect(status().isOk).andReturn().response.contentAsByteArray
        assertThat(mapper.readTree(portalBalance)["balanceKzt"].asLong()).isEqualTo(400)
        assertThat(mapper.readTree(portalBalance)["currency"].asText()).isEqualTo("KZT")
    }

    @Test fun `the exact two minute addition boundary qualifies but one nanosecond later does not`() {
        for ((delay, expected) in listOf(Duration.ofSeconds(120) to 400L, Duration.ofSeconds(120).plusNanos(1) to 0L)) {
            val f = flow()
            acknowledge(f)
            deliver(f, payment(f, additionDelay = delay))
            assertCredit(f, expected)
            assertThat(balance(f.employee)).isEqualTo(expected)
            assertThat(spent(f.world.offers.single())).isEqualTo(expected)
        }
    }

    @Test fun `an offered product already present when the popup is displayed never earns a new reward`() {
        val f = flow(preexisting = true)
        acknowledge(f)
        deliver(f, payment(f))
        assertCredit(f, 0)
        assertThat(cases(f).single()["reason_code"]).isEqualTo("product_preexisting")
        assertThat(balance(f.employee)).isZero()
        assertThat(spent(f.world.offers.single())).isZero()
    }

    @Test fun `removed packages do not earn and a reappearing generation must satisfy its own addition window`() {
        for ((readdedAt, expected) in listOf(40L to 200L, 150L to 0L)) {
            val f = flow(quantities = listOf(1))
            acknowledge(f)
            val offer = f.world.offers.single()
            val steps = listOf(
                Step(offer, 0, 1, Duration.ofSeconds(20), "old"),
                Step(offer, 1, 0, Duration.ofSeconds(30), "old"),
                Step(offer, 0, 1, Duration.ofSeconds(readdedAt), "new"),
            )
            deliver(f, payment(f, steps = steps))
            assertCredit(f, expected)
            assertThat(balance(f.employee)).isEqualTo(expected)
        }
    }

    @Test fun `an offline sale delivered before its shown ACK credits once after the late genuine ACK`() {
        val f = flow()
        val request = payment(f)
        deliver(f, request)
        assertThat(balance(f.employee)).isZero()
        assertThat(cases(f).single()["state"]).isEqualTo("waiting_evidence")
        acknowledge(f)
        acknowledge(f)
        deliver(f, request)
        assertCredit(f, 400)
        assertThat(balance(f.employee)).isEqualTo(400)
        assertThat(spent(f.world.offers.single())).isEqualTo(400)
    }

    @Test fun `six simultaneous identical HTTP deliveries preserve one raw sale receipt budget debit and wallet credit`() {
        val f = flow()
        acknowledge(f)
        val request = payment(f)
        val replies = parallel((1..6).map { { deliver(f, request) } })
        assertThat(replies.count { it["accepted"].asBoolean() }).isEqualTo(1)
        assertCredit(f, 400)
        assertThat(balance(f.employee)).isEqualTo(400)
        assertThat(number("SELECT count(*) FROM pos_sales WHERE id=?", f.sale)).isEqualTo(1)
        assertThat(spent(f.world.offers.single())).isEqualTo(400)
    }

    @Test fun `another transport sale ID for the same physical and fiscal receipt cannot earn again`() {
        val f = flow()
        acknowledge(f)
        val request = payment(f)
        deliver(f, request)
        val alias = request.copy(saleId = "alias_${UUID.randomUUID()}")
        deliver(f, alias)
        assertCredit(f, 400)
        assertThat(balance(f.employee)).isEqualTo(400)
        assertThat(number("SELECT count(*) FROM pos_sales WHERE id IN (?,?)", f.sale, alias.saleId)).isEqualTo(2)
        assertThat(number("SELECT count(*) FROM recommendation_reward_receipts WHERE pharmacy_id=?", f.employee.pharmacy)).isEqualTo(1)
        assertThat(spent(f.world.offers.single())).isEqualTo(400)
    }

    @Test fun `a changed retry body is rejected with conflict while original evidence and money remain intact`() {
        val f = flow()
        acknowledge(f)
        val original = payment(f)
        deliver(f, original)
        val changed = original.copy(items = original.items.map { it.copy(name = "Changed immutable sale") })
        deliver(f, changed, expectedStatus = 409)
        assertCredit(f, 400)
        assertThat(balance(f.employee)).isEqualTo(400)
        assertThat(spent(f.world.offers.single())).isEqualTo(400)
        val stored = mapper.readTree(rows("SELECT items::text FROM pos_sales WHERE id=?", f.sale).single()["items"].toString())
        assertThat(stored[0]["name"].asText()).isEqualTo(original.items.single().name)
    }

    @Test fun `unknown package units and an unmapped raw seller stay in HQ review without moving money`() {
        for (unknownActor in listOf(false, true)) {
            val f = flow(employee = employee(mapped = !unknownActor))
            acknowledge(f)
            val original = payment(f)
            val request = if (unknownActor) original else original.copy(rewardEvidence = original.rewardEvidence!!.let {
                it.copy(completion = it.completion.copy(lines = it.completion.lines.map { line -> line.copy(unitSemantics = "unknown") }))
            })
            deliver(f, request)
            assertCredit(f, 0)
            assertThat(cases(f).single()["state"]).isEqualTo("review_required")
            assertThat(balance(f.employee)).isZero()
            assertThat(spent(f.world.offers.single())).isZero()
        }
    }

    @Test fun `a refund from another cashier delivered before its original sale fences the later reward for HQ`() {
        val f = flow()
        acknowledge(f)
        val other = employee(base = f.employee)
        deliver(f, refund(f, other), employee = other)
        deliver(f, payment(f))
        assertCredit(f, 0)
        assertThat(cases(f).single()["state"]).isIn("review_required", "refund_review")
        assertThat(balance(f.employee)).isZero()
        assertThat(balance(other)).isZero()
        assertThat(spent(f.world.offers.single())).isZero()
        assertThat(number("SELECT count(*) FROM pos_sales WHERE pharmacy_id=?", f.employee.pharmacy)).isEqualTo(2)
    }

    @Test fun `a refund after credit never silently debits the pharmacist or reopens its budget`() {
        val f = flow()
        acknowledge(f)
        deliver(f, payment(f))
        deliver(f, refund(f, f.employee))
        assertThat(cases(f).single()["state"]).isEqualTo("refund_review")
        assertThat(balance(f.employee)).isEqualTo(400)
        assertThat(spent(f.world.offers.single())).isEqualTo(400)
        assertThat(number("SELECT count(*) FROM pharmacist_wallet_entries WHERE pharmacist_id=?", f.employee.person)).isEqualTo(1)
    }

    @Test fun `concurrent refund and original from different cashiers commit only retained credit or HQ hold`() {
        repeat(3) {
            val f = flow()
            acknowledge(f)
            val other = employee(base = f.employee)
            parallel(listOf(
                { deliver(f, payment(f)) },
                { deliver(f, refund(f, other), employee = other) },
            ))
            assertThat(cases(f).single()["state"]).isIn("review_required", "refund_review")
            assertThat(balance(f.employee)).isIn(0L, 400L)
            assertThat(spent(f.world.offers.single())).isEqualTo(balance(f.employee))
            assertThat(balance(other)).isZero()
            assertThat(number("SELECT count(*) FROM pharmacist_wallet_entries WHERE pharmacist_id=?", f.employee.person))
                .isEqualTo(if (balance(f.employee) == 0L) 0L else 1L)
        }
    }

    @Test fun `the last campaign budget is shared atomically between independent employee HTTP sales`() {
        val world = world(budget = 400)
        val first = flow(world = world)
        val second = flow(world = world, employee = employee(base = first.employee))
        acknowledge(first); acknowledge(second)
        parallel(listOf({ deliver(first, payment(first)) }, { deliver(second, payment(second)) }))
        assertThat(balance(first.employee) + balance(second.employee)).isEqualTo(400)
        assertThat(spent(world.offers.single())).isEqualTo(400)
        assertThat((cases(first) + cases(second)).map { it["state"] }).containsExactlyInAnyOrder("credited", "review_required")
        assertThat(number("SELECT count(*) FROM pos_sales WHERE pharmacy_id=?", first.employee.pharmacy)).isEqualTo(2)
    }

    @Test fun `opposite multi campaign orders for the same and different employees cannot deadlock or duplicate funds`() {
        for (sameEmployee in listOf(true, false)) {
            val world = world(offerCount = 2)
            val first = flow(world = world, quantities = listOf(1, 1))
            val second = flow(world = world, employee = if (sameEmployee) first.employee else employee(base = first.employee),
                quantities = listOf(1, 1), reverse = true)
            acknowledge(first); acknowledge(second)
            parallel(listOf({ deliver(first, payment(first)) }, { deliver(second, payment(second)) }))
            assertCredit(first, 400); assertCredit(second, 400)
            if (sameEmployee) assertThat(balance(first.employee)).isEqualTo(800)
            else {
                assertThat(balance(first.employee)).isEqualTo(400)
                assertThat(balance(second.employee)).isEqualTo(400)
            }
            world.offers.forEach { assertThat(spent(it)).isEqualTo(400) }
            assertThat(number("SELECT count(*) FROM pharmacist_wallet_entries WHERE pharmacist_id IN (?,?)", first.employee.person, second.employee.person)).isEqualTo(4)
        }
    }

    @Test fun `sixteen concurrent normal shown ACKs sharing one campaign do not deadlock on its foreign key`() {
        val world = world(budget = 10_000)
        val first = employee()
        val actors = (0 until 16).map { index -> flow(world = world, employee = if (index == 0) first else employee(base = first)) }
        parallel(actors.map { actor -> { acknowledge(actor) } })
        assertThat(number("SELECT count(*) FROM recommendation_reward_shows WHERE promo_id=?", world.offers.single().promo)).isEqualTo(16)
        assertThat(spent(world.offers.single())).isZero()
        assertThat(number("SELECT coalesce(sum(balance),0) FROM pharmacists WHERE pharmacy_id=?", first.pharmacy)).isZero()
        actors.chunked(4).forEach { group -> parallel(group.map { actor -> { deliver(actor, payment(actor)) } }) }
        actors.forEach { actor ->
            assertCredit(actor, 400)
            assertThat(balance(actor.employee)).isEqualTo(400)
        }
        assertThat(spent(world.offers.single())).isEqualTo(6400)
        assertThat(number("SELECT coalesce(sum(balance),0) FROM pharmacists WHERE pharmacy_id=?", first.pharmacy)).isEqualTo(6400)
        assertThat(number("SELECT count(*) FROM recommendation_sale_rewards WHERE pharmacy_id=? AND state='credited'", first.pharmacy)).isEqualTo(16)
        assertThat(number("SELECT count(*) FROM pos_sales WHERE pharmacy_id=?", first.pharmacy)).isEqualTo(16)
        assertThat(number("""SELECT count(*) FROM pharmacist_wallet_entries e JOIN pharmacists p ON p.id=e.pharmacist_id
            WHERE p.pharmacy_id=? AND e.source='receipt_bonus'""", first.pharmacy)).isEqualTo(16)
    }

    @Test fun `HQ scope restricts list detail export and decisions while verified unknown units can be approved once`() {
        val local = flow()
        val foreign = flow()
        for (f in listOf(local, foreign)) {
            acknowledge(f)
            val request = payment(f)
            deliver(f, request.copy(rewardEvidence = request.rewardEvidence!!.let {
                it.copy(completion = it.completion.copy(lines = it.completion.lines.map { line -> line.copy(unitSemantics = "unknown") }))
            }))
        }
        val localId = cases(local).single()["id"].toString()
        val foreignId = cases(foreign).single()["id"].toString()
        val scoped = reviewer(setOf(local.employee.pharmacy), approve = true, export = true)
        val list = mvc.perform(get(REVIEW_PATH).header("Authorization", scoped)).andExpect(status().isOk).andReturn()
        val page = mapper.readTree(list.response.contentAsByteArray)
        assertThat(page["items"].map { it["id"].asText() }).containsExactly(localId)
        mvc.perform(get("$REVIEW_PATH/$foreignId").header("Authorization", scoped)).andExpect(status().isForbidden)
        decide(foreignId, scoped, RewardReviewDecision("reject", "Not my pharmacy"), 403)
        val exported = mvc.perform(get("$REVIEW_PATH/export.xlsx").header("Authorization", scoped))
            .andExpect(status().isOk).andReturn().response.contentAsByteArray
        XSSFWorkbook(ByteArrayInputStream(exported)).use { book ->
            assertThat(book.getSheetAt(0).lastRowNum).isEqualTo(1)
            assertThat(book.getSheetAt(0).getRow(1).getCell(0).stringCellValue).isEqualTo(localId)
            assertThat(book.getSheetAt(0).getRow(1).getCell(3).stringCellValue).isEqualTo(local.employee.pharmacy)
        }
        val viewOnly = reviewer(setOf(local.employee.pharmacy), approve = false)
        val decision = RewardReviewDecision("approve", "Checked original paid receipt and complete packages",
            local.employee.person, 2, confirmedPayment = true, confirmedPackages = true)
        decide(localId, viewOnly, decision, 403)
        assertThat(balance(local.employee)).isZero()
        decide(localId, scoped, decision)
        assertCredit(local, 400)
        assertThat(balance(local.employee)).isEqualTo(400)
        assertThat(spent(local.world.offers.single())).isEqualTo(400)
        decide(localId, scoped, decision, 409)
        assertThat(balance(local.employee)).isEqualTo(400)
        assertThat(balance(foreign.employee)).isZero()
    }

    @Test fun `even HQ confirmation cannot bypass the missing canonical fiscal receipt fence`() {
        val f = flow()
        acknowledge(f)
        val request = payment(f)
        deliver(f, request.copy(rewardEvidence = request.rewardEvidence!!.let {
            it.copy(completion = it.completion.copy(deviceNumber = ""))
        }))
        val reward = cases(f).single()
        assertThat(reward["state"]).isEqualTo("review_required")
        assertThat(rows("SELECT fiscal_key FROM recommendation_reward_receipts WHERE id=?", reward["canonical_receipt_id"]!!).single()["fiscal_key"]).isNull()
        val scoped = reviewer(setOf(f.employee.pharmacy))
        decide(reward["id"].toString(), scoped, RewardReviewDecision("approve", "Cannot replace missing original fiscal provenance",
            f.employee.person, 2, confirmedPayment = true, confirmedPackages = true), 409)
        assertThat(balance(f.employee)).isZero()
        assertThat(spent(f.world.offers.single())).isZero()
    }

    @Test fun `HQ acknowledging an already credited refund records its decision without an automatic debit`() {
        val f = flow()
        acknowledge(f)
        deliver(f, payment(f))
        deliver(f, refund(f, f.employee))
        val id = cases(f).single()["id"].toString()
        val scoped = reviewer(setOf(f.employee.pharmacy))
        val decision = RewardReviewDecision("acknowledge_refund", "Refund reviewed with original credit retained")
        decide(id, scoped, decision)
        assertThat(cases(f).single()["state"]).isEqualTo("credited")
        assertThat(balance(f.employee)).isEqualTo(400)
        assertThat(spent(f.world.offers.single())).isEqualTo(400)
        assertThat(number("SELECT count(*) FROM pharmacist_wallet_entries WHERE pharmacist_id=?", f.employee.person)).isEqualTo(1)
        assertThat(number("SELECT count(*) FROM recommendation_reward_audit WHERE reward_id=? AND action='acknowledge_refund'", id)).isEqualTo(1)
        decide(id, scoped, decision, 409)
        assertThat(balance(f.employee)).isEqualTo(400)
    }

    private data class Employee(val pharmacy: String, val person: String, val rawUser: String, val device: String, val key: String)
    private data class Offer(val id: String, val barcode: String, val promo: String)
    private data class World(val trigger: String, val triggerBarcode: String, val offers: List<Offer>)
    private data class Flow(val employee: Employee, val world: World, val session: String, val sale: String, val doc: Long,
        val shownAt: Instant, val identity: RewardSourceIdentity, val eventIds: Map<String, String>,
        val order: List<Offer>, val quantities: Map<String, Long>, val preexisting: Boolean)
    private data class Step(val offer: Offer, val before: Long, val after: Long, val delay: Duration, val generation: String = "original")

    private fun employee(base: Employee? = null, mapped: Boolean = true): Employee {
        val id = token()
        val pharmacy = base?.pharmacy ?: "ph_$id"
        if (base == null) {
            chains.saveAndFlush(ChainEntity(id = "ch_$id", name = "Synthetic test chain"))
            pharmacies.saveAndFlush(PharmacyEntity(id = pharmacy, name = "Synthetic pharmacy", chainId = "ch_$id", chainName = "Synthetic test chain"))
        }
        val person = "person_$id"
        val n = sequence.incrementAndGet().toString()
        people.saveAndFlush(PharmacistEntity(id = person, name = "Synthetic employee $id", iin = n, phone = "+7${n.takeLast(10)}",
            pharmacyId = pharmacy, pharmacyName = "Synthetic pharmacy", statusRaw = PharmacistStatus.active.name))
        val key = base?.key ?: "test-only-device-${UUID.randomUUID()}-${UUID.randomUUID()}"
        val device = base?.device ?: "cash_$id"
        if (base == null) jdbc.update("INSERT INTO fulfillment_devices(id,device_id,pharmacy_id,secret_sha256,secret_hint) VALUES (?,?,?,?,?)",
            UUID.randomUUID(), device, pharmacy, sha(key), "test-only")
        val raw = "RAW_$id"
        if (mapped) {
            mvc.perform(post("/api/admin/posm/pharmacist-mappings").header("Authorization", hq())
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(
                    UpsertPosmPharmacistMappingRequest(pharmacy, raw, "Synthetic cashier", person))))
                .andExpect(status().isOk)
        }
        return Employee(pharmacy, person, raw, device, key)
    }

    private fun world(offerCount: Int = 1, budget: Long = 10_000): World {
        val id = token()
        val trigger = "trigger_$id"
        val barcode = barcode()
        products.saveAndFlush(ProductEntity(id = trigger, name = "Synthetic trigger $id", barcode = barcode, price = 1000))
        val offers = (1..offerCount).map { index ->
            val product = "offer_${index}_$id"
            val offerCode = barcode()
            products.saveAndFlush(ProductEntity(id = product, name = "Synthetic offer $product", barcode = offerCode, price = 1000))
            val promo = "promo_${index}_$id"
            promos.saveAndFlush(PromoEntity(id = promo, title = "Synthetic funded offer", brand = "TEST", budget = budget,
                medusaProductId = product, productName = "Synthetic offer", tiers = listOf(PromoTier(1, 1000, 200))).also { it.status = PromoStatus.active })
            rules.saveAndFlush(RuleEntity(id = "rule_${index}_$id", trigger = RuleTrigger("product", trigger), recommend = product,
                bonus = 200, promoId = promo).also { it.status = RuleStatus.active })
            Offer(product, offerCode, promo)
        }
        return World(trigger, barcode, offers)
    }

    private fun flow(world: World = world(), employee: Employee = employee(), quantities: List<Long> = listOf(2),
        preexisting: Boolean = false, reverse: Boolean = false): Flow {
        require(quantities.size == world.offers.size)
        val id = token()
        val session = "checkout_$id"
        val body = RecommendRequest(employee.rawUser, pharmacyId = employee.pharmacy, sessionId = session,
            scannedBarcode = world.triggerBarcode, cart = listOf(CartItemDto(barcode = world.triggerBarcode, qty = 1.0)))
        val response = postDevice("/api/posm/recommend", employee, body)
        assertThat(response["rewardEvidenceProtocol"].asInt()).isEqualTo(1)
        val events = response["recommendations"].associate { it["recommendSku"].asText() to it["eventId"].asText() }
        assertThat(events.keys).containsExactlyInAnyOrderElementsOf(world.offers.map { it.id })
        val doc = documents.incrementAndGet()
        val identity = RewardSourceIdentity(sha("synthetic-database:${employee.pharmacy}"), employee.rawUser, doc,
            employee.device, "match")
        return Flow(employee, world, session, "sale_$id", doc, Instant.now().minusSeconds(240).truncatedTo(ChronoUnit.MILLIS),
            identity, events, if (reverse) world.offers.reversed() else world.offers,
            world.offers.mapIndexed { index, offer -> offer.id to quantities[index] }.toMap(), preexisting)
    }

    private fun acknowledge(f: Flow) {
        val baseline = listOf(RewardCartLine(f.doc, 1, barcode = f.world.triggerBarcode, qty = BigDecimal.ONE, unitSemantics = "full_package")) +
            if (f.preexisting) f.world.offers.mapIndexed { index, offer -> RewardCartLine(f.doc, 10L + index, barcode = offer.barcode,
                qty = BigDecimal.ONE, unitSemantics = "full_package") } else emptyList()
        f.eventIds.forEach { (product, event) ->
            postDevice("/api/posm/recommendations/$event/shown", f.employee, MarkShownRequest(f.shownAt,
                RewardShownEvidence(checkoutSessionId = f.session, observationId = "show_${event.takeLast(32)}",
                    identity = f.identity, cartComplete = true, lines = baseline)))
        }
    }

    private fun payment(f: Flow, additionDelay: Duration = Duration.ofSeconds(40), steps: List<Step>? = null): PosSaleRequest {
        val transitions = mutableListOf<RewardLineChange>()
        fun add(at: Instant, line: Long, barcode: String, before: Long, after: Long, generation: String, event: String = "") {
            transitions += RewardLineChange("obs_${f.doc}_${transitions.size + 1}", event, "generation_${f.doc}_$generation",
                transitions.size + 1L, at, f.identity, f.doc, line, barcode = barcode,
                beforeQty = BigDecimal.valueOf(before), afterQty = BigDecimal.valueOf(after), unitSemantics = "full_package")
        }
        add(f.shownAt.minusSeconds(20), 1, f.world.triggerBarcode, 0, 1, "trigger")
        if (f.preexisting) f.world.offers.forEachIndexed { index, offer ->
            add(f.shownAt.minusSeconds(10), 10L + index, offer.barcode, 0, 1, "offer_${index}_original")
        }
        val additions = steps ?: f.order.map { offer -> Step(offer, if (f.preexisting) 1 else 0, f.quantities.getValue(offer.id), additionDelay) }
        additions.forEach { step ->
            val index = f.world.offers.indexOf(step.offer)
            add(f.shownAt.plus(step.delay), 10L + index, step.offer.barcode, step.before, step.after,
                "offer_${index}_${step.generation}", f.eventIds.getValue(step.offer.id))
        }
        add(f.shownAt.plusSeconds(170), 1, f.world.triggerBarcode, 1, 0, "trigger")
        val paidAt = f.shownAt.plusSeconds(180)
        val items = f.order.map { offer ->
            val quantity = f.quantities.getValue(offer.id)
            PosSaleItemDto(barcode = offer.barcode, name = "Synthetic offer ${offer.id}", qty = quantity.toDouble(), price = 1000, total = quantity * 1000)
        }
        val total = items.sumOf { it.total }
        val paidLines = f.order.map { offer ->
            val quantity = f.quantities.getValue(offer.id)
            RewardPaidLine(f.doc, 10L + f.world.offers.indexOf(offer), barcode = offer.barcode, qty = BigDecimal.valueOf(quantity),
                signedQuantity = BigDecimal.valueOf(-quantity), signedPrice = BigDecimal.valueOf(1000),
                signedAmount = BigDecimal.valueOf(-quantity * 1000), unitSemantics = "full_package")
        }
        val complete = RewardCompletionEvidence("paid", "standardn-posted-print", paidAt, f.identity, f.doc, 1, true,
            BigDecimal.valueOf(-total), f.employee.device, f.doc, 1, paidLines)
        return PosSaleRequest(f.sale, f.employee.rawUser, pharmacyId = f.employee.pharmacy, sessionId = f.session,
            sourceDocumentId = f.doc, captureSource = "standardn-print-log", fiscalId = "F-${f.doc}", totalAmount = total,
            items = items, rewardEvidence = RewardSaleEvidence(checkoutSessionId = f.session, journalComplete = true,
                changes = transitions, completion = complete), printedAt = paidAt)
    }

    private fun refund(f: Flow, cashier: Employee): PosSaleRequest {
        val original = payment(f)
        val doc = documents.incrementAndGet()
        val session = "refund_${token()}"
        val at = f.shownAt.plusSeconds(200)
        val identity = f.identity.copy(rawUserId = cashier.rawUser, nativeSessionId = doc, workstationId = cashier.device)
        val completion = original.rewardEvidence!!.completion.let {
            it.copy(status = "refund", completedAt = at, identity = identity, sourceDocumentId = doc,
                signedTotal = it.signedTotal.abs(), fiscalNumber = doc,
                lines = it.lines.map { line -> line.copy(sourceDocumentId = doc, signedQuantity = line.signedQuantity.abs(), signedAmount = line.signedAmount.abs()) },
                originalSourceDocumentId = f.doc, originalFiscalNumber = f.doc, originalFiscalShift = 1)
        }
        return original.copy(saleId = "refund_${token()}", pharmacistId = cashier.rawUser, sessionId = session,
            sourceDocumentId = doc, fiscalId = "F-$doc", printedAt = at,
            rewardEvidence = RewardSaleEvidence(checkoutSessionId = session, journalComplete = true, completion = completion))
    }

    private fun deliver(f: Flow, request: PosSaleRequest, expectedStatus: Int = 200, employee: Employee = f.employee): JsonNode =
        postDevice("/api/posm/sales", employee, request, expectedStatus)
    private fun postDevice(path: String, employee: Employee, body: Any, expectedStatus: Int = 200): JsonNode {
        val result = mvc.perform(post(path).header("X-Posm-Key", employee.key).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(body))).andExpect(status().`is`(expectedStatus)).andReturn()
        return mapper.readTree(result.response.contentAsByteArray)
    }
    private fun assertCredit(f: Flow, amount: Long) {
        assertThat(number("SELECT count(*) FROM pos_sales WHERE id=?", f.sale)).isEqualTo(1)
        assertThat(cases(f)).hasSize(f.world.offers.size)
        assertThat(number("SELECT coalesce(sum(r.bonus_credited),0) FROM receipts r JOIN recommendation_sale_rewards w ON w.receipt_id=r.id WHERE w.source_sale_id=?", f.sale)).isEqualTo(amount)
        assertThat(number("""SELECT count(*) FROM pharmacist_wallet_entries e JOIN recommendation_sale_rewards w ON w.pending_bonus_id=e.source_id
            WHERE w.source_sale_id=? AND e.source='receipt_bonus'""", f.sale)).isEqualTo(if (amount > 0) f.world.offers.size.toLong() else 0)
        if (amount > 0) assertThat(cases(f).map { it["state"] }).containsOnly("credited")
    }
    private fun cases(f: Flow) = rows("SELECT * FROM recommendation_sale_rewards WHERE source_sale_id=? ORDER BY product_id", f.sale)
    private fun balance(employee: Employee) = number("SELECT balance FROM pharmacists WHERE id=?", employee.person)
    private fun spent(offer: Offer) = number("SELECT spent FROM promos WHERE id=?", offer.promo)
    private fun number(sql: String, vararg args: Any) = jdbc.queryForObject(sql, Long::class.java, *args)!!
    private fun rows(sql: String, vararg args: Any) = jdbc.queryForList(sql, *args)
    private fun token() = UUID.randomUUID().toString().replace("-", "")
    private fun sha(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun barcode(): String {
        val prefix = sequence.incrementAndGet().toString()
        val check = (10 - prefix.mapIndexed { index, c -> c.digitToInt() * if (index % 2 == 0) 1 else 3 }.sum() % 10) % 10
        return prefix + check
    }
    private val REVIEW_PATH = "/api/admin/reconcile/recommendation-rewards"
    private fun reviewer(pharmacies: Set<String>, approve: Boolean = true, export: Boolean = false): String {
        val account = admins.saveAndFlush(AdminUserEntity(email = "review-${token()}@example.test", name = "Synthetic scoped reviewer",
            passwordHash = "not-used-by-this-test", roleRaw = AdminRole.FINANCE_REVIEWER.name))
        val person = UUID.randomUUID()
        jdbc.update("INSERT INTO unified_persons VALUES (?,?)", person, "synthetic-test-proof")
        jdbc.update("INSERT INTO unified_admin_profiles(account_id,person_id) VALUES (?,?)", account.id, person)
        val actions = listOf("view") + (if (approve) listOf("approve") else emptyList()) + (if (export) listOf("export") else emptyList())
        val grants = actions.map { StoredGrant("motivation", "sales_review", it, pharmacyIds = pharmacies) }
        jdbc.update("UPDATE unified_admin_profiles SET grants_json=?,version=version+1 WHERE account_id=?", mapper.writeValueAsString(grants), account.id)
        jdbc.update("UPDATE admin_users SET access_mode='MANAGED' WHERE id=?", account.id)
        return "Bearer ${jwt.issueAccessToken(account)}"
    }
    private fun decide(id: String, bearer: String, decision: RewardReviewDecision, expectedStatus: Int = 200): JsonNode {
        val response = mvc.perform(post("$REVIEW_PATH/$id/decision").header("Authorization", bearer)
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(decision)))
            .andExpect(status().`is`(expectedStatus)).andReturn().response.contentAsByteArray
        return mapper.readTree(response)
    }
    private fun hq(): String {
        bearer?.let { return it }
        val account = admins.saveAndFlush(AdminUserEntity(email = "reward-${token()}@example.test", name = "Synthetic HQ",
            passwordHash = "not-used-by-this-test", roleRaw = AdminRole.HQ_HEAD.name))
        return "Bearer ${jwt.issueAccessToken(account)}".also { bearer = it }
    }
    private fun <T> parallel(actions: List<() -> T>): List<T> {
        val gate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(actions.size)
        return try {
            val tasks = actions.map { action -> pool.submit(Callable { check(gate.await(10, TimeUnit.SECONDS)); action() }) }
            gate.countDown()
            tasks.map { it.get(30, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
    }
}
