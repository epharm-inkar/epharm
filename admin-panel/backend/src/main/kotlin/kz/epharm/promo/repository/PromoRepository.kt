package kz.epharm.promo.repository

import jakarta.persistence.LockModeType
import kz.epharm.promo.entity.PromoEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.Optional

interface PromoFundingState {
    val budget: Long
    val spent: Long
    val budgetUnlimited: Boolean
}

@Repository
interface PromoRepository : JpaRepository<PromoEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PromoEntity p WHERE p.id = :id")
    fun findForUpdate(id: String): Optional<PromoEntity>

    /** Scalar DB state bypasses a stale persistence-context entity after the row lock is acquired. */
    @Query(value = "SELECT budget, spent, budget_unlimited AS \"budgetUnlimited\" FROM promos WHERE id=:id", nativeQuery = true)
    fun fundingState(@Param("id") id: String): PromoFundingState

    /** Serialize against reservations; omitted fields preserve persisted settings, not a stale entity. */
    @Modifying(flushAutomatically = true)
    @Query(value = """
        UPDATE promos
        SET budget=COALESCE(CAST(:budget AS bigint),budget),
            budget_unlimited=COALESCE(CAST(:unlimited AS boolean),budget_unlimited)
        WHERE id=:id
          AND COALESCE(CAST(:budget AS bigint),budget)>=0
          AND (COALESCE(CAST(:unlimited AS boolean),budget_unlimited)
               OR spent<=COALESCE(CAST(:budget AS bigint),budget))
    """, nativeQuery = true)
    fun updateFundingIfCoversSpent(@Param("id") id: String, @Param("budget") budget: Long?,
        @Param("unlimited") unlimited: Boolean?): Int

    fun findAllByOrderByUpdatedAtDesc(): List<PromoEntity>
    fun findAllByStatusRawOrderByUpdatedAtDesc(statusRaw: String): List<PromoEntity>

    /** Активные товарные акции для мобильной ленты (только с привязанным товаром Medusa). */
    fun findAllByStatusRawAndMedusaProductIdIsNotNullOrderByUpdatedAtDesc(statusRaw: String): List<PromoEntity>

    /** Кампании на тот же товар Medusa — для проверки 1:1 (товар не должен быть в двух живых акциях). */
    fun findAllByMedusaProductId(medusaProductId: String): List<PromoEntity>

    /** Все товарные акции (с привязкой к Medusa) — для регулярного рефреша цены. */
    fun findAllByMedusaProductIdIsNotNull(): List<PromoEntity>
}

/** Caller holds the promo row lock; synchronize only funding fields, never unrelated authored state. */
internal fun PromoRepository.refreshLockedFunding(entity: PromoEntity): PromoEntity = entity.also {
    val funding = fundingState(entity.id)
    it.budget = funding.budget
    it.spent = funding.spent
    it.budgetUnlimited = funding.budgetUnlimited
}
