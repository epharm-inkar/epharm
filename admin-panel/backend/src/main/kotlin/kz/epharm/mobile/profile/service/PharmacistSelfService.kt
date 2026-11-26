package kz.epharm.mobile.profile.service

import kz.epharm.catalog.entity.ProductEntity
import kz.epharm.catalog.repository.ProductRepository
import kz.epharm.mobile.profile.dto.PharmacistBalanceDto
import kz.epharm.mobile.profile.dto.PharmacistLanguageDto
import kz.epharm.mobile.profile.dto.PharmacistOfferDto
import kz.epharm.mobile.profile.dto.PharmacistRecommendationDto
import kz.epharm.mobile.profile.dto.PharmacistRecommendationsDto
import kz.epharm.mobile.profile.dto.PharmacistTopOfferDto
import kz.epharm.mobile.profile.dto.PharmacistTopProductDto
import kz.epharm.mobile.profile.dto.PharmacistTopProductsDto
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import kz.epharm.mobile.profile.dto.RecommendationCampaignDto
import kz.epharm.mobile.profile.dto.RecommendationProductDto
import kz.epharm.pharmacists.entity.PharmacistEntity
import kz.epharm.pharmacists.entity.PharmacistStatus
import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.promo.entity.PromoStatus
import kz.epharm.promo.repository.PromoRepository
import kz.epharm.rules.entity.RuleEntity
import kz.epharm.rules.entity.RuleStatus
import kz.epharm.rules.entity.RuleTrigger
import kz.epharm.rules.entity.RuleType
import kz.epharm.rules.repository.RuleRepository
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.ZoneId

@Service
class PharmacistSelfService(
    private val pharmacists: PharmacistRepository,
    private val rules: RuleRepository,
    private val promos: PromoRepository,
    private val products: ProductRepository,
    private val soldProducts: PharmacistSoldProducts? = null,
) {
    @Transactional(readOnly = true)
    fun balance(pharmacistId: String): PharmacistBalanceDto = PharmacistBalanceDto(activeAccount(pharmacistId).balance)

    /** Kept on the card (V128); tills show HQ notices in it when HQ wrote a Kazakh text. */
    @Transactional
    fun setLanguage(pharmacistId: String, language: String?): PharmacistLanguageDto {
        val wanted = language?.trim()?.lowercase()
        if (wanted == null || wanted !in LANGUAGES) {
            throw AppException(ErrorCode.VALIDATION_FAILED, "Язык: ru или kk")
        }
        val account = activeAccount(pharmacistId)
        if (account.language != wanted) {
            account.language = wanted
            pharmacists.save(account)
        }
        return PharmacistLanguageDto(account.language)
    }

    @Transactional(readOnly = true)
    fun recommendations(
        pharmacistId: String,
        page: Int,
        size: Int,
        today: LocalDate = LocalDate.now(ZoneId.of("Asia/Almaty")),
    ): PharmacistRecommendationsDto {
        activeAccount(pharmacistId)
        if (page < 0 || size !in 1..100) {
            throw AppException(ErrorCode.VALIDATION_FAILED, "page должен быть ≥ 0, size — от 1 до 100")
        }
        val (eligible, campaigns, catalog) = eligibleRules(today)
        val groups = eligible.filter { it.recommend in catalog }
            .groupBy { GroupKey(it.promoId, it.type, it.trigger) }
            .entries.sortedWith(
                compareByDescending<Map.Entry<GroupKey, List<RuleEntity>>> {
                    it.value.maxOf { rule -> rule.updatedAt }
                }.thenBy { it.value.minOf { rule -> rule.id } },
            )
        // Page whole groups so a five-offer replacement never splits across pages.
        val offset = page.toLong() * size
        val items = if (offset >= groups.size) emptyList() else groups.drop(offset.toInt()).take(size).map { (key, values) ->
            val campaign = key.promoId?.let(campaigns::get)
            PharmacistRecommendationDto(
                campaign = campaign?.let { RecommendationCampaignDto(it.id, it.title, it.dateStart, it.dateEnd) },
                type = key.type,
                trigger = key.trigger,
                triggerProducts = triggerIds(key.trigger).mapNotNull(catalog::get).map(::productDto),
                offers = values.sortedWith(compareBy({ it.card?.offerRank ?: Int.MAX_VALUE }, { -it.bonus }, { it.id }))
                    .distinctBy { it.recommend }.take(5).map {
                        PharmacistOfferDto(it.id, productDto(catalog.getValue(it.recommend)), it.bonus, it.script, it.advantages)
                    },
            )
        }
        return PharmacistRecommendationsDto(items, page, size, groups.size)
    }

    /**
     * What the pharmacist sold most in the last [days] days (receipts with the product among their own
     * sales) and the till's suggestions for each (Grigoriy, 25.11.2026: instead of an unclear list of
     * «рекомендаций», learn in advance what to offer with the products you actually sell).
     */
    @Transactional(readOnly = true)
    fun topProducts(
        pharmacistId: String,
        days: Int = 30,
        limit: Int = 8,
        today: LocalDate = LocalDate.now(ZoneId.of("Asia/Almaty")),
    ): PharmacistTopProductsDto {
        activeAccount(pharmacistId)
        val span = days.coerceIn(7, 90)
        val since = Timestamp.from(Instant.now().minus(span.toLong(), ChronoUnit.DAYS))
        // Read through the (pharmacist_id, printed_at) index; it is partial, so the condition is spelled out.
        val sold = soldProducts?.mostSold(pharmacistId, since.toInstant(), limit.coerceIn(1, 20)).orEmpty()
        val (eligible, _, catalog) = eligibleRules(today)
        val offersByTrigger = eligible.filter { it.recommend in catalog }
            .flatMap { rule -> triggerIds(rule.trigger).map { trigger -> trigger to rule } }
            .groupBy({ it.first }, { it.second })
        return PharmacistTopProductsDto(
            items = sold.map { (productId, name, receipts) ->
                PharmacistTopProductDto(
                    productId = productId,
                    name = catalog[productId]?.name ?: name,
                    receipts = receipts,
                    offers = offersByTrigger[productId].orEmpty()
                        .sortedWith(compareBy({ it.card?.offerRank ?: Int.MAX_VALUE }, { it.id }))
                        .distinctBy { it.recommend }
                        .take(3)
                        .map { PharmacistTopOfferDto(it.id, it.type, productDto(catalog.getValue(it.recommend)), it.script) },
                )
            },
            days = span,
        )
    }

    private data class Eligible(
        val rules: List<RuleEntity>,
        val campaigns: Map<String, kz.epharm.promo.entity.PromoEntity>,
        val catalog: Map<String, ProductEntity>,
    )

    private fun eligibleRules(today: LocalDate): Eligible {
        val activeRules = rules.findAllByStatusRawOrderByUpdatedAtDesc(RuleStatus.active.name)
        val campaigns = promos.findAllById(activeRules.mapNotNull { it.promoId }.toSet())
            .filter {
                it.status == PromoStatus.active &&
                    (it.dateStart == null || !today.isBefore(it.dateStart)) &&
                    (it.dateEnd == null || !today.isAfter(it.dateEnd))
            }.associateBy { it.id }
        val eligible = activeRules.filter { it.promoId == null || it.promoId in campaigns }
        val productIds = eligible.flatMap { listOf(it.recommend) + triggerIds(it.trigger) }.toSet()
        val catalog = products.findAllById(productIds).associateBy { it.id }
        return Eligible(eligible, campaigns, catalog)
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

    private fun triggerIds(trigger: RuleTrigger): List<String> = when (trigger.kind) {
        "product" -> listOfNotNull(trigger.value as? String)
        "product_any" -> (trigger.value as? List<*>)?.filterIsInstance<String>().orEmpty()
        else -> emptyList()
    }

    private fun productDto(product: ProductEntity) = RecommendationProductDto(
        product.id, product.name, product.brand, product.mnn, product.volume,
        product.scanBarcodes(), product.ipartId, product.price, product.updatedAt,
    )

    private data class GroupKey(val promoId: String?, val type: RuleType, val trigger: RuleTrigger)

    private companion object {
        val LANGUAGES = setOf("ru", "kk")
    }
}

/** A product the pharmacist sold: catalogue id, name on the receipt, receipts that had it. */
data class SoldProduct(val productId: String, val name: String, val receipts: Int)

fun interface PharmacistSoldProducts {
    fun mostSold(pharmacistId: String, since: Instant, limit: Int): List<SoldProduct>
}

@org.springframework.stereotype.Component
class JdbcPharmacistSoldProducts(private val jdbc: org.springframework.jdbc.core.JdbcTemplate) : PharmacistSoldProducts {
    // Read through the (pharmacist_id, printed_at) index; it is partial, so the condition is spelled out.
    override fun mostSold(pharmacistId: String, since: Instant, limit: Int): List<SoldProduct> = jdbc.query(
        """
        select i->>'productId' as product_id, max(i->>'name') as name, count(distinct s.id) as receipts
          from pos_sales s
          cross join lateral jsonb_array_elements(
              case when jsonb_typeof(s.items) = 'array' then s.items else '[]'::jsonb end) i
         where s.pharmacist_id = ? and s.pharmacist_id <> '' and s.printed_at >= ?
           and coalesce(i->>'productId', '') <> ''
         group by 1
         order by 3 desc, 2
         limit ?
        """.trimIndent(),
        { rs, _ -> SoldProduct(rs.getString("product_id"), rs.getString("name") ?: "", rs.getInt("receipts")) },
        pharmacistId, Timestamp.from(since), limit,
    )
}
