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
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping

class SalesPremiumOperationsControllerTest {
    private val collector = mockk<SalesPremiumCollector>()
    private val statement = mockk<SalesPremiumStatement>()
    private val matrix = mockk<SalesPremiumMatrixService>()
    private val controller = SalesPremiumOperationsController(collector, statement, matrix)
    private val actor = AdminPrincipal(UUID.randomUUID(), "hq@example.test", "HQ", AdminRole.HQ_HEAD, "Inkar")

    private fun result(dryRun: Boolean) = CollectorResult(
        period = "2026-11", through = LocalDate.of(2026, 11, 25), dryRun = dryRun, products = 188, productsWithSales = 150,
        packs = BigDecimal("56411"), sellers = 971, sellersWithPremium = 960, premiumKzt = 7_118_062,
        withoutIin = WithoutIin(12, BigDecimal("2100"), 247_366), negativeSellers = 0, imported = null, tookMillis = 4_000,
    )

    private fun uploaded(dryRun: Boolean) = MatrixUploadResult("2026-12", dryRun, 188, 187, 1, 0, 188, 0, 0, null)

    // ---- recompute ------------------------------------------------------------------------------------------------

    @Test
    fun `recompute runs the collector as the acting administrator and answers with totals`() {
        every { collector.collect("2026-11", true, PremiumActor.of(actor)) } returns result(dryRun = true)

        val response = controller.recompute("2026-11", true, actor)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body!!.sellers).isEqualTo(971)
        assertThat(response.headers.cacheControl).contains("no-store")
        verify(exactly = 1) { collector.collect("2026-11", true, PremiumActor.of(actor)) }
    }

    @Test
    fun `recompute without a period means the month of yesterday and a blank period too`() {
        every { collector.collect(null, false, any()) } returns result(dryRun = false)

        controller.recompute(null, false, actor)
        controller.recompute("  ", false, actor)

        verify(exactly = 2) { collector.collect(null, false, PremiumActor.of(actor)) }
    }

    @Test
    fun `recompute that does not say dryRun moves nothing, a missing flag is never read as false`() {
        assertThatThrownBy { controller.recompute("2026-11", null, actor) }
            .isInstanceOfSatisfying(AppException::class.java) { error ->
                assertThat(error.code).isEqualTo(ErrorCode.VALIDATION_FAILED)
                assertThat(error.status).isEqualTo(HttpStatus.BAD_REQUEST)
                assertThat(error.message).contains("dryRun")
            }
        verify(exactly = 0) { collector.collect(any(), any(), any()) }
    }

    @Test
    fun `recompute without an authenticated administrator is refused before anything runs`() {
        assertThatThrownBy { controller.recompute("2026-11", true, null) }
            .isInstanceOfSatisfying(AppException::class.java) { error ->
                assertThat(error.code).isEqualTo(ErrorCode.UNAUTHORIZED)
                assertThat(error.status).isEqualTo(HttpStatus.UNAUTHORIZED)
            }
        verify(exactly = 0) { collector.collect(any(), any(), any()) }
    }

    @Test
    fun `a closed Standard-N is a 503 for the administrator, with the text that explains it`() {
        every { collector.collect(any(), any(), any()) } throws StandardNSourceException("Standard-N did not open")

        assertThatThrownBy { controller.recompute("2026-11", true, actor) }
            .isInstanceOfSatisfying(AppException::class.java) { error ->
                assertThat(error.code).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE)
                assertThat(error.status).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                assertThat(error.message).contains("Стандарт-Н").contains("ночью")
                assertThat(error.message).doesNotContain("did not open")
            }
    }

    @Test
    fun `a calculation that is already running stays a 409`() {
        every { collector.collect(any(), any(), any()) } throws
            AppException(ErrorCode.CONFLICT, "Расчёт премии уже идёт", HttpStatus.CONFLICT)

        assertThatThrownBy { controller.recompute(null, true, actor) }
            .isInstanceOfSatisfying(AppException::class.java) { error -> assertThat(error.status).isEqualTo(HttpStatus.CONFLICT) }
    }

    // ---- statement ------------------------------------------------------------------------------------------------

    @Test
    fun `the statement is a downloadable UTF-8 CSV that is never cached`() {
        every { statement.rows("2026-11") } returns listOf(
            StatementRow("Иванова Анна", "050112345678", "Аптека №5", "Алматы", BigDecimal("12"), 3400, credited = true),
        )

        val response = controller.statementCsv("2026-11", actor)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.headers.contentType.toString()).isEqualTo("text/csv;charset=UTF-8")
        assertThat(response.headers.getFirst(HttpHeaders.CONTENT_DISPOSITION)).isEqualTo("attachment; filename=\"sales-premium-2026-11.csv\"")
        assertThat(response.headers.cacheControl).contains("no-store")
        val text = String(response.body!!, Charsets.UTF_8)
        assertThat(text).startsWith("﻿ФИО;ИИН;аптека;город;упаковок;начислено;статус\r\n").contains("Иванова Анна")
    }

    @Test
    fun `the statement of a month with nothing is the header alone`() {
        every { statement.rows("2026-10") } returns emptyList()

        val text = String(controller.statementCsv(" 2026-10 ", actor).body!!, Charsets.UTF_8)

        assertThat(text.removePrefix("﻿").trimEnd()).isEqualTo(SalesPremiumStatementCsv.HEADER)
    }

    @Test
    fun `the statement refuses a period that is not a month and an unknown administrator`() {
        assertThatThrownBy { controller.statementCsv("2026-13", actor) }
            .isInstanceOfSatisfying(AppException::class.java) { error ->
                assertThat(error.code).isEqualTo(ErrorCode.VALIDATION_FAILED)
                assertThat(error.message).contains("ГГГГ-ММ")
            }
        assertThatThrownBy { controller.statementCsv("2026-11", null) }
            .isInstanceOfSatisfying(AppException::class.java) { error -> assertThat(error.status).isEqualTo(HttpStatus.UNAUTHORIZED) }
        verify(exactly = 0) { statement.rows(any()) }
    }

    // ---- matrix upload --------------------------------------------------------------------------------------------

    @Test
    fun `a matrix upload goes to the service with the file as it came and the acting administrator`() {
        val csv = "ware_id,bonus_per_pack\n".toByteArray()
        every { matrix.upload("2026-12", csv, true, actor) } returns uploaded(dryRun = true)

        val response = controller.uploadMatrix("2026-12", true, csv, actor)

        assertThat(response.body!!.products).isEqualTo(188)
        assertThat(response.headers.cacheControl).contains("no-store")
        verify(exactly = 1) { matrix.upload("2026-12", csv, true, actor) }
    }

    @Test
    fun `a matrix upload that does not say dryRun replaces nothing`() {
        assertThatThrownBy { controller.uploadMatrix("2026-12", null, ByteArray(0), actor) }
            .isInstanceOfSatisfying(AppException::class.java) { error ->
                assertThat(error.code).isEqualTo(ErrorCode.VALIDATION_FAILED)
                assertThat(error.message).contains("dryRun")
            }
        verify(exactly = 0) { matrix.upload(any(), any(), any(), any()) }
    }

    // ---- who may call, which paths ---------------------------------------------------------------------------------

    private val methods = mapOf(
        "recompute" to SalesPremiumOperationsController::class.java.getMethod(
            "recompute", String::class.java, java.lang.Boolean::class.java, AdminPrincipal::class.java,
        ),
        "statementCsv" to SalesPremiumOperationsController::class.java.getMethod(
            "statementCsv", String::class.java, AdminPrincipal::class.java,
        ),
        "uploadMatrix" to SalesPremiumOperationsController::class.java.getMethod(
            "uploadMatrix", String::class.java, java.lang.Boolean::class.java, ByteArray::class.java, AdminPrincipal::class.java,
        ),
    )

    @Test
    fun `only the head of the HQ and the system administrator may call any of them`() {
        for ((name, method) in methods) {
            val expression = method.getAnnotation(PreAuthorize::class.java).value
            val roles = Regex("'([A-Z_]+)'").findAll(expression).map { it.groupValues[1] }.toSet()

            assertThat(roles).describedAs(name).containsExactlyInAnyOrder("HQ_HEAD", "SYSTEM_ADMIN")
            assertThat(expression).describedAs(name).startsWith("hasAnyRole(")
        }
    }

    @Test
    fun `the routes are the documented admin paths`() {
        val base = SalesPremiumOperationsController::class.java.getAnnotation(RequestMapping::class.java).value.single()

        assertThat(base + methods.getValue("recompute").getAnnotation(PostMapping::class.java).value.single())
            .isEqualTo("/api/admin/finance/sales-premium/recompute")
        assertThat(base + methods.getValue("statementCsv").getAnnotation(GetMapping::class.java).value.single())
            .isEqualTo("/api/admin/finance/sales-premium/statement.csv")
        assertThat(base + methods.getValue("uploadMatrix").getAnnotation(PostMapping::class.java).value.single())
            .isEqualTo("/api/admin/finance/sales-premium/matrix")
    }
}
