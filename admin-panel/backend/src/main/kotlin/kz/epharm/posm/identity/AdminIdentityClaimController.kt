package kz.epharm.posm.identity

import jakarta.validation.Valid
import kz.epharm.auth.security.AdminPrincipal
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** «ИИН указали сами»: what pharmacists typed about themselves, for HQ to confirm or reject. Same roles as the seller mappings. */
@RestController
@RequestMapping("/api/admin/posm/identity-claims")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','HQ_HEAD')")
class AdminIdentityClaimController(private val service: IdentityClaimAdminService) {
    @GetMapping
    fun list(@RequestParam(required = false) status: String?): List<IdentityClaimDto> = service.list(status)

    @PostMapping("/{id}/confirm")
    fun confirm(
        @PathVariable id: UUID,
        @Valid @RequestBody req: ConfirmIdentityClaimRequest,
        @AuthenticationPrincipal principal: AdminPrincipal?,
    ): IdentityClaimDto = service.confirm(id, req, requirePrincipal(principal))

    @PostMapping("/{id}/reject")
    fun reject(
        @PathVariable id: UUID,
        @Valid @RequestBody req: RejectIdentityClaimRequest,
        @AuthenticationPrincipal principal: AdminPrincipal?,
    ): IdentityClaimDto = service.reject(id, req, requirePrincipal(principal))

    private fun requirePrincipal(principal: AdminPrincipal?): AdminPrincipal = principal
        ?: throw AppException(ErrorCode.UNAUTHORIZED, "Not authenticated", HttpStatus.UNAUTHORIZED)
}
