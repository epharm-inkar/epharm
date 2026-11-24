package kz.epharm.posm.service

import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import kz.epharm.posm.dto.*
import kz.epharm.posm.entity.PosSaleEntity
import kz.epharm.posm.entity.RecommendationEventEntity

data class RewardShowRecord(
    val event: RecommendationEventEntity,
    val evidence: RewardShownEvidence,
    val shownAt: Instant,
    val deviceId: String?,
    val autoEligible: Boolean,
    val promoId: String?,
)

data class RewardEvidenceDecision(
    val productId: String,
    val eventId: String?,
    val packages: Long = 0,
    val unitBonus: Long = 0,
    val reason: String,
) {
    val eligible: Boolean get() = reason == "qualified"
}

/**
 * Pure financial policy. Marketing attribution and fuzzy names are deliberately not evidence.
 *
 * [additionWindow]: how long after the actual display a recommended product may be added. Null (the
 * default since 24.11.2026, Grigoriy's decision) means the whole checkout: the product must be added
 * after the display, in the same checkout session and physical document, and stay in the paid receipt.
 */
class RecommendationRewardEvidencePolicy(private val additionWindow: Duration? = null) {
    fun completionProblem(sale: PosSaleEntity, evidence: RewardSaleEvidence): String? {
        val paid = evidence.completion
        if (evidence.version != 1 || evidence.checkoutSessionId != sale.sessionId) return "receipt_context_mismatch"
        if (!validIdentity(paid.identity) || sale.deviceId.isNullOrBlank()) return "source_identity_missing"
        if (paid.identity.identityStatus == "mismatch") return "employee_identity_mismatch"
        if (paid.status != "paid") return "payment_not_confirmed"
        if (paid.source != "standardn-posted-print" || sale.captureSource != "standardn-print-log" ||
            paid.documentType != null && paid.documentType != 3 || paid.documentStatus != 1 || !paid.finalDetailsRead || paid.sourceDocumentId <= 0 ||
            paid.sourceDocumentId != sale.sourceDocumentId || paid.deviceNumber.isBlank() ||
            paid.fiscalNumber <= 0 || paid.fiscalShift <= 0 || paid.signedTotal >= BigDecimal.ZERO ||
            sale.totalAmount <= 0 || paid.signedTotal.abs().compareTo(BigDecimal.valueOf(sale.totalAmount)) != 0 ||
            Duration.between(paid.completedAt, sale.printedAt).abs() > Duration.ofMillis(1)) return "payment_not_confirmed"
        if (paid.lines.isEmpty() || paid.lines.size != sale.items.size ||
            paid.lines.map { it.sourceLineId }.distinct().size != paid.lines.size) return "paid_lines_inconsistent"
        if (!validObservedLinks(paid.lines)) return "paid_line_link_inconsistent"
        for ((line, item) in paid.lines.zip(sale.items)) {
            if (line.sourceDocumentId != paid.sourceDocumentId || line.sourceLineId <= 0 ||
                !item.qty.isFinite() || line.qty.compareTo(BigDecimal.valueOf(item.qty)) != 0 || line.qty <= BigDecimal.ZERO ||
                line.signedQuantity >= BigDecimal.ZERO || line.signedQuantity.abs().compareTo(line.qty) != 0 ||
                line.signedPrice.compareTo(BigDecimal.valueOf(item.price)) != 0 || line.signedPrice < BigDecimal.ZERO ||
                line.signedAmount > BigDecimal.ZERO || line.signedAmount.abs().compareTo(BigDecimal.valueOf(item.total)) != 0 ||
                line.sku.orEmpty() != item.sku || line.barcode.orEmpty() != item.barcode.orEmpty()) return "paid_lines_inconsistent"
        }
        if (paid.lines.sumOf { it.signedAmount.abs() }.compareTo(paid.signedTotal.abs()) != 0) return "paid_total_inconsistent"
        if (!evidence.journalComplete) return "journal_incomplete"
        if (paid.completedAt.isAfter(Instant.now().plusSeconds(300))) return "evidence_in_future"
        return null
    }

    /** Returns only establish a review fence; they never use addition evidence to issue a new reward. */
    fun refundProblem(sale: PosSaleEntity, evidence: RewardSaleEvidence): String? {
        val returned = evidence.completion
        if (evidence.version != 1 || evidence.checkoutSessionId != sale.sessionId) return "refund_context_mismatch"
        if (!validIdentity(returned.identity) || sale.deviceId.isNullOrBlank() ||
            returned.identity.identityStatus == "mismatch" || sale.reportedPharmacistId != returned.identity.rawUserId) return "refund_source_unverified"
        if (returned.status != "refund" || returned.source != "standardn-posted-refund" || sale.captureSource != "standardn-db-refund" ||
            returned.documentType != 9 || returned.documentStatus != 1 || !returned.finalDetailsRead ||
            returned.sourceDocumentId <= 0 || returned.sourceDocumentId != sale.sourceDocumentId || returned.deviceNumber.isBlank() ||
            returned.fiscalNumber <= 0 || returned.fiscalShift <= 0 || returned.signedTotal <= BigDecimal.ZERO || sale.totalAmount <= 0 ||
            returned.signedTotal.compareTo(BigDecimal.valueOf(sale.totalAmount)) != 0 ||
            Duration.between(returned.completedAt, sale.printedAt).abs() > Duration.ofMillis(1)) return "refund_not_confirmed"
        if (returned.lines.isEmpty() || returned.lines.size != sale.items.size ||
            returned.lines.map { it.sourceLineId }.distinct().size != returned.lines.size || !validObservedLinks(returned.lines)) return "refund_lines_inconsistent"
        for ((line, item) in returned.lines.zip(sale.items)) {
            if (line.sourceDocumentId != returned.sourceDocumentId || line.sourceLineId <= 0 || !item.qty.isFinite() ||
                line.qty <= BigDecimal.ZERO || line.qty.compareTo(BigDecimal.valueOf(item.qty)) != 0 ||
                line.signedQuantity <= BigDecimal.ZERO || line.signedQuantity.compareTo(line.qty) != 0 ||
                line.signedPrice < BigDecimal.ZERO || line.signedPrice.compareTo(BigDecimal.valueOf(item.price)) != 0 ||
                line.signedAmount < BigDecimal.ZERO || line.signedAmount.compareTo(BigDecimal.valueOf(item.total)) != 0 ||
                line.sku.orEmpty() != item.sku || line.barcode.orEmpty() != item.barcode.orEmpty()) return "refund_lines_inconsistent"
        }
        if (returned.lines.sumOf { it.signedAmount }.compareTo(returned.signedTotal) != 0) return "refund_total_inconsistent"
        if (returned.completedAt.isAfter(Instant.now().plusSeconds(300))) return "refund_in_future"
        val original = returned.originalSourceDocumentId
        val fiscalParts = listOf(returned.originalDeviceNumber, returned.originalFiscalNumber, returned.originalFiscalShift)
        val fiscalPresent = fiscalParts.any { it != null }
        val fiscalValid = !returned.originalDeviceNumber.isNullOrBlank() && returned.originalDeviceNumber.length <= 128 &&
            returned.originalDeviceNumber.none(Char::isISOControl) && (returned.originalFiscalNumber ?: 0) > 0 && (returned.originalFiscalShift ?: 0) > 0
        if (original != null && (original <= 0 || original == returned.sourceDocumentId) || fiscalPresent && !fiscalValid) return "refund_original_reference_invalid"
        if (original == null && !fiscalValid) return "refund_original_reference_missing"
        return null
    }

    private fun validObservedLinks(lines: List<RewardPaidLine>): Boolean {
        if (lines.any { line ->
            if (line.observedSourceLineId == null) !line.lineLinkSource.isNullOrBlank()
            else line.observedSourceLineId <= 0 || line.lineLinkSource != "standardn-unique-final-line"
        }) return false
        val observed = lines.map { it.observedSourceLineId ?: it.sourceLineId }
        return observed.distinct().size == observed.size
    }

    fun evaluate(
        sale: PosSaleEntity,
        evidence: RewardSaleEvidence,
        shows: Map<String, RewardShowRecord>,
        resolve: (List<CartItemDto>) -> List<String?>,
    ): List<RewardEvidenceDecision> {
        val completionProblem = completionProblem(sale, evidence)
        val paid = evidence.completion
        val products = resolve(paid.lines.map { CartItemDto(it.sku, it.barcode, qty = it.qty.toDouble()) })
        val saleProducts = resolve(sale.items.map { CartItemDto(it.sku, it.barcode, it.name, it.qty) }).filterNotNull().distinct()
        fun denied(product: String, reason: String): RewardEvidenceDecision {
            val show = shows.values.filter { it.event.recommendSku == product }.maxByOrNull { it.shownAt }
            return RewardEvidenceDecision(product, show?.event?.id, unitBonus = show?.event?.bonus ?: 0, reason = reason)
        }
        if (completionProblem != null) return saleProducts.map { denied(it, completionProblem) }
        if (products.any { it == null }) return saleProducts.map { denied(it, "product_unresolved") }
        val changes = evidence.changes
        val invalidJournal = changes.size > 2048 || changes.firstOrNull()?.sequence?.let { it != 1L } == true ||
            changes.map { it.observationId }.distinct().size != changes.size ||
            changes.any { !id(it.observationId) || !id(it.generationId) || it.sequence <= 0 ||
                !sameIdentity(it.identity, paid.identity) || it.identity.identityStatus == "mismatch" ||
                it.sourceLineId <= 0 || it.sourceDocumentId <= 0 || it.beforeQty < BigDecimal.ZERO ||
                it.afterQty < BigDecimal.ZERO || it.afterQty > MAX_QUANTITY ||
                it.observedAt.isAfter(paid.completedAt) } ||
            changes.zipWithNext().any { (a, b) -> b.sequence != a.sequence + 1 || b.observedAt.isBefore(a.observedAt) }
        if (invalidJournal) return saleProducts.map { denied(it, "journal_inconsistent") }
        val changeProducts = resolve(changes.map { CartItemDto(it.sku, it.barcode, qty = it.afterQty.toDouble()) })
        val baselineProducts = shows.mapValues { (_, show) -> resolve(show.evidence.lines.map {
            CartItemDto(it.sku, it.barcode, qty = it.qty.toDouble())
        }) }
        return saleProducts.map { product ->
            val paidIndexes = products.indices.filter { products[it] == product }
            if (paidIndexes.any { paid.lines[it].unitSemantics != "full_package" || !whole(paid.lines[it].qty) })
                return@map denied(product, "package_unit_unverified")
            var quantity = 0L
            val used = linkedSetOf<RewardShowRecord>()
            var reason = "no_timely_addition"
            for (paidIndex in paidIndexes) {
                val final = paid.lines[paidIndex]
                val lineChanges = changes.indices.filter { changes[it].sourceDocumentId == final.sourceDocumentId &&
                    changes[it].sourceLineId == (final.observedSourceLineId ?: final.sourceLineId) }
                if (lineChanges.isEmpty()) continue
                var previous = BigDecimal.ZERO
                var generation: String? = null
                val lots = linkedMapOf<RewardShowRecord, Long>()
                val retiredGenerations = mutableSetOf<String>()
                var lineBroken = false
                for (index in lineChanges) {
                    val change = changes[index]
                    if (changeProducts[index] != product || change.unitSemantics != "full_package" ||
                        !whole(change.beforeQty) || !whole(change.afterQty) || change.beforeQty.compareTo(previous) != 0 ||
                        change.generationId in retiredGenerations ||
                        (generation != null && change.generationId != generation && previous > BigDecimal.ZERO)) {
                        lineBroken = true; break
                    }
                    generation = change.generationId
                    val delta = change.afterQty.subtract(change.beforeQty).longValueExact()
                    if (delta < 0) {
                        var remove = -delta
                        for ((offer, count) in lots.toMap()) {
                            val removed = minOf(remove, count); lots[offer] = count - removed; remove -= removed
                        }
                    }
                    if (delta > 0) {
                        val show = shows[change.eventId]
                        val problem = showProblem(show, sale, paid.identity, product, change.observedAt,
                            baselineProducts[change.eventId].orEmpty())
                        if (problem == null && show != null) lots[show] = Math.addExact(lots[show] ?: 0, delta)
                        else reason = problem ?: "shown_evidence_missing"
                    }
                    previous = change.afterQty
                    if (previous.signum() == 0) retiredGenerations += change.generationId
                }
                if (lineBroken || previous.compareTo(final.qty) != 0) {
                    return@map denied(product, "line_lifecycle_inconsistent")
                }
                val remaining = lots.filterValues { it > 0 }
                used += remaining.keys
                quantity = Math.addExact(quantity, remaining.values.sum().coerceAtMost(final.qty.longValueExact()))
            }
            if (quantity <= 0) return@map RewardEvidenceDecision(product, used.firstOrNull()?.event?.id, reason = reason)
            if (used.map { it.event.bonus to it.promoId }.distinct().size != 1)
                return@map RewardEvidenceDecision(product, null, reason = "offer_terms_conflict")
            val winner = used.maxBy { it.shownAt }
            try { Math.multiplyExact(quantity, winner.event.bonus) }
            catch (_: ArithmeticException) { return@map RewardEvidenceDecision(product, winner.event.id, reason = "amount_overflow") }
            RewardEvidenceDecision(product, winner.event.id, quantity, winner.event.bonus, "qualified")
        }
    }

    private fun showProblem(show: RewardShowRecord?, sale: PosSaleEntity, identity: RewardSourceIdentity,
        product: String, addedAt: Instant, baseline: List<String?>): String? {
        if (show == null) return "shown_evidence_missing"
        val event = show.event
        if (!show.autoEligible) return "before_activation"
        if (show.evidence.version != 1 || show.deviceId != sale.deviceId || event.pharmacyId != sale.pharmacyId ||
            event.sessionId != sale.sessionId || show.evidence.checkoutSessionId != sale.sessionId ||
            event.pharmacistId != sale.pharmacistId || !sameIdentity(show.evidence.identity, identity) ||
            show.evidence.identity.identityStatus == "mismatch") return "shown_context_mismatch"
        if (!show.evidence.cartComplete || baseline.size != show.evidence.lines.size || baseline.any { it == null } ||
            show.evidence.lines.any { it.sourceDocumentId <= 0 || it.sourceLineId <= 0 || it.qty < BigDecimal.ZERO || it.qty > MAX_QUANTITY } ||
            show.evidence.lines.map { it.sourceDocumentId to it.sourceLineId }.distinct().size != show.evidence.lines.size)
            return "baseline_incomplete"
        if (show.evidence.lines.indices.any { baseline[it] == product && show.evidence.lines[it].qty > BigDecimal.ZERO })
            return "product_preexisting"
        if (event.recommendSku != product || event.bonus <= 0 || event.expectedAmount <= 0 || show.promoId == null ||
            event.outcomeRaw in setOf("rejected", "expired")) return "offer_not_eligible"
        if (show.shownAt.isBefore(event.shownAt.minusSeconds(300)) || show.shownAt.isAfter(Instant.now().plusSeconds(300)))
            return "display_time_inconsistent"
        val duration = Duration.between(show.shownAt, addedAt)
        if (duration.isNegative || (additionWindow != null && duration > additionWindow)) return "addition_outside_window"
        return null
    }

    private fun whole(value: BigDecimal) = value >= BigDecimal.ZERO && value <= MAX_QUANTITY && value.stripTrailingZeros().scale() <= 0
    private fun validIdentity(value: RewardSourceIdentity) = value.sourceNamespace.matches(Regex("[a-f0-9]{64}")) &&
        value.rawUserId.isNotBlank() && value.rawUserId.length <= 128 && value.nativeSessionId > 0 &&
        value.workstationId.isNotBlank() && value.workstationId.length <= 128 && value.identityStatus in setOf("match", "mismatch", "unknown")
    private fun sameIdentity(a: RewardSourceIdentity, b: RewardSourceIdentity) = validIdentity(a) && validIdentity(b) &&
        a.sourceNamespace == b.sourceNamespace && a.rawUserId == b.rawUserId &&
        a.nativeSessionId == b.nativeSessionId && a.workstationId == b.workstationId
    private fun id(value: String) = value.matches(Regex("[A-Za-z0-9:_-]{1,64}"))
    private companion object { val MAX_QUANTITY = BigDecimal("1000000") }
}
