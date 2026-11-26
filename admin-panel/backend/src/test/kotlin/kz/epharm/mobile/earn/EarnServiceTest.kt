package kz.epharm.mobile.earn

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kz.epharm.catalog.entity.ProductEntity
import kz.epharm.catalog.repository.ProductRepository
import kz.epharm.pharmacists.entity.PharmacistEntity
import kz.epharm.pharmacists.entity.PharmacistStatus
import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.promo.entity.PromoEntity
import kz.epharm.promo.entity.PromoStatus
import kz.epharm.promo.repository.PromoRepository
import kz.epharm.rules.entity.RuleEntity
import kz.epharm.rules.entity.RuleStatus
import kz.epharm.rules.entity.RuleTrigger
import kz.epharm.rules.entity.RuleType
import kz.epharm.rules.repository.RuleRepository
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Optional

/** The «Бонусы» tab without a database: what is listed, how it is sorted and filtered, the status and the leaderboards. */
class EarnServiceTest {
    private val pharmacists = mockk<PharmacistRepository>()
    private val rules = mockk<RuleRepository>()
    private val promos = mockk<PromoRepository>()
    private val products = mockk<ProductRepository>()
    private val data = mockk<EarnData>()
    // Thursday 26.11.2026 15:00 in Almaty (UTC+5).
    private val clock = Clock.fixed(Instant.parse("2026-11-26T10:00:00Z"), ZoneOffset.UTC)
    private val service = EarnService(pharmacists, rules, promos, products, data, null, clock)
    private val me = PharmacistEntity(id = "ph-me", name = "Бузурбаева Лаура Ермековна", pharmacyId = "pharmacy-1", pharmacyName = "Аптека со склада, Абая 1")
        .also { it.status = PharmacistStatus.active }

    init {
        every { pharmacists.findById("ph-me") } returns Optional.of(me)
        every { rules.findAllByStatusRawOrderByUpdatedAtDesc("active") } returns listOf(
            rule("r1", "p-tantum", "promo-1", bonus = 200),
            rule("r2", "p-lorid", "promo-2", bonus = 40),
            rule("r3", "p-sept", "promo-3", bonus = 120, type = RuleType.crosssell),
            rule("r4", "p-old", "promo-old", bonus = 500),
            rule("r5", "p-free", "promo-1", bonus = 0),
        )
        every { promos.findAllById(any()) } returns listOf(
            campaign("promo-1"), campaign("promo-2"), campaign("promo-3"),
            campaign("promo-old", end = LocalDate.of(2026, 11, 20)),
        )
        every { products.findAllById(any()) } answers {
            val ids = firstArg<Collection<String>>()
            listOf(
                product("p-tantum", "Тантум Верде спрей", barcode = "4600001"),
                product("p-lorid", "Лоридамин спрей", ipartId = "777"),
                product("p-sept", "Септаназал спрей"),
                product("p-old", "Старая акция"),
                product("trigger-1", "Гервитин спрей"),
            ).filter { it.id in ids }
        }
        every { data.networkSold(any(), any()) } returns listOf(
            sold(productId = "p-lorid", quantity = 3),
            sold(barcode = "4600001", quantity = 12),
            sold(sku = "777", quantity = 4),
        )
        every { data.pharmacySold("pharmacy-1", any(), any()) } returns listOf(sold(barcode = "4600001", quantity = 1), sold(sku = "777", quantity = 1))
    }

    @Test fun `only bonus products of campaigns running today, sold here, the network's best sellers first`() {
        val list = service.products("ph-me")
        assertEquals(listOf("p-tantum", "p-lorid"), list.items.map { it.productId })
        assertEquals(12, list.items[0].soldWeek)
        assertEquals(7, list.items[1].soldWeek, "a product counted by catalogue id and by Standard-N id")
        assertTrue(list.items.all { it.inMyPharmacy })
        assertEquals("sales", list.availabilityBasis)
        assertEquals(30, list.availabilityDays)
        assertEquals("Аптека со склада, Абая 1", list.pharmacyName)
    }

    @Test fun `without the filter every bonus product shows, sorted by the bonus when asked`() {
        val list = service.products("ph-me", EarnService.SORT_BONUS, onlyMine = false)
        assertEquals(listOf("p-tantum", "p-sept", "p-lorid"), list.items.map { it.productId })
        val sept = list.items.first { it.productId == "p-sept" }
        assertFalse(sept.inMyPharmacy)
        assertEquals(listOf("crosssell"), sept.kinds)
        assertEquals(0, sept.soldWeek)
        assertTrue(list.items.first { it.productId == "p-tantum" }.hit)
    }

    @Test fun `an unknown sort is refused`() {
        val error = assertThrows(AppException::class.java) { service.products("ph-me", "price") }
        assertEquals(ErrorCode.VALIDATION_FAILED, error.code)
    }

    @Test fun `a blocked account sees nothing`() {
        me.status = PharmacistStatus.blocked
        val error = assertThrows(AppException::class.java) { service.products("ph-me") }
        assertEquals(ErrorCode.USER_NOT_ACTIVE, error.code)
    }

    @Test fun `the network sales are read once per ten minutes, not on every open`() {
        service.products("ph-me")
        service.products("ph-me", EarnService.SORT_BONUS)
        verify(exactly = 1) { data.networkSold(any(), any()) }
        verify(exactly = 1) { data.pharmacySold(any(), any(), any()) }
    }

    @Test fun `the card of a product says when to offer it and what to say`() {
        val card = service.product("ph-me", "p-tantum")
        assertEquals("Тантум Верде спрей", card.product.name)
        assertEquals(listOf("Гервитин спрей"), card.whenAsked)
        assertEquals(listOf("Скрипт r1"), card.scripts)
        assertNull(card.description, "no catalogue in this test and no description of the campaign")
    }

    @Test fun `a campaign's own description wins`() {
        every { promos.findAllById(any()) } returns listOf(
            campaign("promo-1").also { it.overrideDescription = "Спрей от боли в горле." }, campaign("promo-2"), campaign("promo-3"),
        )
        assertEquals("Спрей от боли в горле.", service.product("ph-me", "p-tantum").description)
    }

    @Test fun `a product without a running campaign has no card`() {
        val error = assertThrows(AppException::class.java) { service.product("ph-me", "p-old") }
        assertEquals(ErrorCode.NOT_FOUND, error.code)
    }

    @Test fun `today's sales, the bonus of sales after suggestions, own receipts with promo products marked, a status`() {
        every { data.pharmacistSold("ph-me", any(), any()) } returns listOf(
            sold(barcode = "4600001", quantity = 2, name = "Тантум Верде спрей"),
            sold(sku = "1", quantity = 5, name = "Парацетамол"),
        )
        every { data.hintTotals("ph-me", any()) } returns (400L to 2)
        every { data.pharmacistReceipts("ph-me", any()) } returns 31
        every { data.hintSales("ph-me", any(), any()) } returns listOf(EarnHintSaleDto(Instant.parse("2026-11-26T09:00:00Z"), "Тантум Верде спрей", 200))
        every { data.leaders(any(), false) } returns listOf(
            EarnData.LeaderRow("ph-1", "Сапарова Айгерим", "Аптека 2", "Астана", 900, 5),
            EarnData.LeaderRow("ph-me", "Бузурбаева Лаура Ермековна", "Аптека со склада, Абая 1", "Алматы", 400, 2),
        )

        val today = service.summary("ph-me")
        assertEquals(Instant.parse("2026-11-25T19:00:00Z"), today.from, "the day starts at midnight in Almaty")
        assertEquals(400L, today.earnedKzt)
        assertEquals(2, today.hintSales)
        assertEquals(31, today.receipts)
        assertEquals(BigDecimal.valueOf(7), today.units)
        assertEquals(listOf(true, false), today.products.map { it.promo })
        assertEquals("Хорошо идёте!", today.status.title)
        assertEquals("start", today.status.level)
        assertEquals(1_000L, today.status.nextLevelKzt)
        assertEquals(2, today.weekPlace)
    }

    @Test fun `the week starts on Monday and the month on the 1st`() {
        every { data.pharmacistSold(any(), any(), any()) } returns emptyList()
        every { data.hintTotals(any(), any()) } returns (0L to 0)
        every { data.pharmacistReceipts(any(), any()) } returns 0
        every { data.hintSales(any(), any(), any()) } returns emptyList()
        every { data.leaders(any(), any()) } returns emptyList()
        assertEquals(Instant.parse("2026-11-22T19:00:00Z"), service.summary("ph-me", EarnService.PERIOD_WEEK).from)
        val month = service.summary("ph-me", EarnService.PERIOD_MONTH)
        assertEquals(Instant.parse("2026-10-31T19:00:00Z"), month.from)
        assertEquals("Начните с первой продажи", month.status.title)
        assertNull(month.weekPlace)
        assertThrows(AppException::class.java) { service.summary("ph-me", "year") }
    }

    @Test fun `the leaderboard shows short names, the top and the pharmacist's own row below it`() {
        every { data.leaders(any(), false) } returns (1..25).map {
            EarnData.LeaderRow("ph-$it", "Фамилия$it Имя Отчество", "Аптека $it", "Город", 1000L - it, 3)
        } + EarnData.LeaderRow("ph-me", "Бузурбаева Лаура Ермековна", "Аптека со склада, Абая 1", "Алматы", 10, 1)

        val board = service.leaderboard("ph-me", EarnService.PERIOD_WEEK, EarnService.SCOPE_PHARMACISTS)
        assertEquals(20, board.items.size)
        assertEquals("Фамилия1 И.", board.items[0].name)
        assertEquals(1, board.items[0].place)
        assertFalse(board.items.any { it.me })
        val mine = assertNotNull(board.me).let { board.me!! }
        assertEquals(26, mine.place)
        assertEquals("Бузурбаева Л.", mine.name)
        assertTrue(mine.me)
        assertEquals(26, board.participants)
    }

    @Test fun `the pharmacies' board marks the pharmacist's pharmacy`() {
        every { data.leaders(any(), true) } returns listOf(
            EarnData.LeaderRow("pharmacy-9", "Аптека 9", "Аптека 9", "Шымкент", 2000, 9),
            EarnData.LeaderRow("pharmacy-1", "Аптека со склада, Абая 1", "Аптека со склада, Абая 1", "Алматы", 300, 2),
        )
        val board = service.leaderboard("ph-me", EarnService.PERIOD_MONTH, EarnService.SCOPE_PHARMACIES)
        assertEquals(listOf(false, true), board.items.map { it.me })
        assertEquals("Аптека со склада, Абая 1", board.items[1].name)
        assertNull(board.me)
        assertThrows(AppException::class.java) { service.leaderboard("ph-me", EarnService.PERIOD_WEEK, "cities") }
    }

    @Test fun `short names`() {
        assertEquals("Бузурбаева Л.", EarnService.shortName("Бузурбаева  Лаура Ермековна"))
        assertEquals("Айгерим", EarnService.shortName("Айгерим"))
        assertEquals("Фармацевт", EarnService.shortName(" "))
    }

    private fun rule(id: String, offer: String, promoId: String, bonus: Int, type: RuleType = RuleType.substitution) = RuleEntity(
        id = id, recommend = offer, trigger = RuleTrigger("product", "trigger-1"), promoId = promoId, bonus = bonus,
        script = "Скрипт $id", advantages = listOf("Преимущество $id"),
    ).also { it.type = type; it.status = RuleStatus.active }

    private fun campaign(id: String, end: LocalDate? = null) =
        PromoEntity(id = id, title = "Кампания $id", dateEnd = end).also { it.status = PromoStatus.active }

    private fun product(id: String, name: String, barcode: String? = null, ipartId: String? = null) = ProductEntity(
        id = id, name = name, brand = "Бренд", mnn = "МНН", volume = "30 мл", price = 2_000,
    ).also { it.barcode = barcode; it.ipartId = ipartId }

    private fun sold(productId: String? = null, barcode: String? = null, sku: String? = null, quantity: Int, name: String = "Товар") =
        EarnData.SoldItem(productId, barcode, sku, name, BigDecimal.valueOf(quantity.toLong()), 1000, 1)
}
