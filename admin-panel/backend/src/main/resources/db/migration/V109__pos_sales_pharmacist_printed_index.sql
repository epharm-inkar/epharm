-- INK-145: the pharmacist roster counts each pharmacist's receipts for 30 days.
-- Without this index PostgreSQL reads every sale of the period through
-- idx_pos_sales_journal_time (about 300 thousand rows of a 2 GB table) and the
-- request ran 40-75 s (HTTP 503). Only about 14 thousand sales carry a resolved
-- pharmacist, so the partial index is small.
--
-- CONCURRENTLY keeps cash desks writing sales while the index is built; Flyway
-- runs this statement outside a transaction. Keep it the only statement here.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_pos_sales_pharmacist_printed
    ON pos_sales (pharmacist_id, printed_at)
    WHERE pharmacist_id <> '';
