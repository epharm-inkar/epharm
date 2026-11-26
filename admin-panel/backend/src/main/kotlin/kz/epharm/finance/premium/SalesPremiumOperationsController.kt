package kz.epharm.finance.premium

import kz.epharm.auth.security.AdminPrincipal
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * What an administrator does around the daily collection of the sales premium: run it now, take the month statement
 * for accounting, replace the matrix of a month. Only the head of the HQ and the system administrator, like the import.
 */
@RestController
@RequestMapping("/api/admin/finance/sales-premium")
class SalesPremiumOperationsController(
    private val collector: SalesPremiumCollector,
    private val statement: SalesPremiumStatement,
    private val matrix: SalesPremiumMatrixService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Runs the collector now and answers with totals only (no IINs). `dryRun=true` reads and computes and shows what
     * would be credited; `dryRun=false` credits. Without `period` it is the month of yesterday, as the daily job does.
     */
    @PreAuthorize("hasAnyRole('HQ_HEAD','SYSTEM_ADMIN')")
    @PostMapping("/recompute")
    fun recompute(
        @RequestParam(required = false) period: String?,
        @RequestParam(required = false) dryRun: Boolean?,
        @AuthenticationPrincipal principal: AdminPrincipal?,
    ): ResponseEntity<CollectorResult> {
        val actor = principal ?: throw unauthorized()
        val dry = dryRun
            ?: throw AppException(ErrorCode.VALIDATION_FAILED, "Укажите dryRun=true для проверки или dryRun=false для расчёта с зачислением")
        val result = try {
            collector.collect(period?.takeIf { it.isNotBlank() }, dry, PremiumActor.of(actor))
        } catch (e: StandardNSourceException) {
            throw AppException(
                ErrorCode.UPSTREAM_UNAVAILABLE,
                "База Стандарт-Н сейчас недоступна (ночью она закрыта на обслуживание). Повторите днём",
                HttpStatus.SERVICE_UNAVAILABLE, e,
            )
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result)
    }

    /** Names, IINs and amounts of one month for accounting: credited premium from the ledger and the waiting figures. */
    @PreAuthorize("hasAnyRole('HQ_HEAD','SYSTEM_ADMIN')")
    @GetMapping("/statement.csv")
    fun statementCsv(
        @RequestParam period: String,
        @AuthenticationPrincipal principal: AdminPrincipal?,
    ): ResponseEntity<ByteArray> {
        val actor = principal ?: throw unauthorized()
        val month = SalesPremiumRules.requirePeriod(period)
        val rows = statement.rows(month)
        log.info("Sales premium statement {} downloaded by {}: rows={}", month, actor.userId, rows.size)
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"sales-premium-$month.csv\"")
            .cacheControl(CacheControl.noStore())
            .body(SalesPremiumStatementCsv.render(rows))
    }

    /**
     * Replaces the matrix of a month with the CSV in the body (UTF-8, `ware_id`, `bonus_per_pack`, optional
     * `promo_name`). `dryRun=true` only compares it with what the month has. A changed matrix re-prices the whole month
     * from the 1st at the next calculation.
     */
    @PreAuthorize("hasAnyRole('HQ_HEAD','SYSTEM_ADMIN')")
    @PostMapping("/matrix")
    fun uploadMatrix(
        @RequestParam period: String,
        @RequestParam(required = false) dryRun: Boolean?,
        @RequestBody csv: ByteArray,
        @AuthenticationPrincipal principal: AdminPrincipal?,
    ): ResponseEntity<MatrixUploadResult> {
        val actor = principal ?: throw unauthorized()
        val dry = dryRun
            ?: throw AppException(ErrorCode.VALIDATION_FAILED, "Укажите dryRun=true для проверки или dryRun=false для замены матрицы")
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(matrix.upload(period, csv, dry, actor))
    }

    private fun unauthorized() = AppException(ErrorCode.UNAUTHORIZED, "Not authenticated", HttpStatus.UNAUTHORIZED)
}
