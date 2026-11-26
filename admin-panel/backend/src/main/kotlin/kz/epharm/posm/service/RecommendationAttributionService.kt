package kz.epharm.posm.service

import java.time.Duration
import kz.epharm.posm.entity.PosSaleEntity
import kz.epharm.posm.entity.RecommendationOutcome
import kz.epharm.posm.repository.RecommendationEventRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Атрибуция продажи к показанной рекомендации (Задача 1 + 1.2).
 *
 * При приёме завершённого чека ищем показанные, ещё не атрибутированные рекомендации ЭТОЙ ЖЕ
 * сессии и проверяем, оказался ли рекомендованный товар в чеке. Если да — фиксируем:
 *   - sold_at         = printed_at чека (когда продали),
 *   - sale_id         = id чека (трассировка),
 *   - seconds_to_sale = время от показа до продажи (Задача 1.2).
 *
 * Корреляция по `session_id` (одна сессия = один чек) надёжнее окна по времени: внутри одного
 * чека показ и продажа заведомо связаны. Матчинг «продан именно рекомендованный товар» — по нашему
 * productId: recommendSku хранит productId (как в /recommend), позиции чека резолвятся в productId
 * тем же коллизионно-устойчивым матчером (штрих-код→iPartID→имя). Без угадывания.
 *
 * Это ЧИСТАЯ аналитика — pending_bonuses / outcome не трогаем. Метод вызывается в транзакции
 * приёма чека (PosSaleService.record), после сохранения pos_sale.
 *
 * Дубли одного товара считаются одним товаром (26.11.2026): в products лежат запись товара с сайта
 * (prod_01K..., часто с локальным кодом Стандарт-Н 8811000...) и запись Daribar (prod_Daribar..., с EAN).
 * Совет рекомендует одну, касса продаёт другую, и продажа терялась: из 9 настоящих продаж по советам за 30 дней
 * не засчитывались 6. Теперь продажа засчитывается, если проданный товар это рекомендованный или его дубль
 * (то же нормализованное имя либо общий штрих-код, см. RulesEngineService.canonicalProductIds).
 */
@Service
class RecommendationAttributionService(
    private val eventRepository: RecommendationEventRepository,
    private val products: RulesEngineService,
) {
    private val log = LoggerFactory.getLogger(RecommendationAttributionService::class.java)

    /**
     * @param sale            сохранённый чек (источник session_id / printed_at / sale_id).
     * @param soldProductIds  productId позиций чека, уже разрезолвленные (из PosSaleService).
     */
    fun attributeSale(sale: PosSaleEntity, soldProductIds: Set<String>) {
        if (sale.rewardEvidence?.completion?.status == "refund" || soldProductIds.isEmpty()) return
        val sessionId = sale.sessionId
        if (sessionId.isNullOrBlank() || sale.pharmacyId.isBlank()) return

        val candidates = eventRepository.findBySessionIdAndSoldAtIsNull(sessionId)
        if (candidates.isEmpty()) return

        // Receipt IDs are generated at the register, not globally scoped identities.
        // Unknown sellers still have analytical conversion; conflicting known people do not.
        val eligible = candidates
            .filter { it.pharmacyId == sale.pharmacyId &&
                (it.pharmacistId.isBlank() || sale.pharmacistId.isBlank() || it.pharmacistId == sale.pharmacistId) }
            .filter { it.recommendSku.isNotBlank() }
        if (eligible.isEmpty()) return

        // A product is one product however many records it has: a tip for one record is sold by the other.
        val canonical = products.canonicalProductIds(soldProductIds + eligible.map { it.recommendSku })
        fun productOf(id: String) = canonical[id] ?: id
        val soldProducts = soldProductIds.mapTo(HashSet(), ::productOf)

        // One sold product can close only one recommendation in a receipt. Group scopes may have
        // shown the same offer for two different scanned products; attributing both would inflate
        // conversion/revenue. Prefer the accepted event, otherwise the most recent actual show.
        val winners = eligible
            .filter { productOf(it.recommendSku) in soldProducts }
            .groupBy { productOf(it.recommendSku) }
            .values.mapNotNull { group ->
                group.maxWithOrNull(
                    compareBy(
                        { if (it.outcome == RecommendationOutcome.accepted) 1 else 0 },
                        { it.displayedAt ?: it.shownAt },
                        { it.shownAt },
                        { it.id },
                    ),
                )
            }
        var attributed = 0
        for (ev in winners) {
            val origin = ev.displayedAt ?: ev.shownAt
            ev.soldAt = sale.printedAt
            ev.saleId = sale.id
            // Keep the conversion, but unknown/skewed timings must not become a zero-second sale.
            // Also avoid wrapping a malformed very large interval into an Int measurement.
            val duration = Duration.between(origin, sale.printedAt)
            ev.secondsToSale = duration.seconds.takeIf { !duration.isNegative && it <= Int.MAX_VALUE }?.toInt()
            eventRepository.save(ev)
            attributed++
        }
        if (attributed > 0) {
            log.info(
                "Атрибуция чека {}: рекомендаций закрыто продажей = {} (сессия {})",
                sale.id, attributed, sessionId,
            )
        }
    }
}
