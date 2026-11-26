package kz.epharm.pharmacists.service

import kz.epharm.pharmacists.entity.StandardNPharmacistEntity
import kz.epharm.pharmacists.repository.StandardNPharmacistRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.nio.charset.Charset
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.util.Properties

data class StandardNSyncResult(
    val importedRows: Int,
    val uniqueIins: Int,
    val activeUniqueIins: Int,
    val completedAt: Instant,
)

@Service
class StandardNPharmacistSyncService(
    private val repository: StandardNPharmacistRepository,
    transactionManager: PlatformTransactionManager,
    private val sourceConnections: StandardNDirectoryConnectionFactory,
    @Value("\${app.standardn-directory.enabled:false}") private val enabled: Boolean,
    @Value("\${app.standardn-directory.host:INKSTDN}") private val host: String,
    @Value("\${app.standardn-directory.port:3050}") private val port: Int,
    @Value("\${app.standardn-directory.database:C:\\Standart-N\\base_g\\ZTRADE_G.FDB}")
    private val database: String,
    @Value("\${app.standardn-directory.user:}") private val user: String,
    @Value("\${app.standardn-directory.password:}") private val password: String,
    private val profileLinker: StandardNProfileLinker? = null,
    /** Claims pharmacists typed about themselves yield to what the directory now says (V126). */
    private val identityClaims: kz.epharm.posm.identity.IdentityClaimDirectoryReconciler? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val snapshotTransaction = TransactionTemplate(transactionManager)

    @Scheduled(
        fixedDelayString = "\${app.standardn-directory.sync-delay-ms:21600000}",
        initialDelayString = "\${app.standardn-directory.initial-delay-ms:30000}",
    )
    fun scheduledSync() {
        if (!enabled) return
        runCatching { sync() }
            .onFailure { log.error("Standard-N pharmacist directory sync failed: {}", it.message) }
        // Also after a failed read: the previous snapshot is still there and new pharmacies may need a link.
        refreshProfileLinks()
    }

    /** Pharmacy <-> Standard-N profile links that seller identity depends on (V112). */
    fun refreshProfileLinks() {
        val linker = profileLinker ?: return
        runCatching { linker.refresh() }
            .onSuccess { changed -> if (changed > 0) log.info("Standard-N profile links changed: {}", changed) }
            .onFailure { log.error("Standard-N profile link refresh failed: {}", it.message) }
    }

    fun sync(): StandardNSyncResult {
        check(enabled) { "Standard-N directory sync is disabled" }
        check(user.isNotBlank() && password.isNotBlank()) {
            "Standard-N read-only credentials are not configured"
        }

        val rows = readSource()
        val now = Instant.now()
        // Explicit transaction also covers scheduledSync's self-call; no DB transaction is held
        // while reading Firebird. Readers see the old complete snapshot until this one commits.
        snapshotTransaction.executeWithoutResult {
            repository.deleteAllInBatch()
            repository.saveAllAndFlush(rows.map { it.toEntity(now) })
        }
        reconcileIdentityClaims()

        val result = StandardNSyncResult(
            importedRows = rows.size,
            uniqueIins = rows.mapTo(mutableSetOf()) { it.iin }.size,
            activeUniqueIins = rows.asSequence()
                .filter { it.status == 0 }
                .map { it.iin }
                .toSet()
                .size,
            completedAt = now,
        )
        log.info(
            "Standard-N directory synced: rows={}, uniqueIins={}, activeUniqueIins={}",
            result.importedRows,
            result.uniqueIins,
            result.activeUniqueIins,
        )
        return result
    }

    /** A failure here never fails the sync: the snapshot is already in, the next sync reconciles again. */
    private fun reconcileIdentityClaims() {
        val reconciler = identityClaims ?: return
        runCatching { reconciler.reconcile() }
            .onFailure { log.error("Identity claims could not be reconciled with the Standard-N directory: {}", it.message) }
    }

    @Transactional(readOnly = true)
    fun status(): StandardNSyncResult {
        val rows = repository.findAll()
        return StandardNSyncResult(
            importedRows = rows.size,
            uniqueIins = rows.mapTo(mutableSetOf()) { it.iin }.size,
            activeUniqueIins = rows.asSequence()
                .filter { it.sourceStatus == 0 }
                .map { it.iin }
                .toSet()
                .size,
            completedAt = rows.maxOfOrNull { it.importedAt } ?: Instant.EPOCH,
        )
    }

    private fun readSource(): List<SourceRow> {
        val properties = Properties().apply {
            setProperty("user", user)
            setProperty("password", password)
            setProperty("encoding", "WIN1251")
            setProperty("connectionTimeout", "10")
        }
        val url = "jdbc:firebirdsql://$host:$port/$database"
        sourceConnections.open(url, properties).use { connection ->
            connection.isReadOnly = true
            connection.createStatement().use { statement ->
                statement.queryTimeout = 30
                statement.executeQuery(SOURCE_SQL).use { rs ->
                    val result = ArrayList<SourceRow>(4_000)
                    while (rs.next()) {
                        val iin = rs.getString("INN")?.trim().orEmpty()
                        if (!iin.matches(IIN_REGEX)) continue
                        val profileId = rs.getLong("G_PROFILE_ID")
                        val userId = rs.getLong("USER_ID")
                        val name = repairDoubleEncoded(firstNonBlank(
                            rs.getString("USERNAME_N"),
                            rs.getString("USERNAME"),
                            "Фармацевт Standard-N",
                        ))
                        result += SourceRow(
                            profileId = profileId,
                            userId = userId,
                            iin = iin,
                            name = name,
                            status = rs.getInt("USER_STATUS"),
                            login = rs.getString("USERCODE")?.trim()?.takeIf(String::isNotBlank),
                            post = rs.text("POST_NAME"),
                            department = rs.text("DEPARTMENT_NAME"),
                            profileName = rs.text("PROFILE_NAME"),
                            profileCity = rs.text("PROFILE_CITY"),
                            profileAddress = rs.text("PROFILE_ADDRESS"),
                        )
                    }
                    return result.distinctBy { "${it.profileId}:${it.userId}" }
                }
            }
        }
    }

    private fun java.sql.ResultSet.text(column: String): String? =
        getString(column)?.trim()?.takeIf(String::isNotBlank)?.let(::repairDoubleEncoded)

    private fun firstNonBlank(vararg values: String?): String =
        values.firstOrNull { !it.isNullOrBlank() }?.trim() ?: "Фармацевт Standard-N"

    private data class SourceRow(
        val profileId: Long,
        val userId: Long,
        val iin: String,
        val name: String,
        val status: Int,
        val login: String?,
        val post: String?,
        val department: String?,
        val profileName: String?,
        val profileCity: String?,
        val profileAddress: String?,
    ) {
        fun toEntity(now: Instant) = StandardNPharmacistEntity(
            directoryKey = "$profileId:$userId",
            sourceProfileId = profileId,
            sourceUserId = userId,
            iin = iin,
            fullName = name,
            sourceStatus = status,
            sourceLogin = login,
            sourcePost = post,
            sourceDepartment = department,
            profileName = profileName,
            profileCity = profileCity,
            profileAddress = profileAddress,
            importedAt = now,
        )
    }

    companion object {
        private val IIN_REGEX = Regex("^[0-9]{12}$")
        private val WIN1251: Charset = Charset.forName("windows-1251")
        /** UTF-8 lead bytes of Cyrillic (D0-D3) read as WIN1251 give Р, С, Т or У before a symbol or letter. */
        private val DOUBLE_ENCODED = Regex("[РСТУ][\u0080-\u00BF\u0400-\u045F\u0490-\u0491\u2013-\u2122]")

        /**
         * Some Standard-N rows hold UTF-8 text that the WIN1251 connection decodes as «РљР°СЂРёРјРѕРІР°».
         * Re-decode only when the WIN1251 bytes form strictly valid UTF-8 with Cyrillic in it; real
         * Cyrillic text in WIN1251 never does (its letters are never UTF-8 continuation bytes).
         */
        fun repairDoubleEncoded(value: String): String {
            if (!DOUBLE_ENCODED.containsMatchIn(value)) return value
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val encoder = WIN1251.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val repaired = try {
                decoder.decode(encoder.encode(java.nio.CharBuffer.wrap(value))).toString()
            } catch (_: CharacterCodingException) {
                return value
            }
            return if (repaired.any { it in 'Ѐ'..'ӿ' }) repaired else value
        }
        private const val SOURCE_SQL = """
            select
                u.ID as USER_ID,
                u.G${'$'}PROFILE_ID as G_PROFILE_ID,
                u.STATUS as USER_STATUS,
                u.USERNAME,
                u.USERNAME_N,
                u.USERCODE,
                u.INN,
                u.POST as POST_NAME,
                u.DEPARTAMENTNAME as DEPARTMENT_NAME,
                p.CAPTION as PROFILE_NAME,
                p.CITY as PROFILE_CITY,
                p.ADRESS as PROFILE_ADDRESS
            from USERS u
            left join G${'$'}PROFILES p on p.ID = u.G${'$'}PROFILE_ID
            where char_length(trim(coalesce(u.INN, ''))) = 12
        """
    }
}
