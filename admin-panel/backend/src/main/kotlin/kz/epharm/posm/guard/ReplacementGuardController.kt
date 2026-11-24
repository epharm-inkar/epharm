package kz.epharm.posm.guard

import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import kz.epharm.auth.security.AdminPrincipal
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.LocalDate

data class ReplacementGuardSettingsRequest(
    val chainPromoEnabled: Boolean,
    val marginMode: MarginMode,
    @field:Min(-100_000) @field:Max(100_000) val marginToleranceKzt: Int = 0,
)

data class MarginUploadItem(
    @field:NotBlank @field:Size(max = 64) val productId: String,
    @field:DecimalMin("0.01") @field:DecimalMax("100000000") val priceKzt: BigDecimal,
    @field:DecimalMin("0.01") @field:DecimalMax("100000000") val purchaseKzt: BigDecimal,
    @field:Min(1) val samples: Int = 1,
)

data class MarginUploadRequest(@field:Valid @field:Size(max = 20_000) val items: List<MarginUploadItem>)

data class ChainPromoItem(
    @field:Pattern(regexp = "^[0-9]{6,32}$") val barcode: String,
    @field:NotBlank @field:Size(max = 120) val promotion: String,
    val validFrom: LocalDate? = null,
    val validUntil: LocalDate? = null,
)

data class ChainPromoReplaceRequest(
    @field:Pattern(regexp = "^(site|upload)$") val source: String,
    @field:Valid @field:Size(max = 20_000) val items: List<ChainPromoItem>,
)

data class GuardWriteResult(val written: Int)

/**
 * Smart replacements: what the till does not offer to replace, and why. HQ roles only (everything under /api/admin).
 * The overview lists every substitution rule of a running or paused campaign with today's verdict.
 */
@RestController
@RequestMapping("/api/admin/replacement-guards")
class ReplacementGuardController(
    private val guards: ReplacementGuardService,
    private val margins: ProductMarginSyncService,
) {
    @GetMapping
    fun overview(): ReplacementGuardOverview = guards.overview()

    @PutMapping("/settings")
    fun settings(
        @Valid @RequestBody req: ReplacementGuardSettingsRequest,
        @AuthenticationPrincipal principal: AdminPrincipal?,
    ): ReplacementGuardSettings = guards.updateSettings(
        ReplacementGuardSettings(req.chainPromoEnabled, req.marginMode, req.marginToleranceKzt),
        principal?.email,
    )

    @PutMapping("/margins")
    fun uploadMargins(@Valid @RequestBody req: MarginUploadRequest): GuardWriteResult {
        return GuardWriteResult(guards.uploadMargins(req.items.map { MarginUpload(it.productId.trim(), it.priceKzt, it.purchaseKzt, it.samples) }))
    }

    @PutMapping("/chain-promos")
    fun replaceChainPromos(@Valid @RequestBody req: ChainPromoReplaceRequest): GuardWriteResult {
        req.items.forEach { item ->
            if (item.validFrom != null && item.validUntil != null && item.validFrom.isAfter(item.validUntil)) {
                throw AppException(ErrorCode.VALIDATION_FAILED, "validFrom позже validUntil: ${item.barcode}", HttpStatus.BAD_REQUEST)
            }
        }
        return GuardWriteResult(
            guards.replaceChainPromos(req.source, req.items.map { ChainPromoUpload(it.barcode, it.promotion, it.validFrom, it.validUntil) }),
        )
    }

    /** Re-measures margins from Standard-N now (the same job runs every night). */
    @PostMapping("/margins/sync")
    fun syncMargins(): ProductMarginSyncResult = margins.sync()
}
