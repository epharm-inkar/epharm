package kz.epharm.posm.messages

import kz.epharm.auth.security.AdminPrincipal
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * «Экраны» → «Уведомления на кассах» (25.11.2026). HQ only: a notice reaches every chosen pharmacist's till, so the
 * business roles that may read the fleet cannot send one.
 */
@RestController
@RequestMapping("/api/admin/posm-messages")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','HQ_HEAD')")
class AdminPosmMessageController(private val messages: PosmMessageService) {
    /** Newest first, with how many people saw each notice on a till and how many closed it. */
    @GetMapping
    fun list(@RequestParam(required = false) limit: Int?): ResponseEntity<List<PosmMessageAdminDto>> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(messages.list(limit))

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @RequestBody request: CreatePosmMessageRequest,
        @AuthenticationPrincipal principal: AdminPrincipal?,
    ): PosmMessageAdminDto = messages.create(request, actor(principal))

    /** Takes the notice off the tills; what people already saw stays counted. */
    @PostMapping("/{id}/withdraw")
    fun withdraw(
        @PathVariable id: UUID,
        @AuthenticationPrincipal principal: AdminPrincipal?,
    ): PosmMessageAdminDto = messages.withdraw(id, actor(principal))

    private fun actor(principal: AdminPrincipal?): String =
        principal?.email ?: throw AppException(ErrorCode.UNAUTHORIZED, "Admin identity is required", HttpStatus.UNAUTHORIZED)
}
