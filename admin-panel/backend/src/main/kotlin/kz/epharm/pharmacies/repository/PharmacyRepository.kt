package kz.epharm.pharmacies.repository

import kz.epharm.pharmacies.entity.PharmacyEntity
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.Optional
import java.time.Instant
import org.springframework.transaction.annotation.Transactional

interface PharmacyDeviceCounts {
    val pharmacyId: String
    val registered: Long
    val online: Long
}

@Repository
interface PharmacyRepository : JpaRepository<PharmacyEntity, String> {
    fun findAllByOrderByNameAsc(): List<PharmacyEntity>
    fun findAllByActiveTrueOrderByNameAsc(): List<PharmacyEntity>
    fun findAllByGroupRawOrderByNameAsc(groupRaw: String): List<PharmacyEntity>
    fun findAllByGroupRawAndChainIdOrderByNameAsc(groupRaw: String, chainId: String): List<PharmacyEntity>
    fun findAllByChainIdOrderByNameAsc(chainId: String): List<PharmacyEntity>
    fun countByActiveTrue(): Long
    fun countByChainId(chainId: String): Long

    @Query("SELECT p.id FROM PharmacyEntity p")
    fun findAllIdsForMetrics(): List<String>

    // Do not merge a stale entity: an inventory/passport PATCH may have committed
    // after the scheduler read the roster. Only the three calculated columns belong here.
    @Modifying
    @Transactional
    @Query(value = """
        UPDATE pharmacies
        SET receipts_30d = :receipts, gmv_30d = :gmv, rules_accepted = :accepts,
            updated_at = CURRENT_TIMESTAMP
        WHERE id = :id
          AND (receipts_30d <> :receipts OR gmv_30d <> :gmv OR rules_accepted <> :accepts)
    """, nativeQuery = true)
    fun updateMetricsIfChanged(
        @Param("id") id: String,
        @Param("receipts") receipts: Int,
        @Param("gmv") gmv: Long,
        @Param("accepts") accepts: Int,
    ): Int

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PharmacyEntity p WHERE p.id = :id")
    fun findByIdForUpdate(@Param("id") id: String): Optional<PharmacyEntity>

    /** Registered POSM identities, not all physical PCs or an AnyDesk inventory. */
    @Query(value = """
        SELECT d.pharmacy_id AS "pharmacyId", COUNT(DISTINCT d.device_id) AS registered,
               COUNT(DISTINCT CASE WHEN p.active = true
                   AND d.last_seen_at >= :cutoff AND d.last_seen_at <= :observedAt
                   THEN d.device_id END) AS online
        FROM fulfillment_devices d JOIN pharmacies p ON p.id = d.pharmacy_id
        WHERE d.pharmacy_id IN (:pharmacyIds) AND d.active = true AND d.revoked_at IS NULL
        GROUP BY d.pharmacy_id
    """, nativeQuery = true)
    fun deviceCounts(
        @Param("pharmacyIds") pharmacyIds: Set<String>,
        @Param("cutoff") cutoff: Instant,
        @Param("observedAt") observedAt: Instant,
    ): List<PharmacyDeviceCounts>

    @Query("""
        SELECT p FROM PharmacyEntity p
        WHERE p.id IN :pharmacyIds
          AND (:groupRaw IS NULL OR p.groupRaw = :groupRaw)
          AND (:chainId IS NULL OR p.chainId = :chainId)
        ORDER BY p.name
    """)
    fun findForAdministration(
        @Param("pharmacyIds") pharmacyIds: Set<String>,
        @Param("groupRaw") groupRaw: String?,
        @Param("chainId") chainId: String?,
    ): List<PharmacyEntity>

    @Query("SELECT p FROM PharmacyEntity p WHERE p.id = :id AND p.id IN :pharmacyIds")
    fun findInAdministrativeScope(
        @Param("id") id: String,
        @Param("pharmacyIds") pharmacyIds: Set<String>,
    ): Optional<PharmacyEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PharmacyEntity p WHERE p.id = :id AND p.id IN :pharmacyIds")
    fun findInAdministrativeScopeForUpdate(
        @Param("id") id: String,
        @Param("pharmacyIds") pharmacyIds: Set<String>,
    ): Optional<PharmacyEntity>
}
