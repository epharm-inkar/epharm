package kz.epharm.pharmacists.service

/**
 * A pharmacist as the text search reads them. [places] are the names, cities and addresses of the pharmacies the
 * person is tied to (their own, the till they sit at).
 */
data class PharmacistSearchRow(
    val id: String,
    val name: String,
    val phone: String,
    val iin: String,
    val places: List<String>,
)

/** What HQ typed, taken apart once. */
class PharmacistSearchQuery internal constructor(internal val terms: List<Term>) {
    val isEmpty: Boolean get() = terms.isEmpty()

    /** [text] is folded (lower case, one alphabet); [digits] are the digits of the word; [numeric]: it looks like a number. */
    internal data class Term(val text: String, val digits: String, val numeric: Boolean)
}

/**
 * Find a pharmacist by what HQ remembers about them (owner, 26.11.2026: «на какие фамилии, имена, номера телефонов»).
 * Pure rules, no I/O, unit-tested. Every word of the query must match something about the person:
 *
 * - any part of the name (surname, name, patronymic), without regard to case; Kazakh letters count as their Russian
 *   twins (ұ and ү as у, қ as к, ө as о, ә as а, ғ as г, ң as н, һ as х, і as и) and ё as е, because the office types
 *   on a Russian keyboard;
 * - the name, the city or the address of one of their pharmacies, any part;
 * - a phone, by digits only (+7 701 123-45-67, 8 (701) 1234567 and 7011234567 are the same), and an IIN, by digits;
 *   a number of fewer than four digits ("103" in «Абая 103») is a part of an address, never of a phone or an IIN.
 *
 * A query made only of digits and the signs of a phone is one number, however it is spaced. An empty query matches
 * everybody.
 */
object PharmacistSearch {
    const val MAX_TERMS = 8
    const val MIN_NUMBER_DIGITS = 4
    private const val PHONE_DIGITS = 10

    private val WORDS = Regex("[\\s,;]+")
    private val PHONE_SIGNS = Regex("[+()\\-.\\s]")
    private const val NUMBER_SIGNS = "+()-."

    fun parse(raw: String?): PharmacistSearchQuery {
        val text = raw.orEmpty().trim()
        if (text.isEmpty()) return PharmacistSearchQuery(emptyList())
        val compact = text.replace(PHONE_SIGNS, "")
        if (compact.length >= MIN_NUMBER_DIGITS && compact.all(Char::isDigit)) {
            return PharmacistSearchQuery(listOf(PharmacistSearchQuery.Term(fold(text), compact, numeric = true)))
        }
        val terms = text.split(WORDS).filter { it.isNotEmpty() }.take(MAX_TERMS).map { word ->
            val digits = word.filter(Char::isDigit)
            PharmacistSearchQuery.Term(
                text = fold(word),
                digits = digits,
                numeric = digits.isNotEmpty() && word.all { it.isDigit() || it in NUMBER_SIGNS },
            )
        }
        return PharmacistSearchQuery(terms)
    }

    fun matches(query: PharmacistSearchQuery, row: PharmacistSearchRow): Boolean {
        if (query.isEmpty) return true
        val texts = listOf(fold(row.name)) + row.places.map(::fold)
        val phone = row.phone.filter(Char::isDigit)
        return query.terms.all { term ->
            texts.any { it.contains(term.text) } || (term.numeric && term.digits.length >= MIN_NUMBER_DIGITS && numberHit(term.digits, phone, row.iin))
        }
    }

    fun filter(rows: List<PharmacistSearchRow>, raw: String?): List<PharmacistSearchRow> {
        val query = parse(raw)
        return if (query.isEmpty) rows else rows.filter { matches(query, it) }
    }

    /** Lower case, ё as е, Kazakh letters as their Russian twins. */
    fun fold(text: String): String {
        val lower = text.lowercase()
        val out = StringBuilder(lower.length)
        for (char in lower) {
            out.append(
                when (char) {
                    'ё' -> 'е'
                    'ә' -> 'а'
                    'ғ' -> 'г'
                    'қ' -> 'к'
                    'ң' -> 'н'
                    'ө' -> 'о'
                    'ұ', 'ү' -> 'у'
                    'һ' -> 'х'
                    'і' -> 'и'
                    else -> char
                },
            )
        }
        return out.toString()
    }

    /** A phone matches by the digits typed, or by its last ten digits when the number was typed with 8 or 7 in front. */
    private fun numberHit(digits: String, phone: String, iin: String): Boolean {
        val phoneHit = phone.contains(digits) || (digits.length >= PHONE_DIGITS && phone.contains(digits.takeLast(PHONE_DIGITS)))
        return phoneHit || iin.contains(digits)
    }
}
