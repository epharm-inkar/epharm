package kz.epharm.posm.commands

import java.util.UUID
import kz.epharm.posm.service.PosmDeviceAuthenticationService
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

/**
 * Till side of remote commands. Device-key authenticated like every /api/posm call; the till is the authenticated
 * device (per-device key only), never an id from the request. The till runs only actions compiled into it.
 */
@RestController
@RequestMapping("/api/posm/commands")
class PosmTillCommandController(
    private val deviceAuthentication: PosmDeviceAuthenticationService,
    private val commands: TillCommandService,
) {
    /** 200 {id, action, expiresAt}: the oldest waiting command, now taken. 204: nothing to do. */
    @PostMapping("/next")
    fun next(@RequestHeader(name = "X-Posm-Key", required = false) key: String?): ResponseEntity<TillCommandDeliveryDto> {
        val command = commands.next(deviceAuthentication.authenticate(key))
            ?: return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build()
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(command)
    }

    /** multipart/form-data: status (done|failed), message (up to 2000 characters), optional file (ZIP, up to 5 MB). */
    @PostMapping("/{id}/result")
    fun result(
        @RequestHeader(name = "X-Posm-Key", required = false) key: String?,
        @PathVariable id: UUID,
        @RequestParam(name = "status", required = false) status: String?,
        @RequestParam(name = "message", required = false) message: String?,
        @RequestParam(name = "file", required = false) file: MultipartFile?,
    ): TillCommandResultDto = commands.result(deviceAuthentication.authenticate(key), id, status, message, file)
}
