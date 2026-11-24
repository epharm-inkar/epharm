package kz.epharm.posm.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/** A person from the central Standard-N directory (`standardn_pharmacist_directory`). */
data class StandardNDirectoryPerson(
    val iin: String,
    val fullName: String,
    val sourceStatus: Int,
)

/**
 * Finds who printed a receipt: Standard-N USER_ID of the pharmacy database -> central directory -> IIN.
 *
 * USERS.ID repeats across Standard-N profiles, so the lookup goes through the pharmacy's linked profile
 * (`pharmacies.standardn_profile_id`, V112). No link, a non-numeric id or no directory row gives null.
 * Checked on prod data 24.11.2026: on 48 144 receipts of 15-18.11 that still carried the cashier name,
 * the directory named the same person in 98.7% of found cases.
 */
@Component
class StandardNDirectoryIdentityLookup(
    private val jdbc: JdbcTemplate,
    @Value("\${app.posm.identity.standardn-directory.enabled:true}") private val enabled: Boolean,
) {
    fun find(pharmacyId: String, nativeUserId: String?): StandardNDirectoryPerson? {
        if (!enabled) return null
        val userId = nativeUserId?.trim()?.takeIf { NATIVE_USER_ID.matches(it) }?.toLongOrNull() ?: return null
        return jdbc.query(
            """
            SELECT d.iin, d.full_name, d.source_status
            FROM pharmacies p
            JOIN standardn_pharmacist_directory d
              ON d.source_profile_id = p.standardn_profile_id AND d.source_user_id = ?
            WHERE p.id = ?
            """.trimIndent(),
            { rs, _ -> StandardNDirectoryPerson(rs.getString(1), rs.getString(2), rs.getInt(3)) },
            userId,
            pharmacyId,
        ).singleOrNull()
    }

    private companion object {
        val NATIVE_USER_ID = Regex("^-?[0-9]{1,15}$")
    }
}
