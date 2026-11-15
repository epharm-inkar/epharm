-- Additive, opt-in financial evidence. No historical event or balance is backfilled.
ALTER TABLE pos_sales ADD COLUMN reward_evidence JSONB;
ALTER TABLE pos_sales ADD COLUMN device_id VARCHAR(128);

CREATE TABLE recommendation_reward_shows (
    event_id VARCHAR(64) PRIMARY KEY REFERENCES recommendation_events(id) ON DELETE RESTRICT,
    pharmacy_id VARCHAR(64) NOT NULL REFERENCES pharmacies(id) ON DELETE RESTRICT,
    device_id VARCHAR(128),
    shown_at TIMESTAMPTZ NOT NULL,
    shown_at_exact VARCHAR(40) NOT NULL,
    evidence JSONB NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    auto_eligible BOOLEAN NOT NULL DEFAULT false,
    promo_id VARCHAR(64) REFERENCES promos(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_reward_shows_pharmacy ON recommendation_reward_shows(pharmacy_id, created_at);
CREATE INDEX ix_reward_event_session ON recommendation_events(pharmacy_id, session_id);

-- Both fences matter: a different saleId must not bypass the physical document,
-- and a different namespace must not monetize the same fiscal receipt again.
CREATE TABLE recommendation_reward_receipts (
    id VARCHAR(64) PRIMARY KEY,
    pharmacy_id VARCHAR(64) NOT NULL REFERENCES pharmacies(id) ON DELETE RESTRICT,
    source_sale_id VARCHAR(64) NOT NULL REFERENCES pos_sales(id) ON DELETE RESTRICT,
    physical_key VARCHAR(64) UNIQUE,
    fiscal_key VARCHAR(64) UNIQUE,
    payload_hash VARCHAR(64) NOT NULL,
    device_id VARCHAR(128),
    session_id VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE recommendation_sale_rewards (
    id VARCHAR(64) PRIMARY KEY,
    canonical_receipt_id VARCHAR(64) NOT NULL REFERENCES recommendation_reward_receipts(id) ON DELETE RESTRICT,
    pharmacy_id VARCHAR(64) NOT NULL REFERENCES pharmacies(id) ON DELETE RESTRICT,
    pharmacist_id VARCHAR(64) REFERENCES pharmacists(id) ON DELETE RESTRICT,
    device_id VARCHAR(128),
    source_sale_id VARCHAR(64) NOT NULL REFERENCES pos_sales(id) ON DELETE RESTRICT,
    product_id VARCHAR(64) NOT NULL,
    event_id VARCHAR(64) REFERENCES recommendation_events(id) ON DELETE RESTRICT,
    promo_id VARCHAR(64) REFERENCES promos(id) ON DELETE RESTRICT,
    state VARCHAR(32) NOT NULL CHECK (state IN
        ('waiting_evidence','not_eligible','review_required','ready','credited','rejected','refund_review')),
    reason_code VARCHAR(64) NOT NULL,
    unit_bonus BIGINT NOT NULL DEFAULT 0 CHECK (unit_bonus >= 0),
    eligible_packages BIGINT NOT NULL DEFAULT 0 CHECK (eligible_packages >= 0),
    total_bonus BIGINT NOT NULL DEFAULT 0 CHECK (total_bonus >= 0),
    pending_bonus_id VARCHAR(64) UNIQUE REFERENCES pending_bonuses(id) ON DELETE RESTRICT,
    receipt_id VARCHAR(64) UNIQUE REFERENCES receipts(id) ON DELETE RESTRICT,
    budget_reserved BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (canonical_receipt_id, product_id)
);
CREATE INDEX ix_sale_rewards_review ON recommendation_sale_rewards(state, created_at, id);
CREATE INDEX ix_sale_rewards_pharmacy ON recommendation_sale_rewards(pharmacy_id, created_at, id);
CREATE INDEX ix_sale_rewards_sale ON recommendation_sale_rewards(source_sale_id);
CREATE INDEX ix_pos_sales_reward_session ON pos_sales(pharmacy_id, session_id) WHERE reward_evidence IS NOT NULL;

CREATE TABLE recommendation_reward_audit (
    id VARCHAR(64) PRIMARY KEY,
    reward_id VARCHAR(64) REFERENCES recommendation_sale_rewards(id) ON DELETE RESTRICT,
    pharmacy_id VARCHAR(64) NOT NULL REFERENCES pharmacies(id) ON DELETE RESTRICT,
    actor_id VARCHAR(64) NOT NULL,
    action VARCHAR(64) NOT NULL,
    reason TEXT NOT NULL,
    source_key VARCHAR(128) UNIQUE,
    detail JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_reward_audit_reward ON recommendation_reward_audit(reward_id, created_at);

-- Refunds can arrive before the original offline sale. Keep the fence, not just a log line.
CREATE TABLE recommendation_reward_refund_holds (
    refund_sale_id VARCHAR(64) PRIMARY KEY REFERENCES pos_sales(id) ON DELETE RESTRICT,
    pharmacy_id VARCHAR(64) NOT NULL REFERENCES pharmacies(id) ON DELETE RESTRICT,
    physical_key VARCHAR(64),
    fiscal_key VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_reward_refund_physical ON recommendation_reward_refund_holds(physical_key) WHERE physical_key IS NOT NULL;
CREATE INDEX ix_reward_refund_fiscal ON recommendation_reward_refund_holds(fiscal_key) WHERE fiscal_key IS NOT NULL;
