package kz.epharm.posm.attill

import kz.epharm.pharmacists.service.PharmacistSearchRow
import java.time.Instant

/** The Standard-N user the server last resolved for one till: who sits at it and since when. */
data class TillPerson(
    val pharmacistId: String,
    /** The pharmacy the till stands in; the person's own pharmacy may be another one. */
    val pharmacyId: String,
    /** The computer name as the till's key spells it. */
    val deviceId: String,
    /** When this person first appeared at this till in the present stretch; a gap longer than the window starts a new one. */
    val since: Instant,
    /** The last request of this till that named the person. */
    val seenAt: Instant,
)

/** A pharmacist at a till right now, with what HQ needs to find and choose them. */
data class AtTillPerson(
    val pharmacistId: String,
    val name: String,
    /** Kept for the search only: HQ may look a person up by phone or IIN, the answer never carries them. */
    val phone: String,
    val iin: String,
    val homePharmacyId: String?,
    val homePharmacyName: String,
    val tillPharmacyId: String,
    val tillPharmacyName: String,
    val tillCity: String,
    val tillAddress: String,
    val deviceId: String,
    val since: Instant,
    val seenAt: Instant,
) {
    /** Last four digits of the phone: enough to tell two namesakes apart without showing the number. */
    val phoneTail: String? get() = phoneTail(phone)

    /** The person as the text search reads them: the name, the phone, the IIN and the places they are tied to. */
    fun searchRow() = PharmacistSearchRow(
        id = pharmacistId,
        name = name,
        phone = phone,
        iin = iin,
        places = listOf(homePharmacyName, tillPharmacyName, tillCity, tillAddress),
    )

    fun toDto() = AtTillItemDto(
        pharmacistId = pharmacistId,
        name = name,
        phoneTail = phoneTail,
        homePharmacyId = homePharmacyId,
        homePharmacyName = homePharmacyName,
        pharmacyId = tillPharmacyId,
        pharmacyName = tillPharmacyName,
        city = tillCity,
        address = tillAddress,
        deviceId = deviceId,
        since = since,
        seenAt = seenAt,
    )

    companion object {
        fun phoneTail(phone: String?): String? =
            phone?.filter(Char::isDigit)?.takeLast(4)?.takeIf { it.length == 4 }
    }
}

/** Everybody at a till at one moment. */
data class AtTillSnapshot(
    val observedAt: Instant,
    /** Minutes after a till's last check during which its person still counts as at the till. */
    val windowMinutes: Long,
    val people: List<AtTillPerson>,
) {
    /** One person, one entry: the person list holds the latest till of a person named by two. */
    val byPharmacist: Map<String, AtTillPerson> = people.associateBy { it.pharmacistId }
}

/** What the roster shows next to a pharmacist. */
data class PharmacistTillFlag(
    val atTillNow: Boolean,
    val lastSeenAt: Instant?,
)

/** One row of «Сейчас за кассой». */
data class AtTillItemDto(
    val pharmacistId: String,
    val name: String,
    val phoneTail: String?,
    val homePharmacyId: String?,
    val homePharmacyName: String,
    /** The till's pharmacy, where the person sits now. */
    val pharmacyId: String,
    val pharmacyName: String,
    val city: String,
    val address: String,
    val deviceId: String,
    val since: Instant,
    val seenAt: Instant,
)

/** GET /api/admin/posm-at-till. */
data class AtTillListDto(
    val observedAt: Instant,
    val windowMinutes: Long,
    /** Everybody at a till now, before the filters. */
    val total: Int,
    /** After the filters; the same as items.size. */
    val shown: Int,
    val items: List<AtTillItemDto>,
)
