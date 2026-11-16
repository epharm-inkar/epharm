package kz.epharm.catalog.repository

import kz.epharm.catalog.entity.ProductEntity
import jakarta.persistence.QueryHint
import org.hibernate.jpa.HibernateHints
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.QueryHints
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface ProductRepository : JpaRepository<ProductEntity, String> {
    @Query("SELECT p FROM ProductEntity p WHERE p.id IN :ids")
    @QueryHints(QueryHint(name = HibernateHints.HINT_READ_ONLY, value = "true"))
    fun findMatchProductsByIds(ids: Collection<String>): List<ProductEntity>

    @Query("SELECT p FROM ProductEntity p WHERE p.mnn IN :mnns ORDER BY p.name ASC")
    @QueryHints(QueryHint(name = HibernateHints.HINT_READ_ONLY, value = "true"))
    fun findMatchProductsByMnns(mnns: Collection<String>): List<ProductEntity>

    fun findAllByOrderByNameAsc(): List<ProductEntity>
    fun findAllByBrandOrderByNameAsc(brand: String): List<ProductEntity>
    fun findAllByMnnOrderByNameAsc(mnn: String): List<ProductEntity>
    fun findAllByMnnInOrderByNameAsc(mnns: Collection<String>): List<ProductEntity>

    /** Товары каталога, привязанные к Medusa — для регулярного рефреша цены планировщиком. */
    fun findAllByMedusaProductIdIsNotNull(): List<ProductEntity>

    /** Товары по списку iPartID Стандарт-Н — точный матчинг корзины POSM-кассы. */
    fun findAllByIpartIdIn(ipartIds: Collection<String>): List<ProductEntity>

    /** Товары по списку штрих-кодов — точный матчинг корзины POSM-кассы. */
    @Query(
        value = """
            SELECT DISTINCT p.* FROM products p
             WHERE p.barcode IN (:barcodes)
                OR EXISTS (SELECT 1 FROM jsonb_array_elements_text(p.barcodes) b(code)
                            WHERE b.code IN (:barcodes))
        """,
        nativeQuery = true,
    )
    fun findAllByBarcodeIn(@Param("barcodes") barcodes: Collection<String>): List<ProductEntity>
}
