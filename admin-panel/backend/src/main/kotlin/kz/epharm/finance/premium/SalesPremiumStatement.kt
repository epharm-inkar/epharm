package kz.epharm.finance.premium

import java.math.BigDecimal
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * One line of the month statement. [credited] lines are the net premium in the wallet ledger; the others wait in
 * `sales_premium_pending` for a card (they are earned, not yet on a wallet). [packs] comes from the latest loaded figure.
 */
data class StatementRow(
    val name: String,
    val iin: String,
    val pharmacy: String,
    val city: String,
    val packs: BigDecimal?,
    val amountKzt: Long,
    val credited: Boolean,
)

/** The statement as a semicolon-separated UTF-8 file with a BOM: what Excel with a Russian locale opens correctly. */
object SalesPremiumStatementCsv {
    const val HEADER = "ФИО;ИИН;аптека;город;упаковок;начислено;статус"
    const val STATUS_CREDITED = "на кошельке"
    const val STATUS_WAITING = "ждёт карточку"

    private const val BOM = "﻿"
    private const val EOL = "\r\n"
    private val FORMULA_START = setOf('=', '+', '-', '@', '\t', '\r')

    fun render(rows: List<StatementRow>): ByteArray {
        val out = StringBuilder(BOM).append(HEADER).append(EOL)
        for (row in rows) {
            out.append(
                listOf(
                    cell(plain(row.name)), cell(iin(row.iin)), cell(plain(row.pharmacy)), cell(plain(row.city)),
                    cell(packs(row.packs)), row.amountKzt.toString(), if (row.credited) STATUS_CREDITED else STATUS_WAITING,
                ).joinToString(";"),
            ).append(EOL)
        }
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    /** A name that starts like a formula is a spreadsheet injection: a leading apostrophe makes it plain text. */
    private fun plain(value: String): String = value.trim().let { if (it.isNotEmpty() && it.first() in FORMULA_START) "'$it" else it }

    /** Excel turns 050112345678 into the number 50112345678 and loses the zero; the text formula keeps all 12 digits. */
    private fun iin(value: String): String = "=\"$value\""

    /** A decimal comma goes with the semicolon; whole numbers carry no fraction. */
    private fun packs(value: BigDecimal?): String = value?.stripTrailingZeros()?.toPlainString()?.replace('.', ',').orEmpty()

    private fun cell(value: String): String =
        if (value.any { it == ';' || it == '"' || it == '\n' || it == '\r' }) "\"${value.replace("\"", "\"\"")}\"" else value
}

/** Reads the statement of a month from the wallet ledger and the pending table. */
@Service
class SalesPremiumStatement(private val jdbc: NamedParameterJdbcTemplate) {

    /**
     * The credited premium per pharmacist (net of corrections) and, per waiting figure, what is not credited yet.
     * Names of people who wait for a card come from the Standard-N directory snapshot, as there is no card to read.
     * The packs of a person stand on the credited line only, so that the lines of one person never add them twice.
     */
    @Transactional(readOnly = true)
    fun rows(period: String): List<StatementRow> = jdbc.query(
        """WITH ledger AS (
                SELECT e.pharmacist_id, SUM(e.amount_kzt) AS credited
                FROM pharmacist_wallet_entries e
                WHERE e.context_key = :context AND e.source IN ('sales_premium', 'sales_premium_correction')
                GROUP BY e.pharmacist_id
                HAVING SUM(e.amount_kzt) <> 0
            ), by_iin AS (
                SELECT ph.iin, SUM(l.credited) AS credited
                FROM ledger l JOIN pharmacists ph ON ph.id = l.pharmacist_id
                GROUP BY ph.iin
            )
            SELECT ph.name AS full_name, ph.iin AS iin,
                   COALESCE(NULLIF(ph.pharmacy_name, ''), pm.name, '') AS pharmacy,
                   COALESCE(NULLIF(ph.city, ''), pm.city, '') AS city,
                   f.packs AS packs, l.credited AS amount, true AS credited
            FROM ledger l
            JOIN pharmacists ph ON ph.id = l.pharmacist_id
            LEFT JOIN pharmacies pm ON pm.id = ph.pharmacy_id
            LEFT JOIN sales_premium_figures f ON f.iin = ph.iin AND f.period = :period
            UNION ALL
            SELECT COALESCE(d.full_name, ''), p.iin, COALESCE(d.profile_name, ''), COALESCE(d.profile_city, ''),
                   CASE WHEN b.iin IS NULL THEN f.packs END, p.amount_kzt - COALESCE(b.credited, 0), false
            FROM sales_premium_pending p
            LEFT JOIN by_iin b ON b.iin = p.iin
            LEFT JOIN LATERAL (
                SELECT full_name, profile_name, profile_city FROM standardn_pharmacist_directory
                WHERE iin = p.iin ORDER BY source_status, imported_at DESC LIMIT 1
            ) d ON true
            LEFT JOIN sales_premium_figures f ON f.iin = p.iin AND f.period = p.period
            WHERE p.period = :period AND p.amount_kzt - COALESCE(b.credited, 0) > 0
            ORDER BY city, pharmacy, full_name""",
        mapOf("period" to period, "context" to SalesPremiumRules.contextKey(period)),
    ) { rs, _ ->
        StatementRow(
            name = rs.getString("full_name"), iin = rs.getString("iin"), pharmacy = rs.getString("pharmacy"),
            city = rs.getString("city"), packs = rs.getBigDecimal("packs"), amountKzt = rs.getLong("amount"),
            credited = rs.getBoolean("credited"),
        )
    }

    fun csv(period: String): ByteArray = SalesPremiumStatementCsv.render(rows(period))
}
