package kz.epharm.training.service

/**
 * Wording on certificates. The pharmacist's gender is not stored, so the verb
 * («завершил» / «завершила») follows the patronymic or, without one, the surname.
 * Anything unclear falls back to the neutral «завершил(а)».
 */
object CertificateWording {
    enum class Gender { female, male, unknown }

    fun completedProgram(fullName: String): String = "${completedVerb(fullName)} программу обучения"

    fun completedVerb(fullName: String): String = when (genderOf(fullName)) {
        Gender.female -> "завершила"
        Gender.male -> "завершил"
        Gender.unknown -> "завершил(а)"
    }

    fun genderOf(fullName: String): Gender {
        val words = fullName.trim().lowercase()
            .split(Regex("[\\s,]+"))
            .map { it.trim('.', '-', '«', '»', '"') }
            .filter { it.isNotEmpty() }
        if (words.isEmpty()) return Gender.unknown
        // Female patronymics are unambiguous wherever they stand: «Сергеевна», «Ильинична», «Ахметқызы», «Ахмет қызы».
        if (words.any { it in FEMALE_PATRONYMIC_WORDS || it.endsWithAny(FEMALE_PATRONYMIC_ENDINGS) }) return Gender.female
        if (words.any { it in MALE_PATRONYMIC_WORDS || it.endsWithAny(MALE_KAZAKH_ENDINGS) }) return Gender.male
        // «-ич» is also a surname ending («Бабич Анна»), so it counts only after the surname, as a patronymic.
        if (words.size >= 3 && words.drop(1).any { it.endsWithAny(listOf("ич")) }) return Gender.male
        // No patronymic: the surname comes first in «Фамилия Имя».
        if (words.size >= 2) {
            val surname = words.first()
            if (surname.endsWithAny(FEMALE_SURNAME_ENDINGS)) return Gender.female
            if (surname.endsWithAny(MALE_SURNAME_ENDINGS)) return Gender.male
        }
        return Gender.unknown
    }

    private fun String.endsWithAny(endings: List<String>): Boolean =
        endings.any { length > it.length + 1 && endsWith(it) }

    private val FEMALE_PATRONYMIC_WORDS = setOf("қызы", "кызы", "кизи", "гызы")
    private val MALE_PATRONYMIC_WORDS = setOf("ұлы", "улы", "уулу", "оглы", "угли")
    private val FEMALE_PATRONYMIC_ENDINGS = listOf("овна", "евна", "ёвна", "ична", "қызы", "кызы")
    private val MALE_KAZAKH_ENDINGS = listOf("ұлы", "улы", "уулу")
    private val FEMALE_SURNAME_ENDINGS = listOf("ова", "ева", "ёва", "ина", "ына", "ская", "цкая")
    private val MALE_SURNAME_ENDINGS = listOf("ов", "ев", "ёв", "ин", "ын", "ский", "цкий")
}
