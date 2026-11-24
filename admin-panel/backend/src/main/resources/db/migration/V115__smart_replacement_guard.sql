-- Smart replacements (Grigoriy, 24.11.2026): the till does not offer to replace what the customer is buying when
-- that product is on a network promotion right now (superprice, health day, 1+1), or when the pharmacy earns at
-- least as much on it as on the promoted product. The rules engine checks both on every scan against these tables;
-- a promotion that ends or a margin that changes takes effect without touching any rule.

-- Trade margin per catalogue product: median retail minus median purchase price over recent Standard-N parts
-- (nightly job), or an upload while the job has not run yet.
CREATE TABLE product_trade_margins (
    product_id   VARCHAR(64)   PRIMARY KEY,
    price_kzt    NUMERIC(14,2) NOT NULL CHECK (price_kzt > 0),
    purchase_kzt NUMERIC(14,2) NOT NULL CHECK (purchase_kzt > 0),
    margin_kzt   NUMERIC(14,2) NOT NULL,
    samples      INTEGER       NOT NULL CHECK (samples > 0),
    source       VARCHAR(16)   NOT NULL CHECK (source IN ('standardn', 'upload')),
    measured_at  TIMESTAMPTZ   NOT NULL
);

-- Products on a network promotion of the chain, by barcode (the till sends barcodes; site promotions mirror the
-- pharmacies). Dates are inclusive, in Almaty time; NULL means open-ended.
CREATE TABLE chain_promo_barcodes (
    barcode     VARCHAR(32)  NOT NULL CHECK (barcode ~ '^[0-9]{6,32}$'),
    promotion   VARCHAR(120) NOT NULL,
    valid_from  DATE,
    valid_until DATE,
    source      VARCHAR(16)  NOT NULL CHECK (source IN ('site', 'upload')),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (barcode, promotion),
    CHECK (valid_from IS NULL OR valid_until IS NULL OR valid_from <= valid_until)
);

-- One row. margin_mode 'vs_promo': no replacement when margin(chosen) - margin(promoted) >= margin_tolerance_kzt.
CREATE TABLE replacement_guard_settings (
    id                   SMALLINT     PRIMARY KEY CHECK (id = 1),
    chain_promo_enabled  BOOLEAN      NOT NULL,
    margin_mode          VARCHAR(16)  NOT NULL CHECK (margin_mode IN ('off', 'vs_promo')),
    margin_tolerance_kzt INTEGER      NOT NULL CHECK (margin_tolerance_kzt BETWEEN -100000 AND 100000),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by           VARCHAR(255)
);
INSERT INTO replacement_guard_settings (id, chain_promo_enabled, margin_mode, margin_tolerance_kzt)
VALUES (1, true, 'vs_promo', 0);

-- A pilot-only campaign runs only on the tills of the POSM pilot channel (POSM_UPDATE_PILOT_DEVICES): a new set of
-- recommendations can be tried in one or two pharmacies before the whole network sees it.
ALTER TABLE promos ADD COLUMN pilot_only BOOLEAN NOT NULL DEFAULT false;
