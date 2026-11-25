package kz.epharm.posm.commands

import java.util.UUID
import kz.epharm.auth.security.AdminPrincipal
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
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

/**
 * «Все кассы» → «Диагностика» / «Перезапуск» (25.11.2026). HQ only: a command reaches a till in a pharmacy, so the
 * business roles that may read the fleet cannot send one.
 */
@RestController
@RequestMapping("/api/admin/tills/commands")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','HQ_HEAD')")
class AdminTillCommandController(private val commands: TillCommandService) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @RequestBody request: CreateTillCommandRequest,
        @AuthenticationPrincipal principal: AdminPrincipal?,
    ): TillCommandDto = commands.create(
        request,
        principal?.email ?: throw AppException(ErrorCode.UNAUTHORIZED, "Admin identity is required", HttpStatus.UNAUTHORIZED),
    )

    /** Newest first; without filters, the latest commands of all tills (the fleet table takes the latest per till). */
    @GetMapping
    fun list(
        @RequestParam(required = false) pharmacyId: String?,
        @RequestParam(required = false) deviceId: String?,
        @RequestParam(required = false) limit: Int?,
    ): ResponseEntity<List<TillCommandDto>> = ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(commands.list(pharmacyId, deviceId, limit))

    @GetMapping("/{id}/artifact")
    fun artifact(@PathVariable id: UUID): ResponseEntity<ByteArray> {
        val archive = commands.artifact(id)
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/zip"))
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(archive.fileName).build().toString(),
            )
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .contentLength(archive.bytes.size.toLong())
            .body(archive.bytes)
    }
}
