package kz.epharm.posm.staff

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.beans.factory.annotation.Value

@Component
class StaffAuthCleanup(
    private val jdbc: JdbcTemplate,
    @Value("\${app.posm.staff.source-proof-retention-days:90}") private val sourceProofRetentionDays: Int = 90,
) {
    init { require(sourceProofRetentionDays in 1..365) { "Source proof retention must be between 1 and 365 days" } }
    /** Expired login credentials are deleted; only bounded minimal receipt-time proof survives. */
    @Scheduled(cron="\${app.posm.staff.cleanup-cron:0 17 * * * *}")
    @Transactional
    fun cleanExpired() {
        jdbc.update("DELETE FROM posm_staff_challenges WHERE expires_at<now()-interval '24 hours'")
        jdbc.update("DELETE FROM posm_staff_sessions WHERE expires_at<now()-interval '24 hours'")
        // DELETE RETURNING and INSERT are one statement: concurrent cleanup cannot duplicate proof,
        // and a resolver's UNION snapshot sees either the grant or its archive, never a gap.
        jdbc.update(ARCHIVE_EXPIRED_SOURCE_PROOFS, sourceProofRetentionDays, sourceProofRetentionDays)
        jdbc.update("DELETE FROM posm_staff_source_proofs WHERE retained_until<=now() OR expires_at<=now()-(? * interval '1 day')", sourceProofRetentionDays)
        jdbc.update("DELETE FROM posm_staff_rate_limits WHERE window_start<now()-interval '24 hours'")
    }

    internal companion object {
        const val ARCHIVE_EXPIRED_SOURCE_PROOFS = """
            WITH expired AS (
                DELETE FROM posm_device_staff_sessions WHERE expires_at<now()-interval '24 hours' RETURNING *
            )
            INSERT INTO posm_staff_source_proofs
                (pharmacist_id,pharmacy_id,device_id,source_namespace,standard_n_user_id,standard_n_session_id,
                 workstation_id,password_fingerprint,created_at,source_verified_at,expires_at,revoked_at,
                 source_proof_method,source_proof_hash,retained_until)
            SELECT pharmacist_id,pharmacy_id,device_id,source_namespace,standard_n_user_id,standard_n_session_id,
                   workstation_id,password_fingerprint,created_at,source_verified_at,expires_at,revoked_at,
                   source_proof_method,source_proof_hash,expires_at+(? * interval '1 day')
            FROM expired WHERE source_verified_at IS NOT NULL AND source_verified_at<expires_at
              AND source_namespace ~ '^[a-f0-9]{64}$' AND source_proof_method IN ('IIN','NAME')
              AND source_proof_hash ~ '^[a-f0-9]{64}$'
              AND NULLIF(standard_n_user_id,'') IS NOT NULL AND NULLIF(standard_n_session_id,'') IS NOT NULL
              AND NULLIF(workstation_id,'') IS NOT NULL AND expires_at>now()-(? * interval '1 day')
        """
    }
}
