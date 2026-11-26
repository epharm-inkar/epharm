-- INK-209 (26.11.2026): a card left in 'pending' by the old registration blocked the person the central Standard-N
-- directory names at the till (65 receipts without a seller on 26.11). The server now takes such a card over for
-- the directory person: active, the directory name and pharmacy, no phone and no password, sessions revoked. The
-- old registration may have been made with someone else's IIN, so its phone, name and password are not carried over;
-- this table keeps what was replaced for HQ review. Only the replaced values, never a plaintext password.
CREATE TABLE pharmacist_registration_replacements (
    id             UUID         PRIMARY KEY,
    pharmacist_id  VARCHAR(64)  NOT NULL REFERENCES pharmacists(id) ON DELETE CASCADE,
    old_name       VARCHAR(255) NOT NULL,
    old_phone      VARCHAR(32)  NOT NULL,
    old_pharmacy_id VARCHAR(64),
    had_password   BOOLEAN      NOT NULL,
    old_created_at TIMESTAMPTZ  NOT NULL,
    new_pharmacy_id VARCHAR(64) NOT NULL,
    reason         VARCHAR(64)  NOT NULL DEFAULT 'standardn_directory_seller',
    replaced_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_pharmacist_registration_replacements_pharmacist ON pharmacist_registration_replacements (pharmacist_id);
