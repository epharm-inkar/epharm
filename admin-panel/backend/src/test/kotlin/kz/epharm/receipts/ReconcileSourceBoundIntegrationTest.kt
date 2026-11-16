package kz.epharm.receipts

import java.time.Instant
import java.util.UUID
import kz.epharm.posm.entity.PosSaleEntity
import kz.epharm.posm.entity.PosSaleItem
import kz.epharm.posm.entity.RecommendationEventEntity
import kz.epharm.posm.repository.PosSaleRepository
import kz.epharm.posm.repository.RecommendationEventRepository
import kz.epharm.receipts.dto.ExcelRowInput
import kz.epharm.receipts.dto.LogSaleInput
import kz.epharm.receipts.dto.LogSaleItem
import kz.epharm.receipts.entity.PendingBonusEntity
import kz.epharm.receipts.entity.PendingBonusStatus
import kz.epharm.receipts.entity.ReceiptEntity
import kz.epharm.receipts.entity.ReceiptStatus
import kz.epharm.receipts.repository.PendingBonusRepository
import kz.epharm.receipts.repository.ReceiptRepository
import kz.epharm.receipts.service.ReconcileService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Transactional
class ReconcileSourceBoundIntegrationTest {
    companion object {
        private val testPharmacy = "ph_${UUID.randomUUID()}"
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("source_bound_reconcile").withUsername("epharm").withPassword("test-only").apply { start() }
        @JvmStatic @DynamicPropertySource fun properties(reg: DynamicPropertyRegistry) {
            reg.add("spring.datasource.url") { postgres.jdbcUrl }
            reg.add("spring.datasource.username") { postgres.username }
            reg.add("spring.datasource.password") { postgres.password }
            reg.add("epharm.receipts.excel.pharmacy-codes.pilot-branch") { testPharmacy }
        }
    }
    @Autowired lateinit var reconcile: ReconcileService
    @Autowired lateinit var pending: PendingBonusRepository
    @Autowired lateinit var receipts: ReceiptRepository
    @Autowired lateinit var sales: PosSaleRepository
    @Autowired lateinit var events: RecommendationEventRepository
    @Autowired lateinit var jdbc: JdbcTemplate
    private val pharmacy = testPharmacy
    private val person = "pharmacist_${UUID.randomUUID()}"
    private val now = Instant.now()

    @BeforeEach fun person() {
        jdbc.update("INSERT INTO chains(id,name,\"group\") VALUES (?,'TEST','pilot')", pharmacy)
        jdbc.update("INSERT INTO pharmacies(id,name,chain_id,chain_name,city,\"group\") VALUES (?,'TEST',?,'TEST','TEST','pilot')", pharmacy, pharmacy)
        jdbc.update("INSERT INTO pharmacists(id,name,iin,phone,pharmacy_id,pharmacy_name,tier,status) VALUES (?,'TEST','900101300011','+77001112233',?,'TEST','Silver','active')", person, pharmacy)
    }

    @Test fun `source bound evidence always stays manual even when legacy log and Excel also arrive`() {
        val (reservation,sale) = bound()
        reconcile.ingestVerifiedRecommendationSale(reservation.id,sale.id)
        assertThat(receipt(reservation).status).isEqualTo(ReceiptStatus.moderation_required)
        reconcile.ingestLogSale(log(sale))
        assertThat(reconcile.ingestExcelRow(excel(sale.fiscalId))).isFalse()
        assertThat(receipt(reservation).status).isEqualTo(ReceiptStatus.moderation_required)
        assertThat(receipt(reservation).confirmedByExcel).isFalse()
        assertThat(balance()).isZero()
    }

    @Test fun `legacy SKU heuristic cannot steal a source bound reservation before receipt creation`() {
        val (reservation,sale) = bound()
        reconcile.ingestLogSale(log(sale))
        assertThat(reconcile.ingestExcelRow(excel(null))).isFalse()
        assertThat(receipts.findFirstByPendingBonusId(reservation.id)).isNull()
        assertThat(balance()).isZero()
    }

    @Test fun `rejected source bound receipt never reopens on printed or Excel retry`() {
        val (reservation,sale) = bound()
        reconcile.ingestVerifiedRecommendationSale(reservation.id,sale.id)
        val rejected = receipt(reservation)
        rejected.status=ReceiptStatus.rejected
        receipts.saveAndFlush(rejected)
        reconcile.ingestVerifiedRecommendationSale(reservation.id,sale.id)
        reconcile.ingestLogSale(log(sale))
        assertThat(reconcile.ingestExcelRow(excel(sale.fiscalId))).isFalse()
        assertThat(receipt(reservation).status).isEqualTo(ReceiptStatus.rejected)
        assertThat(balance()).isZero()
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `different fiscal IDs never cross confirm even with equal pharmacy SKU and amounts`(excelFirst: Boolean) {
        val reservation = legacyPending(1000)
        val row = excel("legacy-fiscal")
        val otherLog = legacyLog(1000).copy(fiscalId="other-fiscal")
        if (excelFirst) {
            assertThat(reconcile.ingestExcelRow(row)).isTrue()
            reconcile.ingestLogSale(otherLog)
        } else {
            reconcile.ingestLogSale(otherLog)
            assertThat(reconcile.ingestExcelRow(row)).isFalse()
        }
        assertThat(receipt(reservation).confirmedByExcel).isEqualTo(excelFirst)
        assertThat(receipt(reservation).confirmedByLog).isEqualTo(!excelFirst)
        assertThat(receipt(reservation).flagReason).isNull()
        assertThat(balance()).isZero()
        if (excelFirst) reconcile.ingestLogSale(legacyLog(1000))
        else assertThat(reconcile.ingestExcelRow(row.copy(fiscalId="other-fiscal"))).isTrue()
        assertThat(balance()).isEqualTo(100)
    }

    @Test fun `log ignores newer same seller SKU reservation in a different pharmacy`() {
        val own = legacyPending(1000)
        val otherPharmacy = "ph_${UUID.randomUUID()}"
        jdbc.update("INSERT INTO pharmacies(id,name,chain_id,chain_name,city,\"group\") VALUES (?,'OTHER',?,'TEST','TEST','pilot')", otherPharmacy, pharmacy)
        val foreign = pending.saveAndFlush(PendingBonusEntity(id="foreign_${UUID.randomUUID()}", pharmacistId=person,
            pharmacyId=otherPharmacy, sku="product-test", expectedAmount=1000, bonus=100, createdAt=now.plusSeconds(1)))
        reconcile.ingestLogSale(legacyLog(1000))
        assertThat(receipts.findFirstByPendingBonusId(foreign.id)).isNull()
        assertThat(receipt(own).confirmedByLog).isTrue()
        assertThat(receipt(own).pharmacyId).isEqualTo(pharmacy)
        assertThat(balance()).isZero()
    }

    @Test fun `fiscal collision with another SKU or unmapped pharmacy cannot confirm a log receipt`() {
        val reservation = legacyPending(1000)
        reconcile.ingestLogSale(legacyLog(1000))
        val valid = excel("legacy-fiscal")
        assertThat(reconcile.ingestExcelRow(valid.copy(sku="other-product"))).isFalse()
        assertThat(reconcile.ingestExcelRow(valid.copy(pharmacyCode="another-branch"))).isFalse()
        assertThat(reconcile.ingestExcelRow(valid.copy(pharmacyCode=null))).isFalse()
        assertThat(reconcile.ingestExcelRow(valid.copy(pharmacyCode=pharmacy))).isFalse()
        assertThat(receipt(reservation).confirmedByExcel).isFalse()
        assertThat(receipt(reservation).flagReason).isNull()
        assertThat(balance()).isZero()
        assertThat(reconcile.ingestExcelRow(valid)).isTrue()
        assertThat(balance()).isEqualTo(100)
    }

    @Test fun `same fiscal number on two receipts is not a unique Excel proof`() {
        for (index in 1..2) {
            val reservation=pending.save(PendingBonusEntity(id="pb_${UUID.randomUUID()}",pharmacistId=person,pharmacistName="TEST",
                pharmacyId=pharmacy,pharmacyName="TEST",sku="product-test",productName="TEST",expectedAmount=1000,bonus=100))
            receipts.save(ReceiptEntity(id="receipt_${UUID.randomUUID()}",pharmacistId=person,pharmacistName="TEST",pharmacyId=pharmacy,
                pharmacyName="TEST",fiscalId="shared-fiscal",parsedSku="product-test",parsedAmount=1000,pendingBonusId=reservation.id,
                confirmedByLog=true).also { it.status=ReceiptStatus.moderation_required })
        }
        assertThat(reconcile.ingestExcelRow(excel("shared-fiscal"))).isFalse()
        assertThat(receipts.findAllByFiscalId("shared-fiscal")).allMatch { it.status==ReceiptStatus.moderation_required }
        assertThat(balance()).isZero()
    }

    @Test fun `narrow receipt requires actual same session display and never accepts cancellation close capture`() {
        val (reservation,sale) = bound(displayed=false)
        reconcile.ingestVerifiedRecommendationSale(reservation.id,sale.id)
        assertThat(receipts.findFirstByPendingBonusId(reservation.id)).isNull()
        val event=events.findAllBySessionId(sale.sessionId!!).single()
        event.displayedAt=now.minusSeconds(1)
        events.saveAndFlush(event)
        sale.captureSource="standardn-firebird-close"
        sales.saveAndFlush(sale)
        reconcile.ingestVerifiedRecommendationSale(reservation.id,sale.id)
        assertThat(receipts.findFirstByPendingBonusId(reservation.id)).isNull()
        assertThat(balance()).isZero()
    }

    @ParameterizedTest
    @CsvSource("true,1000,2000", "false,1000,2000", "true,1000,100", "false,1000,100",
        "true,1021,1000", "false,1021,1000", "true,979,1000", "false,979,1000",
        "true,9223372036854775807,1000", "false,9223372036854775807,1000")
    fun `legacy amount mismatch never credits in either source order or on corrected retries`(
        excelFirst: Boolean, excelAmount: Long, logAmount: Long,
    ) {
        val reservation = legacyPending(excelAmount)
        val row = excel("legacy-fiscal").copy(amount = excelAmount)
        val input = legacyLog(logAmount)
        if (excelFirst) {
            assertThat(reconcile.ingestExcelRow(row)).isTrue()
            reconcile.ingestLogSale(input)
        } else {
            reconcile.ingestLogSale(input)
            assertThat(reconcile.ingestExcelRow(row)).isTrue()
        }
        fun assertNoCredit() {
            val receipt = receipt(reservation)
            assertThat(receipt.confirmedByLog && receipt.confirmedByExcel).isTrue()
            assertThat(receipt.status).isEqualTo(ReceiptStatus.flagged)
            assertThat(receipt.flagReason).isEqualTo("amount_mismatch")
            assertThat(receipt.bonusCredited).isZero()
            assertThat(pending.findById(reservation.id).orElseThrow().status).isEqualTo(PendingBonusStatus.awaiting_receipt)
            assertThat(balance()).isZero()
            assertThat(walletEntries()).isZero()
        }
        assertNoCredit()
        reconcile.ingestLogSale(input)
        reconcile.ingestExcelRow(row)
        // New matching observations cannot silently clear an already recorded discrepancy.
        reconcile.ingestLogSale(legacyLog(excelAmount))
        reconcile.ingestExcelRow(row.copy(amount = logAmount))
        assertNoCredit()
    }

    @ParameterizedTest
    @CsvSource("true,980", "false,980", "true,1000", "false,1000", "true,1020", "false,1020")
    fun `legacy equal and inclusive two percent amounts still credit once in either source order`(
        excelFirst: Boolean, excelAmount: Long,
    ) {
        val reservation = legacyPending(excelAmount)
        val row = excel("legacy-fiscal").copy(amount = excelAmount)
        val input = legacyLog(1000)
        if (excelFirst) {
            assertThat(reconcile.ingestExcelRow(row)).isTrue()
            reconcile.ingestLogSale(input)
        } else {
            reconcile.ingestLogSale(input)
            assertThat(reconcile.ingestExcelRow(row)).isTrue()
        }
        assertThat(receipt(reservation).status).isEqualTo(ReceiptStatus.approved)
        assertThat(receipt(reservation).bonusCredited).isEqualTo(100)
        assertThat(balance()).isEqualTo(100)
        reconcile.ingestLogSale(input)
        assertThat(reconcile.ingestExcelRow(row)).isFalse()
        assertThat(balance()).isEqualTo(100)
        assertThat(walletEntries()).isEqualTo(1)
    }

    private fun legacyPending(expected: Long) = pending.saveAndFlush(PendingBonusEntity(
        id="legacy_${UUID.randomUUID()}", pharmacistId=person, pharmacistName="TEST", pharmacyId=pharmacy,
        pharmacyName="TEST", sku="product-test", productName="TEST", expectedAmount=expected, bonus=100, createdAt=now))

    private fun legacyLog(amount: Long) = LogSaleInput(person, pharmacy, "legacy-fiscal", null, now,
        listOf(LogSaleItem("product-test", 1.0, amount, amount)))

    @ParameterizedTest
    @ValueSource(strings = ["duplicate_receipt", "wrong_pharmacy", "legacy_review", "", "  "])
    fun `recorded review reason survives bad source and valid retries while explicit HQ decision still works`(reason: String) {
        val reservation = legacyPending(1000)
        assertThat(reconcile.ingestExcelRow(excel("legacy-fiscal"))).isTrue()
        receipt(reservation).also { it.flagReason = reason; receipts.saveAndFlush(it) }
        reconcile.ingestLogSale(legacyLog(0))
        reconcile.ingestLogSale(legacyLog(1000))
        assertThat(reconcile.ingestExcelRow(excel("legacy-fiscal"))).isTrue()
        assertThat(receipt(reservation).status).isEqualTo(ReceiptStatus.flagged)
        assertThat(receipt(reservation).flagReason).isEqualTo(reason.takeUnless { it.isBlank() } ?: "amount_invalid")
        assertThat(balance()).isZero()
        assertThat(walletEntries()).isZero()

        reconcile.approve(receipt(reservation).id, "synthetic-hq-reviewer")
        assertThat(receipt(reservation).status).isEqualTo(ReceiptStatus.approved)
        assertThat(receipt(reservation).autoApproved).isFalse()
        assertThat(balance()).isEqualTo(100)
        assertThat(walletEntries()).isEqualTo(1)
    }

    @ParameterizedTest
    @CsvSource("true,excel,missing", "false,excel,missing", "true,excel,0", "false,excel,0",
        "true,excel,-1", "false,excel,-1", "true,log,0", "false,log,0", "true,log,-1", "false,log,-1")
    fun `incomplete or invalid source amounts preserve review evidence without a wallet mutation`(
        excelFirst: Boolean, invalidSource: String, amountText: String,
    ) {
        val reservation = legacyPending(1000)
        val invalidAmount = amountText.toLongOrNull()
        val row = excel("legacy-fiscal").copy(amount = if (invalidSource == "excel") invalidAmount else 1000)
        val input = legacyLog(if (invalidSource == "log") invalidAmount!! else 1000)
        if (excelFirst) {
            assertThat(reconcile.ingestExcelRow(row)).isEqualTo(invalidSource != "excel")
            reconcile.ingestLogSale(input)
        } else {
            reconcile.ingestLogSale(input)
            assertThat(reconcile.ingestExcelRow(row)).isTrue()
        }
        assertThat(reconcile.ingestExcelRow(row)).isTrue()
        reconcile.ingestLogSale(input)
        assertThat(receipt(reservation).status).isEqualTo(ReceiptStatus.flagged)
        assertThat(receipt(reservation).flagReason).isEqualTo(if (invalidAmount == null) "amount_missing" else "amount_invalid")
        assertThat(receipt(reservation).confirmedByExcel).isEqualTo(invalidSource != "excel")
        assertThat(receipt(reservation).confirmedByLog).isEqualTo(invalidSource != "log")
        assertThat(receipt(reservation).bonusCredited).isZero()
        assertThat(balance()).isZero()
        assertThat(walletEntries()).isZero()
    }

    private fun bound(displayed: Boolean=true): Pair<PendingBonusEntity,PosSaleEntity> {
        val sale=sales.save(PosSaleEntity(id="sale_${UUID.randomUUID()}",sessionId="session_${UUID.randomUUID()}",pharmacistId=person,
            pharmacyId=pharmacy,pharmacistSource="posm_internal",captureSource="standardn-print-log",fiscalId="fiscal_${UUID.randomUUID()}",totalAmount=1000,
            items=listOf(PosSaleItem("123",barcode="4600000000017",name="TEST",qty=1.0,price=1000,total=1000,productId="product-test")),printedAt=now))
        val reservation=pending.save(PendingBonusEntity(id="pb_${UUID.randomUUID()}",pharmacistId=person,pharmacistName="TEST",
            pharmacyId=pharmacy,pharmacyName="TEST",sku="product-test",productName="TEST",expectedAmount=1000,bonus=100,sourceSaleId=sale.id))
        events.save(RecommendationEventEntity(id="event_${UUID.randomUUID()}",sessionId=sale.sessionId!!,pharmacistId=person,pharmacyId=pharmacy,
            ruleId="test-rule",recommendSku="product-test",recommendName="TEST",pendingBonusId=reservation.id,
            displayedAt=if(displayed) now.minusSeconds(1) else null))
        return reservation to sale
    }
    private fun receipt(reservation: PendingBonusEntity)=receipts.findFirstByPendingBonusId(reservation.id)!!
    private fun log(sale: PosSaleEntity)=LogSaleInput(person,pharmacy,sale.fiscalId,null,sale.printedAt,listOf(LogSaleItem("product-test",1.0,1000,1000)))
    private fun excel(fiscal: String?)=ExcelRowInput(fiscal,"pilot-branch",null,"product-test","TEST",java.math.BigDecimal.ONE,1000,now)
    private fun balance()=jdbc.queryForObject("SELECT balance FROM pharmacists WHERE id=?",Long::class.java,person)!!
    private fun walletEntries()=jdbc.queryForObject("SELECT count(*) FROM pharmacist_wallet_entries WHERE pharmacist_id=?",Long::class.java,person)!!
}
