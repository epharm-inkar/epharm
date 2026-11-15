package kz.epharm.posm.controller

import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kz.epharm.auth.security.AdminPrincipal
import kz.epharm.posm.service.RecommendationRewardReviewService
import kz.epharm.posm.service.RewardReviewDecision
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/admin/reconcile/recommendation-rewards")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','HQ_HEAD','FINANCE_REVIEWER')")
class RecommendationRewardReviewController(private val reviews: RecommendationRewardReviewService) {
    @GetMapping
    fun list(@RequestParam(required = false) state: String?, @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?, @RequestParam(defaultValue = "0") offset: Int,
        @RequestParam(defaultValue = "50") limit: Int): Map<String, Any> {
        if (limit !in 1..200) invalid("Размер страницы от 1 до 200")
        return reviews.list(state, time(from, false), time(to, true), offset, limit)
    }

    @GetMapping("/{id}")
    fun detail(@PathVariable id: String): Map<String, Any?> = reviews.detail(id)

    @PostMapping("/{id}/decision")
    fun decide(@PathVariable id: String, @RequestBody request: RewardReviewDecision,
        @AuthenticationPrincipal principal: AdminPrincipal?): Map<String, Any?> {
        val actor = principal?.userId?.toString() ?: throw AppException(ErrorCode.FORBIDDEN, "Требуется вход администратора", HttpStatus.FORBIDDEN)
        return reviews.decide(id, request, actor)
    }

    @GetMapping("/export.xlsx")
    fun export(@RequestParam(required = false) state: String?, @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?): ResponseEntity<ByteArray> {
        val page = reviews.list(state, time(from, false), time(to, true), 0, 10000)
        if ((page["total"] as Number).toLong() > 10000) invalid("Выберите более узкий период или состояние: экспорт ограничен 10 000 строками")
        @Suppress("UNCHECKED_CAST") val records = page["items"] as List<Map<String, Any?>>
        val fields = linkedMapOf("id" to "ID начисления", "sourceSaleId" to "ID продажи", "pharmacyName" to "Аптека",
            "pharmacyId" to "ID аптеки", "pharmacistName" to "Фармацевт", "pharmacistId" to "ID фармацевта",
            "productName" to "Товар", "productId" to "ID товара", "state" to "Состояние", "reasonCode" to "Причина",
            "unitBonus" to "Бонус за упаковку, ₸", "eligiblePackages" to "Подходящие упаковки", "totalBonus" to "Расчётный бонус, ₸",
            "budgetReserved" to "Бюджет использован", "receiptId" to "ID проверки чека", "createdAt" to "Создано UTC")
        val bytes = XSSFWorkbook().use { book ->
            val sheet = book.createSheet("Продажи по рекомендациям")
            sheet.createRow(0).apply { fields.values.forEachIndexed { i, title -> createCell(i).setCellValue(title) } }
            records.forEachIndexed { index, record -> sheet.createRow(index + 1).apply {
                fields.keys.forEachIndexed { col, key ->
                    val value = record[key]
                    val cell = createCell(col)
                    when (value) {
                        is Number -> if (value.toDouble() in -9007199254740991.0..9007199254740991.0) cell.setCellValue(value.toDouble()) else cell.setCellValue(value.toString())
                        is Boolean -> cell.setCellValue(value)
                        else -> cell.setCellValue(value?.toString().orEmpty()) // String cells never execute spreadsheet formulas.
                    }
                }
            } }
            sheet.createFreezePane(0, 1)
            fields.keys.indices.forEach { sheet.setColumnWidth(it, 24 * 256) }
            val meta = book.createSheet("Параметры")
            listOf("Состояние" to (state ?: "Все"), "Дата с (Алматы)" to from.orEmpty(), "Дата по (Алматы)" to to.orEmpty(),
                "Сформировано UTC" to Instant.now().toString(), "Методика" to "Добавлено за 0–120 секунд после фактического показа; оплата может быть позже. Расчёт и зачисление различаются.")
                .forEachIndexed { i, pair -> meta.createRow(i).apply { createCell(0).setCellValue(pair.first); createCell(1).setCellValue(pair.second) } }
            ByteArrayOutputStream().use { out -> book.write(out); out.toByteArray() }
        }
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"recommendation-rewards.xlsx\"")
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).body(bytes)
    }

    private fun time(value: String?, end: Boolean): Instant? {
        if (value.isNullOrBlank()) return null
        return try {
            if (value.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
                val date = LocalDate.parse(value).let { if (end) it.plusDays(1) else it }
                date.atStartOfDay(ZoneId.of("Asia/Almaty")).toInstant()
            } else Instant.parse(value)
        } catch (_: Exception) { invalid("Дата должна быть в формате ГГГГ-ММ-ДД или UTC ISO-8601") }
    }
    private fun invalid(message: String): Nothing = throw AppException(ErrorCode.VALIDATION_FAILED, message)
}
