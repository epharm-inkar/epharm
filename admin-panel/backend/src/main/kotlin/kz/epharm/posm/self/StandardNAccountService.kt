package kz.epharm.posm.self

import kz.epharm.finance.premium.PharmacistCardReady
import kz.epharm.pharmacists.entity.PharmacistEntity
import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.posm.service.StandardNDirectoryPerson
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * No registration (24.11.2026): the pharmacist card is created from the central Standard-N directory the first
 * time the person is seen at a till — by a receipt, a recommendation or the till's personal window. Phone and
 * password stay empty until the pharmacist adds them. An existing card (registered earlier or created before) is
 * always reused by IIN; a blocked card is returned as is and never re-created.
 */
@Service
class StandardNAccountService(
    private val jdbc: JdbcTemplate,
    private val pharmacists: PharmacistRepository,
    @Value("\${app.posm.identity.standardn-directory.auto-accounts:true}") private val enabled: Boolean,
    /** A new card may have a sales premium waiting for its IIN; it is credited after this transaction commits. */
    private val events: ApplicationEventPublisher? = null,
) {
    /** Own transaction: callers resolve identity inside read-only transactions. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun ensure(person: StandardNDirectoryPerson, pharmacyId: String): PharmacistEntity? {
        pharmacists.findByIin(person.iin)?.let { return it }
        if (!enabled) return null
        val pharmacy = jdbc.query(
            "SELECT name, city FROM pharmacies WHERE id = ?",
            { rs, _ -> rs.getString(1) to (rs.getString(2) ?: "") },
            pharmacyId,
        ).singleOrNull()
        val id = "ph_${UUID.randomUUID()}"
        val inserted = jdbc.update(
            """
            INSERT INTO pharmacists(id, name, iin, phone, password_hash, tier, status, pharmacy_id, pharmacy_name, city, account_source)
            VALUES (?, ?, ?, '', NULL, 'Silver', 'active', ?, ?, ?, 'standardn')
            ON CONFLICT (iin) DO NOTHING
            """.trimIndent(),
            id,
            person.fullName.trim().ifEmpty { "Фармацевт Standard-N" }.take(255),
            person.iin,
            pharmacyId,
            pharmacy?.first,
            pharmacy?.second ?: "",
        )
        if (inserted > 0) events?.publishEvent(PharmacistCardReady(id))
        // A concurrent receipt of the same person may have won the insert; both read the same card.
        return pharmacists.findByIin(person.iin)
    }
}
