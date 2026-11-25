package kz.epharm.posm.commands

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus

/** Pure rules of till commands: limits, validation and lifecycle decisions. No I/O, unit-tested. */
object TillCommandRules {
    /** A waiting command may be taken for 30 minutes after HQ pressed the button. */
    const val DELIVERY_MINUTES = 30L
    /** A taken command must be answered within 10 minutes of being taken. */
    const val RESULT_MINUTES = 10L
    const val ARCHIVE_RETENTION_DAYS = 14L
    const val MAX_MESSAGE_CHARS = 2000
    const val MAX_ARCHIVE_BYTES = 5L * 1024 * 1024
    const val DEFAULT_LIST_LIMIT = 50
    const val MAX_LIST_LIMIT = 500
    const val ARCHIVE_PREFIX = "till-diagnostics/"

    private val ARCHIVE_KEY = Regex("""till-diagnostics/[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}/[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}\.zip""")
    private val FILE_NAME_UNSAFE = Regex("[^A-Za-z0-9._-]+")
    private val FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(ZoneId.of("Asia/Almaty"))

    enum class ResultDecision { ACCEPT, NOT_FOUND, NOT_TAKEN, ALREADY_FINISHED, EXPIRED }

    fun action(raw: String?): TillCommandAction =
        TillCommandAction.fromWire(raw) ?: invalid("action: допустимы diagnostics и restart")

    fun resultStatus(raw: String?): TillCommandStatus = when (val status = TillCommandStatus.fromWire(raw)) {
        TillCommandStatus.DONE, TillCommandStatus.FAILED -> status
        else -> invalid("status: допустимы done и failed")
    }

    fun requiredId(raw: String?, field: String, maxLength: Int): String {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty() || value.length > maxLength || value.any(Char::isISOControl)) {
            invalid("$field: обязательное поле, не длиннее $maxLength символов")
        }
        return value
    }

    fun optionalId(raw: String?, field: String, maxLength: Int): String? =
        raw?.takeIf { it.isNotBlank() }?.let { requiredId(it, field, maxLength) }

    fun listLimit(raw: Int?): Int = (raw ?: DEFAULT_LIST_LIMIT).coerceIn(1, MAX_LIST_LIMIT)

    /**
     * The till's message as stored: control characters (except line breaks and tabs) and broken surrogates removed,
     * trimmed, cut to 2000 characters. A longer text is cut rather than refused, so the result itself is not lost.
     */
    fun message(raw: String?): String? {
        if (raw == null) return null
        val cleaned = StringBuilder(minOf(raw.length, MAX_MESSAGE_CHARS * 2))
        var index = 0
        while (index < raw.length) {
            val codePoint = raw.codePointAt(index)
            index += Character.charCount(codePoint)
            val whitespace = codePoint == '\n'.code || codePoint == '\r'.code || codePoint == '\t'.code
            val broken = Character.getType(codePoint) == Character.SURROGATE.toInt()
            if (whitespace || !(Character.isISOControl(codePoint) || broken)) cleaned.appendCodePoint(codePoint)
        }
        val text = cleaned.toString().trim()
        if (text.isEmpty()) return null
        if (text.codePointCount(0, text.length) <= MAX_MESSAGE_CHARS) return text
        return text.substring(0, text.offsetByCodePoints(0, MAX_MESSAGE_CHARS)).trimEnd()
    }

    /** ZIP signature: a local file header (PK 3 4) or an empty archive (PK 5 6). */
    fun looksLikeZip(head: ByteArray): Boolean =
        head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() &&
            ((head[2] == 3.toByte() && head[3] == 4.toByte()) || (head[2] == 5.toByte() && head[3] == 6.toByte()))

    /** An archive is accepted only for diagnostics, up to 5 MB and only with the ZIP signature. */
    fun checkArchive(action: TillCommandAction, size: Long, head: ByteArray) {
        if (action != TillCommandAction.DIAGNOSTICS) invalid("file: архив принимается только у диагностики")
        if (size > MAX_ARCHIVE_BYTES) {
            throw AppException(ErrorCode.VALIDATION_FAILED, "file: архив больше 5 МБ", HttpStatus.PAYLOAD_TOO_LARGE)
        }
        if (!looksLikeZip(head)) invalid("file: нужен ZIP-архив")
    }

    /** What to do with a result for a command (null: no such command of this till). */
    fun resultDecision(stored: TillCommandStatus?, overdue: Boolean): ResultDecision = when (stored) {
        null -> ResultDecision.NOT_FOUND
        TillCommandStatus.PENDING -> if (overdue) ResultDecision.EXPIRED else ResultDecision.NOT_TAKEN
        TillCommandStatus.DELIVERED -> if (overdue) ResultDecision.EXPIRED else ResultDecision.ACCEPT
        TillCommandStatus.DONE, TillCommandStatus.FAILED -> ResultDecision.ALREADY_FINISHED
        TillCommandStatus.EXPIRED -> ResultDecision.EXPIRED
    }

    /** Status HQ sees: a waiting or taken command past its deadline is already expired, before any write marks it. */
    fun visibleStatus(stored: TillCommandStatus, overdue: Boolean): TillCommandStatus =
        if (stored.active && overdue) TillCommandStatus.EXPIRED else stored

    fun visibleFinishedAt(stored: TillCommandStatus, overdue: Boolean, finishedAt: Instant?, expiresAt: Instant): Instant? =
        if (stored.active && overdue) expiresAt else finishedAt

    fun archiveKey(commandId: UUID, nonce: UUID = UUID.randomUUID()): String = "$ARCHIVE_PREFIX$commandId/$nonce.zip"

    fun isArchiveKey(key: String): Boolean = ARCHIVE_KEY.matches(key)

    /** epharm-diagnostics-<pharmacy>-<till>-<yyyyMMdd-HHmm Almaty>.zip with safe ASCII characters only. */
    fun archiveFileName(pharmacyId: String, deviceId: String, finishedAt: Instant?): String {
        fun part(value: String) = value.replace(FILE_NAME_UNSAFE, "_").trim('_', '.').take(48).ifEmpty { "till" }
        val time = finishedAt?.let { "-" + FILE_TIME.format(it) }.orEmpty()
        return "epharm-diagnostics-${part(pharmacyId)}-${part(deviceId)}$time.zip"
    }

    private fun invalid(message: String): Nothing = throw AppException(ErrorCode.VALIDATION_FAILED, message)
}
