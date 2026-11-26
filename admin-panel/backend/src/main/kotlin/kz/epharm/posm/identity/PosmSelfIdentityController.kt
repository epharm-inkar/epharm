package kz.epharm.posm.identity

import jakarta.validation.Valid
import kz.epharm.posm.service.PosmDeviceAuthenticationService
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * A Standard-N user without a card types their IIN and full name at the till (POSM 1.0.101). Device-key authenticated
 * like every /api/posm call; the person is the Standard-N user signed in at this till. The answer never says whether
 * the IIN belongs to someone else's card.
 */
@RestController
@RequestMapping("/api/posm/self/identity")
class PosmSelfIdentityController(
    private val deviceAuthentication: PosmDeviceAuthenticationService,
    private val service: IdentityClaimSubmissionService,
) {
    /** Where this user stands: known, unknown (show the form), pending or rejected (with the office's reason). */
    @PostMapping
    fun state(
        @RequestHeader(name = "X-Posm-Key", required = false) key: String?,
        @Valid @RequestBody req: PosmIdentityRequest,
    ): ResponseEntity<PosmIdentityDto> = noStore(
        service.tillState(deviceAuthentication.authenticate(key, claimedPharmacyId = req.pharmacyId), req),
    )

    /** IIN and full name typed in the personal window. Waits for HQ: nothing is credited before that. */
    @PostMapping("/claim")
    fun claim(
        @RequestHeader(name = "X-Posm-Key", required = false) key: String?,
        @Valid @RequestBody req: PosmIdentityRequest,
    ): ResponseEntity<PosmIdentityDto> = noStore(
        service.tillSubmit(deviceAuthentication.authenticate(key, claimedPharmacyId = req.pharmacyId), req),
    )

    private fun <T> noStore(body: T): ResponseEntity<T> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body)
}
