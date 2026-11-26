package kz.epharm.finance.premium

import java.math.BigDecimal
import java.sql.Connection
import java.sql.SQLException
import java.sql.Timestamp
import java.time.LocalDate
import java.util.Properties
import kz.epharm.pharmacists.service.StandardNDirectoryConnectionFactory
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/** Standard-N is not configured, does not open (it is closed at night) or failed in the middle of a read. */
class StandardNSourceException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * One group of the premium query: [quantity] of one product on documents of [docType] by one seller. [iin] is as
 * Standard-N keeps it, so it may be empty or not a valid IIN. Sale receipts (type 3) carry negative quantities, returns
 * (type 9) positive ones.
 */
data class StandardNPremiumLine(
    val profileId: Long?,
    val userId: Long?,
    val iin: String?,
    val docType: Int,
    val quantity: BigDecimal,
)

/** The central Standard-N database, as far as the premium needs it: one connection per run, read only. */
interface StandardNPremiumSource {
    /** Opens the database for one run; [StandardNSourceException] when it is not configured or does not open. */
    fun open(): StandardNPremiumReader
}

interface StandardNPremiumReader : AutoCloseable {
    /**
     * The sale and return lines of one product with a commit date from [from] (inclusive) to [until] (exclusive), one
     * per seller and document type. A failure is [StandardNSourceException]: a partial read is never returned, because
     * a figure that misses a product would take valid premium back.
     */
    fun lines(wareId: String, from: LocalDate, until: LocalDate): List<StandardNPremiumLine>

    override fun close()
}

/**
 * The Firebird reader. It reuses the connection setup and the `STANDARDN_*` settings of the pharmacist directory
 * (`app.standardn-directory.*`), so the same switch and the same read-only credentials open the central database.
 */
@Component
class FirebirdPremiumSource(
    private val connections: StandardNDirectoryConnectionFactory,
    @Value("\${app.standardn-directory.enabled:false}") private val enabled: Boolean,
    @Value("\${app.standardn-directory.host:INKSTDN}") private val host: String,
    @Value("\${app.standardn-directory.port:3050}") private val port: Int,
    @Value("\${app.standardn-directory.database:C:\\Standart-N\\base_g\\ZTRADE_G.FDB}") private val database: String,
    @Value("\${app.standardn-directory.user:}") private val user: String,
    @Value("\${app.standardn-directory.password:}") private val password: String,
) : StandardNPremiumSource {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun open(): StandardNPremiumReader {
        if (!enabled || user.isBlank() || password.isBlank()) {
            throw StandardNSourceException("Standard-N read-only access is not configured")
        }
        val properties = Properties().apply {
            setProperty("user", user)
            setProperty("password", password)
            setProperty("encoding", "WIN1251")
            setProperty("connectionTimeout", "10")
        }
        val connection = try {
            connections.open("jdbc:firebirdsql://$host:$port/$database", properties).also { it.isReadOnly = true }
        } catch (e: Exception) {
            // The message may name the host and the database path: it stays in the server log.
            log.warn("Standard-N did not open: {}", e.message)
            throw StandardNSourceException("Standard-N did not open", e)
        }
        return FirebirdPremiumReader(connection)
    }

    private class FirebirdPremiumReader(private val connection: Connection) : StandardNPremiumReader {
        override fun lines(wareId: String, from: LocalDate, until: LocalDate): List<StandardNPremiumLine> = try {
            connection.prepareStatement(PREMIUM_SQL).use { statement ->
                statement.queryTimeout = QUERY_TIMEOUT_SECONDS
                statement.setTimestamp(1, Timestamp.valueOf(from.atStartOfDay()))
                statement.setTimestamp(2, Timestamp.valueOf(until.atStartOfDay()))
                statement.setString(3, wareId)
                statement.executeQuery().use { rs ->
                    val lines = ArrayList<StandardNPremiumLine>()
                    while (rs.next()) {
                        val profileId = rs.getLong(1).takeUnless { rs.wasNull() }
                        val userId = rs.getLong(2).takeUnless { rs.wasNull() }
                        lines += StandardNPremiumLine(
                            profileId = profileId,
                            userId = userId,
                            iin = rs.getString(3),
                            docType = rs.getInt(5),
                            quantity = rs.getBigDecimal(6) ?: BigDecimal.ZERO,
                        )
                    }
                    lines
                }
            }
        } catch (e: SQLException) {
            throw StandardNSourceException("Standard-N read failed", e)
        }

        override fun close() {
            try {
                connection.close()
            } catch (_: SQLException) {
                // Nothing was written; a connection that does not close cleanly changes nothing.
            }
        }
    }

    private companion object {
        const val QUERY_TIMEOUT_SECONDS = 120

        /**
         * The proven read-only query, one call per product (columns: profile, user, IIN, name, document type,
         * quantity, documents). The name column stays so that the plan stays the proven one; it is not read.
         */
        const val PREMIUM_SQL = """
            select dd.G${'$'}PROFILE_ID, s.USER_ID, u.INN, coalesce(u.USERNAME_N, u.USERNAME), d.DOC_TYPE,
                   sum(dd.QUANT), count(distinct d.ID)
            from PARTS p
            join DOC_DETAIL dd on dd.G${'$'}PROFILE_ID = p.G${'$'}PROFILE_ID and dd.PART_ID = p.ID
                 and dd.DOC_COMMITDATE >= ? and dd.DOC_COMMITDATE < ?
            join DOCS d on d.ID = dd.DOC_ID and d.G${'$'}PROFILE_ID = dd.G${'$'}PROFILE_ID and d.DOC_TYPE in (3, 9)
            left join SESSIONS s on s.ID = d.AUDIT_ID and s.G${'$'}PROFILE_ID = d.G${'$'}PROFILE_ID
            left join USERS u on u.ID = s.USER_ID and u.G${'$'}PROFILE_ID = s.G${'$'}PROFILE_ID
            where p.WARE_ID = ?
            group by 1, 2, 3, 4, 5
        """
    }
}
