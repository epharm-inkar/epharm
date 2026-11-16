package kz.epharm.receipts

import io.mockk.*
import jakarta.persistence.EntityManager
import java.math.BigDecimal
import java.time.Instant
import java.util.Optional
import kz.epharm.finance.service.PharmacistWalletService
import kz.epharm.pharmacists.entity.PharmacistEntity
import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.receipts.dto.ExcelRowInput
import kz.epharm.receipts.dto.LogSaleInput
import kz.epharm.receipts.dto.LogSaleItem
import kz.epharm.receipts.entity.PendingBonusEntity
import kz.epharm.receipts.entity.PendingBonusStatus
import kz.epharm.receipts.entity.ReceiptEntity
import kz.epharm.receipts.entity.ReceiptStatus
import kz.epharm.receipts.repository.PendingBonusRepository
import kz.epharm.receipts.repository.ReceiptRepository
import kz.epharm.receipts.service.RecommendationRewardCreditDecision
import kz.epharm.receipts.service.RecommendationRewardCreditPolicy
import kz.epharm.receipts.service.ReconcileService
import kz.epharm.receipts.service.ExcelPharmacyMappingProperties
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper

/** Real source-order decisions with isolated storage/locking doubles; PostgreSQL tests cover the real ledger. */
class ReconcileLegacyAmountsTest {
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `different known fiscal IDs cannot confirm each other in either arrival order`(excelFirst: Boolean) {
        val f = Fixture(1000)
        val row = f.excel(1000)
        val otherLog = f.log(1000).copy(fiscalId = "other-fiscal")
        if (excelFirst) {
            assertTrue(f.service.ingestExcelRow(row))
            f.service.ingestLogSale(otherLog)
        } else {
            f.service.ingestLogSale(otherLog)
            assertFalse(f.service.ingestExcelRow(row))
        }
        assertEquals(excelFirst, f.stored!!.confirmedByExcel)
        assertEquals(!excelFirst, f.stored!!.confirmedByLog)
        assertNull(f.stored!!.flagReason)
        assertEquals(0L, f.stored!!.bonusCredited)
        verify { f.wallet wasNot Called; f.policy wasNot Called }
        if (excelFirst) f.service.ingestLogSale(f.log(1000))
        else assertTrue(f.service.ingestExcelRow(row.copy(fiscalId = "other-fiscal")))
        verify(exactly = 1) { f.wallet.credit(any(), "pending", "person", 100, any()) }
    }

    @Test
    fun `log cannot confirm the same pharmacist and SKU pending at another pharmacy`() {
        val f = Fixture(1000)
        f.pending.pharmacyId = "other-pharmacy"
        f.service.ingestLogSale(f.log(1000))
        assertNull(f.stored)
        verify { f.wallet wasNot Called; f.policy wasNot Called }
        f.service.ingestLogSale(f.log(1000).copy(pharmacyId = "other-pharmacy"))
        assertTrue(f.stored!!.confirmedByLog)
        assertEquals("other-pharmacy", f.stored!!.pharmacyId)
    }

    @ParameterizedTest
    @ValueSource(strings = ["other-product", "", "  "])
    fun `a fiscal match cannot confirm a different or missing canonical SKU`(sku: String) {
        val f = Fixture(1000)
        f.service.ingestLogSale(f.log(1000))
        assertFalse(f.service.ingestExcelRow(f.excel(1000).copy(sku = sku)))
        assertFalse(f.service.ingestExcelRow(f.excel(1000).copy(sku = null)))
        assertFalse(f.stored!!.confirmedByExcel)
        verify { f.wallet wasNot Called; f.policy wasNot Called }
        assertTrue(f.service.ingestExcelRow(f.excel(1000)))
        verify(exactly = 1) { f.wallet.credit(any(), "pending", "person", 100, any()) }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `unknown raw code and explicitly mapped other pharmacy never confirm either source order`(excelFirst: Boolean) {
        val f = Fixture(1000)
        if (!excelFirst) f.service.ingestLogSale(f.log(1000))
        for (code in listOf("unknown", "pharmacy", "other-branch")) {
            assertFalse(f.service.ingestExcelRow(f.excel(1000).copy(pharmacyCode = code)))
        }
        if (excelFirst) assertNull(f.stored) else assertFalse(f.stored!!.confirmedByExcel)
        verify { f.wallet wasNot Called; f.policy wasNot Called }
        f.deliver(excelFirst, 1000, 1000)
        verify(exactly = 1) { f.wallet.credit(any(), "pending", "person", 100, any()) }
    }

    @Test
    fun `missing code cannot prove pharmacy even for a unique fiscal log and requires a scoped retry`() {
        val f = Fixture(1000)
        val row = f.excel(1000).copy(pharmacyCode = null)
        assertFalse(f.service.ingestExcelRow(row))
        assertNull(f.stored)
        f.service.ingestLogSale(f.log(1000))
        assertFalse(f.service.ingestExcelRow(row))
        assertFalse(f.stored!!.confirmedByExcel)
        assertNull(f.stored!!.flagReason)
        verify { f.wallet wasNot Called; f.policy wasNot Called }
        assertTrue(f.service.ingestExcelRow(f.excel(1000)))
        verify(exactly = 1) { f.wallet.credit(any(), "pending", "person", 100, any()) }
    }

    @Test
    fun `missing code cannot confirm an Excel only receipt even with matching fiscal`() {
        val f = Fixture(1000)
        assertTrue(f.service.ingestExcelRow(f.excel(1000)))
        assertFalse(f.service.ingestExcelRow(f.excel(1000).copy(pharmacyCode = null)))
        verify { f.wallet wasNot Called; f.policy wasNot Called }
    }

    @ParameterizedTest
    @ValueSource(strings = ["duplicate_receipt", "wrong_pharmacy", "legacy_review", "", "  "])
    fun `existing review reason survives bad evidence and valid retries until explicit HQ approval`(reason: String) {
        val f = Fixture(1000)
        assertTrue(f.service.ingestExcelRow(f.excel(1000)))
        f.stored!!.flagReason = reason
        f.service.ingestLogSale(f.log(0))
        f.service.ingestLogSale(f.log(1000))
        assertTrue(f.service.ingestExcelRow(f.excel(1000)))
        assertEquals(ReceiptStatus.flagged, f.stored!!.status)
        assertEquals(reason.takeUnless { it.isBlank() } ?: "amount_invalid", f.stored!!.flagReason)
        assertEquals(0L, f.stored!!.bonusCredited)
        verify { f.wallet wasNot Called; f.policy wasNot Called }

        // Explicit existing HQ decision remains a separate path; no permissions are added here.
        f.service.approve(f.stored!!.id, "synthetic-hq-reviewer")
        assertEquals(ReceiptStatus.approved, f.stored!!.status)
        assertFalse(f.stored!!.autoApproved)
        verify(exactly = 1) { f.wallet.credit(any(), "pending", "person", 100, any()) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "  "])
    fun `blank reason does not block otherwise valid legacy source confirmation`(reason: String) {
        val f = Fixture(1000)
        assertTrue(f.service.ingestExcelRow(f.excel(1000)))
        f.stored!!.flagReason = reason
        f.service.ingestLogSale(f.log(1000))
        assertEquals(ReceiptStatus.approved, f.stored!!.status)
        verify(exactly = 1) { f.wallet.credit(any(), "pending", "person", 100, any()) }
    }

    @ParameterizedTest
    @CsvSource("true,excel,missing", "false,excel,missing", "true,excel,0", "false,excel,0",
        "true,excel,-1", "false,excel,-1", "true,log,0", "false,log,0", "true,log,-1", "false,log,-1")
    fun `missing and nonpositive amounts retain a reviewable receipt without financial confirmation`(
        excelFirst: Boolean, invalidSource: String, amountText: String,
    ) {
        val f = Fixture(1000)
        val invalidAmount = amountText.toLongOrNull()
        val row = f.excel(1000).copy(amount = if (invalidSource == "excel") invalidAmount else 1000)
        val input = f.log(if (invalidSource == "log") invalidAmount!! else 1000)
        if (excelFirst) {
            // Missing/invalid Excel-first amounts cannot uniquely prove a pending by amount.
            assertEquals(invalidSource != "excel", f.service.ingestExcelRow(row))
            f.service.ingestLogSale(input)
        } else {
            f.service.ingestLogSale(input)
            assertTrue(f.service.ingestExcelRow(row))
        }
        // Once the log supplies an exact fiscal receipt, even an incomplete Excel row is retained for HQ.
        assertTrue(f.service.ingestExcelRow(row))
        f.service.ingestLogSale(input)
        assertEquals(ReceiptStatus.flagged, f.stored!!.status)
        assertEquals(if (invalidAmount == null) "amount_missing" else "amount_invalid", f.stored!!.flagReason)
        assertEquals(invalidSource != "excel", f.stored!!.confirmedByExcel)
        assertEquals(invalidSource != "log", f.stored!!.confirmedByLog)
        assertEquals(PendingBonusStatus.awaiting_receipt, f.pending.status)
        assertEquals(0L, f.stored!!.bonusCredited)
        verify { f.wallet wasNot Called; f.policy wasNot Called }
    }

    @ParameterizedTest
    @CsvSource("true,1000,2000", "false,1000,2000", "true,1000,100", "false,1000,100",
        "true,979,1000", "false,979,1000", "true,1021,1000", "false,1021,1000",
        "true,9223372036854775807,1000", "false,9223372036854775807,1000")
    fun `mismatching amounts never reach the wallet in either order or after retries`(
        excelFirst: Boolean, excelAmount: Long, logAmount: Long,
    ) {
        val f = Fixture(excelAmount)
        f.deliver(excelFirst, excelAmount, logAmount)
        fun assertBlocked() {
            assertEquals(ReceiptStatus.flagged, f.stored!!.status)
            assertEquals("amount_mismatch", f.stored!!.flagReason)
            assertTrue(f.stored!!.confirmedByLog && f.stored!!.confirmedByExcel)
            assertEquals(0L, f.stored!!.bonusCredited)
            assertEquals(PendingBonusStatus.awaiting_receipt, f.pending.status)
            verify { f.wallet wasNot Called; f.policy wasNot Called }
        }
        assertBlocked()
        f.service.ingestLogSale(f.log(logAmount))
        f.service.ingestExcelRow(f.excel(excelAmount))
        f.service.ingestLogSale(f.log(excelAmount))
        f.service.ingestExcelRow(f.excel(logAmount))
        assertBlocked()
    }

    @ParameterizedTest
    @CsvSource("true,980", "false,980", "true,1000", "false,1000", "true,1020", "false,1020")
    fun `equal and inclusive two percent amounts credit once regardless of arrival order`(
        excelFirst: Boolean, excelAmount: Long,
    ) {
        val f = Fixture(excelAmount)
        f.deliver(excelFirst, excelAmount, 1000)
        assertEquals(ReceiptStatus.approved, f.stored!!.status)
        assertEquals(100L, f.stored!!.bonusCredited)
        assertEquals(PendingBonusStatus.matched, f.pending.status)
        f.service.ingestLogSale(f.log(1000))
        assertFalse(f.service.ingestExcelRow(f.excel(excelAmount)))
        verify(exactly = 1) { f.wallet.credit(any(), "pending", "person", 100, any()) }
    }

    private class Fixture(expected: Long) {
        private val now = Instant.now()
        val pending = PendingBonusEntity(id="pending", pharmacistId="person", pharmacyId="pharmacy",
            sku="product", bonus=100, expectedAmount=expected, createdAt=now)
        var stored: ReceiptEntity? = null
        val wallet = mockk<PharmacistWalletService>(relaxed=true)
        val policy = mockk<RecommendationRewardCreditPolicy>()
        private val receipts = mockk<ReceiptRepository>()
        private val bonuses = mockk<PendingBonusRepository>()
        private val people = mockk<PharmacistRepository>()
        private val jdbc = mockk<JdbcTemplate>()
        private val em = mockk<EntityManager>(relaxed=true)
        val mapping = ExcelPharmacyMappingProperties().apply {
            pharmacyCodes = mapOf("pilot-branch" to "pharmacy", "other-branch" to "other-pharmacy")
        }
        val service = ReconcileService(receipts, bonuses, people, mockk(), wallet, mockk(), mockk(), jdbc, em, policy, mapping)

        init {
            every { bonuses.findAllBySkuAndStatusRaw("product", "awaiting_receipt") } answers {
                listOf(pending).filter { it.status == PendingBonusStatus.awaiting_receipt }
            }
            every { bonuses.findAllByPharmacistIdAndSkuAndStatusRawOrderByCreatedAtDesc("person", "product", "awaiting_receipt") } answers {
                listOf(pending).filter { it.status == PendingBonusStatus.awaiting_receipt }
            }
            every { bonuses.findById("pending") } returns Optional.of(pending)
            every { bonuses.save(any<PendingBonusEntity>()) } answers { firstArg() }
            every { receipts.findFirstByPendingBonusId("pending") } answers { stored }
            every { receipts.findAllByFiscalId(any()) } answers {
                val fiscal = firstArg<String>()
                listOfNotNull(stored).filter { it.fiscalId == fiscal }
            }
            every { receipts.findById(any()) } answers { Optional.ofNullable(stored) }
            every { receipts.save(any<ReceiptEntity>()) } answers { firstArg<ReceiptEntity>().also { stored=it } }
            every { jdbc.queryForList(any<String>(), *anyVararg()) } returns listOf(mapOf("id" to "pending"))
            every { jdbc.query(any<String>(), any<RowMapper<String>>(), *anyVararg()) } returns listOf("pending")
            every { jdbc.query("SELECT pharmacist_id FROM receipts WHERE id=?", any<RowMapper<String>>(), any<String>()) } returns listOf("person")
            every { people.findById("person") } returns Optional.of(PharmacistEntity(id="person"))
            every { policy.beforeCredit(any(), any()) } returns RecommendationRewardCreditDecision(true, 100)
            every { policy.afterCredit(any(), any(), any()) } returns Unit
        }

        fun excel(amount: Long) = ExcelRowInput("fiscal", "pilot-branch", null, "product", "TEST", BigDecimal.ONE, amount, now)
        fun log(amount: Long) = LogSaleInput("person", "pharmacy", "fiscal", null, now,
            listOf(LogSaleItem("product", 1.0, amount, amount)))
        fun deliver(excelFirst: Boolean, excelAmount: Long, logAmount: Long) {
            if (excelFirst) {
                assertTrue(service.ingestExcelRow(excel(excelAmount)))
                service.ingestLogSale(log(logAmount))
            } else {
                service.ingestLogSale(log(logAmount))
                assertTrue(service.ingestExcelRow(excel(excelAmount)))
            }
        }
    }
}
