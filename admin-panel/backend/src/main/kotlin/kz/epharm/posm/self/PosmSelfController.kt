package kz.epharm.posm.self

import jakarta.validation.Valid
import kz.epharm.posm.service.PosmDeviceAuthenticationService
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The till's personal ePharm window (tray icon). Device-key authenticated like every /api/posm call; the person
 * is the Standard-N user signed in at this till. No pharmacist password is involved.
 */
@RestController
@RequestMapping("/api/posm/self")
class PosmSelfController(
    private val deviceAuthentication: PosmDeviceAuthenticationService,
    private val service: PosmSelfService,
) {
    @PostMapping
    fun view(
        @RequestHeader(name = "X-Posm-Key", required = false) key: String?,
        @Valid @RequestBody req: PosmSelfRequest,
    ): PosmSelfDto = service.view(deviceAuthentication.authenticate(key, claimedPharmacyId = req.pharmacyId), req)

    /** A fresh one-time link for the «Мой кабинет» button (the window's QR carries its own). */
    @PostMapping("/cabinet-link")
    fun cabinetLink(
        @RequestHeader(name = "X-Posm-Key", required = false) key: String?,
        @Valid @RequestBody req: PosmSelfRequest,
    ): PosmCabinetLinkDto = service.cabinetLink(deviceAuthentication.authenticate(key, claimedPharmacyId = req.pharmacyId), req)

    /** Phone and/or password typed by the pharmacist in the window (phone without SMS, decision 24.11). */
    @PostMapping("/contact")
    fun contact(
        @RequestHeader(name = "X-Posm-Key", required = false) key: String?,
        @Valid @RequestBody req: PosmSelfRequest,
    ): PosmSelfDto = service.saveContact(deviceAuthentication.authenticate(key, claimedPharmacyId = req.pharmacyId), req)
}
