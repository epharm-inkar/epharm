package kz.epharm.posm.identity

import jakarta.validation.Valid
import kz.epharm.mobile.auth.security.PharmacistPrincipal
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The cabinet profile: where the name on the card came from, and a full-name proposal for a card whose name has fewer
 * than two words. The proposal waits for HQ and yields to the Standard-N directory. Pharmacist JWT only.
 */
@RestController
@RequestMapping("/api/mobile/me/identity")
class MobileIdentityController(private val service: IdentityClaimSubmissionService) {
    @GetMapping
    fun state(@AuthenticationPrincipal principal: PharmacistPrincipal): ResponseEntity<MeIdentityDto> =
        noStore(service.cabinetState(principal.pharmacistId))

    @PostMapping("/name")
    fun propose(
        @AuthenticationPrincipal principal: PharmacistPrincipal,
        @Valid @RequestBody req: NameClaimRequest,
    ): ResponseEntity<MeIdentityDto> = noStore(service.cabinetPropose(principal.pharmacistId, req.fullName))

    private fun <T> noStore(body: T): ResponseEntity<T> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body)
}
