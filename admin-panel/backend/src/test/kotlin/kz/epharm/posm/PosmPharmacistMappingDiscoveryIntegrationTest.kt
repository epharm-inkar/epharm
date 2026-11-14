package kz.epharm.posm

import io.mockk.mockk
import kz.epharm.posm.service.PosmPharmacistMappingService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/** Exercises the discovery SQL in a disposable schema; never creates a financial mapping. */
@Testcontainers
class PosmPharmacistMappingDiscoveryIntegrationTest {
    companion object {
        @Container @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("mapping_discovery_test")
    }

    private val jdbc by lazy {
        JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
    }
    private val service by lazy { PosmPharmacistMappingService(mockk(), mockk(), mockk(), jdbc) }

    @BeforeEach
    fun schema() {
        jdbc.execute("DROP TABLE IF EXISTS pos_sales, pharmacies, posm_pharmacist_mappings")
        jdbc.execute("CREATE TABLE pharmacies(id text PRIMARY KEY, name text)")
        jdbc.execute("""CREATE TABLE pos_sales(pharmacy_id text, reported_pharmacist_id text,
            reported_pharmacist_name text, pharmacist_source text, capture_source text,
            source_document_id bigint, device_id text, printed_at timestamptz DEFAULT now())""")
        jdbc.execute("""CREATE TABLE posm_pharmacist_mappings(pharmacy_id text,
            external_user_id text, active boolean)""")
        jdbc.update("INSERT INTO pharmacies VALUES ('ph-a','Synthetic pharmacy A'),('ph-b','Synthetic pharmacy B')")
    }

    @Test
    fun `native name matched and internal sellers remain discoverable until HQ explicitly maps them`() {
        sale("17", "standardn_name_match")
        sale("-1", "posm_internal")
        sale("17", "standardn_name_match")

        val rows = service.unmapped().associateBy { it.externalUserId }
        assertEquals(setOf("17", "-1"), rows.keys)
        assertEquals(2L, rows.getValue("17").salesCount)
        assertEquals("Synthetic seller", rows.getValue("-1").externalUserName)
        assertEquals("Synthetic pharmacy A", rows.getValue("17").pharmacyName)
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM posm_pharmacist_mappings", Long::class.java))
    }

    @Test
    fun `new discovery sources require every native witness and never promote legacy profile ids`() {
        for (source in listOf("standardn_name_match", "posm_internal")) {
            sale("11", source, capture = null)
            sale("12", source, capture = "legacy-log")
            sale("13", source, document = null)
            sale("14", source, document = 0)
            sale("15", source, document = -1)
            sale("16", source, device = null)
            sale("17", source, device = "   ")
            sale("legacy-profile-id", source)
            sale("18.0", source)
        }
        sale("19", "unresolved")
        sale("20", "standardn_explicit_mapping")
        assertEquals(emptyList<Any>(), service.unmapped())
    }

    @Test
    fun `active explicit maps exclude only their own pharmacy while revoked maps can be repaired`() {
        sale("21", "standardn_name_match")
        sale("21", "standardn_name_match", pharmacy = "ph-b")
        sale("22", "posm_internal")
        jdbc.update("INSERT INTO posm_pharmacist_mappings VALUES ('ph-a','21',true),('ph-a','22',false)")

        assertEquals(setOf("ph-b" to "21", "ph-a" to "22"),
            service.unmapped().map { it.pharmacyId to it.externalUserId }.toSet())
    }

    @Test
    fun `existing unmapped seller discovery keeps legacy compatibility without blank ids`() {
        sale("old-external-id", "standardn_unmapped", capture = null, document = null, device = null)
        sale("", "standardn_unmapped")
        assertEquals(listOf("old-external-id"), service.unmapped().map { it.externalUserId })
    }

    private fun sale(id: String, source: String, capture: String? = "standardn-print-log",
        document: Long? = 123, device: String? = "synthetic-device", pharmacy: String = "ph-a") {
        jdbc.update("""INSERT INTO pos_sales(pharmacy_id,reported_pharmacist_id,reported_pharmacist_name,
            pharmacist_source,capture_source,source_document_id,device_id) VALUES (?,?,?,?,?,?,?)""",
            pharmacy, id, "Synthetic seller", source, capture, document, device)
    }
}
