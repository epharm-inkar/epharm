package kz.epharm.finance.premium

import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.LocalDate
import kz.epharm.auth.security.AdminPrincipal
import kz.epharm.merch.commercial.CommercialCsvParser
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * The matrix file of a month, the format of the owner's list: a header with `ware_id` (Standard-N product id, GUID),
 * `bonus_per_pack` (tenge, empty means zero) and optionally `promo_name`; other columns are ignored. Comma or
 * semicolon, UTF-8 with or without a BOM.
 */
object SalesPremiumMatrixCsv {
    const val MAX_BYTES = 1_000_000
    const val MAX_ITEMS = 2000
    const val MAX_NAME = 255

    /** Far above any real bonus per pack; it only guards against a unit mistake. */
    const val MAX_BONUS = 100_000L

    private const val MAX_PROBLEMS = 10
    private val WARE = Regex("[0-9A-F]{8}(-[0-9A-F]{4}){3}-[0-9A-F]{12}")
    private val SPACES = Regex("[\\s\\u00A0\\u202F]")

    fun parse(bytes: ByteArray): List<MatrixItem> {
        if (bytes.isEmpty()) invalid("Файл пустой")
        if (bytes.size > MAX_BYTES) invalid("Файл больше ${MAX_BYTES / 1000} КБ")
        val table = CommercialCsvParser.parse(decode(bytes))
        val header = table.first().map(CommercialCsvParser::normalize)
        val wareColumn = column(header, "ware_id", required = true)!!
        val bonusColumn = column(header, "bonus_per_pack", required = true)!!
        val nameColumn = column(header, "promo_name") ?: column(header, "name")

        val problems = mutableListOf<String>()
        val items = ArrayList<MatrixItem>(table.size - 1)
        val firstSeen = HashMap<String, Int>()
        table.drop(1).forEachIndexed { index, cells ->
            val number = index + 2
            val ware = cells.getOrElse(wareColumn) { "" }.trim().uppercase()
            val name = nameColumn?.let { cells.getOrElse(it) { "" } }?.trim().orEmpty()
            val bonus = bonus(cells.getOrElse(bonusColumn) { "" })
            val problem = when {
                !WARE.matches(ware) -> "id товара должен быть GUID вида XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX"
                name.length > MAX_NAME -> "название длиннее $MAX_NAME знаков"
                bonus == null -> "бонус за упаковку должен быть числом тенге от 0 до $MAX_BONUS, не больше двух знаков после запятой"
                else -> null
            }
            when {
                problem != null -> problems += "строка $number: $problem"
                firstSeen.putIfAbsent(ware, number) != null -> problems += "строка $number: этот товар уже есть в строке ${firstSeen[ware]}"
                else -> items += MatrixItem(ware, name, bonus!!)
            }
        }
        if (problems.isNotEmpty()) {
            val shown = problems.take(MAX_PROBLEMS).joinToString("; ")
            invalid(if (problems.size > MAX_PROBLEMS) "$shown; и ещё ${problems.size - MAX_PROBLEMS}" else shown)
        }
        if (items.size > MAX_ITEMS) invalid("В матрице больше $MAX_ITEMS товаров")
        return items
    }

    private fun decode(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        invalid("Файл должен быть в кодировке UTF-8 (в Excel: «CSV UTF-8»)")
    }

    private fun column(header: List<String>, name: String, required: Boolean = false): Int? {
        val indices = header.indices.filter { header[it] == name }
        if (indices.size > 1) invalid("Колонка $name встречается несколько раз")
        if (indices.isEmpty() && required) invalid("В заголовке нет колонки $name")
        return indices.singleOrNull()
    }

    /** Empty is zero; a decimal comma is read as a point; spaces (thousands) are dropped. Null when not a valid bonus. */
    private fun bonus(raw: String): BigDecimal? {
        val text = SPACES.replace(raw, "").replace(',', '.')
        if (text.isEmpty()) return BigDecimal.ZERO
        val value = text.toBigDecimalOrNull() ?: return null
        val valid = value.signum() >= 0 && value <= BigDecimal.valueOf(MAX_BONUS) && value.stripTrailingZeros().scale() <= 2
        return if (valid) value.setScale(2) else null
    }

    private fun invalid(message: String): Nothing = throw AppException(ErrorCode.VALIDATION_FAILED, message)
}

/** The matrix table. A month's matrix is read whole and replaced whole. */
@Service
class SalesPremiumMatrixStore(private val jdbc: NamedParameterJdbcTemplate) {
    @Transactional(readOnly = true)
    fun items(period: String): List<MatrixItem> = jdbc.query(
        "SELECT ware_id, name, bonus_per_pack FROM sales_premium_matrix WHERE period = :period ORDER BY ware_id",
        mapOf("period" to period),
    ) { rs, _ -> MatrixItem(rs.getString("ware_id"), rs.getString("name"), rs.getBigDecimal("bonus_per_pack")) }

    @Transactional
    fun replace(period: String, items: List<MatrixItem>) {
        jdbc.update("DELETE FROM sales_premium_matrix WHERE period = :period", mapOf("period" to period))
        val batch = items.map { item ->
            MapSqlParameterSource().addValue("period", period).addValue("ware", item.wareId)
                .addValue("name", item.name).addValue("bonus", item.bonusPerPack)
        }.toTypedArray()
        jdbc.batchUpdate(
            "INSERT INTO sales_premium_matrix(period, ware_id, name, bonus_per_pack) VALUES (:period, :ware, :name, :bonus)",
            batch,
        )
    }

    /** The cut-off of the latest real import of the month, or null while nothing was loaded for it. */
    @Transactional(readOnly = true)
    fun loadedThrough(period: String): LocalDate? = jdbc.queryForObject(
        "SELECT max(through) FROM sales_premium_imports WHERE period = :period", mapOf("period" to period),
    ) { rs, _ -> rs.getObject(1, LocalDate::class.java) }
}

/**
 * What an upload did (or, for a dry run, would do). [before] is what the month had; [added], [removed] and [changed]
 * compare the products and their bonuses; [loadedThrough] is the cut-off of the figures already loaded for the month:
 * a changed matrix re-prices the whole month from the 1st at the next calculation, so the difference is credited or
 * taken back for sales before the change as well.
 */
data class MatrixUploadResult(
    val period: String,
    val dryRun: Boolean,
    val products: Int,
    val withBonus: Int,
    val zeroBonus: Int,
    val before: Int,
    val added: Int,
    val removed: Int,
    val changed: Int,
    val loadedThrough: LocalDate?,
)

@Service
class SalesPremiumMatrixService(private val store: SalesPremiumMatrixStore) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun upload(period: String, bytes: ByteArray, dryRun: Boolean, actor: AdminPrincipal): MatrixUploadResult {
        val month = SalesPremiumRules.requirePeriod(period)
        val items = SalesPremiumMatrixCsv.parse(bytes)
        val before = store.items(month).associateBy { it.wareId }
        val after = items.associateBy { it.wareId }
        val result = MatrixUploadResult(
            period = month,
            dryRun = dryRun,
            products = items.size,
            withBonus = items.count { it.bonusPerPack.signum() > 0 },
            zeroBonus = items.count { it.bonusPerPack.signum() == 0 },
            before = before.size,
            added = after.keys.count { it !in before },
            removed = before.keys.count { it !in after },
            changed = after.count { (ware, item) -> before[ware]?.let { it.bonusPerPack.compareTo(item.bonusPerPack) != 0 } == true },
            loadedThrough = store.loadedThrough(month),
        )
        if (!dryRun) {
            store.replace(month, items)
            log.info(
                "Sales premium matrix {} replaced by {}: products={} withBonus={} added={} removed={} changed={} loadedThrough={}",
                month, actor.userId, result.products, result.withBonus, result.added, result.removed, result.changed, result.loadedThrough,
            )
        }
        return result
    }
}
