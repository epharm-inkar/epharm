package kz.epharm.posm

import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import kz.epharm.posm.dto.*
import kz.epharm.posm.entity.PosSaleEntity
import kz.epharm.posm.entity.PosSaleItem
import kz.epharm.posm.entity.RecommendationEventEntity
import kz.epharm.posm.service.RecommendationRewardEvidencePolicy
import kz.epharm.posm.service.RewardEvidenceDecision
import kz.epharm.posm.service.RewardShowRecord
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** Pure monetary policy: no Spring, database, HTTP, device credentials or real personal data. */
class RecommendationRewardEvidencePolicyTest {
    private val policy = RecommendationRewardEvidencePolicy()
    /** The pre-24.11 behaviour, still available as a configured window: its unit accounting stays covered. */
    private val windowed = RecommendationRewardEvidencePolicy(Duration.ofSeconds(120))
    private val at = Instant.now().minusSeconds(3600)
    private val identity = RewardSourceIdentity("a".repeat(64), "native-user-17", 80, "workstation-9", "unknown")
    private val barcode = "4600000000012"
    private val product = "catalogue-product-1"
    private fun bd(value: Long) = BigDecimal.valueOf(value)
    private fun cart(qty: Long = 1, document: Long = 100, line: Long = 501) = RewardCartLine(
        sourceDocumentId = document, sourceLineId = line, sku = "10", barcode = barcode, qty = bd(qty), unitSemantics = "full_package",
    )
    private fun show(id: String = "event-1", bonus: Long = 200, promo: String = "promo-1",
        baseline: List<RewardCartLine> = emptyList(), shownAt: Instant = at) = RewardShowRecord(
        event = RecommendationEventEntity(id = id, sessionId = "checkout-1", pharmacistId = "mapped-pharmacist-1",
            pharmacyId = "pharmacy-1", ruleId = "rule-1", recommendSku = product, bonus = bonus, expectedAmount = 100,
            shownAt = shownAt.minusSeconds(1)),
        evidence = RewardShownEvidence(checkoutSessionId = "checkout-1", observationId = "show-$id",
            identity = identity, cartComplete = true, lines = baseline),
        shownAt = shownAt, deviceId = "individual-device-1", autoEligible = true, promoId = promo,
    )
    private fun change(sequence: Long = 1, before: Long = 0, after: Long = 1, seconds: Long = 10,
        event: String = "event-1", generation: String = "generation-1", line: Long = 501, document: Long = 100) = RewardLineChange(
        observationId = "observation-$sequence", eventId = event, generationId = generation, sequence = sequence,
        observedAt = at.plusSeconds(seconds), identity = identity, sourceDocumentId = document, sourceLineId = line,
        sku = "10", barcode = barcode, beforeQty = bd(before), afterQty = bd(after), unitSemantics = "full_package",
    )
    private fun sale(qty: Long = 1, completedAt: Instant = at.plusSeconds(600)) = PosSaleEntity(
        id = "synthetic-sale-1", sessionId = "checkout-1", pharmacistId = "mapped-pharmacist-1", pharmacyId = "pharmacy-1",
        sourceDocumentId = 100, captureSource = "standardn-print-log", totalAmount = qty * 100,
        items = listOf(PosSaleItem(sku = "10", barcode = barcode, name = "Synthetic product", qty = qty.toDouble(), price = 100, total = qty * 100)),
        printedAt = completedAt, deviceId = "individual-device-1",
    )
    private fun evidence(qty: Long = 1, changes: List<RewardLineChange> = listOf(change()),
        completedAt: Instant = at.plusSeconds(600)) = RewardSaleEvidence(
        checkoutSessionId = "checkout-1", journalComplete = true, changes = changes,
        completion = RewardCompletionEvidence(status = "paid", source = "standardn-posted-print", completedAt = completedAt,
            identity = identity, sourceDocumentId = 100, documentStatus = 1, finalDetailsRead = true, signedTotal = bd(-qty * 100),
            deviceNumber = "synthetic-register", fiscalNumber = 900, fiscalShift = 4,
            lines = listOf(RewardPaidLine(sourceDocumentId = 100, sourceLineId = 501, sku = "10", barcode = barcode,
                qty = bd(qty), signedQuantity = bd(-qty), signedPrice = bd(100), signedAmount = bd(-qty * 100), unitSemantics = "full_package"))),
    )
    private fun resolve(lines: List<CartItemDto>): List<String?> = lines.map { line ->
        // Exact deterministic barcode AND local-SKU mapping. No name/fuzzy or nearest-product fallback.
        when {
            line.barcode == barcode && line.sku == "10" -> product
            line.barcode == "4600000000029" && line.sku == "20" -> "catalogue-trigger"
            else -> null
        }
    }
    private fun decide(sale: PosSaleEntity = sale(), evidence: RewardSaleEvidence = evidence(),
        shows: List<RewardShowRecord> = listOf(show())): RewardEvidenceDecision =
        policy.evaluate(sale, evidence, shows.associateBy { it.event.id }, ::resolve).single()

    @ParameterizedTest
    @ValueSource(longs = [-1, 0, 119, 120, 121, 1800])
    fun `any addition after the display in the same checkout is eligible, never one before it`(seconds: Long) {
        val paidAt = at.plusSeconds(maxOf(seconds, 0) + 60)
        val decision = decide(sale(completedAt = paidAt), evidence(changes = listOf(change(seconds = seconds)), completedAt = paidAt))
        assertEquals(seconds >= 0, decision.eligible)
        if (decision.eligible) assertEquals(1L, decision.packages) else assertEquals("addition_outside_window", decision.reason)
    }

    @Test
    fun `a configured addition window still cuts off one nanosecond after it`() {
        val windowed = RecommendationRewardEvidencePolicy(java.time.Duration.ofSeconds(120))
        fun decideWith(change: RewardLineChange) =
            windowed.evaluate(sale(), evidence(changes = listOf(change)), listOf(show()).associateBy { it.event.id }, ::resolve).single()
        assertTrue(decideWith(change(seconds = 120)).eligible)
        assertEquals("addition_outside_window", decideWith(change().copy(observedAt = at.plusSeconds(120).plusNanos(1))).reason)
    }

    @ParameterizedTest
    @ValueSource(longs = [100, 101])
    fun `recommended product preexisting anywhere in logical checkout excludes automatic credit`(document: Long) {
        val baseline = listOf(cart(document = document, line = 900))
        val result = decide(shows = listOf(show(baseline = baseline)))
        assertFalse(result.eligible)
        assertEquals("product_preexisting", result.reason)
    }

    @ParameterizedTest
    @ValueSource(strings = ["flag", "unresolved", "negative", "physical-id", "duplicate"])
    fun `unknown or invalid baseline never trusts a claimed complete flag`(problem: String) {
        val other = cart().copy(sku = "20", barcode = "4600000000029")
        val original = show(baseline = listOf(other))
        val shown = when (problem) {
            "flag" -> original.copy(evidence = original.evidence.copy(cartComplete = false))
            "unresolved" -> original.copy(evidence = original.evidence.copy(lines = listOf(other.copy(barcode = "unresolved"))))
            "negative" -> original.copy(evidence = original.evidence.copy(lines = listOf(other.copy(qty = bd(-1)))))
            "physical-id" -> original.copy(evidence = original.evidence.copy(lines = listOf(other.copy(sourceLineId = 0))))
            else -> original.copy(evidence = original.evidence.copy(lines = listOf(other, other)))
        }
        assertFalse(decide(shows = listOf(shown)).eligible)
    }

    @Test
    fun `unrelated baseline trigger needs exact identity but not a package-unit claim`() {
        val trigger = cart().copy(sku = "20", barcode = "4600000000029", unitSemantics = "unknown")
        assertTrue(decide(shows = listOf(show(baseline = listOf(trigger)))).eligible)
    }

    @ParameterizedTest
    @ValueSource(strings = ["show-user", "show-session", "show-workstation", "show-namespace", "show-mismatch", "add-user", "add-session", "add-mismatch", "paid-mismatch"])
    fun `all three source snapshots must agree and known RPS mismatch always needs HQ`(problem: String) {
        var shown = show(); var added = change(); var paid = evidence()
        when (problem) {
            "show-user" -> shown = shown.copy(evidence = shown.evidence.copy(identity = identity.copy(rawUserId = "another-user")))
            "show-session" -> shown = shown.copy(evidence = shown.evidence.copy(identity = identity.copy(nativeSessionId = 81)))
            "show-workstation" -> shown = shown.copy(evidence = shown.evidence.copy(identity = identity.copy(workstationId = "another-workstation")))
            "show-namespace" -> shown = shown.copy(evidence = shown.evidence.copy(identity = identity.copy(sourceNamespace = "b".repeat(64))))
            "show-mismatch" -> shown = shown.copy(evidence = shown.evidence.copy(identity = identity.copy(identityStatus = "mismatch")))
            "add-user" -> added = added.copy(identity = identity.copy(rawUserId = "another-user"))
            "add-session" -> added = added.copy(identity = identity.copy(nativeSessionId = 81))
            "add-mismatch" -> added = added.copy(identity = identity.copy(identityStatus = "mismatch"))
            "paid-mismatch" -> paid = paid.copy(completion = paid.completion.copy(identity = identity.copy(identityStatus = "mismatch")))
        }
        assertFalse(decide(evidence = paid.copy(changes = listOf(added)), shows = listOf(shown)).eligible)
    }

    @ParameterizedTest
    @ValueSource(strings = ["device", "pharmacy", "checkout", "employee", "activation", "promo", "bonus", "rejected", "expired"])
    fun `show also requires the same authenticated device actor pharmacy checkout and eligible offer`(problem: String) {
        val original = show()
        val changed = when (problem) {
            "device" -> original.copy(deviceId = "another-device")
            "pharmacy" -> original.also { it.event.pharmacyId = "another-pharmacy" }
            "checkout" -> original.copy(evidence = original.evidence.copy(checkoutSessionId = "another-checkout"))
            "employee" -> original.also { it.event.pharmacistId = "another-employee" }
            "activation" -> original.copy(autoEligible = false)
            "promo" -> original.copy(promoId = null)
            "bonus" -> original.also { it.event.bonus = 0 }
            else -> original.also { it.event.outcomeRaw = problem }
        }
        assertFalse(decide(shows = listOf(changed)).eligible)
    }

    @ParameterizedTest
    @ValueSource(strings = ["state", "source", "document", "posted", "details", "device", "number", "shift", "total", "signed-quantity", "signed-price", "signed-amount", "line-document", "zero-line", "barcode", "time"])
    fun `payment requires posted signed physical document and exact sale body`(problem: String) {
        val input = evidence(); val paid = input.completion; val line = paid.lines.single()
        val invalid = when (problem) {
            "state" -> paid.copy(status = "unknown")
            "source" -> paid.copy(source = "PrintCheque-command")
            "document" -> paid.copy(sourceDocumentId = 101)
            "posted" -> paid.copy(documentStatus = 0)
            "details" -> paid.copy(finalDetailsRead = false)
            "device" -> paid.copy(deviceNumber = "")
            "number" -> paid.copy(fiscalNumber = 0)
            "shift" -> paid.copy(fiscalShift = 0)
            "total" -> paid.copy(signedTotal = bd(-101))
            "signed-quantity" -> paid.copy(lines = listOf(line.copy(signedQuantity = bd(1))))
            "signed-price" -> paid.copy(lines = listOf(line.copy(signedPrice = bd(-100))))
            "signed-amount" -> paid.copy(lines = listOf(line.copy(signedAmount = bd(100))))
            "line-document" -> paid.copy(lines = listOf(line.copy(sourceDocumentId = 101)))
            "zero-line" -> paid.copy(lines = listOf(line.copy(sourceLineId = 0)))
            "barcode" -> paid.copy(lines = listOf(line.copy(barcode = "4600000000029")))
            else -> paid.copy(completedAt = paid.completedAt.plusMillis(2))
        }
        assertFalse(decide(evidence = input.copy(completion = invalid)).eligible)
    }

    @Test
    fun `removal consumes earlier eligibility and readd uses a new generation`() {
        val changes = listOf(change(seconds = 10), change(2, 1, 0, 20), change(3, 0, 1, 119, generation = "generation-2"))
        val result = decide(evidence = evidence(changes = changes))
        assertTrue(result.eligible); assertEquals(1L, result.packages)
        val noRemaining = changes.dropLast(1)
        assertFalse(decide(sale(0), evidence(0, noRemaining)).eligible)
    }

    @Test
    fun `removed line cannot reuse its old generation to claim continuity`() {
        val changes = listOf(change(seconds = 10), change(2, 1, 0, 20), change(3, 0, 1, 30))
        assertFalse(decide(evidence = evidence(changes = changes)).eligible)
    }

    @Test
    fun `refund proof never becomes automatic new credit even with an original document reference`() {
        val input = evidence()
        val refund = input.copy(completion = input.completion.copy(status = "refund", originalSourceDocumentId = 100,
            originalFiscalNumber = 900, originalFiscalShift = 4))
        assertFalse(decide(evidence = refund).eligible)
    }

    private fun decideWindowed(sale: PosSaleEntity, evidence: RewardSaleEvidence): RewardEvidenceDecision =
        windowed.evaluate(sale, evidence, listOf(show()).associateBy { it.event.id }, ::resolve).single()

    @Test
    fun `with a configured window quantity is bounded by timely additions and a late increase is not rewarded`() {
        val result = decideWindowed(sale(3), evidence(3, listOf(change(after = 2, seconds = 10), change(2, 2, 3, 121))))
        assertTrue(result.eligible); assertEquals(2L, result.packages)
    }

    @Test
    fun `without a window a later increase in the same checkout is rewarded too`() {
        val result = decide(sale(3), evidence(3, listOf(change(after = 2, seconds = 10), change(2, 2, 3, 121))))
        assertTrue(result.eligible); assertEquals(3L, result.packages)
    }

    @Test
    fun `with a configured window decreases consume qualified units before unqualified units conservatively`() {
        val result = decideWindowed(sale(2), evidence(2, listOf(change(seconds = 10), change(2, 1, 3, 121), change(3, 3, 2, 122))))
        assertFalse(result.eligible)
        assertEquals(0L, result.packages)
    }

    @Test
    fun `repeat observation cannot duplicate reward and two shows cannot multiply sold quantity`() {
        val repeated = change()
        assertFalse(decide(evidence = evidence(changes = listOf(repeated, repeated))).eligible)
        val second = show("event-2", shownAt = at.plusSeconds(20))
        val result = decide(sale(2), evidence(2, listOf(change(seconds = 10), change(2, 1, 2, 30, event = "event-2"))), listOf(show(), second))
        assertTrue(result.eligible); assertEquals(2L, result.packages); assertEquals(200L, result.unitBonus)
    }

    @Test
    fun `different terms for remaining units require HQ but fully removed terms do not contaminate winner`() {
        val second = show("event-2", bonus = 300, promo = "promo-2", shownAt = at.plusSeconds(20))
        val mixed = listOf(change(seconds = 10), change(2, 1, 2, 30, event = "event-2"))
        assertEquals("offer_terms_conflict", decide(sale(2), evidence(2, mixed), listOf(show(), second)).reason)
        val replaced = listOf(change(seconds = 10), change(2, 1, 0, 15), change(3, 0, 1, 30, event = "event-2", generation = "generation-2"))
        val result = decide(evidence = evidence(changes = replaced), shows = listOf(show(), second))
        assertTrue(result.eligible); assertEquals(1L, result.packages); assertEquals(300L, result.unitBonus); assertEquals("event-2", result.eventId)
    }

    @Test
    fun `bonus multiplication overflow requires review instead of wraparound or throwing`() {
        val result = decide(sale(2), evidence(2, listOf(change(after = 2))), listOf(show(bonus = Long.MAX_VALUE)))
        assertFalse(result.eligible); assertEquals("amount_overflow", result.reason)
    }

    @ParameterizedTest
    @ValueSource(strings = ["document", "line", "product"])
    fun `addition cannot move into a different paid physical line or product`(problem: String) {
        val input = when (problem) {
            "document" -> change(document = 101)
            "line" -> change(line = 502)
            else -> change().copy(sku = "20", barcode = "4600000000029")
        }
        assertFalse(decide(evidence = evidence(changes = listOf(input))).eligible)
    }

    @ParameterizedTest
    @ValueSource(strings = ["unknown", "tablet", "fractional"])
    fun `unknown unit or fractional sale must not be rounded into packages`(unit: String) {
        val input = evidence()
        val line = input.completion.lines.single()
        val paid = if (unit == "fractional") input.completion.copy(signedTotal = bd(-150), lines = listOf(line.copy(qty = BigDecimal("1.5"), signedQuantity = BigDecimal("-1.5"), signedAmount = bd(-150))))
            else input.completion.copy(lines = listOf(line.copy(unitSemantics = unit)))
        val body = sale()
        if (unit == "fractional") { body.totalAmount = 150; body.items = listOf(body.items.single().copy(qty = 1.5, total = 150)) }
        assertFalse(decide(body, input.copy(completion = paid)).eligible)
    }

    @Test fun `only a missing legacy or explicit sale document type may claim payment`() {
        val input = evidence()
        assertTrue(decide(evidence = input.copy(completion = input.completion.copy(documentType = null))).eligible)
        assertTrue(decide(evidence = input.copy(completion = input.completion.copy(documentType = 3))).eligible)
        for (type in listOf(0, 9, -1)) assertFalse(decide(evidence = input.copy(completion = input.completion.copy(documentType = type))).eligible)
    }

    @Test fun `recognized same document posted line link retains the real ID and matches the observed lifecycle`() {
        val input = evidence()
        val line = input.completion.lines.single().copy(sourceLineId = 801, observedSourceLineId = 501,
            lineLinkSource = "standardn-unique-final-line")
        val result = decide(evidence = input.copy(completion = input.completion.copy(lines = listOf(line))))
        assertTrue(result.eligible); assertEquals(1L, result.packages)
        assertEquals(801L, line.sourceLineId)
    }

    @ParameterizedTest
    @ValueSource(strings = ["unknown-source", "missing-id", "zero-id", "negative-id", "cross-document"])
    fun `unproved or invalid posted line aliases need review`(problem: String) {
        val input = evidence()
        val valid = input.completion.lines.single().copy(sourceLineId = 801, observedSourceLineId = 501,
            lineLinkSource = "standardn-unique-final-line")
        val changed = when (problem) {
            "unknown-source" -> valid.copy(lineLinkSource = "best-guess")
            "missing-id" -> valid.copy(observedSourceLineId = null)
            "zero-id" -> valid.copy(observedSourceLineId = 0)
            "negative-id" -> valid.copy(observedSourceLineId = -1)
            else -> valid.copy(sourceDocumentId = 101)
        }
        assertFalse(decide(evidence = input.copy(completion = input.completion.copy(lines = listOf(changed)))).eligible)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `aliased observed IDs must be unique including collisions with a direct line`(direct: Boolean) {
        val body = sale(2).also { it.items = List(2) { sale().items.single() } }
        val input = evidence(2, changes = listOf(change(), change(sequence = 2, line = 502)))
        val line = evidence().completion.lines.single()
        val first = if (direct) line else line.copy(sourceLineId = 801, observedSourceLineId = 501, lineLinkSource = "standardn-unique-final-line")
        val second = line.copy(sourceLineId = 802, observedSourceLineId = 501, lineLinkSource = "standardn-unique-final-line")
        assertEquals("paid_line_link_inconsistent", decide(body, input.copy(completion = input.completion.copy(lines = listOf(first, second)))).reason)
    }

    @Test fun `overlap between posted and observed ID domains is a valid one to one mapping`() {
        val body = sale(3).also { it.items = listOf(sale().items.single(), sale(2).items.single()) }
        val input = evidence(3, changes = listOf(change(line = 10), change(sequence = 2, line = 20, after = 2)))
        val one = evidence().completion.lines.single().copy(sourceLineId = 20, observedSourceLineId = 10, lineLinkSource = "standardn-unique-final-line")
        val two = evidence(2).completion.lines.single().copy(sourceLineId = 30, observedSourceLineId = 20, lineLinkSource = "standardn-unique-final-line")
        val result = decide(body, input.copy(completion = input.completion.copy(lines = listOf(one, two))))
        assertTrue(result.eligible); assertEquals(3L, result.packages)
    }

    private fun refundInput(): Pair<PosSaleEntity, RewardSaleEvidence> {
        val body = sale().also { it.captureSource = "standardn-db-refund"; it.reportedPharmacistId = identity.rawUserId }
        val input = evidence()
        val complete = input.completion.copy(status = "refund", source = "standardn-posted-refund", documentType = 9,
            signedTotal = bd(100), originalSourceDocumentId = 99,
            lines = input.completion.lines.map { it.copy(signedQuantity = bd(1), signedAmount = bd(100)) })
        return body to input.copy(journalComplete = false, changes = emptyList(), completion = complete)
    }

    @Test fun `posted positive type nine return is independent of recommendation journal and supports proven original register`() {
        val (body, input) = refundInput()
        assertNull(policy.refundProblem(body, input))
        val fiscalOnly = input.copy(completion = input.completion.copy(originalSourceDocumentId = null,
            originalDeviceNumber = "original-register", originalFiscalNumber = 700, originalFiscalShift = 3))
        assertNull(policy.refundProblem(body, fiscalOnly))
        assertFalse(decide(body, input).eligible)
    }

    @ParameterizedTest
    @ValueSource(strings = ["version", "checkout", "identity", "actor", "mismatch", "source", "capture", "type-missing", "type", "posted", "details",
        "document", "signed-total", "quantity", "price", "amount", "fiscal", "sum", "future", "zero-original", "self-original", "fiscal-no-register", "no-original"])
    fun `malformed unknown and unposted return cannot establish an original receipt hold`(problem: String) {
        val (body, input) = refundInput()
        val c = input.completion
        var changed = input
        when (problem) {
            "version" -> changed = input.copy(version = 2)
            "checkout" -> changed = input.copy(checkoutSessionId = "another-checkout")
            "identity" -> changed = input.copy(completion = c.copy(identity = identity.copy(sourceNamespace = "")))
            "actor" -> body.reportedPharmacistId = "another-source-user"
            "mismatch" -> changed = input.copy(completion = c.copy(identity = identity.copy(identityStatus = "mismatch")))
            "source" -> changed = input.copy(completion = c.copy(source = "standardn-posted-print"))
            "capture" -> body.captureSource = "standardn-print-log"
            "type-missing" -> changed = input.copy(completion = c.copy(documentType = null))
            "type" -> changed = input.copy(completion = c.copy(documentType = 3))
            "posted" -> changed = input.copy(completion = c.copy(documentStatus = 0))
            "details" -> changed = input.copy(completion = c.copy(finalDetailsRead = false))
            "document" -> changed = input.copy(completion = c.copy(sourceDocumentId = 101))
            "signed-total" -> changed = input.copy(completion = c.copy(signedTotal = bd(-100)))
            "quantity" -> changed = input.copy(completion = c.copy(lines = c.lines.map { it.copy(signedQuantity = bd(-1)) }))
            "price" -> changed = input.copy(completion = c.copy(lines = c.lines.map { it.copy(signedPrice = bd(-100)) }))
            "amount" -> changed = input.copy(completion = c.copy(lines = c.lines.map { it.copy(signedAmount = bd(-100)) }))
            "fiscal" -> changed = input.copy(completion = c.copy(fiscalNumber = 0))
            "sum" -> { body.totalAmount = 200; changed = input.copy(completion = c.copy(signedTotal = bd(200))) }
            "future" -> { val at = Instant.now().plusSeconds(400); body.printedAt = at; changed = input.copy(completion = c.copy(completedAt = at)) }
            "zero-original" -> changed = input.copy(completion = c.copy(originalSourceDocumentId = 0))
            "self-original" -> changed = input.copy(completion = c.copy(originalSourceDocumentId = c.sourceDocumentId))
            "fiscal-no-register" -> changed = input.copy(completion = c.copy(originalFiscalNumber = 700, originalFiscalShift = 3))
            else -> changed = input.copy(completion = c.copy(originalSourceDocumentId = null))
        }
        assertNotNull(policy.refundProblem(body, changed))
    }

    @ParameterizedTest
    @ValueSource(strings = ["complete-flag", "first-sequence", "gap", "duplicate-sequence", "reverse-time", "future-change", "unknown-generation", "missing-identity", "oversized", "broken-before"])
    fun `partial or inconsistent journal never claims automatic money`(problem: String) {
        val input = evidence()
        val invalid = when (problem) {
            "complete-flag" -> input.copy(journalComplete = false)
            "first-sequence" -> input.copy(changes = listOf(change(sequence = 2)))
            "gap" -> evidence(2, listOf(change(), change(3, 1, 2, 20)))
            "duplicate-sequence" -> evidence(2, listOf(change(), change(1, 1, 2, 20).copy(observationId = "another-observation")))
            "reverse-time" -> evidence(2, listOf(change(seconds = 20), change(2, 1, 2, 10)))
            "future-change" -> input.copy(changes = listOf(change().copy(observedAt = input.completion.completedAt.plusNanos(1))))
            "unknown-generation" -> input.copy(changes = listOf(change(generation = "")))
            "missing-identity" -> input.copy(changes = listOf(change().copy(identity = identity.copy(rawUserId = ""))))
            "oversized" -> input.copy(changes = (1L..2049L).map { change(it, 0, 1, 10, line = it) })
            else -> input.copy(changes = listOf(change(before = 1, after = 2)))
        }
        val body = if (problem in setOf("gap", "duplicate-sequence", "reverse-time")) sale(2) else sale()
        assertFalse(decide(body, invalid).eligible)
    }
}
