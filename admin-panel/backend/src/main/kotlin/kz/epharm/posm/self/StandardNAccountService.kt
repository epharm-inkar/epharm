package kz.epharm.posm.self

import kz.epharm.finance.premium.PharmacistCardReady
import kz.epharm.pharmacists.entity.PharmacistEntity
import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.posm.service.StandardNDirectoryPerson
import org.slf4j.LoggerFactory
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
 * password stay empty until the pharmacist adds them. An existing active card (registered earlier or created
 * before) is always reused by IIN; a blocked card is returned as is and never re-created. A card still pending
 * from the old registration is taken over for the directory person (INK-209, see [adoptPendingRegistration]).
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
        // Before any JPA read: the update must not be hidden by a card already loaded in this transaction.
        if (enabled) adoptPendingRegistration(person, pharmacyId)?.let { events?.publishEvent(PharmacistCardReady(it)) }
        pharmacists.findByIin(person.iin)?.let { return it }
        if (!enabled) return null
        val pharmacy = pharmacy(pharmacyId)
        val id = "ph_${UUID.randomUUID()}"
        val inserted = jdbc.update(
            """
            INSERT INTO pharmacists(id, name, iin, phone, password_hash, tier, status, pharmacy_id, pharmacy_name, city, account_source)
            VALUES (?, ?, ?, '', NULL, 'Silver', 'active', ?, ?, ?, 'standardn')
            ON CONFLICT (iin) DO NOTHING
            """.trimIndent(),
            id,
            directoryName(person),
            person.iin,
            pharmacyId,
            pharmacy?.first,
            pharmacy?.second ?: "",
        )
        if (inserted > 0) events?.publishEvent(PharmacistCardReady(id))
        // A concurrent receipt of the same person may have won the insert; both read the same card.
        return pharmacists.findByIin(person.iin)
    }

    /**
     * INK-209: a card left in 'pending' by the old registration takes the directory person, because the directory
     * says who sells under this IIN at this pharmacy. That registration may have been made with someone else's IIN,
     * so its phone, name and password are not carried over and its sessions end; the replaced values go to
     * pharmacist_registration_replacements for HQ review. Active and blocked cards are never touched. Returns the id
     * of the card that turned active, so that a sales premium waiting for its IIN is credited after the commit.
     */
    private fun adoptPendingRegistration(person: StandardNDirectoryPerson, pharmacyId: String): String? {
        val old = jdbc.queryForList(
            "SELECT id, name, phone, pharmacy_id, password_hash, created_at FROM pharmacists " +
                "WHERE iin = ? AND status = 'pending' FOR UPDATE",
            person.iin,
        ).singleOrNull() ?: return null
        val id = old["id"] as String
        val pharmacy = pharmacy(pharmacyId)
        jdbc.update(
            """
            INSERT INTO pharmacist_registration_replacements(id, pharmacist_id, old_name, old_phone, old_pharmacy_id,
                had_password, old_created_at, new_pharmacy_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            UUID.randomUUID(), id, old["name"], old["phone"] ?: "", old["pharmacy_id"], old["password_hash"] != null,
            old["created_at"], pharmacyId,
        )
        jdbc.update(
            """
            UPDATE pharmacists SET status = 'active', name = ?, phone = '', password_hash = NULL, pharmacy_id = ?,
                pharmacy_name = ?, city = ?, account_source = 'standardn', updated_at = now()
            WHERE id = ? AND status = 'pending'
            """.trimIndent(),
            directoryName(person), pharmacyId, pharmacy?.first, pharmacy?.second ?: "", id,
        )
        jdbc.update("UPDATE mobile_refresh_tokens SET revoked_at = now() WHERE pharmacist_id = ? AND revoked_at IS NULL", id)
        jdbc.update("UPDATE posm_staff_sessions SET revoked_at = now() WHERE pharmacist_id = ? AND revoked_at IS NULL", id)
        jdbc.update("UPDATE posm_device_staff_sessions SET revoked_at = now() WHERE pharmacist_id = ? AND revoked_at IS NULL", id)
        jdbc.update(
            "UPDATE learner_password_vault SET state = 'unavailable', key_id = NULL, nonce = NULL, ciphertext = NULL " +
                "WHERE pharmacist_id = ?",
            id,
        )
        log.warn("INK-209: pending registration {} taken over by the Standard-N directory person at {}", id, pharmacyId)
        return id
    }

    private fun pharmacy(pharmacyId: String): Pair<String?, String>? = jdbc.query(
        "SELECT name, city FROM pharmacies WHERE id = ?",
        { rs, _ -> rs.getString(1) to (rs.getString(2) ?: "") },
        pharmacyId,
    ).singleOrNull()

    private fun directoryName(person: StandardNDirectoryPerson) =
        person.fullName.trim().ifEmpty { "Фармацевт Standard-N" }.take(255)

    private companion object {
        val log = LoggerFactory.getLogger(StandardNAccountService::class.java)
    }
}
