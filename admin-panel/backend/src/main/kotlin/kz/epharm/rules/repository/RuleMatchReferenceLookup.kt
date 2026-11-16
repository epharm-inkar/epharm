package kz.epharm.rules.repository

import jakarta.persistence.EntityManager
import jakarta.persistence.FlushModeType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

interface RuleMatchReferenceLookup {
    fun findActiveMatchReferences(): List<RuleMatchReference>
}

/** Scalar rows become immutable values directly, without per-rule projection proxies. */
@Repository
class RuleMatchReferenceLookupImpl(
    private val jdbc: JdbcTemplate,
    private val entityManager: EntityManager,
) : RuleMatchReferenceLookup {
    @Transactional(readOnly = true)
    override fun findActiveMatchReferences(): List<RuleMatchReference> {
        // Preserve the prior JPA query's visibility of pending writes in a read/write transaction.
        if (entityManager.isJoinedToTransaction && entityManager.flushMode == FlushModeType.AUTO) entityManager.flush()
        return jdbc.query("""
            SELECT r.id, r.trigger->>'kind' AS kind,
                   CASE WHEN jsonb_typeof(r.trigger->'value') = 'string' THEN r.trigger->>'value' END AS value,
                   CASE WHEN r.trigger->>'kind' = 'product_any' AND jsonb_typeof(r.trigger->'value') = 'array'
                        THEN CAST(r.trigger->'value' AS text) END AS "productAnyJson",
                   r.recommend, r.promo_id AS "promoId",
                   COALESCE(r.trigger->'conditions', '[]'::jsonb) <> '[]'::jsonb AS "hasConditions"
              FROM rules r WHERE r.status = 'active' ORDER BY r.updated_at DESC
        """.trimIndent()) { row, _ ->
            RuleMatchReference(
                row.getString("id"), row.getString("kind"), row.getString("value"),
                row.getString("productAnyJson"), row.getString("recommend"), row.getString("promoId"),
                row.getBoolean("hasConditions"),
            )
        }
    }
}
