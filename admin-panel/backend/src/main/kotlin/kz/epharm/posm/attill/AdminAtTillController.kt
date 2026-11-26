package kz.epharm.posm.attill

import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * «Экраны» → «Сейчас за кассой» (owner, 26.11.2026): the pharmacists who sit at a till right now. HQ only, like the
 * notices: it shows where each person is at this minute.
 */
@RestController
@RequestMapping("/api/admin/posm-at-till")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','HQ_HEAD')")
class AdminAtTillController(private val atTill: AtTillService) {
    /**
     * Everybody at a till now, by the till's pharmacy and the pharmacy name, then by name. Filters: [pharmacyId] the
     * pharmacy of the till, [q] any part of the name, the phone (digits), the IIN, the name or address of a pharmacy.
     */
    @GetMapping
    fun list(
        @RequestParam(required = false) pharmacyId: String?,
        @RequestParam(required = false) q: String?,
    ): ResponseEntity<AtTillListDto> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(atTill.list(pharmacyId, q))
}
