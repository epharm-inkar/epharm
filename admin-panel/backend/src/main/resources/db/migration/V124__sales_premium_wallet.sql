-- Sales premium (26.11.2026): the November premium is computed outside ePharm from the central Standard-N database and
-- arrives as cumulative month-to-date amounts per seller IIN. ePharm credits the wallet by the difference to what is
-- already in the ledger. Payouts are a separate piece of work.

-- 1. The ledger learns two sources. A premium credit is always earned money (earned = amount). A correction (a return
--    took the premium back) is the only entry that lowers «earned» together with the balance, by the same amount;
--    every other source keeps its direction rule.
ALTER TABLE pharmacist_wallet_entries
    DROP CONSTRAINT pharmacist_wallet_entries_source_check,
    DROP CONSTRAINT pharmacist_wallet_entries_earned_delta_kzt_check,
    DROP CONSTRAINT ck_wallet_credit_direction;
ALTER TABLE pharmacist_wallet_entries
    ADD CONSTRAINT pharmacist_wallet_entries_source_check CHECK (source IN
        ('receipt_bonus', 'training_bonus', 'merch_task', 'admin_adjustment', 'payout_reservation',
         'sales_premium', 'sales_premium_correction')),
    ADD CONSTRAINT ck_wallet_earned_delta CHECK (earned_delta_kzt >= 0 OR source = 'sales_premium_correction'),
    ADD CONSTRAINT ck_wallet_credit_direction CHECK (
        (source IN ('receipt_bonus', 'training_bonus', 'merch_task') AND amount_kzt > 0)
        OR (source = 'sales_premium' AND amount_kzt > 0 AND earned_delta_kzt = amount_kzt)
        OR (source = 'payout_reservation' AND amount_kzt < 0 AND earned_delta_kzt = 0)
        OR (source = 'admin_adjustment' AND earned_delta_kzt = 0)
        OR (source = 'sales_premium_correction' AND amount_kzt < 0 AND earned_delta_kzt = amount_kzt)
    );

-- 2. Premium of sellers who have no active ePharm card yet: the latest cumulative figure per IIN and month. It is
--    credited automatically when a card with that IIN appears or becomes active, and the row is deleted then.
--    A figure of zero is never stored (nothing is owed).
CREATE TABLE sales_premium_pending (
    iin        VARCHAR(12) NOT NULL CHECK (iin ~ '^[0-9]{12}$'),
    period     VARCHAR(7)  NOT NULL CHECK (period ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
    amount_kzt BIGINT      NOT NULL CHECK (amount_kzt > 0),
    through    DATE        NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (iin, period)
);

-- 3. One row per real (not dry-run) import: who, which month and cut-off, a hash of the file and the summary of what
--    it did. No IIN, name or phone is kept here; the per-person amounts are the ledger entries themselves.
CREATE TABLE sales_premium_imports (
    id             UUID PRIMARY KEY,
    period         VARCHAR(7)  NOT NULL CHECK (period ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
    through        DATE        NOT NULL,
    actor_id       UUID        NOT NULL,
    actor_role     VARCHAR(32) NOT NULL,
    payload_sha256 VARCHAR(64) NOT NULL CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    rows_count     INTEGER     NOT NULL CHECK (rows_count BETWEEN 1 AND 5000),
    summary        JSONB       NOT NULL CHECK (jsonb_typeof(summary) = 'object'),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_sales_premium_imports_period ON sales_premium_imports (period, through DESC);
CREATE TRIGGER sales_premium_imports_append_only BEFORE UPDATE OR DELETE ON sales_premium_imports
FOR EACH ROW EXECUTE FUNCTION reject_wallet_entry_mutation();
