package kz.epharm.posm.self

import jakarta.validation.Valid
import kz.epharm.posm.service.PosmDeviceAuthenticationService
import kz.epharm.training.tasks.PharmacistTaskFeedDto
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * «Задания» in the till's personal ePharm window: what HQ asks the signed-in pharmacist to do (a course to take),
 * what they get for it and whether it is done. Device-key authenticated like every /api/posm call; the body is
 * the same as /api/posm/self. Read-only.
 */
@RestController
@RequestMapping("/api/posm/self/tasks")
class PosmSelfTasksController(
    private val deviceAuthentication: PosmDeviceAuthenticationService,
    private val service: PosmSelfTasksService,
) {
    @PostMapping
    fun tasks(
        @RequestHeader(name = "X-Posm-Key", required = false) key: String?,
        @Valid @RequestBody req: PosmSelfRequest,
    ): PharmacistTaskFeedDto = service.tasks(deviceAuthentication.authenticate(key, claimedPharmacyId = req.pharmacyId), req)
}
