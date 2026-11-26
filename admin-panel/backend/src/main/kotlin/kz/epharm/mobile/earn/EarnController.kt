package kz.epharm.mobile.earn

import kz.epharm.mobile.auth.security.PharmacistPrincipal
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** The cabinet's «Бонусы» tab: only the signed-in pharmacist's own data and the shared leaderboards. */
@RestController
@RequestMapping("/api/mobile/me/earn")
class EarnController(private val service: EarnService) {
    @GetMapping("/products")
    fun products(
        @AuthenticationPrincipal principal: PharmacistPrincipal,
        @RequestParam(defaultValue = EarnService.SORT_SALES) sort: String,
        @RequestParam(defaultValue = "true") mine: Boolean,
    ) = privateResponse(service.products(principal.pharmacistId, sort, mine))

    @GetMapping("/products/{productId}")
    fun product(
        @AuthenticationPrincipal principal: PharmacistPrincipal,
        @PathVariable productId: String,
    ) = privateResponse(service.product(principal.pharmacistId, productId))

    @GetMapping("/summary")
    fun summary(
        @AuthenticationPrincipal principal: PharmacistPrincipal,
        @RequestParam(defaultValue = EarnService.PERIOD_TODAY) period: String,
    ) = privateResponse(service.summary(principal.pharmacistId, period))

    @GetMapping("/leaderboard")
    fun leaderboard(
        @AuthenticationPrincipal principal: PharmacistPrincipal,
        @RequestParam(defaultValue = EarnService.PERIOD_WEEK) period: String,
        @RequestParam(defaultValue = EarnService.SCOPE_PHARMACISTS) scope: String,
    ) = privateResponse(service.leaderboard(principal.pharmacistId, period, scope))

    private fun <T> privateResponse(body: T): ResponseEntity<T> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body)
}
