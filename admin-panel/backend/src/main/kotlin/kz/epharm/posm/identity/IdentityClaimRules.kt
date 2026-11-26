package kz.epharm.posm.identity

import kz.epharm.shared.IinUtil
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import java.text.Normalizer

/**
 * What a pharmacist may type about themselves (owner, 26.11.2026) and the statuses of such a claim. Pure: no
 * database, no Spring. The till (`App/Services/IdentityClaimRules.cs`) and the cabinet (`IdentityNameClaim.tsx`)
 * repeat the same rules for a quick answer; this object is the one the server trusts.
 */
object IdentityClaimRules {
    const val KIND_PERSON = "person"
    const val KIND_NAME = "name"

    const val PENDING = "pending"
    const val CONFIRMED = "confirmed"
    const val REJECTED = "rejected"
    const val SUPERSEDED = "superseded"

    /** Where a card's IIN or name came from (`pharmacists.iin_source`, `name_source`). */
    const val SOURCE_STANDARDN = "standardn"
    const val SOURCE_SELF = "self"
    const val SOURCE_HQ = "hq"

    /** Flags kept on a claim; more are computed when HQ opens the list. */
    const val CONFLICT_IIN_OTHER_CARD = "iin_other_card"
    const val CONFLICT_DIRECTORY_IIN_DIFFERS = "directory_iin_differs"

    /** `decision_reason` of a claim ePharm took without HQ; `decided_by` stays empty. */
    const val AUTO_CONFIRMED = "auto"
    /** How HQ sees who decided such a claim. */
    const val AUTO_DECIDER_NAME = "ePharm, автоматически"

    const val NAME_MAX_LENGTH = 255
    private const val WORD_MAX_COUNT = 6

    /** A word: letters of any script, with a hyphen or an apostrophe inside, at least two letters. */
    private val NAME_WORD = Regex("^\\p{L}[\\p{L}\\p{M}'’ʼ-]*\\p{L}$")
    private val WHITESPACE = Regex("[\\s\\u00A0\\u2007\\u202F]+")
    private val CONTROL = Regex("[\\p{Cc}\\p{Cf}]")
    private val USER_ID = Regex("^[0-9]{1,15}$")

    /** Trimmed, one space between words, composed form. Never null. */
    fun normalizeName(raw: String?): String =
        Normalizer.normalize(raw.orEmpty(), Normalizer.Form.NFC).replace(CONTROL, " ").replace(WHITESPACE, " ").trim()

    fun wordCount(name: String?): Int = normalizeName(name).split(' ').count { it.isNotEmpty() }

    /** A card name of fewer than two words (a surname alone, a nickname) is the case the cabinet lets a person fix. */
    fun isIncompleteName(name: String?): Boolean = wordCount(name) < 2

    /** Why [raw] is not a full name, or null when it is one. */
    fun fullNameProblem(raw: String?): String? {
        val name = normalizeName(raw)
        if (name.length > NAME_MAX_LENGTH) return "Слишком длинное имя"
        val words = name.split(' ').filter { it.isNotEmpty() }
        if (words.size < 2 || words.size > WORD_MAX_COUNT) return "Укажите фамилию и имя полностью, не меньше двух слов"
        if (words.any { !NAME_WORD.matches(it) }) return "В имени только буквы, дефис и апостроф, каждое слово не короче двух букв"
        return null
    }

    fun requireFullName(raw: String?): String {
        fullNameProblem(raw)?.let { invalid(it) }
        return normalizeName(raw)
    }

    /** Why [raw] is not an IIN, or null: twelve ASCII digits and the Kazakhstan control digit. */
    fun iinProblem(raw: String?): String? {
        val iin = raw?.trim().orEmpty()
        if (iin.length != 12 || iin.any { it !in '0'..'9' }) return "Проверьте ИИН: нужно 12 цифр"
        if (!IinUtil.isValid(iin)) return "Проверьте ИИН: контрольная цифра не сходится"
        return null
    }

    fun requireIin(raw: String?): String {
        iinProblem(raw)?.let { invalid(it) }
        return raw!!.trim()
    }

    /**
     * The Standard-N USER_ID as the receipts carry it: a positive whole number, no leading zeros, so the claim, the
     * mapping and `pos_sales.reported_pharmacist_id` all hold the same text. Null when [raw] is not such a number.
     */
    fun canonicalUserId(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        val value = if (USER_ID.matches(text)) text.toLongOrNull() else null
        return value?.takeIf { it > 0 }?.toString()
    }

    fun requireUserId(raw: String?): String =
        canonicalUserId(raw) ?: invalid("Касса передала неверный номер пользователя Стандарт-Н")

    /** The user name the till saw in Standard-N: shown to HQ as it came, only tidied and cut. */
    fun cleanUserName(raw: String?): String? = normalizeName(raw).take(NAME_MAX_LENGTH).takeIf { it.isNotEmpty() }

    fun maskIin(iin: String?): String? = iin?.takeIf { it.length == 12 }?.let { it.take(6) + "•••••" + it.takeLast(1) }

    private fun invalid(message: String): Nothing =
        throw AppException(ErrorCode.VALIDATION_FAILED, message, HttpStatus.BAD_REQUEST)
}
