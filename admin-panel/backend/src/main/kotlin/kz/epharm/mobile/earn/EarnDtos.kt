package kz.epharm.mobile.earn

import kz.epharm.mobile.catalog.dto.MobileCatalogQaDto
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** A product of an active campaign: what the pharmacist earns a bonus for when the till suggests it and it is sold. */
data class EarnProductDto(
    /** Catalogue product id (`products.id`, the rule's recommended product). */
    val productId: String,
    val name: String,
    val brand: String,
    val mnn: String,
    val volume: String,
    val priceKzt: Int,
    val imageUrl: String?,
    /** The largest bonus the active rules pay for one pack of it. */
    val bonusKzt: Int,
    /** `substitution` and/or `cross_sell`. */
    val kinds: List<String>,
    val campaignTitle: String?,
    val campaignEndsOn: LocalDate?,
    /** Packs sold in the whole network over the last 7 days (all receipts, not only by the till's suggestion). */
    val soldWeek: Int,
    /** Sold in the pharmacist's pharmacy within the last 30 days: the basis of «Есть в моей аптеке». */
    val inMyPharmacy: Boolean,
    /** Among the three most sold over the week. */
    val hit: Boolean,
)

data class EarnProductsDto(
    val items: List<EarnProductDto>,
    val pharmacyName: String?,
    /** What «Есть в моей аптеке» means today: sales of this pharmacy over [AVAILABILITY_DAYS] days. */
    val availabilityBasis: String,
    val availabilityDays: Int,
    val generatedAt: Instant,
)

data class EarnProductDetailDto(
    val product: EarnProductDto,
    val description: String?,
    val keyFacts: List<String>,
    val qa: List<MobileCatalogQaDto>,
    val rxOtc: String?,
    val manufacturer: String?,
    /** What the customer asks for when the till suggests this product (names of trigger products). */
    val whenAsked: List<String>,
    /** What to say to the customer (the rules' scripts). */
    val scripts: List<String>,
    val advantages: List<String>,
)

/** One product of the pharmacist's own receipts in the period. */
data class EarnSoldProductDto(
    val name: String,
    val quantity: BigDecimal,
    val sumKzt: Long,
    val receipts: Int,
    /** A product of an active campaign. */
    val promo: Boolean,
)

/** A sale made after the till's suggestion: the bonus it earns. */
data class EarnHintSaleDto(
    val at: Instant,
    val productName: String,
    val bonusKzt: Int,
)

data class EarnStatusDto(
    /** `start`, `confident`, `pro`, `star` by the bonus of this month. */
    val level: String,
    val title: String,
    val message: String,
    val monthEarnedKzt: Long,
    /** Bonus of this month needed for the next level; null at the top. */
    val nextLevelKzt: Long?,
)

data class EarnSummaryDto(
    val period: String,
    val from: Instant,
    /** Bonus for sales after the till's suggestions in the period. */
    val earnedKzt: Long,
    val hintSales: Int,
    val receipts: Int,
    val units: BigDecimal,
    val products: List<EarnSoldProductDto>,
    val recentHintSales: List<EarnHintSaleDto>,
    val status: EarnStatusDto,
    /** The pharmacist's place among all pharmacists by bonus this week; null without a bonus this week. */
    val weekPlace: Int?,
)

data class EarnLeaderDto(
    val place: Int,
    val name: String,
    val pharmacy: String?,
    val city: String?,
    val earnedKzt: Long,
    val sales: Int,
    val me: Boolean,
)

data class EarnLeaderboardDto(
    val period: String,
    val scope: String,
    val items: List<EarnLeaderDto>,
    /** The pharmacist's own row (or their pharmacy's) when it is outside the top. */
    val me: EarnLeaderDto?,
    val participants: Int,
)
