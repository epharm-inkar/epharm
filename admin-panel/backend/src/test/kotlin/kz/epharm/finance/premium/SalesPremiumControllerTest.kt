package kz.epharm.finance.premium

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.security.AdminPrincipal
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping

class SalesPremiumControllerTest {
    private val service = mockk<SalesPremiumService>()
    private val controller = SalesPremiumController(service)
    private val actor = AdminPrincipal(UUID.randomUUID(), "hq@example.test", "HQ", AdminRole.HQ_HEAD, "Inkar")

    private fun request(dryRun: Boolean) = SalesPremiumImportRequest(
        period = "2026-11", through = LocalDate.of(2026, 11, 25), dryRun = dryRun,
        rows = listOf(SalesPremiumRow("123456789012", BigDecimal("1000"), 2.0)),
    )

    private fun result(dryRun: Boolean) = SalesPremiumImportResult(
        period = "2026-11", through = LocalDate.of(2026, 11, 25), dryRun = dryRun,
        rows = PremiumTotals(1, 1000), matched = PremiumTotals(1, 1000), credited = PremiumTotals(1, 1000),
        corrections = PremiumCorrections(0, 0, 0), unchanged = 0, pending = PremiumPending(0, 0, 0),
        ambiguous = PremiumAmbiguous(0, 0, emptyList()), packsTotal = BigDecimal("2.00"),
        auditId = if (dryRun) null else UUID.randomUUID().toString(),
    )

    @Test
    fun `a dry run only previews and never reaches the writer`() {
        every { service.preview(any()) } returns result(dryRun = true)

        val response = controller.importPremium(request(dryRun = true), actor)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body!!.dryRun).isTrue()
        assertThat(response.body!!.auditId).isNull()
        verify(exactly = 1) { service.preview(any()) }
        verify(exactly = 0) { service.apply(any(), any()) }
    }

    @Test
    fun `a real import goes to the writer with the acting admin`() {
        every { service.apply(any(), actor) } returns result(dryRun = false)

        val response = controller.importPremium(request(dryRun = false), actor)

        assertThat(response.body!!.dryRun).isFalse()
        assertThat(response.body!!.auditId).isNotNull()
        verify(exactly = 1) { service.apply(any(), actor) }
        verify(exactly = 0) { service.preview(any()) }
        assertThat(response.headers.cacheControl).contains("no-store")
    }

    @Test
    fun `a file that does not say dryRun moves nothing, a missing flag is never read as false`() {
        val withoutFlag = request(dryRun = false).copy(dryRun = null)

        assertThatThrownBy { controller.importPremium(withoutFlag, actor) }
            .isInstanceOfSatisfying(AppException::class.java) { error ->
                assertThat(error.code).isEqualTo(ErrorCode.VALIDATION_FAILED)
                assertThat(error.status).isEqualTo(HttpStatus.BAD_REQUEST)
                assertThat(error.message).contains("dryRun")
            }
        verify(exactly = 0) { service.preview(any()) }
        verify(exactly = 0) { service.apply(any(), any()) }
    }

    @Test
    fun `a request without an authenticated admin is refused before anything runs`() {
        assertThatThrownBy { controller.importPremium(request(dryRun = false), null) }
            .isInstanceOfSatisfying(AppException::class.java) { error ->
                assertThat(error.code).isEqualTo(ErrorCode.UNAUTHORIZED)
                assertThat(error.status).isEqualTo(HttpStatus.UNAUTHORIZED)
            }
        verify(exactly = 0) { service.preview(any()) }
        verify(exactly = 0) { service.apply(any(), any()) }
    }

    @Test
    fun `only the head of the HQ and the system administrator may call the import`() {
        val method = SalesPremiumController::class.java.getMethod(
            "importPremium", SalesPremiumImportRequest::class.java, AdminPrincipal::class.java,
        )
        val roles = Regex("'([A-Z_]+)'").findAll(method.getAnnotation(PreAuthorize::class.java).value).map { it.groupValues[1] }.toSet()

        assertThat(roles).containsExactlyInAnyOrder("HQ_HEAD", "SYSTEM_ADMIN")
        assertThat(method.getAnnotation(PreAuthorize::class.java).value).startsWith("hasAnyRole(")
    }

    @Test
    fun `the route is the documented admin path`() {
        val base = SalesPremiumController::class.java.getAnnotation(RequestMapping::class.java).value.single()
        val method = SalesPremiumController::class.java.getMethod(
            "importPremium", SalesPremiumImportRequest::class.java, AdminPrincipal::class.java,
        )

        assertThat(base + method.getAnnotation(PostMapping::class.java).value.single())
            .isEqualTo("/api/admin/finance/sales-premium/import")
    }
}
