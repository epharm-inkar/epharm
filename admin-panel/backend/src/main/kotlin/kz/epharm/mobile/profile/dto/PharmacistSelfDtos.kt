package kz.epharm.mobile.profile.dto

import kz.epharm.rules.entity.RuleTrigger
import kz.epharm.rules.entity.RuleType
import java.time.Instant
import java.time.LocalDate

data class PharmacistBalanceDto(val balanceKzt: Long, val currency: String = "KZT")

data class PharmacistRecommendationsDto(
    val items: List<PharmacistRecommendationDto>,
    val page: Int,
    val size: Int,
    val total: Int,
    val currency: String = "KZT",
)

data class PharmacistRecommendationDto(
    val campaign: RecommendationCampaignDto?,
    val type: RuleType,
    val trigger: RuleTrigger,
    val triggerProducts: List<RecommendationProductDto>,
    val offers: List<PharmacistOfferDto>,
)

data class RecommendationCampaignDto(
    val id: String,
    val title: String,
    val dateStart: LocalDate?,
    val dateEnd: LocalDate?,
)

data class PharmacistOfferDto(
    val ruleId: String,
    val product: RecommendationProductDto,
    val bonusKzt: Int,
    val script: String,
    val advantages: List<String>,
)

data class RecommendationProductDto(
    val id: String,
    val name: String,
    val brand: String,
    val mnn: String,
    val volume: String,
    val barcodes: List<String>,
    val ipartId: String?,
    val catalogPriceKzt: Int,
    val catalogUpdatedAt: Instant,
)

/** What the pharmacist sold most in the last [days] days and what the till suggests with each product. */
data class PharmacistTopProductsDto(
    val items: List<PharmacistTopProductDto>,
    val days: Int,
)

data class PharmacistTopProductDto(
    val productId: String,
    val name: String,
    /** Receipts with this product among the pharmacist's own sales. */
    val receipts: Int,
    val offers: List<PharmacistTopOfferDto>,
)

data class PharmacistTopOfferDto(
    val ruleId: String,
    val type: kz.epharm.rules.entity.RuleType,
    val product: RecommendationProductDto,
    val script: String,
)
