package kz.epharm.posm.identity

import kz.epharm.auth.access.UnifiedAuthorityLock
import kz.epharm.posm.staff.SourceStaffBindingService
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

data class IdentityReconcileResult(
    val superseded: Int = 0,
    /** Claims whose IIN the directory contradicts: HQ sees them flagged, nothing was merged. */
    val flagged: Int = 0,
    val mappingsRevoked: Int = 0,
    val namesTaken: Int = 0,
)

/**
 * The Standard-N directory wins (owner, 26.11.2026: «если потом в стандарте появится обновлённая информация, то её будем
 * показывать»). After every directory sync, a claim whose Standard-N user (or IIN) the directory now names yields:
 *
 * - the directory has the same IIN: the claim is superseded, the card shows the directory's name when it is a full
 *   one, and its IIN source becomes Standard-N;
 * - the directory has another IIN: the claim is superseded and flagged `directory_iin_differs` for HQ, the mapping
 *   the claim made is switched off so that receipts follow the directory person. The two cards are never merged;
 *   what to do with the self-made card is HQ's decision;
 * - a name claim whose IIN the directory knows with a full name: the card takes the directory's name.
 *
 * A name HQ set by hand (`name_source = 'hq'`) is never rewritten. Idempotent: a second run changes nothing.
 */
@Service
class IdentityClaimDirectoryReconciler(
    private val jdbc: JdbcTemplate,
    private val store: IdentityClaimStore,
) {
    private data class PersonMatch(
        val id: UUID, val status: String, val claimIin: String, val pharmacistId: String?, val mappingId: UUID?,
        val pharmacyId: String, val userId: String, val directoryIin: String, val directoryName: String,
    )

    private data class NameMatch(val id: UUID, val pharmacistId: String, val directoryName: String)

    @Transactional
    fun reconcile(): IdentityReconcileResult {
        UnifiedAuthorityLock.acquire(jdbc)
        var result = IdentityReconcileResult()
        for (match in personMatches()) {
            result = if (match.directoryIin == match.claimIin) sameIin(match, result) else differentIin(match, result)
        }
        for (match in nameMatches()) result = takeName(match, result)
        if (result != IdentityReconcileResult()) {
            log.info(
                "Identity claims reconciled with the Standard-N directory: superseded={}, flagged={}, mappingsRevoked={}, namesTaken={}",
                result.superseded, result.flagged, result.mappingsRevoked, result.namesTaken,
            )
        }
        return result
    }

    /** Open or confirmed claims of a Standard-N user the directory now has (through the pharmacy's profile link). */
    private fun personMatches(): List<PersonMatch> = jdbc.query(
        """
        SELECT DISTINCT ON (c.id) c.id, c.status, c.iin, c.pharmacist_id, c.mapping_id, c.pharmacy_id, c.standardn_user_id,
               d.iin AS directory_iin, d.full_name AS directory_name
        FROM pharmacist_identity_claims c
        JOIN pharmacies p ON p.id = c.pharmacy_id AND p.standardn_profile_id IS NOT NULL
        JOIN standardn_pharmacist_directory d
          ON d.source_profile_id = p.standardn_profile_id AND d.source_user_id = c.standardn_user_id::bigint
        WHERE c.kind = 'person' AND c.status IN ('pending', 'confirmed')
        ORDER BY c.id
        """.trimIndent(),
    ) { rs, _ ->
        PersonMatch(
            id = rs.getObject("id", UUID::class.java), status = rs.getString("status"), claimIin = rs.getString("iin"),
            pharmacistId = rs.getString("pharmacist_id"), mappingId = rs.getObject("mapping_id", UUID::class.java),
            pharmacyId = rs.getString("pharmacy_id"), userId = rs.getString("standardn_user_id"),
            directoryIin = rs.getString("directory_iin"), directoryName = rs.getString("directory_name"),
        )
    }

    private fun sameIin(match: PersonMatch, before: IdentityReconcileResult): IdentityReconcileResult {
        store.markSuperseded(match.id, "directory_same_iin", conflict = null, directoryIin = null)
        var taken = 0
        if (match.pharmacistId != null) {
            // The card shows the directory's data: its IIN is Standard-N's now, and so is its name when it is a full one.
            jdbc.update(
                "UPDATE pharmacists SET iin_source = 'standardn', updated_at = now() WHERE id = ? AND iin = ? AND iin_source = 'self'",
                match.pharmacistId, match.claimIin,
            )
            if (!IdentityClaimRules.isIncompleteName(match.directoryName)) taken += adoptName(match.pharmacistId, match.directoryName)
        }
        return before.copy(superseded = before.superseded + 1, namesTaken = before.namesTaken + taken)
    }

    private fun differentIin(match: PersonMatch, before: IdentityReconcileResult): IdentityReconcileResult {
        store.markSuperseded(match.id, "directory_iin_differs", IdentityClaimRules.CONFLICT_DIRECTORY_IIN_DIFFERS, match.directoryIin)
        val revoked = if (match.status == IdentityClaimRules.CONFIRMED) revokeMapping(match) else 0
        return before.copy(superseded = before.superseded + 1, flagged = before.flagged + 1, mappingsRevoked = before.mappingsRevoked + revoked)
    }

    /** The mapping the claim made, if it is still the active one for this user and still points at the claim's card. */
    private fun revokeMapping(match: PersonMatch): Int {
        val mappingId = match.mappingId ?: return 0
        jdbc.queryForList("SELECT id FROM pharmacists WHERE id = ? FOR UPDATE", match.pharmacistId)
        SourceStaffBindingService.lockAccount(jdbc, match.pharmacyId, match.userId)
        val changed = jdbc.update(
            """
            UPDATE posm_pharmacist_mappings SET active = false, updated_by = NULL, updated_at = now(), revoked_at = now()
            WHERE id = ? AND active = true AND pharmacist_id = ?
            """.trimIndent(),
            mappingId, match.pharmacistId,
        )
        if (changed == 1) {
            jdbc.update(
                """
                INSERT INTO posm_pharmacist_mapping_audit (mapping_id, action, pharmacy_id, external_user_id, pharmacist_id, actor_id)
                VALUES (?, 'revoked', ?, ?, ?, NULL)
                """.trimIndent(),
                mappingId, match.pharmacyId, match.userId, match.pharmacistId,
            )
        }
        return changed
    }

    /** Open or confirmed name claims whose IIN the directory knows with a full name (active users first, longest name). */
    private fun nameMatches(): List<NameMatch> = jdbc.query(
        """
        SELECT c.id, c.pharmacist_id, d.full_name
        FROM pharmacist_identity_claims c
        JOIN LATERAL (
            SELECT full_name FROM standardn_pharmacist_directory
            WHERE iin = c.iin ORDER BY (source_status = 0) DESC, length(full_name) DESC LIMIT 1
        ) d ON true
        WHERE c.kind = 'name' AND c.status IN ('pending', 'confirmed')
        ORDER BY c.id
        """.trimIndent(),
    ) { rs, _ -> NameMatch(rs.getObject("id", UUID::class.java), rs.getString("pharmacist_id"), rs.getString("full_name")) }
        .filter { !IdentityClaimRules.isIncompleteName(it.directoryName) }

    private fun takeName(match: NameMatch, before: IdentityReconcileResult): IdentityReconcileResult {
        val changed = adoptName(match.pharmacistId, match.directoryName)
        store.markSuperseded(match.id, "directory_name", conflict = null, directoryIin = null)
        return before.copy(superseded = before.superseded + 1, namesTaken = before.namesTaken + changed)
    }

    /** The card shows the directory's name; a name HQ set by hand stays. Returns how many cards changed. */
    private fun adoptName(pharmacistId: String, directoryName: String): Int {
        val name = IdentityClaimRules.normalizeName(directoryName)
        return jdbc.update(
            """
            UPDATE pharmacists SET name = ?, name_source = 'standardn', updated_at = now()
            WHERE id = ? AND name_source <> 'hq' AND (name <> ? OR name_source <> 'standardn')
            """.trimIndent(),
            name, pharmacistId, name,
        )
    }

    private companion object {
        val log = LoggerFactory.getLogger(IdentityClaimDirectoryReconciler::class.java)
    }
}
