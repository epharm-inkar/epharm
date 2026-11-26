package kz.epharm.mobile.earn

import kz.epharm.catalog.entity.ProductEntity
import kz.epharm.catalog.repository.ProductRepository
import kz.epharm.mobile.catalog.service.MobileCatalogService
import kz.epharm.pharmacists.entity.PharmacistEntity
import kz.epharm.pharmacists.entity.PharmacistStatus
import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.promo.entity.PromoEntity
import kz.epharm.promo.entity.PromoStatus
import kz.epharm.promo.repository.PromoRepository
import kz.epharm.rules.entity.RuleEntity
import kz.epharm.rules.entity.RuleStatus
import kz.epharm.rules.repository.RuleRepository
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.ConcurrentHashMap

/**
 * The cabinet's «Бонусы» tab (owner, 26.11.2026): the products of active campaigns a pharmacist earns a bonus for, sorted
 * by what the network sells or by the bonus, with «Есть в моей аптеке» on by default; a product card with what the drug
 * is and how it is used; the pharmacist's own sales of the day, week or month with the bonus of the sales after the
 * till's suggestions, a status, and the leaderboards of pharmacists and pharmacies.
 *
 * «Есть в моей аптеке» means «sold in this pharmacy over the last 30 days»: there are no pharmacy stock balances in
 * ePharm today (`merch_product_balances` is empty, the central Standard-N base is not polled for stock).
 */
@Service
class EarnService(
    private val pharmacists: PharmacistRepository,
    private val rules: RuleRepository,
    private val promos: PromoRepository,
    private val products: ProductRepository,
    private val data: EarnData,
    private val catalog: MobileCatalogService? = null,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val cache = ConcurrentHashMap<String, Pair<Instant, Any>>()

    @Transactional(readOnly = true)
    fun products(pharmacistId: String, sort: String = SORT_SALES, onlyMine: Boolean = true): EarnProductsDto {
        val me = activeAccount(pharmacistId)
        if (sort !in SORTS) invalid("Неизвестная сортировка")
        val offers = offers()
        val keys = keysOf(offers)
        val now = clock.instant()
        val week = cached("network-week", NETWORK_TTL) { unitsByProduct(data.networkSold(now.minus(Duration.ofDays(7)), keys.items), keys) }
        val mine = me.pharmacyId?.takeIf { it.isNotBlank() }?.let { pharmacyId ->
            cached("pharmacy|$pharmacyId", PHARMACY_TTL) {
                unitsByProduct(data.pharmacySold(pharmacyId, now.minus(Duration.ofDays(AVAILABILITY_DAYS.toLong())), keys.items), keys).keys
            }
        }.orEmpty()
        val hits = week.entries.filter { it.value > BigDecimal.ZERO }.sortedByDescending { it.value }.take(3).map { it.key }.toSet()
        val items = offers.values.map { offer -> offer.dto(week[offer.product.id], offer.product.id in mine, offer.product.id in hits) }
            .filter { !onlyMine || it.inMyPharmacy }
            .sortedWith(
                if (sort == SORT_BONUS) compareByDescending<EarnProductDto> { it.bonusKzt }.thenByDescending { it.soldWeek }.thenBy { it.name }
                else compareByDescending<EarnProductDto> { it.soldWeek }.thenByDescending { it.bonusKzt }.thenBy { it.name },
            )
        return EarnProductsDto(items, me.pharmacyName, "sales", AVAILABILITY_DAYS, now)
    }

    @Transactional(readOnly = true)
    fun product(pharmacistId: String, productId: String): EarnProductDetailDto {
        val me = activeAccount(pharmacistId)
        val offers = offers()
        val offer = offers[productId] ?: throw AppException(ErrorCode.NOT_FOUND, "Товар без активной акции", HttpStatus.NOT_FOUND)
        val listed = products(me.id, SORT_SALES, onlyMine = false).items.firstOrNull { it.productId == productId }
            ?: offer.dto(null, false, false)
        val detail = offer.product.medusaProductId?.let { id ->
            runCatching { catalog?.detail(id, includeIncentive = true) }
                .onFailure { log.warn("Catalogue card {} unavailable for the bonus tab: {}", id, it.message) }
                .getOrNull()
        }
        val triggerNames = offer.rules.flatMap { triggerIds(it) }.distinct()
            .let { ids -> products.findAllById(ids).map { it.name } }
            .filter { it.isNotBlank() }.distinct().sorted().take(12)
        return EarnProductDetailDto(
            product = listed.copy(imageUrl = listed.imageUrl ?: detail?.imageUrl),
            description = offer.promo?.overrideDescription?.takeIf { it.isNotBlank() } ?: detail?.description,
            keyFacts = detail?.keyFacts.orEmpty(),
            qa = detail?.qa.orEmpty(),
            rxOtc = detail?.rxOtc,
            manufacturer = detail?.manufacturer,
            whenAsked = triggerNames,
            scripts = offer.rules.map { it.script.trim() }.filter { it.isNotEmpty() }.distinct().take(3),
            advantages = offer.rules.flatMap { it.advantages }.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(6),
        )
    }

    @Transactional(readOnly = true)
    fun summary(pharmacistId: String, period: String = PERIOD_TODAY): EarnSummaryDto {
        val me = activeAccount(pharmacistId)
        val from = periodStart(period)
        val keys = keysOf(offers())
        val sold = data.pharmacistSold(me.id, from, SOLD_LIMIT)
        val (earned, hintSales) = data.hintTotals(me.id, from)
        val month = if (period == PERIOD_MONTH) earned else data.hintTotals(me.id, periodStart(PERIOD_MONTH)).first
        val weekPlace = leaderboard(me.id, PERIOD_WEEK, SCOPE_PHARMACISTS).let { board ->
            (board.items + listOfNotNull(board.me)).firstOrNull { it.me }?.takeIf { it.earnedKzt > 0 }?.place
        }
        return EarnSummaryDto(
            period = period,
            from = from,
            earnedKzt = earned,
            hintSales = hintSales,
            receipts = data.pharmacistReceipts(me.id, from),
            units = sold.fold(BigDecimal.ZERO) { sum, item -> sum + item.quantity },
            products = sold.map { EarnSoldProductDto(it.name, it.quantity, it.sumKzt, it.receipts, keys.productOf(it) != null) },
            recentHintSales = data.hintSales(me.id, from, RECENT_LIMIT),
            status = status(period, earned, hintSales, month),
            weekPlace = weekPlace,
        )
    }

    @Transactional(readOnly = true)
    fun leaderboard(pharmacistId: String, period: String = PERIOD_WEEK, scope: String = SCOPE_PHARMACISTS): EarnLeaderboardDto {
        val me = activeAccount(pharmacistId)
        if (scope !in SCOPES) invalid("Неизвестный рейтинг")
        val from = periodStart(period)
        val byPharmacy = scope == SCOPE_PHARMACIES
        val rows = cached("leaders|$period|$scope|${from.epochSecond}", LEADERS_TTL) { data.leaders(from, byPharmacy) }
        val myId = if (byPharmacy) me.pharmacyId else me.id
        val ranked = rows.mapIndexed { index, row ->
            EarnLeaderDto(
                place = index + 1,
                name = if (byPharmacy) row.name else shortName(row.name),
                pharmacy = if (byPharmacy) null else row.pharmacy,
                city = row.city,
                earnedKzt = row.earnedKzt,
                sales = row.sales,
                me = row.id == myId,
            )
        }
        val top = ranked.take(LEADERS_LIMIT)
        return EarnLeaderboardDto(period, scope, top, ranked.firstOrNull { it.me && it.place > LEADERS_LIMIT }, ranked.size)
    }

    // ── offers ──────────────────────────────────────────────────────────────

    /** A promo product with the active rules that recommend it. */
    private class Offer(val product: ProductEntity, val promo: PromoEntity?, val rules: List<RuleEntity>) {
        fun dto(soldWeek: BigDecimal?, inMyPharmacy: Boolean, hit: Boolean) = EarnProductDto(
            productId = product.id,
            name = product.name,
            brand = product.brand,
            mnn = product.mnn,
            volume = product.volume,
            priceKzt = product.price,
            imageUrl = promo?.overrideImage?.takeIf { it.isNotBlank() } ?: promo?.productImage?.takeIf { it.isNotBlank() },
            bonusKzt = rules.maxOf { it.bonus },
            kinds = rules.map { it.type.name }.distinct().sorted(),
            campaignTitle = promo?.title,
            campaignEndsOn = promo?.dateEnd,
            soldWeek = soldWeek?.setScale(0, java.math.RoundingMode.HALF_UP)?.toInt()?.coerceAtLeast(0) ?: 0,
            inMyPharmacy = inMyPharmacy,
            hit = hit,
        )
    }

    /** Active rules of active campaigns in their dates (the same as the till's suggestions), by recommended product. */
    private fun offers(): Map<String, Offer> {
        val today = LocalDate.now(clock.withZone(ALMATY))
        val active = rules.findAllByStatusRawOrderByUpdatedAtDesc(RuleStatus.active.name).filter { it.bonus > 0 }
        val campaigns = promos.findAllById(active.mapNotNull { it.promoId }.toSet())
            .filter {
                it.status == PromoStatus.active &&
                    (it.dateStart == null || !today.isBefore(it.dateStart)) &&
                    (it.dateEnd == null || !today.isAfter(it.dateEnd))
            }.associateBy { it.id }
        val eligible = active.filter { it.promoId == null || it.promoId in campaigns }
        val catalogue = products.findAllById(eligible.map { it.recommend }.toSet()).associateBy { it.id }
        return eligible.filter { it.recommend in catalogue }.groupBy { it.recommend }.mapValues { (productId, productRules) ->
            Offer(catalogue.getValue(productId), productRules.firstNotNullOfOrNull { rule -> rule.promoId?.let(campaigns::get) }, productRules)
        }
    }

    /** Receipt lines name a product by catalogue id, barcode or Standard-N id: all three point back to the product. */
    private class Keys(val items: EarnData.ItemKeys, private val byId: Map<String, String>, private val byBarcode: Map<String, String>, private val bySku: Map<String, String>) {
        fun productOf(item: EarnData.SoldItem): String? =
            item.productId?.let(byId::get) ?: item.barcode?.let(byBarcode::get) ?: item.sku?.let(bySku::get)
    }

    private fun keysOf(offers: Map<String, Offer>): Keys {
        val byId = offers.keys.associateWith { it }
        val byBarcode = offers.values.flatMap { offer -> offer.product.scanBarcodes().map { it to offer.product.id } }.toMap()
        val bySku = offers.values.mapNotNull { offer -> offer.product.ipartId?.takeIf { it.isNotBlank() }?.let { it to offer.product.id } }.toMap()
        return Keys(EarnData.ItemKeys(byId.keys, byBarcode.keys, bySku.keys), byId, byBarcode, bySku)
    }

    private fun unitsByProduct(sold: List<EarnData.SoldItem>, keys: Keys): Map<String, BigDecimal> =
        sold.mapNotNull { item -> keys.productOf(item)?.let { it to item.quantity } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, values) -> values.fold(BigDecimal.ZERO, BigDecimal::add) }

    private fun triggerIds(rule: RuleEntity): List<String> = when (rule.trigger.kind) {
        "product" -> listOfNotNull(rule.trigger.value as? String)
        "product_any" -> (rule.trigger.value as? List<*>)?.filterIsInstance<String>().orEmpty()
        else -> emptyList()
    }

    // ── status and periods ──────────────────────────────────────────────────

    private fun status(period: String, earned: Long, sales: Int, month: Long): EarnStatusDto {
        val (level, next) = LEVELS.last { month >= it.second }.let { current ->
            current.first to LEVELS.firstOrNull { it.second > month }?.second
        }
        val (title, message) = when {
            earned > 0 && period == PERIOD_TODAY -> "Хорошо идёте!" to "Сегодня продаж по подсказкам: $sales, бонус $earned ₸."
            earned > 0 -> "Отличный темп!" to "Продаж по подсказкам: $sales, бонус $earned ₸."
            month > 0 -> "Вы уже в игре" to "В этом месяце бонус $month ₸. Предложите товар с бонусом следующему покупателю."
            else -> "Начните с первой продажи" to "Посмотрите товары с бонусом и предложите один из них, когда касса подскажет."
        }
        return EarnStatusDto(level, title, message, month, next)
    }

    private fun periodStart(period: String): Instant {
        val today = LocalDate.now(clock.withZone(ALMATY))
        val day = when (period) {
            PERIOD_TODAY -> today
            PERIOD_WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            PERIOD_MONTH -> today.withDayOfMonth(1)
            else -> invalid("Неизвестный период")
        }
        return day.atStartOfDay(ALMATY).toInstant()
    }

    private fun activeAccount(id: String): PharmacistEntity {
        val account = pharmacists.findById(id).orElseThrow {
            AppException(ErrorCode.USER_NOT_FOUND, "Фармацевт не найден", HttpStatus.UNAUTHORIZED)
        }
        if (account.status != PharmacistStatus.active) {
            throw AppException(ErrorCode.USER_NOT_ACTIVE, "Аккаунт фармацевта не активен", HttpStatus.FORBIDDEN)
        }
        return account
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> cached(key: String, ttl: Duration, load: () -> T): T {
        val now = clock.instant()
        cache[key]?.let { (at, value) -> if (at.plus(ttl).isAfter(now)) return value as T }
        return load().also { cache[key] = now to it }
    }

    private fun invalid(message: String): Nothing = throw AppException(ErrorCode.VALIDATION_FAILED, message, HttpStatus.BAD_REQUEST)

    companion object {
        const val SORT_SALES = "sales"
        const val SORT_BONUS = "bonus"
        const val PERIOD_TODAY = "today"
        const val PERIOD_WEEK = "week"
        const val PERIOD_MONTH = "month"
        const val SCOPE_PHARMACISTS = "pharmacists"
        const val SCOPE_PHARMACIES = "pharmacies"
        const val AVAILABILITY_DAYS = 30
        private val SORTS = setOf(SORT_SALES, SORT_BONUS)
        private val SCOPES = setOf(SCOPE_PHARMACISTS, SCOPE_PHARMACIES)
        private val ALMATY: ZoneId = ZoneId.of("Asia/Almaty")
        private val NETWORK_TTL: Duration = Duration.ofMinutes(10)
        private val PHARMACY_TTL: Duration = Duration.ofMinutes(30)
        private val LEADERS_TTL: Duration = Duration.ofMinutes(5)
        private const val SOLD_LIMIT = 30
        private const val RECENT_LIMIT = 20
        private const val LEADERS_LIMIT = 20

        /** Levels by the bonus of the month: name and the bonus it starts at. */
        private val LEVELS = listOf("start" to 0L, "confident" to 1_000L, "pro" to 5_000L, "star" to 15_000L)

        /** «Бузурбаева Лаура Ермековна» → «Бузурбаева Л.»: the leaderboard shows colleagues, not their full names. */
        fun shortName(full: String): String {
            val words = full.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            return when (words.size) {
                0 -> "Фармацевт"
                1 -> words[0]
                else -> "${words[0]} ${words[1].first().uppercaseChar()}."
            }
        }
    }
}
