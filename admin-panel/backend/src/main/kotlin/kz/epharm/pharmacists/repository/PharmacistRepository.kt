package kz.epharm.pharmacists.repository

import kz.epharm.pharmacists.entity.PharmacistEntity
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.Optional
import java.time.Instant

interface PharmacistReceiptCountRow {
    val pharmacistId: String
    val pharmacyId: String
    val count: Long
}

interface PharmacyPharmacistCountRow {
    val pharmacyId: String
    val count: Long
}

@Repository
interface PharmacistRepository : JpaRepository<PharmacistEntity, String> {
    fun findAllByOrderByNameAsc(): List<PharmacistEntity>
    fun findAllByStatusRawOrderByNameAsc(statusRaw: String): List<PharmacistEntity>
    fun findAllByStatusRawAndPharmacyIdOrderByNameAsc(statusRaw: String, pharmacyId: String): List<PharmacistEntity>
    fun findAllByPharmacyIdOrderByNameAsc(pharmacyId: String): List<PharmacistEntity>
    fun findAllByCityIgnoreCaseAndStatusRawOrderByNameAsc(city: String, statusRaw: String): List<PharmacistEntity>
    fun findAllByPharmacyIdAndNameIgnoreCaseAndStatusRaw(
        pharmacyId: String,
        name: String,
        statusRaw: String,
    ): List<PharmacistEntity>
    fun findByIin(iin: String): PharmacistEntity?
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PharmacistEntity p WHERE p.iin = :iin")
    fun findByIinForUpdate(@Param("iin") iin: String): PharmacistEntity?
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PharmacistEntity p WHERE p.id = :id")
    fun findByIdForUpdate(@Param("id") id: String): PharmacistEntity?
    fun findByPhone(phone: String): PharmacistEntity?
    fun countByStatusRaw(statusRaw: String): Long
    fun countByPharmacyId(pharmacyId: String): Long

    /**
     * Read-only, exact resolved sellers in their current pharmacy; raw/name matches are not inferred.
     * Sales are filtered by `s.pharmacist_id` first, with the literal `<> ''` predicate, so PostgreSQL
     * can use the partial index `idx_pos_sales_pharmacist_printed` (V109) instead of reading every
     * sale of the period through the time index (INK-145: 40-75 s and HTTP 503 on the roster).
     */
    @Query(nativeQuery = true, value = """
        SELECT p.id AS "pharmacistId", p.pharmacy_id AS "pharmacyId", COUNT(*) AS "count"
        FROM pos_sales s
        JOIN pharmacists p ON p.id = s.pharmacist_id AND p.pharmacy_id = s.pharmacy_id
        WHERE s.pharmacist_id IN (:ids)
          AND s.pharmacist_id <> ''
          AND p.pharmacy_id <> ''
          AND s.printed_at >= :since AND s.printed_at <= :observedAt
          AND COALESCE(s.reward_evidence #>> '{completion,status}', '') <> 'refund'
        GROUP BY p.id, p.pharmacy_id
    """)
    fun receiptCounts(
        @Param("ids") ids: Set<String>,
        @Param("since") since: Instant,
        @Param("observedAt") observedAt: Instant,
    ): List<PharmacistReceiptCountRow>

    /** Live profile count per pharmacy; `pharmacies.pharmacists` is a stale seed column (INK-145). */
    @Query(nativeQuery = true, value = """
        SELECT p.pharmacy_id AS "pharmacyId", COUNT(*) AS "count"
        FROM pharmacists p
        WHERE p.pharmacy_id IN (:pharmacyIds)
        GROUP BY p.pharmacy_id
    """)
    fun countsByPharmacy(@Param("pharmacyIds") pharmacyIds: Set<String>): List<PharmacyPharmacistCountRow>

    @Query("""
        SELECT p FROM PharmacistEntity p
        WHERE p.pharmacyId IN :pharmacyIds
          AND (:statusRaw IS NULL OR p.statusRaw = :statusRaw)
          AND (:pharmacyId IS NULL OR p.pharmacyId = :pharmacyId)
        ORDER BY p.name
    """)
    fun findForAdministration(
        @Param("pharmacyIds") pharmacyIds: Set<String>,
        @Param("statusRaw") statusRaw: String?,
        @Param("pharmacyId") pharmacyId: String?,
    ): List<PharmacistEntity>

    @Query("SELECT p FROM PharmacistEntity p WHERE p.id = :id AND p.pharmacyId IN :pharmacyIds")
    fun findInAdministrativeScope(
        @Param("id") id: String,
        @Param("pharmacyIds") pharmacyIds: Set<String>,
    ): Optional<PharmacistEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PharmacistEntity p WHERE p.id = :id AND p.pharmacyId IN :pharmacyIds")
    fun findInAdministrativeScopeForUpdate(
        @Param("id") id: String,
        @Param("pharmacyIds") pharmacyIds: Set<String>,
    ): Optional<PharmacistEntity>
}
