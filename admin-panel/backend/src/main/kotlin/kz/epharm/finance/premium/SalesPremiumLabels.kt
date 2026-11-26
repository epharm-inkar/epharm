package kz.epharm.finance.premium

import java.time.YearMonth

/** How a premium entry of the wallet ledger is named for the pharmacist. */
object SalesPremiumLabels {
    const val PREMIUM = "Премия за продажи"
    const val CORRECTION = "Корректировка премии"

    private val MONTHS = listOf(
        "январь", "февраль", "март", "апрель", "май", "июнь",
        "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь",
    )

    /** «Премия за продажи, ноябрь 2026» for the context `premium:2026-11`; without a month just the name. */
    fun entryLabel(contextKey: String?): String = label(PREMIUM, contextKey)

    fun correctionLabel(contextKey: String?): String = label(CORRECTION, contextKey)

    private fun label(name: String, contextKey: String?): String {
        val month = SalesPremiumRules.periodOf(contextKey)?.let(YearMonth::parse) ?: return name
        return "$name, ${MONTHS[month.monthValue - 1]} ${month.year}"
    }
}
