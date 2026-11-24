-- No registration (Grigoriy and Dmitry, 24.11.2026): a pharmacist card is created by the server from the
-- central Standard-N directory (IIN, full name, pharmacy) the first time the person is seen at a till.
-- Phone and password become optional details the pharmacist adds later from the till window.

-- Cards created from Standard-N have no phone yet: only real numbers stay unique.
DROP INDEX ux_pharmacists_phone;
CREATE UNIQUE INDEX ux_pharmacists_phone ON pharmacists (phone) WHERE phone <> '';

ALTER TABLE pharmacists ADD COLUMN account_source VARCHAR(16) NOT NULL DEFAULT 'registration';
ALTER TABLE pharmacists ADD CONSTRAINT ck_pharmacists_account_source
    CHECK (account_source IN ('registration', 'standardn'));

-- One-time links that open the pharmacist web cabinet already signed in, issued to a till for the person
-- currently signed in to Standard-N there. Only the SHA-256 of the token is stored.
CREATE TABLE posm_cabinet_links (
    token_hash    VARCHAR(64)  PRIMARY KEY CHECK (token_hash ~ '^[a-f0-9]{64}$'),
    pharmacist_id VARCHAR(64)  NOT NULL REFERENCES pharmacists(id) ON DELETE CASCADE,
    pharmacy_id   VARCHAR(64)  NOT NULL,
    device_id     VARCHAR(128) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ  NOT NULL,
    used_at       TIMESTAMPTZ
);
CREATE INDEX ix_posm_cabinet_links_expires ON posm_cabinet_links (expires_at);

-- A cabinet opened from a till runs on a shared computer: its session ends with the shift (12 hours), and
-- every refresh keeps that end. NULL for ordinary password logins.
ALTER TABLE mobile_refresh_tokens ADD COLUMN session_ends_at TIMESTAMPTZ;
