package kz.epharm.pharmacists

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kz.epharm.auth.access.AdministrativeScope
import kz.epharm.pharmacists.dto.PharmacistDto
import kz.epharm.pharmacists.dto.PharmacistPharmacyRefDto
import kz.epharm.pharmacists.entity.PharmacistEntity
import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.pharmacists.service.PharmacistRosterFact
import kz.epharm.pharmacists.service.PharmacistRosterFacts
import kz.epharm.pharmacists.service.PharmacistService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.core.namedparam.SqlParameterSource
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Optional

class PharmacistRosterFactsTest {
    private val repository = mockk<PharmacistRepository>()
    private val scope = mockk<AdministrativeScope>()
    private val facts = mockk<PharmacistRosterFacts>()
    private val service = PharmacistService(repository, mockk(), mockk(), mockk(), scope, mockk(), facts)
    private val lastSale = Instant.parse("2026-11-25T08:30:00Z")
    private val home = PharmacistPharmacyRefDto("pharmacy", "Аптека на Абая", "Алматы")
    private val foreign = PharmacistPharmacyRefDto("foreign", "Аптека в другой области", "Шымкент")

    init {
        every { scope.currentPharmacyIds() } returns setOf("pharmacy")
        every { scope.pharmacist(any()) } answers { firstArg<PharmacistDto>() }
        every { repository.receiptCounts(any(), any(), any()) } returns emptyList()
    }

    private fun person(id: String, phone: String = "", source: String = "standardn") =
        PharmacistEntity(id = id, pharmacyId = "pharmacy", phone = phone).also { it.accountSource = source }

    @Test
    fun `list loads facts once for every visible row and keeps Standard-N pharmacies inside the scope`() {
        val rows = listOf(person("one", phone = "+77010000001", source = "registration"), person("two"))
        every { repository.findForAdministration(setOf("pharmacy"), null, null) } returns rows
        every { facts.load(rows, any()) } returns mapOf(
            "one" to PharmacistRosterFact(lastSale, 4_500, 2, listOf(home, foreign)),
        )

        val (one, two) = service.list()

        assertEquals("registration", one.accountSource)
        assertTrue(one.hasPhone)
        assertEquals(lastSale, one.lastSaleAt)
        assertEquals(4_500L, one.earnedMonthKzt)
        assertEquals(2, one.openAssignments)
        assertEquals(listOf(home), one.standardNPharmacies)
        // A visible row the aggregate did not return is unknown, never a fabricated zero.
        assertEquals("standardn", two.accountSource)
        assertFalse(two.hasPhone)
        assertNull(two.lastSaleAt)
        assertNull(two.earnedMonthKzt)
        assertNull(two.openAssignments)
        assertEquals(emptyList<PharmacistPharmacyRefDto>(), two.standardNPharmacies)
        verify(exactly = 1) { facts.load(any(), any()) }
    }

    @Test
    fun `HQ without a pharmacy scope sees every Standard-N pharmacy and masking stays the last step`() {
        every { scope.currentPharmacyIds() } returns null
        val row = person("one")
        every { repository.findAllByOrderByNameAsc() } returns listOf(row)
        every { facts.load(listOf(row), any()) } returns mapOf("one" to PharmacistRosterFact(lastSale, 900, 0, listOf(home, foreign)))
        assertEquals(listOf(home, foreign), service.list().single().standardNPharmacies)

        every { scope.pharmacist(any()) } answers { firstArg<PharmacistDto>().copy(lastSaleAt = null, earnedMonthKzt = null) }
        val masked = service.list().single()
        assertNull(masked.lastSaleAt)
        assertNull(masked.earnedMonthKzt)
        assertEquals(0, masked.openAssignments)
    }

    @Test
    fun `detail uses the same facts for its single row and an empty scope runs no aggregate`() {
        val row = person("one")
        every { repository.findInAdministrativeScope("one", setOf("pharmacy")) } returns Optional.of(row)
        every { facts.load(listOf(row), any()) } returns mapOf("one" to PharmacistRosterFact(null, 0, 1, emptyList()))
        val detail = service.get("one")
        assertNull(detail.lastSaleAt)
        assertEquals(0L, detail.earnedMonthKzt)
        assertEquals(1, detail.openAssignments)

        every { scope.currentPharmacyIds() } returns emptySet()
        assertEquals(emptyList<PharmacistDto>(), service.list())
        verify(exactly = 1) { facts.load(any(), any()) }
    }

    @Test
    fun `without the facts component the roster still answers with source and phone presence`() {
        val plain = PharmacistService(repository, mockk(), mockk(), mockk(), scope, mockk())
        every { repository.findForAdministration(setOf("pharmacy"), null, null) } returns listOf(person("one", phone = "+77010000001"))
        val row = plain.list().single()
        assertTrue(row.hasPhone)
        assertNull(row.openAssignments)
    }

    @Test
    fun `facts query binds the Almaty calendar month and maps every column`() {
        val jdbc = mockk<NamedParameterJdbcTemplate>()
        val params = slot<SqlParameterSource>()
        val mapper = slot<RowMapper<Pair<String, PharmacistRosterFact>>>()
        every { jdbc.query(any<String>(), capture(params), capture(mapper)) } answers {
            val rs = mockk<ResultSet>()
            every { rs.getString("pharmacist_id") } returns "one"
            every { rs.getTimestamp("last_sale_at") } returns Timestamp.from(lastSale)
            every { rs.getLong("earned_month_kzt") } returns 1_250
            every { rs.getLong("open_assignments") } returns 3
            every { rs.getString("standardn_pharmacies") } returns
                """[{"id":"pharmacy","name":"Аптека на Абая","city":"Алматы"}]"""
            listOf(mapper.captured.mapRow(rs, 0)!!)
        }
        // 19:30 UTC on 30 November is already 1 December in Almaty: the month starts there, not in UTC.
        val observed = Instant.parse("2026-11-30T19:30:00Z")
        val loaded = PharmacistRosterFacts(jdbc, jacksonObjectMapper()).load(listOf(person("one"), person("one"), person("")), observed)

        val bound = params.captured as MapSqlParameterSource
        assertEquals(listOf("one"), bound.getValue("ids"))
        assertEquals(Timestamp.from(observed), bound.getValue("observedAt"))
        val monthStart = LocalDate.of(2026, 12, 1).atStartOfDay(ZoneId.of("Asia/Almaty")).toInstant()
        assertEquals(Timestamp.from(monthStart), bound.getValue("monthStart"))
        assertEquals(PharmacistRosterFact(lastSale, 1_250, 3, listOf(home)), loaded["one"])
    }

    @Test
    fun `facts query is skipped for an empty roster and never maps a missing sale or list to a value`() {
        val jdbc = mockk<NamedParameterJdbcTemplate>()
        assertEquals(emptyMap<String, PharmacistRosterFact>(), PharmacistRosterFacts(jdbc, ObjectMapper()).load(emptyList()))
        verify(exactly = 0) { jdbc.query(any<String>(), any<SqlParameterSource>(), any<RowMapper<Any>>()) }

        val mapper = slot<RowMapper<Pair<String, PharmacistRosterFact>>>()
        every { jdbc.query(any<String>(), any<SqlParameterSource>(), capture(mapper)) } answers {
            val rs = mockk<ResultSet>()
            every { rs.getString("pharmacist_id") } returns "one"
            every { rs.getTimestamp("last_sale_at") } returns null
            every { rs.getLong("earned_month_kzt") } returns 0
            every { rs.getLong("open_assignments") } returns 0
            every { rs.getString("standardn_pharmacies") } returns null
            listOf(mapper.captured.mapRow(rs, 0)!!)
        }
        assertEquals(PharmacistRosterFact(null, 0, 0, emptyList()),
            PharmacistRosterFacts(jdbc, ObjectMapper()).load(listOf(person("one")))["one"])
    }
}
