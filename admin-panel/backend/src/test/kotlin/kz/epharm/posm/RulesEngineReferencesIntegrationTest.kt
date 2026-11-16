package kz.epharm.posm

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.persistence.EntityManager
import kz.epharm.posm.dto.CartItemDto
import kz.epharm.posm.service.RulesEngineService
import kz.epharm.rules.entity.RuleCard
import kz.epharm.rules.entity.RuleComparisonRow
import kz.epharm.rules.entity.RuleEntity
import kz.epharm.rules.entity.RuleStatus
import kz.epharm.rules.repository.RuleMatchReference
import kz.epharm.rules.repository.RuleRepository
import org.assertj.core.api.Assertions.assertThat
import org.hibernate.Session
import org.hibernate.SessionFactory
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.transaction.TestTransaction
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Transactional
class RulesEngineReferencesIntegrationTest {
    companion object {
        @Container @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("rules_references_test").withUsername("epharm").withPassword("epharm_test")
            .apply { start() }

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("spring.jpa.properties.hibernate.generate_statistics") { true }
        }
    }

    @Autowired private lateinit var engine: RulesEngineService
    @Autowired private lateinit var rules: RuleRepository
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var em: EntityManager
    @Autowired private lateinit var json: ObjectMapper
    private val name = "Препарат 20 мг капсулы №30"
    private val stats get() = em.entityManagerFactory.unwrap(SessionFactory::class.java).statistics
    private val ruleLoads get() = stats.getEntityStatistics(RuleEntity::class.java.name).loadCount

    @BeforeEach
    fun seed() {
        jdbc.update("""
            INSERT INTO products(id,name,brand,vendor,mnn,price,barcode,barcodes)
            VALUES ('trigger',?,'','','',100,'primary','["secondary"]'),
                   ('123',?,'','','',100,'numeric-shadow','[]'),
                   ('offer','Offer','','','',100,'offer-code','[]')
        """.trimIndent(), name, name)
        jdbc.update("""
            INSERT INTO products(id,name,brand,vendor,mnn,price,barcode)
            SELECT 'filler_'||i,'Unrelated '||i,'','','',100,'unrelated-'||i
              FROM generate_series(1,470) i
        """.trimIndent())
        val card = RuleCard(offerRank = 0, comparison = List(3) {
            RuleComparisonRow("Comparison $it", "before ".repeat(12), "after ".repeat(12))
        })
        jdbc.update("""
            INSERT INTO rules(id,type,status,trigger,recommend,bonus,script,advantages,card)
            SELECT 'r_'||i,'crosssell','active',
                   jsonb_build_object('kind','product','value',CASE WHEN i=0 THEN 'trigger' ELSE 'filler_'||(1+i%470) END),
                   'offer',100,'Rich script','["Rich advantage"]',CAST(? AS jsonb)
              FROM generate_series(0,980) i
        """.trimIndent(), json.writeValueAsString(card))
        jdbc.update("""UPDATE rules SET trigger='{"kind":"product_any","value":[123,"filler_470"],"conditions":[]}' WHERE id='r_980'""")
        em.clear()
        stats.clear()
    }

    @Test
    fun `native references do not deserialize rich rule entities and keep scalar types exact`() {
        val refs = rules.findActiveMatchReferences()
        assertThat(refs).hasSize(981)
        assertThat(refs.map { it.javaClass }.distinct()).containsExactly(RuleMatchReference::class.java)
        assertThat(ruleLoads).isZero()
        val exact = refs.single { it.id == "r_0" }
        assertThat(exact.kind).isEqualTo("product")
        assertThat(exact.value).isEqualTo("trigger")
        assertThat(exact.hasConditions).isFalse()
        val any = refs.single { it.id == "r_980" }
        assertThat(any.value).isNull()
        assertThat(json.readTree(any.productAnyJson)[0].isNumber).isTrue()
        assertThat(json.readTree(any.productAnyJson)[1].textValue()).isEqualTo("filler_470")
        assertThat(any.recommend).isEqualTo("offer")
        assertThat(any.promoId).isNull()
        TestTransaction.flagForRollback()
        TestTransaction.end()
        // A direct repository read outside a caller transaction starts its own read-only transaction.
        assertThat(TestTransaction.isActive()).isFalse()
        assertThat(rules.findActiveMatchReferences()).isEmpty()
    }

    @Test
    fun `981 rich rules hydrate only the matching card for a strong positive scan`() {
        val result = engine.match(listOf(CartItemDto(barcode = "secondary")), "secondary")
        assertThat(result.matches.map { it.rule.id }).containsExactly("r_0")
        assertThat(ruleLoads).isEqualTo(1)
        assertThat(result.matches.single().rule.card!!.comparison).hasSize(3)
        val session = em.unwrap(Session::class.java)
        assertThat(session.isReadOnly(result.matches.single().rule)).isTrue()
        assertThat(session.isReadOnly(result.matches.single().recommend)).isTrue()
    }

    @Test
    fun `unknown barcode name fallback keeps all candidates and dose safety without hydrating unrelated cards`() {
        val result = engine.match(listOf(CartItemDto(barcode = "unknown", name = name)))
        assertThat(result.matches.map { it.rule.id }).containsExactly("r_0")
        assertThat(ruleLoads).isEqualTo(1)
        em.clear(); stats.clear()
        val wrongDose = engine.match(listOf(CartItemDto(barcode = "unknown", name = "Препарат 10 мг капсулы №30")))
        assertThat(wrongDose.matches).isEmpty()
        assertThat(ruleLoads).isZero()
    }

    @Test
    fun `pause and reactivation are immediate and generic admin loads remain writable`() {
        val cart = listOf(CartItemDto(barcode = "secondary"))
        assertThat(engine.match(cart).matches).hasSize(1)
        jdbc.update("UPDATE rules SET status='paused' WHERE id='r_0'")
        assertThat(engine.match(cart).matches).isEmpty()
        jdbc.update("UPDATE rules SET status='active' WHERE id='r_0'")
        assertThat(engine.match(cart).matches).hasSize(1)
        em.clear()
        val editable = rules.findById("r_0").orElseThrow()
        assertThat(em.unwrap(Session::class.java).isReadOnly(editable)).isFalse()
        // The JDBC reference read shares the JPA transaction and retains the former query's autoflush semantics.
        editable.status = RuleStatus.paused
        assertThat(engine.match(cart).matches).isEmpty()
        editable.status = RuleStatus.active
        assertThat(engine.match(cart).matches).hasSize(1)
        editable.impressions = 7
        em.flush()
        assertThat(jdbc.queryForObject("SELECT impressions FROM rules WHERE id='r_0'", Int::class.java)).isEqualTo(7)
    }
}
