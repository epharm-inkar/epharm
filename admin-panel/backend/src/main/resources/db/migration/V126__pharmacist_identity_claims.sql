-- Self-reported identity (owner, 26.11.2026). 41 people sell every day without an IIN in Standard-N, some names are
-- cut short (no patronymic, a nickname). The pharmacist types the IIN and the full name at the till (or the full
-- name in the cabinet), the office confirms, and the central Standard-N directory wins as soon as it has the person.
-- A claim never pays by itself: only a confirmed one creates (or reuses) a card and an HQ mapping.

-- Cards created from a confirmed claim have their own source, and a card shows where its IIN and its name came from.
ALTER TABLE pharmacists DROP CONSTRAINT ck_pharmacists_account_source;
ALTER TABLE pharmacists ADD CONSTRAINT ck_pharmacists_account_source
    CHECK (account_source IN ('registration', 'standardn', 'self'));

ALTER TABLE pharmacists
    ADD COLUMN iin_source  VARCHAR(16) NOT NULL DEFAULT 'registration',
    ADD COLUMN name_source VARCHAR(16) NOT NULL DEFAULT 'registration';
ALTER TABLE pharmacists ADD CONSTRAINT ck_pharmacists_iin_source
    CHECK (iin_source IN ('registration', 'standardn', 'self', 'hq'));
ALTER TABLE pharmacists ADD CONSTRAINT ck_pharmacists_name_source
    CHECK (name_source IN ('registration', 'standardn', 'self', 'hq'));
UPDATE pharmacists SET iin_source = 'standardn', name_source = 'standardn' WHERE account_source = 'standardn';

-- kind 'person': a Standard-N user without a card (till): IIN and full name, key = pharmacy + Standard-N USER_ID.
-- kind 'name': a full name for an existing card (cabinet): IIN is the card's own, key = the card.
CREATE TABLE pharmacist_identity_claims (
    id                     UUID         PRIMARY KEY,
    kind                   VARCHAR(8)   NOT NULL,
    pharmacy_id            VARCHAR(64)  REFERENCES pharmacies (id) ON DELETE RESTRICT,
    -- The Standard-N profile of the pharmacy at the time of the claim (null when the pharmacy has no link yet).
    standardn_profile_id   BIGINT,
    -- Standard-N USERS.ID as the till reports it: the same value that receipts carry and that
    -- posm_pharmacist_mappings.external_user_id holds.
    standardn_user_id      VARCHAR(20),
    standardn_user_name    VARCHAR(255),
    device_id              VARCHAR(128),
    -- The card: set when HQ confirms a 'person' claim, always set for a 'name' claim.
    pharmacist_id          VARCHAR(64)  REFERENCES pharmacists (id) ON DELETE CASCADE,
    iin                    VARCHAR(12)  NOT NULL,
    full_name              VARCHAR(255) NOT NULL,
    status                 VARCHAR(12)  NOT NULL DEFAULT 'pending',
    -- Flag written when the claim is made or superseded; more flags are computed when HQ opens the list.
    conflict               VARCHAR(32),
    conflict_pharmacist_id VARCHAR(64),
    -- The IIN the directory brought when it disagreed with the claim.
    directory_iin          VARCHAR(12),
    decided_by             UUID         REFERENCES admin_users (id) ON DELETE SET NULL,
    decided_at             TIMESTAMPTZ,
    decision_reason        VARCHAR(500),
    mapping_id             UUID         REFERENCES posm_pharmacist_mappings (id) ON DELETE SET NULL,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_identity_claims_kind CHECK (kind IN ('person', 'name')),
    CONSTRAINT ck_identity_claims_status CHECK (status IN ('pending', 'confirmed', 'rejected', 'superseded')),
    CONSTRAINT ck_identity_claims_iin CHECK (iin ~ '^[0-9]{12}$'),
    CONSTRAINT ck_identity_claims_directory_iin CHECK (directory_iin IS NULL OR directory_iin ~ '^[0-9]{12}$'),
    CONSTRAINT ck_identity_claims_user_id CHECK (standardn_user_id IS NULL OR standardn_user_id ~ '^[1-9][0-9]{0,14}$'),
    CONSTRAINT ck_identity_claims_person CHECK (kind <> 'person' OR (pharmacy_id IS NOT NULL AND standardn_user_id IS NOT NULL)),
    CONSTRAINT ck_identity_claims_name CHECK (kind <> 'name' OR pharmacist_id IS NOT NULL),
    CONSTRAINT ck_identity_claims_decided CHECK (status = 'pending' OR decided_at IS NOT NULL)
);

-- One open claim per Standard-N user of a pharmacy, and one open name proposal per card.
CREATE UNIQUE INDEX ux_identity_claims_pending_person
    ON pharmacist_identity_claims (pharmacy_id, standardn_user_id)
    WHERE status = 'pending' AND kind = 'person';
CREATE UNIQUE INDEX ux_identity_claims_pending_name
    ON pharmacist_identity_claims (pharmacist_id)
    WHERE status = 'pending' AND kind = 'name';
CREATE INDEX ix_identity_claims_status ON pharmacist_identity_claims (status, created_at DESC);
CREATE INDEX ix_identity_claims_iin ON pharmacist_identity_claims (iin);
CREATE INDEX ix_identity_claims_user ON pharmacist_identity_claims (pharmacy_id, standardn_user_id);
CREATE INDEX ix_identity_claims_pharmacist ON pharmacist_identity_claims (pharmacist_id);

COMMENT ON TABLE pharmacist_identity_claims IS
    'IIN and full name typed by a pharmacist (till or cabinet). Pending claims pay nothing; HQ confirms; the Standard-N directory supersedes them.';
