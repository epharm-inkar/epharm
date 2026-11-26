package kz.epharm.posm.attill

import kz.epharm.pharmacists.service.PharmacistSearch
import kz.epharm.posm.service.DevicePresenceService
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * «Сейчас за кассой» (owner, 26.11.2026): the pharmacists who sit at a till right now, for the page of that name, for
 * the count before a notice is sent, for the flag next to a name in the roster and in the notice picker, and for the
 * «only those at a till now» switch of notices. The rule is [AtTillRules]: the till is online and the person it last
 * named was named within the presence window. The list is read-only; nothing here writes.
 */
@Service
class AtTillService(
    private val devices: DevicePresenceService,
    private val persons: TillPersonPresence,
    private val jdbc: JdbcTemplate,
    private val named: NamedParameterJdbcTemplate,
) {
    /** Everybody at a till at [now], read afresh: the device presence, the people the tills named, their cards. */
    fun snapshot(now: Instant = Instant.now()): AtTillSnapshot = build(now)

    /**
     * The list for the page, filtered by the till's pharmacy and by text: any part of the name, the phone (digits), the
     * IIN, the name, city or address of the pharmacy. Always read afresh: the page has a refresh button.
     */
    fun list(pharmacyId: String?, query: String?, now: Instant = Instant.now()): AtTillListDto {
        val snapshot = snapshot(now)
        val inPharmacy = pharmacyId?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { id -> snapshot.people.filter { it.tillPharmacyId == id } }
            ?: snapshot.people
        val search = PharmacistSearch.parse(query)
        val items = inPharmacy.filter { PharmacistSearch.matches(search, it.searchRow()) }.map { it.toDto() }
        return AtTillListDto(
            observedAt = snapshot.observedAt,
            windowMinutes = snapshot.windowMinutes,
            total = snapshot.people.size,
            shown = items.size,
            items = items,
        )
    }

    /**
     * For every pharmacist seen at a till: whether they are there now and the last time they were. A person at a till
     * now is shown with the time of the till's last check, which is newer than the stored one when the stored one is up
     * to five minutes behind.
     */
    fun flags(): Map<String, PharmacistTillFlag> {
        val atNow = snapshot().byPharmacist
        val lastSeen = HashMap<String, Instant>()
        jdbc.query(
            "SELECT pharmacist_id, seen_at FROM pharmacist_till_seen",
            RowCallbackHandler { rs -> lastSeen[rs.getString("pharmacist_id")] = rs.getTimestamp("seen_at").toInstant() },
        )
        return (lastSeen.keys + atNow.keys).associateWith { id ->
            val here = atNow[id]
            PharmacistTillFlag(
                atTillNow = here != null,
                lastSeenAt = listOfNotNull(lastSeen[id], here?.seenAt).maxOrNull(),
            )
        }
    }

    private fun build(now: Instant): AtTillSnapshot {
        val onlineKeys = devices.connected(now).mapTo(HashSet()) { AtTillRules.tillKey(it.pharmacyId, it.deviceId) }
        val here = persons.latest(now)
            .filter { AtTillRules.isAtTill(it, onlineKeys, now, persons.window) }
            // A person named by two tills is shown at the later one.
            .groupBy { it.pharmacistId }
            .mapNotNull { (_, tills) -> tills.maxByOrNull { it.seenAt } }
        val people = if (here.isEmpty()) emptyList() else describe(here)
        return AtTillSnapshot(observedAt = now, windowMinutes = persons.window.toMinutes(), people = people)
    }

    /** Adds the card and the till's pharmacy; a person blocked or removed since is left out. */
    private fun describe(here: List<TillPerson>): List<AtTillPerson> {
        val cards = HashMap<String, Card>()
        named.query(
            """
            SELECT id, name, phone, iin, pharmacy_id, coalesce(pharmacy_name, '') AS pharmacy_name
            FROM pharmacists
            WHERE id IN (:ids) AND status <> 'blocked'
            """.trimIndent(),
            mapOf("ids" to here.map { it.pharmacistId }),
            RowCallbackHandler { rs ->
                cards[rs.getString("id")] = Card(
                    name = rs.getString("name"),
                    phone = rs.getString("phone").orEmpty(),
                    iin = rs.getString("iin").orEmpty(),
                    pharmacyId = rs.getString("pharmacy_id")?.takeIf { it.isNotBlank() },
                    pharmacyName = rs.getString("pharmacy_name"),
                )
            },
        )
        val places = HashMap<String, Place>()
        named.query(
            "SELECT id, name, city, addr FROM pharmacies WHERE id IN (:ids)",
            mapOf("ids" to here.map { it.pharmacyId }.distinct()),
            RowCallbackHandler { rs ->
                places[rs.getString("id")] = Place(rs.getString("name"), rs.getString("city").orEmpty(), rs.getString("addr").orEmpty())
            },
        )
        return here.mapNotNull { person ->
            val card = cards[person.pharmacistId] ?: return@mapNotNull null
            val place = places[person.pharmacyId]
            AtTillPerson(
                pharmacistId = person.pharmacistId,
                name = card.name,
                phone = card.phone,
                iin = card.iin,
                homePharmacyId = card.pharmacyId,
                homePharmacyName = card.pharmacyName,
                tillPharmacyId = person.pharmacyId,
                tillPharmacyName = place?.name ?: person.pharmacyId,
                tillCity = place?.city.orEmpty(),
                tillAddress = place?.address.orEmpty(),
                deviceId = person.deviceId,
                since = person.since,
                seenAt = person.seenAt,
            )
        }.sortedWith(compareBy<AtTillPerson>({ it.tillCity }, { it.tillAddress }, { it.tillPharmacyName }, { it.name }, { it.pharmacistId }))
    }

    private data class Card(val name: String, val phone: String, val iin: String, val pharmacyId: String?, val pharmacyName: String)

    private data class Place(val name: String, val city: String, val address: String)

}
