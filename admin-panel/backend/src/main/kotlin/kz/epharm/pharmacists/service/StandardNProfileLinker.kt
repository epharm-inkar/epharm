package kz.epharm.pharmacists.service

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Links ePharm pharmacies to Standard-N profiles (G$PROFILES.ID) by the profile caption: ePharm pharmacy
 * names were imported from those captions. Only a caption that belongs to exactly one profile links.
 * HQ links (`standardn_profile_source = 'manual'`) are never touched; stale caption links are cleared.
 * On 24.11.2026 this linked 482 of 527 active pharmacies.
 */
@Component
class StandardNProfileLinker(private val jdbc: JdbcTemplate) {
    @Transactional
    fun refresh(): Int = jdbc.update(
        """
        WITH profiles AS (
            SELECT lower(regexp_replace(profile_name, '[^0-9a-zа-яё]+', '', 'gi')) AS caption_key,
                   min(source_profile_id) AS profile_id,
                   count(DISTINCT source_profile_id) AS profiles
            FROM standardn_pharmacist_directory
            WHERE profile_name IS NOT NULL
            GROUP BY 1
        ), matched AS (
            SELECT p.id, pr.profile_id
            FROM pharmacies p
            LEFT JOIN profiles pr
              ON pr.profiles = 1
             AND pr.caption_key <> ''
             AND pr.caption_key = lower(regexp_replace(p.name, '[^0-9a-zа-яё]+', '', 'gi'))
            WHERE p.standardn_profile_source IS DISTINCT FROM 'manual'
        )
        UPDATE pharmacies p
        SET standardn_profile_id = m.profile_id,
            standardn_profile_source = CASE WHEN m.profile_id IS NULL THEN NULL ELSE 'caption' END
        FROM matched m
        WHERE p.id = m.id
          AND p.standardn_profile_id IS DISTINCT FROM m.profile_id
        """.trimIndent(),
    )
}
