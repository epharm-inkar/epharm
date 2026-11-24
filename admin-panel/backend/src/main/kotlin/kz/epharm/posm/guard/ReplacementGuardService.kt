package kz.epharm.posm.guard

import kz.epharm.catalog.entity.ProductEntity
import kz.epharm.catalog.repository.ProductRepository
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.sql.Date
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicReference

/** What the rules engine asks before it offers a replacement (substitution) for a product in the basket. */
fun interface ReplacementGuard {
    /** Null when the replacement may be offered; otherwise the reason it is not (`chain_promo:<name>` or `margin`). */
    fun blockReason(trigger: ProductEntity?, triggerBarcode: String?, recommend: ProductEntity): String?
}

enum class MarginMode { off, vs_promo }

data class ReplacementGuardSettings(
    val chainPromoEnabled: Boolean = true,
    val marginMode: MarginMode = MarginMode.vs_promo,
    /** No replacement when margin(chosen) - margin(promoted) >= this many tenge. 0 = whenever the pharmacy earns no less. */
    val marginToleranceKzt: Int = 0,
    val updatedAt: Instant? = null,
    val updatedBy: String? = null,
)

/** Everything one decision needs, loaded at most once a minute. */
data class ReplacementGuardSnapshot(
    val settings: ReplacementGuardSettings,
    val marginByProduct: Map<String, BigDecimal>,
    /** Barcode -> promotion, only promotions running today (Almaty). */
    val promotionByBarcode: Map<String, String>,
    val loadedAt: Instant,
) {
    fun decide(triggerProductId: String?, triggerBarcodes: Collection<String>, recommendProductId: String): String? {
        if (settings.chainPromoEnabled) {
            triggerBarcodes.firstNotNullOfOrNull { promotionByBarcode[it.trim()] }?.let { return "chain_promo:$it" }
        }
        if (settings.marginMode == MarginMode.vs_promo && triggerProductId != null) {
            val chosen = marginByProduct[triggerProductId]
            val promoted = marginByProduct[recommendProductId]
            if (chosen != null && promoted != null && chosen - promoted >= BigDecimal(settings.marginToleranceKzt)) return "margin"
        }
        return null
    }
}

data class MarginUpload(val productId: String, val priceKzt: BigDecimal, val purchaseKzt: BigDecimal, val samples: Int)
data class ChainPromoUpload(val barcode: String, val promotion: String, val validFrom: LocalDate?, val validUntil: LocalDate?)

data class GuardedRuleView(
    val ruleId: String,
    val promoId: String?,
    val ruleStatus: String,
    val triggerProductId: String,
    val triggerName: String?,
    val recommendProductId: String,
    val recommendName: String?,
    val triggerMarginKzt: BigDecimal?,
    val recommendMarginKzt: BigDecimal?,
    val reason: String?,
)

data class ReplacementGuardOverview(
    val settings: ReplacementGuardSettings,
    val margins: Int,
    val marginsMeasuredAt: Instant?,
    val chainPromoBarcodes: Int,
    val chainPromoBarcodesToday: Int,
    val substitutionRules: Int,
    val blockedByReason: Map<String, Int>,
    val rules: List<GuardedRuleView>,
)

/**
 * Smart replacements (Grigoriy, 24.11.2026): do not offer to replace what the customer is buying when it is on a
 * network promotion now, or when the pharmacy earns at least as much on it as on the promoted product. The decision
 * reads live tables on every scan (cached for a minute), so an ended promotion or a new margin needs no rule edit.
 */
@Service
class ReplacementGuardService(
    private val jdbc: JdbcTemplate,
    private val products: ProductRepository,
    private val clock: Clock = Clock.systemUTC(),
) : ReplacementGuard {
    private val log = LoggerFactory.getLogger(javaClass)
    private val cached = AtomicReference<ReplacementGuardSnapshot?>(null)

    override fun blockReason(trigger: ProductEntity?, triggerBarcode: String?, recommend: ProductEntity): String? {
        val snapshot = snapshot() ?: return null
        val barcodes = buildList {
            triggerBarcode?.trim()?.takeIf(String::isNotEmpty)?.let(::add)
            trigger?.let { addAll(it.scanBarcodes()) }
        }
        return snapshot.decide(trigger?.id, barcodes, recommend.id)
    }

    /** The latest snapshot; a failed reload keeps the previous one (or none: replacements are offered as before). */
    fun snapshot(): ReplacementGuardSnapshot? {
        val current = cached.get()
        val now = clock.instant()
        if (current != null && Duration.between(current.loadedAt, now) < TTL) return current
        return runCatching { load(now) }
            .onSuccess { cached.set(it) }
            .onFailure { log.error("Replacement guard data could not be read; replacements are not filtered: {}", it.message) }
            .getOrNull() ?: current
    }

    fun invalidate() = cached.set(null)

    @Transactional(readOnly = true)
    fun settings(): ReplacementGuardSettings = readSettings()

    @Transactional
    fun updateSettings(settings: ReplacementGuardSettings, updatedBy: String?): ReplacementGuardSettings {
        if (settings.marginToleranceKzt !in -100_000..100_000) {
            throw AppException(ErrorCode.VALIDATION_FAILED, "marginToleranceKzt вне диапазона", HttpStatus.BAD_REQUEST)
        }
        jdbc.update(
            """
            UPDATE replacement_guard_settings
            SET chain_promo_enabled = ?, margin_mode = ?, margin_tolerance_kzt = ?, updated_at = now(), updated_by = ?
            WHERE id = 1
            """.trimIndent(),
            settings.chainPromoEnabled, settings.marginMode.name, settings.marginToleranceKzt, updatedBy?.take(255),
        )
        invalidate()
        return readSettings()
    }

    /** Upload while the nightly Standard-N job has not measured a product yet; its rows are never overwritten. */
    @Transactional
    fun uploadMargins(items: List<MarginUpload>): Int {
        val now = Timestamp.from(clock.instant())
        var written = 0
        items.forEach { item ->
            written += jdbc.update(
                """
                INSERT INTO product_trade_margins(product_id, price_kzt, purchase_kzt, margin_kzt, samples, source, measured_at)
                VALUES (?, ?, ?, ?, ?, 'upload', ?)
                ON CONFLICT (product_id) DO UPDATE SET price_kzt = excluded.price_kzt, purchase_kzt = excluded.purchase_kzt,
                    margin_kzt = excluded.margin_kzt, samples = excluded.samples, measured_at = excluded.measured_at
                WHERE product_trade_margins.source = 'upload'
                """.trimIndent(),
                item.productId, item.priceKzt, item.purchaseKzt, item.priceKzt - item.purchaseKzt, item.samples, now,
            )
        }
        invalidate()
        return written
    }

    /** Measured from Standard-N: replaces whatever was there for these products. */
    @Transactional
    fun storeMeasuredMargins(items: List<MarginUpload>, measuredAt: Instant): Int {
        val at = Timestamp.from(measuredAt)
        items.forEach { item ->
            jdbc.update(
                """
                INSERT INTO product_trade_margins(product_id, price_kzt, purchase_kzt, margin_kzt, samples, source, measured_at)
                VALUES (?, ?, ?, ?, ?, 'standardn', ?)
                ON CONFLICT (product_id) DO UPDATE SET price_kzt = excluded.price_kzt, purchase_kzt = excluded.purchase_kzt,
                    margin_kzt = excluded.margin_kzt, samples = excluded.samples, source = 'standardn', measured_at = excluded.measured_at
                """.trimIndent(),
                item.productId, item.priceKzt, item.purchaseKzt, item.priceKzt - item.purchaseKzt, item.samples, at,
            )
        }
        invalidate()
        return items.size
    }

    /** Replaces every row of one source at once (the list of a source is always sent whole). */
    @Transactional
    fun replaceChainPromos(source: String, items: List<ChainPromoUpload>): Int {
        if (source !in SOURCES) throw AppException(ErrorCode.VALIDATION_FAILED, "source: site или upload", HttpStatus.BAD_REQUEST)
        jdbc.update("DELETE FROM chain_promo_barcodes WHERE source = ?", source)
        val distinct = items.distinctBy { it.barcode.trim() to it.promotion.trim() }
        distinct.forEach { item ->
            jdbc.update(
                "INSERT INTO chain_promo_barcodes(barcode, promotion, valid_from, valid_until, source) VALUES (?, ?, ?, ?, ?)",
                item.barcode.trim(), item.promotion.trim().take(120), item.validFrom?.let(Date::valueOf),
                item.validUntil?.let(Date::valueOf), source,
            )
        }
        invalidate()
        return distinct.size
    }

    /** Every substitution rule of a running or paused campaign, with what the guard says about it today. */
    @Transactional(readOnly = true)
    fun overview(): ReplacementGuardOverview {
        val snapshot = load(clock.instant())
        val rules = jdbc.query(
            """
            SELECT r.id, r.promo_id, r.status, r.trigger->>'value' AS trigger_value, r.recommend
            FROM rules r
            WHERE r.type = 'substitution' AND r.status IN ('active', 'paused') AND r.trigger->>'kind' = 'product'
            ORDER BY r.promo_id NULLS LAST, r.id
            """.trimIndent(),
        ) { rs, _ -> listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)) }
        val ids = rules.flatMap { listOf(it[3], it[4]) }.filterNotNull().toSet()
        val byId = if (ids.isEmpty()) emptyMap() else products.findAllById(ids).associateBy(ProductEntity::id)
        val views = rules.map { (ruleId, promoId, status, triggerId, recommendId) ->
            val trigger = triggerId?.let(byId::get)
            GuardedRuleView(
                ruleId = ruleId!!,
                promoId = promoId,
                ruleStatus = status!!,
                triggerProductId = triggerId.orEmpty(),
                triggerName = trigger?.name,
                recommendProductId = recommendId.orEmpty(),
                recommendName = recommendId?.let(byId::get)?.name,
                triggerMarginKzt = triggerId?.let(snapshot.marginByProduct::get),
                recommendMarginKzt = recommendId?.let(snapshot.marginByProduct::get),
                reason = snapshot.decide(triggerId, trigger?.scanBarcodes().orEmpty(), recommendId.orEmpty()),
            )
        }
        val margins = jdbc.queryForMap("SELECT count(*) AS n, max(measured_at) AS at FROM product_trade_margins")
        val promoTotal = jdbc.queryForObject("SELECT count(*) FROM chain_promo_barcodes", Int::class.java) ?: 0
        return ReplacementGuardOverview(
            settings = snapshot.settings,
            margins = (margins["n"] as Number).toInt(),
            marginsMeasuredAt = (margins["at"] as? Timestamp)?.toInstant(),
            chainPromoBarcodes = promoTotal,
            chainPromoBarcodesToday = snapshot.promotionByBarcode.size,
            substitutionRules = views.size,
            blockedByReason = views.mapNotNull { it.reason?.substringBefore(':') }.groupingBy { it }.eachCount(),
            rules = views,
        )
    }

    private fun load(now: Instant): ReplacementGuardSnapshot {
        val today = LocalDate.ofInstant(now, ALMATY)
        val margins = HashMap<String, BigDecimal>()
        jdbc.query("SELECT product_id, margin_kzt FROM product_trade_margins", RowCallbackHandler { rs ->
            margins[rs.getString(1)] = rs.getBigDecimal(2)
        })
        val promotions = HashMap<String, String>()
        jdbc.query(
            """
            SELECT barcode, promotion FROM chain_promo_barcodes
            WHERE (valid_from IS NULL OR valid_from <= ?) AND (valid_until IS NULL OR valid_until >= ?)
            ORDER BY barcode, promotion
            """.trimIndent(),
            RowCallbackHandler { rs -> promotions.putIfAbsent(rs.getString(1), rs.getString(2)) },
            Date.valueOf(today), Date.valueOf(today),
        )
        return ReplacementGuardSnapshot(readSettings(), margins, promotions, now)
    }

    private fun readSettings(): ReplacementGuardSettings =
        jdbc.query(
            "SELECT chain_promo_enabled, margin_mode, margin_tolerance_kzt, updated_at, updated_by FROM replacement_guard_settings WHERE id = 1",
        ) { rs, _ ->
            ReplacementGuardSettings(
                chainPromoEnabled = rs.getBoolean(1),
                marginMode = MarginMode.valueOf(rs.getString(2)),
                marginToleranceKzt = rs.getInt(3),
                updatedAt = rs.getTimestamp(4)?.toInstant(),
                updatedBy = rs.getString(5),
            )
        }.singleOrNull() ?: ReplacementGuardSettings()

    private companion object {
        val TTL: Duration = Duration.ofSeconds(60)
        val ALMATY: ZoneId = ZoneId.of("Asia/Almaty")
        val SOURCES = setOf("site", "upload")
    }
}
