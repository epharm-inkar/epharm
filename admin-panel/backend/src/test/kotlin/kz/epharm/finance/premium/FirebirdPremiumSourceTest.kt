package kz.epharm.finance.premium

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.math.BigDecimal
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Properties
import kz.epharm.pharmacists.service.StandardNDirectoryConnectionFactory
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** The Firebird reader against fake JDBC: what it binds, what it maps and that it stays read only. */
class FirebirdPremiumSourceTest {
    private val connections = mockk<StandardNDirectoryConnectionFactory>()
    private val connection = mockk<Connection>(relaxed = true)
    private val statement = mockk<PreparedStatement>(relaxed = true)
    private val rows = mockk<ResultSet>(relaxed = true)

    private fun source(enabled: Boolean = true, user: String = "reader", password: String = "secret") =
        FirebirdPremiumSource(connections, enabled, "stdn-host", 3050, "C:\\Standart-N\\base_g\\ZTRADE_G.FDB", user, password)

    private fun givenAnOpenDatabase() {
        every { connections.open(any(), any()) } returns connection
        every { connection.prepareStatement(any()) } returns statement
        every { statement.executeQuery() } returns rows
    }

    @Test
    fun `a source that is not enabled or has no credentials does not even try to connect`() {
        for (closed in listOf(source(enabled = false), source(user = ""), source(password = " "))) {
            assertThatThrownBy { closed.open() }.isInstanceOf(StandardNSourceException::class.java).hasMessageContaining("not configured")
        }

        verify(exactly = 0) { connections.open(any(), any()) }
    }

    @Test
    fun `it opens the database of the directory settings read only, in WIN1251 with a short connect timeout`() {
        givenAnOpenDatabase()
        val url = slot<String>()
        val properties = slot<Properties>()
        every { connections.open(capture(url), capture(properties)) } returns connection

        source().open().close()

        assertThat(url.captured).isEqualTo("jdbc:firebirdsql://stdn-host:3050/C:\\Standart-N\\base_g\\ZTRADE_G.FDB")
        assertThat(properties.captured.getProperty("user")).isEqualTo("reader")
        assertThat(properties.captured.getProperty("password")).isEqualTo("secret")
        assertThat(properties.captured.getProperty("encoding")).isEqualTo("WIN1251")
        assertThat(properties.captured.getProperty("connectionTimeout")).isEqualTo("10")
        verify { connection.isReadOnly = true }
        verify(exactly = 1) { connection.close() }
    }

    @Test
    fun `a database that does not open, as at night, is a source exception that does not leak the host`() {
        every { connections.open(any(), any()) } throws SQLException("connect failed to stdn-host/3050")

        assertThatThrownBy { source().open() }
            .isInstanceOf(StandardNSourceException::class.java)
            .hasMessage("Standard-N did not open")
            .hasCauseInstanceOf(SQLException::class.java)
    }

    @Test
    fun `a missing driver is also a source exception`() {
        every { connections.open(any(), any()) } throws ClassNotFoundException("org.firebirdsql.jdbc.FBDriver")

        assertThatThrownBy { source().open() }.isInstanceOf(StandardNSourceException::class.java)
    }

    @Test
    fun `it binds the day range and the product in the order of the proven query and maps the groups`() {
        givenAnOpenDatabase()
        every { rows.next() } returnsMany listOf(true, true, false)
        every { rows.getLong(1) } returnsMany listOf(7L, 9L)
        every { rows.getLong(2) } returnsMany listOf(42L, 0L)
        every { rows.wasNull() } returnsMany listOf(false, false, false, true)
        every { rows.getString(3) } returnsMany listOf("050112345678", null)
        every { rows.getInt(5) } returnsMany listOf(3, 9)
        every { rows.getBigDecimal(6) } returnsMany listOf(BigDecimal("-12.000"), BigDecimal("2"))

        val lines = source().open().use {
            it.lines("BB0D85CD-225B-480E-AE84-C7EA8CC01A76", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 26))
        }

        verify { statement.setTimestamp(1, Timestamp.valueOf(LocalDateTime.of(2026, 11, 1, 0, 0))) }
        verify { statement.setTimestamp(2, Timestamp.valueOf(LocalDateTime.of(2026, 11, 26, 0, 0))) }
        verify { statement.setString(3, "BB0D85CD-225B-480E-AE84-C7EA8CC01A76") }
        assertThat(lines).hasSize(2)
        assertThat(lines[0]).isEqualTo(StandardNPremiumLine(7, 42, "050112345678", 3, BigDecimal("-12.000")))
        assertThat(lines[1].iin).isNull()
        assertThat(lines[1].docType).isEqualTo(9)
        assertThat(lines[1].quantity).isEqualByComparingTo("2")
    }

    @Test
    fun `a seller whose session has no user comes with empty ids and no IIN`() {
        givenAnOpenDatabase()
        every { rows.next() } returnsMany listOf(true, false)
        every { rows.getLong(1) } returns 5L
        every { rows.getLong(2) } returns 0L
        every { rows.wasNull() } returnsMany listOf(false, true)
        every { rows.getString(3) } returns null
        every { rows.getInt(5) } returns 3
        every { rows.getBigDecimal(6) } returns BigDecimal("-1")

        val line = source().open().use { it.lines("W", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 2)) }.single()

        assertThat(line.profileId).isEqualTo(5)
        assertThat(line.userId).isNull()
        assertThat(line.iin).isNull()
    }

    @Test
    fun `a null sum is zero`() {
        givenAnOpenDatabase()
        every { rows.next() } returnsMany listOf(true, false)
        every { rows.getBigDecimal(6) } returns null

        val line = source().open().use { it.lines("W", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 2)) }.single()

        assertThat(line.quantity).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    fun `a failing query is a source exception, never a partial list, and the statement and connection are closed`() {
        givenAnOpenDatabase()
        every { statement.executeQuery() } throws SQLException("lock conflict")

        val reader = source().open()
        assertThatThrownBy { reader.lines("W", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 2)) }
            .isInstanceOf(StandardNSourceException::class.java).hasMessage("Standard-N read failed")
        reader.close()

        verify(exactly = 1) { statement.close() }
        verify(exactly = 1) { connection.close() }
    }

    @Test
    fun `a failing failure to close changes nothing`() {
        givenAnOpenDatabase()
        every { connection.close() } throws SQLException("already gone")

        source().open().close()
    }

    @Test
    fun `the query is the proven one, read only and guarded by a timeout`() {
        givenAnOpenDatabase()
        every { rows.next() } returns false
        val sql = slot<String>()
        every { connection.prepareStatement(capture(sql)) } returns statement

        source().open().use { it.lines("W", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 2)) }

        val query = sql.captured.lowercase().replace(Regex("\\s+"), " ")
        assertThat(query).contains("from parts p join doc_detail dd on dd.g\$profile_id = p.g\$profile_id and dd.part_id = p.id")
        assertThat(query).contains("dd.doc_commitdate >= ? and dd.doc_commitdate < ?")
        assertThat(query).contains("d.doc_type in (3, 9)")
        assertThat(query).contains("left join sessions s on s.id = d.audit_id")
        assertThat(query).contains("left join users u on u.id = s.user_id")
        assertThat(query).contains("where p.ware_id = ? group by 1, 2, 3, 4, 5")
        assertThat(query).doesNotContain("insert ").doesNotContain("update ").doesNotContain("delete ")
        verify { statement.queryTimeout = 120 }
    }
}
