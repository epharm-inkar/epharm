-- Every till recommendation request looks up the merchandising branch of its pharmacy:
-- WHERE epharm_pharmacy_id = ? AND pharmacy_binding_verified AND active (NativeRecommendationContextReader).
-- Without an index PostgreSQL read all 680 branches every time: about 29 million full scans and
-- 20 billion row reads on prod by 24.11.2026 (pg_stat_user_tables), the most read table of the base.
--
-- CONCURRENTLY keeps tills working while the index is built; Flyway runs this statement outside a
-- transaction. Keep it the only statement here.
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_merch_branches_epharm_pharmacy
    ON merch_branches (epharm_pharmacy_id)
    WHERE pharmacy_binding_verified AND active;
