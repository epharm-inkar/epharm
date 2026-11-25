package kz.epharm.posm.messages

import jakarta.validation.Valid
import kz.epharm.posm.self.PosmSelfRequest
import kz.epharm.posm.service.PosmDeviceAuthenticationService
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Notices from HQ at the till (POSM 1.0.99): the till asks for the notices of the person signed in to Standard-N
 * at it and reports when one appeared and when the person closed it with ×. Device-key authenticated like every
 * /api/posm call; the body is the same as /api/posm/self (pharmacy and Standard-N user).
 */
@RestController
@RequestMapping("/api/posm/self/messages")
class PosmSelfMessagesController(
    private val deviceAuthentication: PosmDeviceAuthenticationService,
    private val messages: PosmMessageService,
) {
    @PostMapping
    fun messages(
        @RequestHeader(name = "X-Posm-Key", required = false) key: String?,
        @Valid @RequestBody req: PosmSelfRequest,
    ): ResponseEntity<PosmSelfMessagesDto> = ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .body(messages.forPerson(deviceAuthentication.authenticate(key, claimedPharmacyId = req.pharmacyId), req))

    @PostMapping("/{id}/shown")
    fun shown(
        @RequestHeader(name = "X-Posm-Key", required = false) key: String?,
        @PathVariable id: UUID,
        @Valid @RequestBody req: PosmSelfRequest,
    ): PosmSelfMessageAckDto =
        messages.markShown(deviceAuthentication.authenticate(key, claimedPharmacyId = req.pharmacyId), req, id)

    @PostMapping("/{id}/closed")
    fun closed(
        @RequestHeader(name = "X-Posm-Key", required = false) key: String?,
        @PathVariable id: UUID,
        @Valid @RequestBody req: PosmSelfRequest,
    ): PosmSelfMessageAckDto =
        messages.markClosed(deviceAuthentication.authenticate(key, claimedPharmacyId = req.pharmacyId), req, id)
}
