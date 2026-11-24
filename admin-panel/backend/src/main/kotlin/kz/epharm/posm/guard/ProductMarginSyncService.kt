package kz.epharm.posm.guard

import kz.epharm.catalog.repository.ProductRepository
import kz.epharm.pharmacists.service.StandardNDirectoryConnectionFactory
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.Connection
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean

data class ProductMarginSyncResult(
    val products: Int,
    val withWare: Int,
    val measured: Int,
    val completedAt: Instant,
)

/**
 * Nightly, read-only: the trade margin of every product in a substitution rule (trigger and promoted product),
 * from the central Standard-N base. Product barcode -> WARES.ID -> PARTS received in the last N days with a retail
 * and a purchase price; margin = median retail - median purchase. Feeds the smart-replacement guard.
 */
@Service
class ProductMarginSyncService(
    private val jdbc: JdbcTemplate,
    private val products: ProductRepository,
    private val guards: ReplacementGuardService,
    private val sourceConnections: StandardNDirectoryConnectionFactory,
    @Value("\${app.standardn-directory.enabled:false}") private val sourceEnabled: Boolean,
    @Value("\${app.replacement-guard.margin-sync.enabled:true}") private val enabled: Boolean,
    @Value("\${app.replacement-guard.margin-sync.days:45}") private val days: Long,
    @Value("\${app.standardn-directory.host:INKSTDN}") private val host: String,
    @Value("\${app.standardn-directory.port:3050}") private val port: Int,
    @Value("\${app.standardn-directory.database:C:\\Standart-N\\base_g\\ZTRADE_G.FDB}") private val database: String,
    @Value("\${app.standardn-directory.user:}") private val user: String,
    @Value("\${app.standardn-directory.password:}") private val password: String,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val running = AtomicBoolean(false)

    @Scheduled(cron = "\${app.replacement-guard.margin-sync.cron:0 30 4 * * *}", zone = "Asia/Almaty")
    fun scheduled() {
        if (!sourceEnabled || !enabled || user.isBlank() || password.isBlank()) return
        runCatching { sync() }
            .onSuccess { log.info("Product margins from Standard-N: {}", it) }
            .onFailure { log.error("Product margin sync from Standard-N failed: {}", it.message) }
    }

    fun sync(): ProductMarginSyncResult {
        check(sourceEnabled && user.isNotBlank() && password.isNotBlank()) { "Standard-N read-only access is not configured" }
        check(running.compareAndSet(false, true)) { "Margin sync is already running" }
        try {
            val ids = productIdsInSubstitutionRules()
            val barcodesByProduct = if (ids.isEmpty()) emptyMap()
                else products.findAllById(ids).associate { it.id to it.scanBarcodes().map(String::trim).filter(String::isNotEmpty).toSet() }
            val since = clock.instant().minus(Duration.ofDays(days.coerceIn(7, 365)))
            val measured = openSource().use { connection ->
                val waresByBarcode = waresByBarcode(connection, barcodesByProduct.values.flatten().toSet())
                val waresByProduct = barcodesByProduct.mapValues { (_, barcodes) -> barcodes.flatMap { waresByBarcode[it].orEmpty() }.toSet() }
                    .filterValues { it.isNotEmpty() }
                val prices = partsByWare(connection, waresByProduct.values.flatten().toSet(), since)
                waresByProduct.mapNotNull { (productId, wares) ->
                    measure(productId, wares.flatMap { prices[it].orEmpty() })
                }.also { log.debug("Standard-N wares for {} of {} products", waresByProduct.size, ids.size) }
                    .let { it to waresByProduct.size }
            }
            guards.storeMeasuredMargins(measured.first, clock.instant())
            return ProductMarginSyncResult(ids.size, measured.second, measured.first.size, clock.instant())
        } finally {
            running.set(false)
        }
    }

    private fun productIdsInSubstitutionRules(): Set<String> =
        jdbc.query(
            """
            SELECT r.trigger->>'value', r.recommend FROM rules r
            WHERE r.type = 'substitution' AND r.status IN ('active', 'paused') AND r.trigger->>'kind' = 'product'
            """.trimIndent(),
        ) { rs, _ -> listOfNotNull(rs.getString(1), rs.getString(2)) }.flatten().filter(String::isNotBlank).toSet()

    private fun openSource(): Connection {
        val properties = Properties().apply {
            setProperty("user", user)
            setProperty("password", password)
            setProperty("encoding", "WIN1251")
            setProperty("connectionTimeout", "10")
        }
        return sourceConnections.open("jdbc:firebirdsql://$host:$port/$database", properties).also { it.isReadOnly = true }
    }

    private fun waresByBarcode(connection: Connection, barcodes: Set<String>): Map<String, Set<String>> {
        val result = HashMap<String, MutableSet<String>>()
        barcodes.chunked(BARCODE_BATCH).forEach { batch ->
            connection.prepareStatement("SELECT ID, BARCODE FROM WARES WHERE BARCODE IN (${batch.joinToString(",") { "?" }})").use { statement ->
                statement.queryTimeout = 60
                batch.forEachIndexed { index, barcode -> statement.setString(index + 1, barcode) }
                statement.executeQuery().use { rs ->
                    while (rs.next()) {
                        val ware = rs.getString(1)?.trim()?.uppercase() ?: continue
                        val barcode = rs.getString(2)?.trim() ?: continue
                        result.getOrPut(barcode) { mutableSetOf() } += ware
                    }
                }
            }
        }
        return result
    }

    private fun partsByWare(connection: Connection, wares: Set<String>, since: Instant): Map<String, List<Pair<BigDecimal, BigDecimal>>> {
        val result = HashMap<String, MutableList<Pair<BigDecimal, BigDecimal>>>()
        wares.chunked(WARE_BATCH).forEach { batch ->
            connection.prepareStatement(
                "SELECT WARE_ID, PRICE, PRICE_O FROM PARTS WHERE WARE_ID IN (${batch.joinToString(",") { "?" }}) " +
                    "AND INSERTDT >= ? AND PRICE > 0 AND PRICE_O > 0",
            ).use { statement ->
                statement.queryTimeout = 120
                batch.forEachIndexed { index, ware -> statement.setString(index + 1, ware) }
                statement.setTimestamp(batch.size + 1, Timestamp.from(since))
                statement.executeQuery().use { rs ->
                    while (rs.next()) {
                        val ware = rs.getString(1)?.trim()?.uppercase() ?: continue
                        val price = rs.getBigDecimal(2) ?: continue
                        val purchase = rs.getBigDecimal(3) ?: continue
                        result.getOrPut(ware) { mutableListOf() } += price to purchase
                    }
                }
            }
        }
        return result
    }

    companion object {
        private const val BARCODE_BATCH = 200
        private const val WARE_BATCH = 40

        /** Median retail and median purchase over all received parts; null without data. */
        fun measure(productId: String, parts: List<Pair<BigDecimal, BigDecimal>>): MarginUpload? {
            if (parts.isEmpty()) return null
            val price = median(parts.map { it.first })
            val purchase = median(parts.map { it.second })
            if (price.signum() <= 0 || purchase.signum() <= 0) return null
            return MarginUpload(productId, price, purchase, parts.size)
        }

        fun median(values: List<BigDecimal>): BigDecimal {
            val sorted = values.sorted()
            val middle = sorted.size / 2
            val value = if (sorted.size % 2 == 1) sorted[middle]
                else (sorted[middle - 1] + sorted[middle]).divide(BigDecimal(2), 2, RoundingMode.HALF_UP)
            return value.setScale(2, RoundingMode.HALF_UP)
        }
    }
}
