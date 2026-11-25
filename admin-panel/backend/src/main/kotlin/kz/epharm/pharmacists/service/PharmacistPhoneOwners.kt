package kz.epharm.pharmacists.service

import kz.epharm.pharmacists.repository.PharmacistPhoneRow
import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.shared.PhoneUtil
import org.springframework.stereotype.Component

/**
 * Who a phone number belongs to: the cards whose stored phone means this number, whatever format it was stored
 * in. The unique index (V114) covers only the exact stored text, so two cards can still hold one number in two
 * formats; callers treat more than one owner as an ambiguous number.
 */
@Component
class PharmacistPhoneOwners(private val pharmacists: PharmacistRepository) {
    /** [canonical] is `+7XXXXXXXXXX` (see [PhoneUtil.canonicalOrNull]). */
    fun owners(canonical: String): List<PharmacistPhoneRow> {
        if (PhoneUtil.canonicalOrNull(canonical) != canonical) return emptyList()
        return pharmacists.findPhoneCandidates(canonical.takeLast(10))
            .filter { PhoneUtil.normalize(it.phone) == canonical }
    }
}
