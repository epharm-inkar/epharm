package kz.epharm.mobile.earn

import kz.epharm.mobile.earn.EarnData.ItemKeys
import kz.epharm.mobile.earn.EarnData.LeaderRow
import kz.epharm.mobile.earn.EarnData.SoldItem
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementCreator
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant

/** The reads of the «Бонусы» tab: receipts (`pos_sales`) and sales after the till's suggestions (`recommendation_events`). */
interface EarnData {
    /** How a receipt line names a product: catalogue id, barcode, Standard-N id (any may be empty). */
    data class ItemKeys(val productIds: Collection<String>, val barcodes: Collection<String>, val skus: Collection<String>)

    /** A receipt line group: its keys and how much of it went out. */
    data class SoldItem(
        val productId: String?, val barcode: String?, val sku: String?, val name: String,
        val quantity: BigDecimal, val sumKzt: Long, val receipts: Int,
    )

    data class LeaderRow(val id: String, val name: String, val pharmacy: String?, val city: String?, val earnedKzt: Long, val sales: Int)

    /** Lines of the whole network since [since] that match [keys]. */
    fun networkSold(since: Instant, keys: ItemKeys): List<SoldItem>

    /** Lines of one pharmacy since [since] that match [keys]. */
    fun pharmacySold(pharmacyId: String, since: Instant, keys: ItemKeys): List<SoldItem>

    /** The pharmacist's own receipt lines since [since], most sold first. */
    fun pharmacistSold(pharmacistId: String, since: Instant, limit: Int): List<SoldItem>

    fun pharmacistReceipts(pharmacistId: String, since: Instant): Int

    fun hintSales(pharmacistId: String, since: Instant, limit: Int): List<EarnHintSaleDto>

    /** Bonus and number of sales after the till's suggestions since [since]. */
    fun hintTotals(pharmacistId: String, since: Instant): Pair<Long, Int>

    /** Pharmacists (or pharmacies) by the bonus of their sales after suggestions since [since], the best first. */
    fun leaders(since: Instant, byPharmacy: Boolean): List<LeaderRow>
}

@Component
class JdbcEarnData(private val jdbc: JdbcTemplate) : EarnData {
    private val soldMapper = RowMapper { rs, _ ->
        SoldItem(
            productId = rs.getString("product_id"), barcode = rs.getString("barcode"), sku = rs.getString("sku"),
            name = rs.getString("name") ?: "", quantity = rs.getBigDecimal("qty") ?: BigDecimal.ZERO,
            sumKzt = rs.getBigDecimal("total")?.toLong() ?: 0L, receipts = rs.getInt("receipts"),
        )
    }

    override fun networkSold(since: Instant, keys: ItemKeys): List<SoldItem> =
        matching("s.printed_at >= ?", listOf(Timestamp.from(since)), keys)

    override fun pharmacySold(pharmacyId: String, since: Instant, keys: ItemKeys): List<SoldItem> =
        matching("s.pharmacy_id = ? and s.printed_at >= ?", listOf(pharmacyId, Timestamp.from(since)), keys)

    /** Lines whose catalogue id, barcode or Standard-N id is one of [keys], grouped by those three. */
    private fun matching(where: String, args: List<Any>, keys: ItemKeys): List<SoldItem> {
        if (keys.productIds.isEmpty() && keys.barcodes.isEmpty() && keys.skus.isEmpty()) return emptyList()
        val sql = """
            select i->>'productId' as product_id, i->>'barcode' as barcode, i->>'sku' as sku, max(i->>'name') as name,
                   sum(coalesce((i->>'qty')::numeric, 1)) as qty, sum(coalesce((i->>'total')::numeric, 0)) as total,
                   count(distinct s.id) as receipts
              from pos_sales s
             cross join lateral jsonb_array_elements(case when jsonb_typeof(s.items) = 'array' then s.items else '[]'::jsonb end) i
             where $where
               and (i->>'productId' = any(?) or i->>'barcode' = any(?) or i->>'sku' = any(?))
             group by 1, 2, 3
        """.trimIndent()
        return jdbc.query(
            PreparedStatementCreator { connection ->
                connection.prepareStatement(sql).also { ps ->
                    args.forEachIndexed { index, value -> ps.setObject(index + 1, value) }
                    ps.setArray(args.size + 1, connection.createArrayOf("text", keys.productIds.toTypedArray()))
                    ps.setArray(args.size + 2, connection.createArrayOf("text", keys.barcodes.toTypedArray()))
                    ps.setArray(args.size + 3, connection.createArrayOf("text", keys.skus.toTypedArray()))
                }
            },
            soldMapper,
        )
    }

    // Read through the partial (pharmacist_id, printed_at) index: its condition is spelled out.
    override fun pharmacistSold(pharmacistId: String, since: Instant, limit: Int): List<SoldItem> = jdbc.query(
        """
        select max(i->>'productId') as product_id, max(i->>'barcode') as barcode, max(i->>'sku') as sku,
               max(i->>'name') as name, sum(coalesce((i->>'qty')::numeric, 1)) as qty,
               sum(coalesce((i->>'total')::numeric, 0)) as total, count(distinct s.id) as receipts
          from pos_sales s
         cross join lateral jsonb_array_elements(case when jsonb_typeof(s.items) = 'array' then s.items else '[]'::jsonb end) i
         where s.pharmacist_id = ? and s.pharmacist_id <> '' and s.printed_at >= ?
         group by coalesce(nullif(i->>'sku', ''), nullif(i->>'barcode', ''), i->>'name')
         order by 5 desc, 4
         limit ?
        """.trimIndent(),
        soldMapper, pharmacistId, Timestamp.from(since), limit,
    )

    override fun pharmacistReceipts(pharmacistId: String, since: Instant): Int = jdbc.queryForObject(
        "select count(*) from pos_sales where pharmacist_id = ? and pharmacist_id <> '' and printed_at >= ?",
        Int::class.java, pharmacistId, Timestamp.from(since),
    ) ?: 0

    override fun hintSales(pharmacistId: String, since: Instant, limit: Int): List<EarnHintSaleDto> = jdbc.query(
        """
        select sold_at, recommend_name, bonus from recommendation_events
         where pharmacist_id = ? and sold_at >= ?
         order by sold_at desc limit ?
        """.trimIndent(),
        { rs, _ -> EarnHintSaleDto(rs.getTimestamp(1).toInstant(), rs.getString(2) ?: "", rs.getInt(3)) },
        pharmacistId, Timestamp.from(since), limit,
    )

    override fun hintTotals(pharmacistId: String, since: Instant): Pair<Long, Int> = jdbc.queryForObject(
        "select coalesce(sum(bonus), 0), count(*) from recommendation_events where pharmacist_id = ? and sold_at >= ?",
        { rs, _ -> rs.getLong(1) to rs.getInt(2) }, pharmacistId, Timestamp.from(since),
    ) ?: (0L to 0)

    override fun leaders(since: Instant, byPharmacy: Boolean): List<LeaderRow> {
        val sql = if (byPharmacy) {
            """
            select e.pharmacy_id as id, coalesce(max(ph.name), '') as name, max(ph.name) as pharmacy, max(ph.city) as city,
                   sum(e.bonus) as earned, count(*) as sales
              from recommendation_events e
              left join pharmacies ph on ph.id = e.pharmacy_id
             where e.sold_at >= ? and coalesce(e.pharmacy_id, '') <> ''
             group by e.pharmacy_id
             order by earned desc, sales desc, id
            """
        } else {
            """
            select e.pharmacist_id as id, max(p.name) as name, max(ph.name) as pharmacy, max(ph.city) as city,
                   sum(e.bonus) as earned, count(*) as sales
              from recommendation_events e
              join pharmacists p on p.id = e.pharmacist_id
              left join pharmacies ph on ph.id = e.pharmacy_id
             where e.sold_at >= ? and coalesce(e.pharmacist_id, '') <> ''
             group by e.pharmacist_id
             order by earned desc, sales desc, id
            """
        }
        return jdbc.query(
            sql.trimIndent(),
            { rs, _ ->
                LeaderRow(
                    rs.getString("id"), rs.getString("name") ?: "", rs.getString("pharmacy"), rs.getString("city"),
                    rs.getLong("earned"), rs.getInt("sales"),
                )
            },
            Timestamp.from(since),
        )
    }
}
