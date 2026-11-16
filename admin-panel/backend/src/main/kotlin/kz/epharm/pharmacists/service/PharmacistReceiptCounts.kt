package kz.epharm.pharmacists.service

import kz.epharm.pharmacists.repository.PharmacistRepository
import java.time.Instant
import java.time.temporal.ChronoUnit

/** One read-only aggregate for current displays and newly captured payout metadata, never a counter write. */
fun PharmacistRepository.currentReceiptCounts(
    pharmacyByPerson: Map<String, String?>,
    observedAt: Instant = Instant.now(),
): Map<String, Int> {
    val assigned = pharmacyByPerson.filter { (id, pharmacy) -> id.isNotBlank() && !pharmacy.isNullOrBlank() }
    if (assigned.isEmpty()) return emptyMap()
    return receiptCounts(assigned.keys, observedAt.minus(30, ChronoUnit.DAYS), observedAt)
        // A reassignment between the roster and aggregate reads must not attach another pharmacy's receipts.
        .filter { assigned[it.pharmacistId] == it.pharmacyId }
        .associate { it.pharmacistId to it.count.coerceIn(0, Int.MAX_VALUE.toLong()).toInt() }
}
