package kz.epharm.posm.repository

import java.time.Instant
import kz.epharm.posm.entity.PosSaleEntity
import kz.epharm.posm.entity.RecommendationEventEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

/**
 * «Рекомендация принята» для счётчиков «Принято» (Dashboard, Lift, реестр аптек).
 *
 * С POSM 1.0.13 (26.06.2026) попап кассы информационный: клавиш «принять/пропустить» нет, касса
 * `/outcome` не вызывает, поэтому `outcome='accepted'` в проде не появляется никогда (0 из 113 тыс.
 * показов на 25.11.2026). Источник истины — реальный чек: когда касса присылает продажу той же сессии
 * с рекомендованным товаром, [kz.epharm.posm.service.RecommendationAttributionService] ставит `sold_at`.
 * Поэтому принятой считаем рекомендацию с проданным товаром; историческое `outcome='accepted'` тоже
 * учитывается (OR, одна строка считается один раз). Сам `outcome` атрибуция не трогает: от него зависят
 * резерв бонуса и сверка выплат.
 */
private const val ACCEPTED_SQL = "(outcome = 'accepted' OR sold_at IS NOT NULL)"

// ── Проекции для агрегации аналитики (реальные данные из таблицы событий) ────
// Алиасы колонок в кавычках ("ruleId") — чтобы PostgreSQL сохранил camelCase и
// Spring Data сопоставил их с геттерами проекции один-в-один.

/** Агрегат по правилу: показы и принятия. convRate считается в сервисе. */
interface RuleAggRow {
    val ruleId: String
    val impressions: Long
    val accepts: Long
}

/** The Rules page per rule: shown, accepted, offer prices of accepted tips (₸) and pharmacies that saw the tip. */
interface RulePageAggRow {
    val ruleId: String
    val impressions: Long
    val accepts: Long
    val revenue: Long
    val pharmacies: Long
}

/** Агрегат по рекомендованному товару: принятия и атрибутированная выручка. */
interface SkuAggRow {
    val sku: String
    val name: String
    val accepts: Long
    val revenue: Long
}

/** Агрегат по аптеке: показы и принятия рекомендаций (для lift и топа аптек). */
interface PharmacyAggRow {
    val pharmacyId: String
    val impressions: Long
    val accepts: Long
}

/** Количество чего-либо по аптеке (например, чеков за период). */
interface PharmacyCountRow {
    val pharmacyId: String
    val cnt: Long
}

/** Сумма завершённых продаж по аптеке за период, в тенге. */
interface PharmacyAmountRow {
    val pharmacyId: String
    val amount: Long
}

/**
 * Одна уже развёрнутая строка журнала показов/продаж. Продажные позиции извлекаются из
 * `pos_sales.items` на стороне PostgreSQL, чтобы API мог читать только запрошенную страницу,
 * а не загружать все чеки за аналитическое окно в память JVM.
 */
interface RecommendationLogRow {
    val id: String
    val eventType: String
    val eventAt: Instant
    val title: String
    val triggerSku: String?
    val triggerName: String?
    val pharmacyId: String
    val pharmacistId: String
    val pharmacistName: String?
    val reportedPharmacistId: String?
    val reportedPharmacistName: String?
    val pharmacistSource: String?
    val amount: Long
    val converted: Boolean
    val secondsToSale: Int?
    val units: Double?
    val saleId: String?
    val receiptStatus: String?
}

@Repository
interface PosSaleRepository : JpaRepository<PosSaleEntity, String> {
    fun findByPharmacyIdAndSessionIdOrderByCreatedAtAsc(pharmacyId: String, sessionId: String): List<PosSaleEntity>

    /** Чеки за период (свежие первыми) — для единого журнала показ+продажа в админке. */
    fun findByPrintedAtGreaterThanEqualOrderByPrintedAtDesc(since: Instant): List<PosSaleEntity>

    /** Чеки кассы за период по каждой аптеке — для receipts30d (реальные данные). */
    @Query(
        nativeQuery = true,
        value = """
            SELECT pharmacy_id AS "pharmacyId", COUNT(*) AS "cnt"
            FROM pos_sales WHERE printed_at >= :since
              AND COALESCE(reward_evidence #>> '{completion,status}', '') <> 'refund' GROUP BY pharmacy_id
        """,
    )
    fun countByPharmacySince(@Param("since") since: Instant): List<PharmacyCountRow>

    /** GMV завершённых чеков за период по каждой аптеке. */
    @Query(
        nativeQuery = true,
        value = """
            SELECT pharmacy_id AS "pharmacyId", COALESCE(SUM(total_amount), 0) AS "amount"
            FROM pos_sales WHERE printed_at >= :since
              AND COALESCE(reward_evidence #>> '{completion,status}', '') <> 'refund' GROUP BY pharmacy_id
        """,
    )
    fun amountByPharmacySince(@Param("since") since: Instant): List<PharmacyAmountRow>

    /**
     * Всего чеков за период по группе аптек (для пилот/контроль-сумм в lift).
     * Native с `>= :since` — согласовано с [countByPharmacySince] (граница окна включительна).
     */
    @Query(
        nativeQuery = true,
        value = """SELECT COUNT(*) FROM pos_sales WHERE pharmacy_id IN (:ids) AND printed_at >= :since
            AND COALESCE(reward_evidence #>> '{completion,status}', '') <> 'refund'""",
    )
    fun countByPharmaciesSince(@Param("ids") ids: Collection<String>, @Param("since") since: Instant): Long
}

@Repository
interface RecommendationEventRepository : JpaRepository<RecommendationEventEntity, String> {
    /** Все события чека — для фильтра «не показывать отклонённое в этом чеке». */
    fun findAllBySessionId(sessionId: String): List<RecommendationEventEntity>

    /** Неатрибутированные показы этого чека — кандидаты на закрытие продажей (V032). */
    fun findBySessionIdAndSoldAtIsNull(sessionId: String): List<RecommendationEventEntity>

    /** События за период (свежие первыми) — для раздела аналитики «Показано рекомендаций». */
    fun findByShownAtGreaterThanEqualOrderByShownAtDesc(since: Instant): List<RecommendationEventEntity>

    /**
     * Единый журнал: показы + каждая позиция завершённого чека. Атрибуцию рекомендации
     * сопоставляем отдельно с каждой рекомендованной позицией: productId → штрихкод →
     * точное legacy-имя без других идентификаторов. Не угадываем первую позицию чека.
     * Каждый чек даёт хотя бы одну строку, поэтому для страницы нужны
     * не более offset + size последних чеков. Сохраняем все чеки на граничном timestamp:
     * порядок их развёрнутых id может отличаться от порядка исходных id (например, префиксы).
     * Итоговые LIMIT/OFFSET применяются уже после UNION и сортировки.
     */
    @Query(
        nativeQuery = true,
        value = """
            WITH receipt_candidates AS MATERIALIZED (
                SELECT *
                FROM pos_sales
                WHERE printed_at >= :since
                ORDER BY printed_at DESC
                FETCH FIRST (:rowOffset + :pageSize) ROWS WITH TIES
            ),
            show_candidates AS (
                SELECT *
                FROM recommendation_events
                WHERE shown_at >= :since
                ORDER BY COALESCE(displayed_at, shown_at) DESC, id DESC
                LIMIT (:rowOffset + :pageSize)
            ),
            attribution_candidates AS (
                SELECT
                       re.sale_id,
                       re.seconds_to_sale,
                       re.recommend_sku AS recommend_product_id,
                       re.recommend_name,
                       p.barcode AS recommend_barcode,
                       COALESCE(p.barcodes, '[]'::jsonb) AS recommend_barcodes
                FROM recommendation_events re
                JOIN receipt_candidates receipt ON receipt.id = re.sale_id
                    AND receipt.pharmacy_id = re.pharmacy_id
                    AND (receipt.pharmacist_id = '' OR re.pharmacist_id = ''
                         OR receipt.pharmacist_id = re.pharmacist_id)
                LEFT JOIN products p ON p.id = re.recommend_sku
                WHERE re.sale_id IS NOT NULL
                  AND re.seconds_to_sale >= 0
                  AND re.sold_at >= :since
                  AND (receipt.reward_evidence #>> '{completion,status}') IS DISTINCT FROM 'refund'
            ),
            matched_attributions AS (
                SELECT s.id AS sale_id,
                       CASE WHEN jsonb_array_length(s.items) = 0 THEN 1
                            ELSE matched.item_ordinality END AS item_ordinality,
                       MIN(attr.seconds_to_sale) AS seconds_to_sale
                FROM receipt_candidates s
                JOIN attribution_candidates attr ON attr.sale_id = s.id
                LEFT JOIN LATERAL (
                    SELECT COALESCE(
                        MIN(candidate.ordinality) FILTER (
                            WHERE NULLIF(attr.recommend_product_id, '') IS NOT NULL
                              AND candidate.value ->> 'productId' = attr.recommend_product_id
                        ),
                        MIN(candidate.ordinality) FILTER (
                            WHERE NULLIF(candidate.value ->> 'productId', '') IS NULL
                              AND NULLIF(candidate.value ->> 'barcode', '') IS NOT NULL
                              AND (candidate.value ->> 'barcode' = attr.recommend_barcode
                                   OR jsonb_exists(attr.recommend_barcodes, candidate.value ->> 'barcode'))
                        ),
                        MIN(candidate.ordinality) FILTER (
                            WHERE NULLIF(candidate.value ->> 'productId', '') IS NULL
                              AND NULLIF(candidate.value ->> 'barcode', '') IS NULL
                              AND BTRIM(COALESCE(candidate.value ->> 'sku', '')) = ''
                              AND BTRIM(LOWER(COALESCE(attr.recommend_name, ''))) <> ''
                              AND BTRIM(LOWER(COALESCE(candidate.value ->> 'name', ''))) =
                                  BTRIM(LOWER(attr.recommend_name))
                        )
                    ) AS item_ordinality
                    FROM jsonb_array_elements(s.items) WITH ORDINALITY candidate(value, ordinality)
                ) matched ON true
                GROUP BY s.id, CASE WHEN jsonb_array_length(s.items) = 0 THEN 1
                                   ELSE matched.item_ordinality END
            ),
            sale_rows AS (
                SELECT s.*,
                       item.value AS item,
                       item.ordinality AS item_ordinality,
                       jsonb_array_length(s.items) AS item_count,
                       attr.seconds_to_sale AS attributed_seconds
                FROM receipt_candidates s
                CROSS JOIN LATERAL jsonb_array_elements(
                    CASE
                        WHEN jsonb_array_length(s.items) = 0 THEN '[{}]'::jsonb
                        ELSE s.items
                    END
                ) WITH ORDINALITY item(value, ordinality)
                LEFT JOIN matched_attributions attr
                    ON attr.sale_id = s.id AND attr.item_ordinality = item.ordinality
            )
            SELECT e.id AS "id",
                   'show' AS "eventType",
                   COALESCE(e.displayed_at, e.shown_at) AS "eventAt",
                   e.recommend_name AS "title",
                   e.trigger_sku AS "triggerSku",
                   e.trigger_name AS "triggerName",
                   e.pharmacy_id AS "pharmacyId",
                   e.pharmacist_id AS "pharmacistId",
                   CAST(NULL AS VARCHAR) AS "pharmacistName",
                   CAST(NULL AS VARCHAR) AS "reportedPharmacistId",
                   CAST(NULL AS VARCHAR) AS "reportedPharmacistName",
                   CAST(NULL AS VARCHAR) AS "pharmacistSource",
                   e.expected_amount AS "amount",
                   (e.sold_at IS NOT NULL) AS "converted",
                   e.seconds_to_sale AS "secondsToSale",
                   CAST(NULL AS DOUBLE PRECISION) AS "units",
                   CAST(NULL AS VARCHAR) AS "saleId",
                   CAST(NULL AS VARCHAR) AS "receiptStatus"
            FROM show_candidates e

            UNION ALL

            SELECT CASE
                       WHEN s.item_count = 0 THEN s.id
                       ELSE s.id || '#' || (s.item_ordinality - 1)::text
                   END AS "id",
                   'sale' AS "eventType",
                   s.printed_at AS "eventAt",
                   CASE
                       WHEN s.item_count = 0 THEN CASE WHEN s.reward_evidence #>> '{completion,status}' = 'refund' THEN 'Возврат' ELSE 'Чек' END
                       WHEN s.reward_evidence #>> '{completion,status}' = 'refund' THEN 'Возврат: ' || COALESCE(NULLIF(s.item ->> 'name', ''), NULLIF(s.item ->> 'sku', ''), 'позиция')
                       ELSE COALESCE(NULLIF(s.item ->> 'name', ''), NULLIF(s.item ->> 'sku', ''), 'позиция')
                   END AS "title",
                   CAST(NULL AS VARCHAR) AS "triggerSku",
                   CAST(NULL AS VARCHAR) AS "triggerName",
                   s.pharmacy_id AS "pharmacyId",
                   s.pharmacist_id AS "pharmacistId",
                   s.pharmacist_name AS "pharmacistName",
                   s.reported_pharmacist_id AS "reportedPharmacistId",
                   s.reported_pharmacist_name AS "reportedPharmacistName",
                   s.pharmacist_source AS "pharmacistSource",
                   CASE
                       WHEN s.item_count = 0 THEN s.total_amount
                       ELSE CAST(COALESCE(NULLIF(s.item ->> 'total', ''), '0') AS BIGINT)
                   END AS "amount",
                   false AS "converted",
                   s.attributed_seconds AS "secondsToSale",
                   CASE
                       WHEN s.item_count > 0
                        AND CAST(COALESCE(NULLIF(s.item ->> 'qty', ''), '0') AS DOUBLE PRECISION) > 0
                       THEN CAST(s.item ->> 'qty' AS DOUBLE PRECISION)
                       ELSE NULL
                   END AS "units",
                   s.id AS "saleId",
                   CASE WHEN s.reward_evidence #>> '{completion,status}' = 'refund'
                        THEN 'refund' ELSE NULL END AS "receiptStatus"
            FROM sale_rows s

            ORDER BY "eventAt" DESC, "id" DESC
            LIMIT :pageSize OFFSET :rowOffset
        """,
    )
    fun findJournalRowsSince(
        @Param("since") since: Instant,
        @Param("pageSize") pageSize: Int,
        @Param("rowOffset") rowOffset: Long,
    ): List<RecommendationLogRow>

    /** Общее число строк после разворачивания каждого чека в позиции. */
    @Query(
        nativeQuery = true,
        value = """
            SELECT
                (SELECT COUNT(*) FROM recommendation_events WHERE shown_at >= :since) +
                (SELECT COALESCE(SUM(GREATEST(jsonb_array_length(items), 1)), 0)
                 FROM pos_sales WHERE printed_at >= :since)
        """,
    )
    fun countJournalRowsSince(@Param("since") since: Instant): Long

    /** Последний показ того же правила для того же конкретного товара-триггера в чеке. */
    fun findFirstBySessionIdAndRuleIdAndTriggerSkuOrderByShownAtDesc(
        sessionId: String,
        ruleId: String,
        triggerSku: String?,
    ): RecommendationEventEntity?

    /**
     * Прогресс цели (Фаза 2): сколько раз фармацевт ПРИНЯЛ это правило с начала периода —
     * для динамического счётчика «N/target» на карточке.
     */
    fun countByPharmacistIdAndRuleIdAndOutcomeRawAndDecidedAtAfter(
        pharmacistId: String,
        ruleId: String,
        outcomeRaw: String,
        decidedAt: java.time.Instant,
    ): Long

    // ── Агрегация для аналитики (реальные данные, ТЗ §3.2) ───────────────────
    // FILTER (WHERE ...) — стандарт PostgreSQL; быстрее, чем несколько запросов.

    // «Принято» везде — [ACCEPTED_SQL]: проданный по чеку товар или историческое outcome='accepted'.

    /** Показы/принятия по правилу за период (для convRate и топа правил). */
    @Query(
        nativeQuery = true,
        value = """
            SELECT rule_id AS "ruleId",
                   COUNT(*) AS "impressions",
                   COUNT(*) FILTER (WHERE $ACCEPTED_SQL) AS "accepts"
            FROM recommendation_events
            WHERE shown_at >= :since
            GROUP BY rule_id
        """,
    )
    fun aggregateByRuleSince(@Param("since") since: Instant): List<RuleAggRow>

    /**
     * The Rules page: the dashboard's shown and «Принято» plus revenue and pharmacies. Kept apart from
     * [aggregateByRuleSince]: COUNT(DISTINCT) costs about 0.4 s over 90 days in production (26.11.2026),
     * the dashboard does not need it.
     */
    @Query(
        nativeQuery = true,
        value = """
            SELECT rule_id AS "ruleId",
                   COUNT(*) AS "impressions",
                   COUNT(*) FILTER (WHERE $ACCEPTED_SQL) AS "accepts",
                   COALESCE(SUM(expected_amount) FILTER (WHERE $ACCEPTED_SQL), 0) AS "revenue",
                   COUNT(DISTINCT pharmacy_id) AS "pharmacies"
            FROM recommendation_events
            WHERE shown_at >= :since
            GROUP BY rule_id
        """,
    )
    fun aggregateRulesPageSince(@Param("since") since: Instant): List<RulePageAggRow>

    /** One rule (its card, a save): the same figures read through idx_rec_events_rule, not across all rules. */
    @Query(
        nativeQuery = true,
        value = """
            SELECT rule_id AS "ruleId",
                   COUNT(*) AS "impressions",
                   COUNT(*) FILTER (WHERE $ACCEPTED_SQL) AS "accepts",
                   COALESCE(SUM(expected_amount) FILTER (WHERE $ACCEPTED_SQL), 0) AS "revenue",
                   COUNT(DISTINCT pharmacy_id) AS "pharmacies"
            FROM recommendation_events
            WHERE rule_id = :ruleId AND shown_at >= :since
            GROUP BY rule_id
        """,
    )
    fun aggregateRuleSince(@Param("ruleId") ruleId: String, @Param("since") since: Instant): List<RulePageAggRow>

    /** Принятия и атрибутированная выручка по рекомендованному товару за период. */
    @Query(
        nativeQuery = true,
        value = """
            SELECT recommend_sku AS "sku",
                   MIN(recommend_name) AS "name",
                   COUNT(*) FILTER (WHERE $ACCEPTED_SQL) AS "accepts",
                   COALESCE(SUM(expected_amount) FILTER (WHERE $ACCEPTED_SQL), 0) AS "revenue"
            FROM recommendation_events
            WHERE shown_at >= :since
            GROUP BY recommend_sku
        """,
    )
    fun aggregateBySkuSince(@Param("since") since: Instant): List<SkuAggRow>

    /** Показы/принятия по аптеке за период (для lift-конверсии и топа аптек). */
    @Query(
        nativeQuery = true,
        value = """
            SELECT pharmacy_id AS "pharmacyId",
                   COUNT(*) AS "impressions",
                   COUNT(*) FILTER (WHERE $ACCEPTED_SQL) AS "accepts"
            FROM recommendation_events
            WHERE shown_at >= :since
            GROUP BY pharmacy_id
        """,
    )
    fun aggregateByPharmacySince(@Param("since") since: Instant): List<PharmacyAggRow>
}
