package kz.epharm.posm.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant

/** Optional evidence v1. Legacy shown/sale payloads remain valid, but cannot auto-credit. */
data class RewardSourceIdentity(
    val sourceNamespace: String = "",
    val rawUserId: String = "",
    val nativeSessionId: Long = 0,
    val workstationId: String = "",
    /** A known RPS/Standard-N mismatch always requires review. Unknown is not a match. */
    val identityStatus: String = "unknown",
)

data class RewardCartLine(
    val sourceDocumentId: Long = 0,
    val sourceLineId: Long = 0,
    val sku: String? = null,
    val barcode: String? = null,
    val qty: BigDecimal = BigDecimal.ZERO,
    /** Full packages must be proven by the adapter; QUANT alone leaves this unknown. */
    val unitSemantics: String = "unknown",
)

data class RewardShownEvidence(
    val version: Int = 1,
    val checkoutSessionId: String = "",
    val observationId: String = "",
    @field:Valid val identity: RewardSourceIdentity = RewardSourceIdentity(),
    val cartComplete: Boolean = false,
    @field:Valid @field:Size(max = 512) val lines: List<RewardCartLine> = emptyList(),
)

/** An immutable cart transition. A removed/reappearing physical line gets a new generation. */
data class RewardLineChange(
    val observationId: String = "",
    val eventId: String = "",
    val generationId: String = "",
    val sequence: Long = 0,
    val observedAt: Instant = Instant.EPOCH,
    @field:Valid val identity: RewardSourceIdentity = RewardSourceIdentity(),
    val sourceDocumentId: Long = 0,
    val sourceLineId: Long = 0,
    val sku: String? = null,
    val barcode: String? = null,
    val beforeQty: BigDecimal = BigDecimal.ZERO,
    val afterQty: BigDecimal = BigDecimal.ZERO,
    val unitSemantics: String = "unknown",
)

data class RewardPaidLine(
    val sourceDocumentId: Long = 0,
    val sourceLineId: Long = 0,
    val sku: String? = null,
    val barcode: String? = null,
    val qty: BigDecimal = BigDecimal.ZERO,
    val signedQuantity: BigDecimal = BigDecimal.ZERO,
    val signedPrice: BigDecimal = BigDecimal.ZERO,
    val signedAmount: BigDecimal = BigDecimal.ZERO,
    val unitSemantics: String = "unknown",
)

data class RewardCompletionEvidence(
    val status: String = "unknown",
    val source: String = "",
    val completedAt: Instant = Instant.EPOCH,
    @field:Valid val identity: RewardSourceIdentity = RewardSourceIdentity(),
    val sourceDocumentId: Long = 0,
    val documentStatus: Int = 0,
    val finalDetailsRead: Boolean = false,
    val signedTotal: BigDecimal = BigDecimal.ZERO,
    val deviceNumber: String = "",
    val fiscalNumber: Long = 0,
    val fiscalShift: Long = 0,
    @field:Valid @field:Size(max = 512) val lines: List<RewardPaidLine> = emptyList(),
    /** A refund without a proven original reference is a review case, never a guessed debit. */
    val originalSourceDocumentId: Long? = null,
    val originalFiscalNumber: Long? = null,
    val originalFiscalShift: Long? = null,
)

data class RewardSaleEvidence(
    val version: Int = 1,
    val checkoutSessionId: String = "",
    val journalComplete: Boolean = false,
    @field:Valid @field:Size(max = 2048) val changes: List<RewardLineChange> = emptyList(),
    @field:Valid val completion: RewardCompletionEvidence = RewardCompletionEvidence(),
)
