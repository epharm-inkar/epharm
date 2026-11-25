package kz.epharm.posm.notice

import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import kz.epharm.auth.security.AdminPrincipal
import kz.epharm.posm.messages.PosmMessageService
import kz.epharm.posm.service.PosmDeviceAuthenticationService
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

data class PosmNoticeRequest(
    val maintenance: Boolean,
    val pilotLive: Boolean = true,
    @field:Size(max = 120) val title: String? = null,
    @field:Size(max = 600) val message: String? = null,
)

/**
 * «ePharm на технических работах». The till polls its view about once a minute (device key, like every /api/posm
 * call); HQ reads and switches it under /api/admin (HQ roles only).
 */
@RestController
class PosmNoticeController(
    private val deviceAuthentication: PosmDeviceAuthenticationService,
    private val notices: PosmNoticeService,
    private val messages: PosmMessageService,
) {
    /** The works switch for this till, plus when HQ last sent or withdrew a notice to pharmacists (read outside it). */
    @GetMapping("/api/posm/notice")
    fun tillNotice(@RequestHeader(name = "X-Posm-Key", required = false) key: String?): ResponseEntity<PosmTillNotice> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(notices.forTill(deviceAuthentication.authenticate(key)).copy(messagesAt = messages.changeStamp()))

    @GetMapping("/api/admin/posm-notice")
    fun adminNotice(): PosmNotice = notices.current()

    @PutMapping("/api/admin/posm-notice")
    fun updateNotice(
        @Valid @RequestBody req: PosmNoticeRequest,
        @AuthenticationPrincipal principal: AdminPrincipal?,
    ): PosmNotice = notices.update(req.maintenance, req.pilotLive, req.title, req.message, principal?.email)
}
