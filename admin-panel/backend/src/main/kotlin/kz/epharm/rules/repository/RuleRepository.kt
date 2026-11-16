package kz.epharm.rules.repository

import jakarta.persistence.LockModeType
import jakarta.persistence.QueryHint
import kz.epharm.rules.entity.RuleEntity
import org.hibernate.jpa.HibernateHints
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.QueryHints
import org.springframework.stereotype.Repository
import java.util.Optional

/** Matching inputs only: rich cards and managed RuleEntity snapshots are loaded for candidates later. */
interface RuleMatchReference {
    val id: String
    val kind: String
    val value: String?
    val productAnyJson: String?
    val recommend: String
    val promoId: String?
    val hasConditions: Boolean
}

@Repository
interface RuleRepository : JpaRepository<RuleEntity, String> {
    @Query(value = """
        SELECT r.id, r.trigger->>'kind' AS kind,
               CASE WHEN jsonb_typeof(r.trigger->'value') = 'string' THEN r.trigger->>'value' END AS value,
               CASE WHEN r.trigger->>'kind' = 'product_any' AND jsonb_typeof(r.trigger->'value') = 'array'
                    THEN CAST(r.trigger->'value' AS text) END AS "productAnyJson",
               r.recommend, r.promo_id AS "promoId",
               COALESCE(r.trigger->'conditions', '[]'::jsonb) <> '[]'::jsonb AS "hasConditions"
          FROM rules r WHERE r.status = 'active' ORDER BY r.updated_at DESC
    """, nativeQuery = true)
    fun findActiveMatchReferences(): List<RuleMatchReference>

    @Query("SELECT r FROM RuleEntity r WHERE r.id IN :ids AND r.statusRaw = 'active'")
    @QueryHints(QueryHint(name = HibernateHints.HINT_READ_ONLY, value = "true"))
    fun findMatchCandidatesByIds(ids: Collection<String>): List<RuleEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM RuleEntity r WHERE r.id = :id")
    fun findForUpdate(id: String): Optional<RuleEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM RuleEntity r WHERE r.promoId = :promoId ORDER BY r.id")
    fun findAllByPromoIdForUpdate(promoId: String): List<RuleEntity>

    fun findAllByOrderByUpdatedAtDesc(): List<RuleEntity>
    fun findAllByTypeRawOrderByUpdatedAtDesc(typeRaw: String): List<RuleEntity>
    fun findAllByStatusRawOrderByUpdatedAtDesc(statusRaw: String): List<RuleEntity>
    fun countByStatusRaw(statusRaw: String): Long

    /** Правила, сгенерированные из кампании (для редактирования/перегенерации из карточки промо). */
    fun findAllByPromoIdOrderByUpdatedAtDesc(promoId: String): List<RuleEntity>
    fun deleteByPromoId(promoId: String)
}
