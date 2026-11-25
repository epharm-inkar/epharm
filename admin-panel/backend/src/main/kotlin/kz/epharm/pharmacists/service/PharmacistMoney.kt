package kz.epharm.pharmacists.service

import kz.epharm.pharmacists.repository.PharmacistRepository
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * What each pharmacist sold and earned in the period (INK-156, Grigoriy 25.11.2026: «продажи и заработок каждого
 * в админке»). Receipts and their sum are the exact sales at the current pharmacy, refunds excluded, the same rule as
 * [currentReceiptCounts]; «earned» is what the wallet ledger credited in the period, not the stored running counter.
 */
data class PharmacistMoney(val receipts: Int, val sales: Long, val earned: Long)

/** Two bounded aggregates per response (sales, wallet), never one per row; read-only. */
fun PharmacistRepository.currentMoney(
    pharmacyByPerson: Map<String, String?>,
    observedAt: Instant = Instant.now(),
    days: Long = 30,
): Map<String, PharmacistMoney> {
    val ids = pharmacyByPerson.keys.filter { it.isNotBlank() }.toSet()
    if (ids.isEmpty()) return emptyMap()
    val since = observedAt.minus(days, ChronoUnit.DAYS)
    val assigned = pharmacyByPerson.filter { (id, pharmacy) -> id.isNotBlank() && !pharmacy.isNullOrBlank() }
    val sales = if (assigned.isEmpty()) emptyList()
        else salesTotals(assigned.keys, since, observedAt)
            // A reassignment between the roster and aggregate reads must not attach another pharmacy's receipts.
            .filter { assigned[it.pharmacistId] == it.pharmacyId }
    val earned = walletEarned(ids, since, observedAt).associate { it.pharmacistId to it.earned }
    val money = HashMap<String, PharmacistMoney>()
    for (row in sales) {
        money[row.pharmacistId] = PharmacistMoney(
            receipts = row.count.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
            sales = row.amount,
            earned = earned[row.pharmacistId] ?: 0,
        )
    }
    for ((id, credited) in earned) money.putIfAbsent(id, PharmacistMoney(receipts = 0, sales = 0, earned = credited))
    return money
}
