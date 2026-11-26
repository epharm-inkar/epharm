package kz.epharm.posm.identity

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Component
import java.util.UUID

/** SQL of `pharmacist_identity_claims` (V126), one place. Callers own the transaction. */
@Component
class IdentityClaimStore(private val jdbc: JdbcTemplate) {
    private val mapper = RowMapper { rs, _ ->
        IdentityClaimRow(
            id = rs.getObject("id", UUID::class.java),
            kind = rs.getString("kind"),
            pharmacyId = rs.getString("pharmacy_id"),
            standardNProfileId = rs.getLong("standardn_profile_id").takeUnless { rs.wasNull() },
            standardNUserId = rs.getString("standardn_user_id"),
            standardNUserName = rs.getString("standardn_user_name"),
            deviceId = rs.getString("device_id"),
            pharmacistId = rs.getString("pharmacist_id"),
            iin = rs.getString("iin"),
            fullName = rs.getString("full_name"),
            status = rs.getString("status"),
            conflict = rs.getString("conflict"),
            conflictPharmacistId = rs.getString("conflict_pharmacist_id"),
            directoryIin = rs.getString("directory_iin"),
            decidedBy = rs.getObject("decided_by", UUID::class.java),
            decidedAt = rs.getTimestamp("decided_at")?.toInstant(),
            decisionReason = rs.getString("decision_reason"),
            mappingId = rs.getObject("mapping_id", UUID::class.java),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant(),
        )
    }

    fun find(id: UUID, forUpdate: Boolean = false): IdentityClaimRow? =
        jdbc.query("SELECT * FROM pharmacist_identity_claims WHERE id = ?" + if (forUpdate) " FOR UPDATE" else "", mapper, id)
            .singleOrNull()

    /** The Standard-N profile the pharmacy is linked to (V112); null when it has no link yet. */
    fun profileIdOf(pharmacyId: String): Long? = jdbc.query(
        "SELECT standardn_profile_id FROM pharmacies WHERE id = ?",
        { rs, _ -> rs.getLong(1).takeUnless { rs.wasNull() } }, pharmacyId,
    ).singleOrNull()

    /** Every name the Standard-N directory holds for this IIN: active users first, the longest name first. */
    fun directoryNamesOf(iin: String): List<String> = jdbc.query(
        "SELECT full_name FROM standardn_pharmacist_directory WHERE iin = ? ORDER BY (source_status = 0) DESC, length(full_name) DESC",
        { rs, _ -> rs.getString(1) }, iin,
    )

    /** Claims for the HQ list: open ones first, then the newest. [status] null means every status. */
    fun list(status: String?, limit: Int): List<IdentityClaimRow> = jdbc.query(
        """
        SELECT * FROM pharmacist_identity_claims
        WHERE (?::varchar IS NULL OR status = ?::varchar)
        ORDER BY (status = 'pending') DESC, created_at DESC, id
        LIMIT ?
        """.trimIndent(),
        mapper, status, status, limit,
    )

    /** The newest claim of this Standard-N user at this pharmacy, whatever its status. */
    fun latestPerson(pharmacyId: String, userId: String): IdentityClaimRow? = jdbc.query(
        """
        SELECT * FROM pharmacist_identity_claims
        WHERE kind = 'person' AND pharmacy_id = ? AND standardn_user_id = ?
        ORDER BY created_at DESC, id LIMIT 1
        """.trimIndent(),
        mapper, pharmacyId, userId,
    ).singleOrNull()

    /** The newest name proposal for this card, whatever its status. */
    fun latestName(pharmacistId: String): IdentityClaimRow? = jdbc.query(
        """
        SELECT * FROM pharmacist_identity_claims
        WHERE kind = 'name' AND pharmacist_id = ?
        ORDER BY created_at DESC, id LIMIT 1
        """.trimIndent(),
        mapper, pharmacistId,
    ).singleOrNull()

    /**
     * The card of a confirmed claim that is still in force: the HQ mapping made at confirmation is active, points at the
     * same card and the card sits in this pharmacy. A revoked mapping ends the claim's reach, as it ends any HQ mapping.
     */
    fun confirmedCardId(pharmacyId: String, userId: String): String? = jdbc.query(
        """
        SELECT c.pharmacist_id
        FROM pharmacist_identity_claims c
        JOIN posm_pharmacist_mappings m
          ON m.pharmacy_id = c.pharmacy_id AND m.external_user_id = c.standardn_user_id
         AND m.active = true AND m.pharmacist_id = c.pharmacist_id
        JOIN pharmacists p ON p.id = c.pharmacist_id AND p.pharmacy_id = c.pharmacy_id
        WHERE c.kind = 'person' AND c.status = 'confirmed' AND c.pharmacy_id = ? AND c.standardn_user_id = ?
        ORDER BY c.decided_at DESC LIMIT 1
        """.trimIndent(),
        { rs, _ -> rs.getString(1) }, pharmacyId, userId,
    ).singleOrNull()

    /** A new open claim, or the open claim of the same Standard-N user rewritten (a typo corrected before HQ looked). */
    fun upsertPerson(
        pharmacyId: String, profileId: Long?, userId: String, userName: String?, deviceId: String,
        iin: String, fullName: String, conflict: String?, conflictPharmacistId: String?,
    ): UUID = jdbc.query(
        """
        INSERT INTO pharmacist_identity_claims(id, kind, pharmacy_id, standardn_profile_id, standardn_user_id,
            standardn_user_name, device_id, iin, full_name, conflict, conflict_pharmacist_id)
        VALUES (?, 'person', ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (pharmacy_id, standardn_user_id) WHERE status = 'pending' AND kind = 'person'
        DO UPDATE SET standardn_profile_id = EXCLUDED.standardn_profile_id, standardn_user_name = EXCLUDED.standardn_user_name,
            device_id = EXCLUDED.device_id, iin = EXCLUDED.iin, full_name = EXCLUDED.full_name,
            conflict = EXCLUDED.conflict, conflict_pharmacist_id = EXCLUDED.conflict_pharmacist_id, updated_at = now()
        RETURNING id
        """.trimIndent(),
        { rs, _ -> rs.getObject(1, UUID::class.java) },
        UUID.randomUUID(), pharmacyId, profileId, userId, userName, deviceId, iin, fullName, conflict, conflictPharmacistId,
    ).single()

    fun upsertName(pharmacistId: String, pharmacyId: String?, iin: String, fullName: String): UUID = jdbc.query(
        """
        INSERT INTO pharmacist_identity_claims(id, kind, pharmacy_id, pharmacist_id, iin, full_name)
        VALUES (?, 'name', ?, ?, ?, ?)
        ON CONFLICT (pharmacist_id) WHERE status = 'pending' AND kind = 'name'
        DO UPDATE SET iin = EXCLUDED.iin, full_name = EXCLUDED.full_name, pharmacy_id = EXCLUDED.pharmacy_id, updated_at = now()
        RETURNING id
        """.trimIndent(),
        { rs, _ -> rs.getObject(1, UUID::class.java) },
        UUID.randomUUID(), pharmacyId, pharmacistId, iin, fullName,
    ).single()

    /** [actor] null with a [reason]: ePharm took the claim itself, see [IdentityClaimRules.AUTO_CONFIRMED]. */
    fun markConfirmed(id: UUID, actor: UUID?, pharmacistId: String, mappingId: UUID?, reason: String? = null) {
        jdbc.update(
            """
            UPDATE pharmacist_identity_claims
            SET status = 'confirmed', decided_by = ?, decided_at = now(), pharmacist_id = ?, mapping_id = ?,
                decision_reason = ?, updated_at = now()
            WHERE id = ? AND status = 'pending'
            """.trimIndent(),
            actor, pharmacistId, mappingId, reason, id,
        )
    }

    /** Another open claim (of any kind) names this IIN: two people say it is theirs, HQ looks. */
    fun otherOpenWithIin(iin: String, exceptId: UUID): Boolean = jdbc.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM pharmacist_identity_claims WHERE status = 'pending' AND iin = ? AND id <> ?)",
        Boolean::class.java, iin, exceptId,
    ) == true

    /** HQ once rejected a claim of this Standard-N user or with this IIN: the next one goes to HQ again. */
    fun rejectedBefore(pharmacyId: String, userId: String, iin: String): Boolean = jdbc.queryForObject(
        """
        SELECT EXISTS(
            SELECT 1 FROM pharmacist_identity_claims
            WHERE status = 'rejected' AND (iin = ? OR (kind = 'person' AND pharmacy_id = ? AND standardn_user_id = ?))
        )
        """.trimIndent(),
        Boolean::class.java, iin, pharmacyId, userId,
    ) == true

    fun markRejected(id: UUID, actor: UUID, reason: String) {
        jdbc.update(
            """
            UPDATE pharmacist_identity_claims
            SET status = 'rejected', decided_by = ?, decided_at = now(), decision_reason = ?, updated_at = now()
            WHERE id = ? AND status = 'pending'
            """.trimIndent(),
            actor, reason, id,
        )
    }

    /**
     * The directory brought its own data: the claim yields. The system decided it, so `decided_by` is emptied even when
     * HQ had confirmed the claim before: who confirmed it stays in the mapping and its audit.
     */
    fun markSuperseded(id: UUID, reason: String, conflict: String?, directoryIin: String?) {
        jdbc.update(
            """
            UPDATE pharmacist_identity_claims
            SET status = 'superseded', decided_by = NULL, decided_at = now(), decision_reason = ?, conflict = ?, directory_iin = ?,
                updated_at = now()
            WHERE id = ? AND status IN ('pending', 'confirmed')
            """.trimIndent(),
            reason, conflict, directoryIin, id,
        )
    }
}
