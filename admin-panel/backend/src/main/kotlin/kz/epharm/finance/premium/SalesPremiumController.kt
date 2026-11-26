package kz.epharm.finance.premium

import kz.epharm.auth.security.AdminPrincipal
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Daily load of the sales premium computed from the central Standard-N database. Only the head of the HQ and the
 * system administrator may move this money; a finance reviewer approves payouts but does not load premiums.
 */
@RestController
@RequestMapping("/api/admin/finance/sales-premium")
class SalesPremiumController(private val service: SalesPremiumService) {

    /** `dryRun: true` reads and computes only; `dryRun: false` credits wallets and writes the audit row. */
    @PreAuthorize("hasAnyRole('HQ_HEAD','SYSTEM_ADMIN')")
    @PostMapping("/import")
    fun importPremium(
        @RequestBody request: SalesPremiumImportRequest,
        @AuthenticationPrincipal principal: AdminPrincipal?,
    ): ResponseEntity<SalesPremiumImportResult> {
        val actor = principal ?: throw AppException(ErrorCode.UNAUTHORIZED, "Not authenticated", HttpStatus.UNAUTHORIZED)
        val dryRun = request.dryRun
            ?: throw AppException(ErrorCode.VALIDATION_FAILED, "Укажите dryRun: true для проверки или false для загрузки")
        val result = if (dryRun) service.preview(request) else service.apply(request, actor)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result)
    }
}
