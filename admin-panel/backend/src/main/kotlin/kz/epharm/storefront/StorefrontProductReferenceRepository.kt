package kz.epharm.storefront

import com.fasterxml.jackson.databind.ObjectMapper
import kz.epharm.medusa.dto.MedusaProduct
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/** Website IDs need their original handle after a restart; names are never identity keys. */
@Repository
class StorefrontProductReferenceRepository(
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
) {
    /**
     * Writes in its own transaction. Readers call the website inside read-only transactions (the bonus tab):
     * there PostgreSQL refuses the INSERT and aborts the caller's whole transaction, so every later query of the
     * request fails with 25P02 (27.11.2026, «Не удалось загрузить» on every product of the bonus tab).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun remember(origin: String, products: List<MedusaProduct>) {
        val valid = products.filter { it.id.isNotBlank() && !it.handle.isNullOrBlank() }
        if (valid.isEmpty()) return
        jdbc.batchUpdate(
            """
            INSERT INTO storefront_product_references (origin, product_id, payload)
            VALUES (?, ?, ?::jsonb)
            ON CONFLICT (origin, product_id) DO UPDATE
            SET payload = EXCLUDED.payload, updated_at = now()
            """.trimIndent(),
            valid, valid.size,
        ) { statement, product ->
            statement.setString(1, origin)
            statement.setString(2, product.id)
            statement.setString(3, mapper.writeValueAsString(product))
        }
    }

    fun find(origin: String, id: String): MedusaProduct? = jdbc.query(
        "SELECT payload::text FROM storefront_product_references WHERE origin = ? AND product_id = ?",
        { rs, _ -> mapper.readValue(rs.getString(1), MedusaProduct::class.java) },
        origin, id,
    ).firstOrNull()?.takeIf { it.id == id }
}
