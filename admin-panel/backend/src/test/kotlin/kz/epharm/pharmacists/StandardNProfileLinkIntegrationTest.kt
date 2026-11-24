package kz.epharm.pharmacists

import kz.epharm.pharmacists.service.StandardNProfileLinker
import kz.epharm.posm.service.StandardNDirectoryIdentityLookup
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/** Real PostgreSQL: V112 links, caption matching and the receipt seller lookup. Synthetic data only. */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class StandardNProfileLinkIntegrationTest {
    companion object {
        @Container @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine").apply { start() }
        @JvmStatic @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
        }
    }

    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var linker: StandardNProfileLinker
    @Autowired private lateinit var lookup: StandardNDirectoryIdentityLookup

    @BeforeEach fun setup() {
        jdbc.update("DELETE FROM standardn_pharmacist_directory")
        jdbc.update("DELETE FROM pharmacies WHERE id LIKE 'sn-link-%'")
        jdbc.update("""INSERT INTO chains(id,name,"group") VALUES ('sn-link-chain','Synthetic','pilot') ON CONFLICT DO NOTHING""")
        pharmacy("sn-link-caption", "Аптека №5 Абая 10")
        pharmacy("sn-link-twin", "Двойник")
        pharmacy("sn-link-none", "Нет в Стандарт-Н")
        pharmacy("sn-link-manual", "Аптека №5 Абая 10")
        jdbc.update("UPDATE pharmacies SET standardn_profile_id = 900, standardn_profile_source = 'manual' WHERE id = 'sn-link-manual'")
        directory(profile = 501, user = 34, iin = "000101500011", caption = "Аптека № 5, Абая 10")
        directory(profile = 502, user = 34, iin = "000101500022", caption = "Двойник")
        directory(profile = 503, user = 35, iin = "000101500033", caption = "двойник")
        directory(profile = 900, user = 34, iin = "000101500044", caption = "Ручная связь")
    }

    private fun pharmacy(id: String, name: String) = jdbc.update(
        """INSERT INTO pharmacies(id,name,chain_id,chain_name,city,"group") VALUES (?,?,'sn-link-chain','Synthetic','Synthetic','pilot')""",
        id, name,
    )

    private fun directory(profile: Long, user: Long, iin: String, caption: String) = jdbc.update(
        """INSERT INTO standardn_pharmacist_directory(directory_key,source_profile_id,source_user_id,iin,full_name,source_status,profile_name)
           VALUES (?,?,?,?,?,0,?)""",
        "$profile:$user", profile, user, iin, "Synthetic $iin", caption,
    )

    private fun link(id: String): Pair<Long?, String?> = jdbc.queryForObject(
        "SELECT standardn_profile_id, standardn_profile_source FROM pharmacies WHERE id = ?",
        { rs, _ -> (rs.getObject(1) as Number?)?.toLong() to rs.getString(2) }, id,
    )!!

    @Test fun `caption links one profile, ambiguous captions and HQ links stay as they are`() {
        linker.refresh()
        assertEquals(501L to "caption", link("sn-link-caption"))
        assertEquals(null to null, link("sn-link-twin"), "a caption shared by two profiles must not link")
        assertEquals(null to null, link("sn-link-none"))
        assertEquals(900L to "manual", link("sn-link-manual"))
        assertEquals(0, linker.refresh(), "a second run changes nothing")
    }

    @Test fun `a receipt user id finds the person only through the linked profile`() {
        linker.refresh()
        assertEquals("000101500011", lookup.find("sn-link-caption", "34")?.iin)
        assertEquals("000101500044", lookup.find("sn-link-manual", " 34 ")?.iin)
        assertNull(lookup.find("sn-link-caption", "35"))
        assertNull(lookup.find("sn-link-twin", "34"))
        assertNull(lookup.find("sn-link-caption", "ph_abc"))
        assertNull(lookup.find("sn-link-caption", null))
        assertNull(lookup.find("missing-pharmacy", "34"))
    }

    @Test fun `a renamed profile drops its caption link on the next refresh`() {
        linker.refresh()
        jdbc.update("UPDATE standardn_pharmacist_directory SET profile_name = 'Закрыта' WHERE source_profile_id = 501")
        linker.refresh()
        assertEquals(null to null, link("sn-link-caption"))
        assertNull(lookup.find("sn-link-caption", "34"))
    }

    @Test fun `a profile link always says where it came from`() {
        assertThrows(org.springframework.dao.DataIntegrityViolationException::class.java) {
            jdbc.update(
                """INSERT INTO pharmacies(id,name,chain_id,chain_name,city,"group",standardn_profile_id,standardn_profile_source)
                   VALUES ('sn-link-bad','x','sn-link-chain','x','x','pilot',1,NULL)""",
            )
        }
    }
}
