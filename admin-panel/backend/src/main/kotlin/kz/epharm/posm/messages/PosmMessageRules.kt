package kz.epharm.posm.messages

import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import java.time.Duration
import java.time.Instant

/** A notice as written by HQ, checked and cleaned. */
data class PosmMessageDraft(
    val title: String,
    val text: String,
    val audience: PosmMessageAudience,
    val pharmacyIds: List<String>,
    val pharmacistIds: List<String>,
    val days: Int,
)

/** What a till learns about one notice for one person. */
data class PosmMessageTarget(
    val audience: PosmMessageAudience,
    val pharmacyIds: Set<String>,
    val pharmacistIds: Set<String>,
)

/**
 * Pure rules of HQ notices at the till: limits, cleaning of the text, who a notice is for and what a till shows.
 * No I/O, unit-tested; the SQL in PosmMessageService follows the same targeting rule.
 */
object PosmMessageRules {
    const val TITLE_MAX = 120
    const val TEXT_MAX = 1000
    const val DEFAULT_DAYS = 7
    const val MAX_DAYS = 30
    const val MAX_PHARMACIES = 1000
    const val MAX_PHARMACISTS = 2000
    const val ID_MAX = 64

    /** The till's history covers this many days and at most this many notices. */
    const val HISTORY_DAYS = 30L
    const val HISTORY_LIMIT = 20

    /** At most this many rows are read for one person; pending ones always come first. */
    const val PERSON_READ_LIMIT = 100

    const val DEFAULT_LIST_LIMIT = 50
    const val MAX_LIST_LIMIT = 200

    /** Names of chosen pharmacies or pharmacists shown per notice in the admin list. */
    const val TARGET_NAMES = 5

    private val BLANK_LINES = Regex("\n{3,}")
    private val INNER_SPACES = Regex("[ \t]+")

    fun draft(request: CreatePosmMessageRequest): PosmMessageDraft {
        val title = title(request.title)
        val text = text(request.text)
        val audience = PosmMessageAudience.fromWire(request.audience)
            ?: invalid("audience: допустимы all, pharmacies и pharmacists")
        val days = days(request.days)
        return when (audience) {
            PosmMessageAudience.ALL -> PosmMessageDraft(title, text, audience, emptyList(), emptyList(), days)
            PosmMessageAudience.PHARMACIES -> PosmMessageDraft(
                title, text, audience, ids(request.pharmacyIds, "pharmacyIds", MAX_PHARMACIES, "Выберите хотя бы одну аптеку"),
                emptyList(), days,
            )
            PosmMessageAudience.PHARMACISTS -> PosmMessageDraft(
                title, text, audience, emptyList(),
                ids(request.pharmacistIds, "pharmacistIds", MAX_PHARMACISTS, "Выберите хотя бы одного сотрудника"), days,
            )
        }
    }

    /** One line: control characters removed, spaces collapsed, at most 120 characters. */
    fun title(raw: String?): String {
        val clean = INNER_SPACES.replace(strip(raw.orEmpty(), keepLineBreaks = false), " ").trim()
        if (clean.isEmpty()) invalid("Введите заголовок")
        if (clean.codePointCount(0, clean.length) > TITLE_MAX) invalid("Заголовок до $TITLE_MAX знаков")
        return clean
    }

    /** Paragraphs kept (at most one empty line between them), trailing spaces cut, at most 1000 characters. */
    fun text(raw: String?): String {
        val lines = strip(raw.orEmpty().replace("\r\n", "\n").replace('\r', '\n'), keepLineBreaks = true)
            .split('\n')
            .map { INNER_SPACES.replace(it, " ").trim() }
        val clean = BLANK_LINES.replace(lines.joinToString("\n"), "\n\n").trim()
        if (clean.isEmpty()) invalid("Введите текст уведомления")
        if (clean.codePointCount(0, clean.length) > TEXT_MAX) invalid("Текст до $TEXT_MAX знаков")
        return clean
    }

    fun days(raw: Int?): Int {
        val days = raw ?: DEFAULT_DAYS
        if (days !in 1..MAX_DAYS) invalid("Срок показа от 1 до $MAX_DAYS дней")
        return days
    }

    /** Trimmed, without duplicates, in the order HQ chose them; empty or too many is refused. */
    fun ids(raw: List<String>?, field: String, max: Int, emptyMessage: String): List<String> {
        val ids = raw.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (ids.isEmpty()) invalid(emptyMessage)
        if (ids.size > max) invalid("$field: не больше $max")
        ids.firstOrNull { it.length > ID_MAX || it.any(Char::isISOControl) }?.let { invalid("$field: неверный идентификатор") }
        return ids
    }

    fun listLimit(raw: Int?): Int = (raw ?: DEFAULT_LIST_LIMIT).coerceIn(1, MAX_LIST_LIMIT)

    fun expiresAt(createdAt: Instant, days: Int): Instant = createdAt.plus(Duration.ofDays(days.toLong()))

    /**
     * Whether a notice is for this person at this till. «pharmacies» reaches whoever signs in at a till of a chosen
     * pharmacy and the staff of a chosen pharmacy wherever they sign in (their card's pharmacy).
     */
    fun isFor(target: PosmMessageTarget, pharmacistId: String, homePharmacyId: String?, tillPharmacyId: String): Boolean =
        when (target.audience) {
            PosmMessageAudience.ALL -> true
            PosmMessageAudience.PHARMACIES ->
                tillPharmacyId in target.pharmacyIds || (homePharmacyId != null && homePharmacyId in target.pharmacyIds)
            PosmMessageAudience.PHARMACISTS -> pharmacistId in target.pharmacistIds
        }

    /** Still delivered to tills: not withdrawn and not past its last day. */
    fun isActive(expiresAt: Instant, withdrawnAt: Instant?, now: Instant): Boolean = withdrawnAt == null && expiresAt.isAfter(now)

    /** Waiting for the person: active and not closed by them yet. */
    fun isPending(row: PosmPersonMessageRow, now: Instant): Boolean = row.closedAt == null && row.expiresAt.isAfter(now)

    /**
     * What the till gets for one person: every notice of the last 30 days, newest first, at most 20, but every
     * pending one is always included (a person who missed many notices still sees each of them).
     */
    fun forPerson(rows: List<PosmPersonMessageRow>, now: Instant): PosmSelfMessagesDto {
        val since = now.minus(Duration.ofDays(HISTORY_DAYS))
        val recent = rows.filter { !it.createdAt.isBefore(since) }
            .sortedWith(compareByDescending<PosmPersonMessageRow> { it.createdAt }.thenByDescending { it.id })
        val pending = recent.filter { isPending(it, now) }
        val history = recent.filterNot { isPending(it, now) }.take((HISTORY_LIMIT - pending.size).coerceAtLeast(0))
        val shown = (pending + history).sortedWith(compareByDescending<PosmPersonMessageRow> { it.createdAt }.thenByDescending { it.id })
        return PosmSelfMessagesDto(
            messages = shown.map { PosmSelfMessageDto(it.id, it.title, it.text, it.createdAt, it.closedAt, isPending(it, now)) },
            pendingCount = pending.size,
        )
    }

    private fun strip(raw: String, keepLineBreaks: Boolean): String {
        val out = StringBuilder(raw.length)
        var index = 0
        while (index < raw.length) {
            val codePoint = raw.codePointAt(index)
            index += Character.charCount(codePoint)
            when {
                codePoint == '\n'.code -> out.append(if (keepLineBreaks) '\n' else ' ')
                codePoint == '\t'.code -> out.append(' ')
                Character.isISOControl(codePoint) -> Unit
                Character.getType(codePoint) == Character.SURROGATE.toInt() -> Unit
                Character.getType(codePoint) == Character.FORMAT.toInt() -> Unit
                else -> out.appendCodePoint(codePoint)
            }
        }
        return out.toString()
    }

    private fun invalid(message: String): Nothing = throw AppException(ErrorCode.VALIDATION_FAILED, message)
}
