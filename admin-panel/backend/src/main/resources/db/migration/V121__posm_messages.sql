-- V121: notices from HQ to pharmacists at the till (Grigoriy, 25.11.2026). HQ writes a title and a text, chooses who
-- gets it (everybody, the staff of chosen pharmacies or chosen pharmacists) and for how many days tills show it.
-- A till (POSM 1.0.99+) shows a notice to the person signed in to Standard-N at it until they close it with ×;
-- one receipt row per person records when they first saw it and when they closed it. A withdrawn notice leaves
-- the tills at their next check. Nothing here touches money or the maintenance switch (posm_notice, V116).
CREATE TABLE posm_messages (
    id               UUID          PRIMARY KEY,
    title            VARCHAR(120)  NOT NULL CHECK (length(btrim(title)) > 0),
    body             VARCHAR(1000) NOT NULL CHECK (length(btrim(body)) > 0),
    audience         VARCHAR(16)   NOT NULL CHECK (audience IN ('all', 'pharmacies', 'pharmacists')),
    -- Active cards in scope when HQ pressed «Отправить»; a person seen later at a targeted till still gets it.
    recipients_total INT           NOT NULL DEFAULT 0 CHECK (recipients_total >= 0),
    created_by       VARCHAR(255)  NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    expires_at       TIMESTAMPTZ   NOT NULL,
    withdrawn_at     TIMESTAMPTZ,
    withdrawn_by     VARCHAR(255),
    CONSTRAINT ck_posm_messages_window CHECK (expires_at > created_at),
    CONSTRAINT ck_posm_messages_withdrawn CHECK ((withdrawn_at IS NULL) = (withdrawn_by IS NULL))
);

CREATE INDEX ix_posm_messages_created ON posm_messages (created_at DESC);

-- Who a notice is for when it is not for everybody: one row per chosen pharmacy or per chosen pharmacist.
CREATE TABLE posm_message_targets (
    message_id    UUID        NOT NULL REFERENCES posm_messages (id) ON DELETE CASCADE,
    pharmacy_id   VARCHAR(64) REFERENCES pharmacies (id) ON DELETE CASCADE,
    pharmacist_id VARCHAR(64) REFERENCES pharmacists (id) ON DELETE CASCADE,
    CONSTRAINT ck_posm_message_targets_one CHECK ((pharmacy_id IS NULL) <> (pharmacist_id IS NULL))
);

CREATE UNIQUE INDEX uq_posm_message_targets_pharmacy
    ON posm_message_targets (message_id, pharmacy_id) WHERE pharmacy_id IS NOT NULL;
CREATE UNIQUE INDEX uq_posm_message_targets_pharmacist
    ON posm_message_targets (message_id, pharmacist_id) WHERE pharmacist_id IS NOT NULL;
CREATE INDEX ix_posm_message_targets_pharmacy ON posm_message_targets (pharmacy_id) WHERE pharmacy_id IS NOT NULL;
CREATE INDEX ix_posm_message_targets_pharmacist ON posm_message_targets (pharmacist_id) WHERE pharmacist_id IS NOT NULL;

-- One row per notice and person: first shown at a till, closed with × (both kept as first reported).
CREATE TABLE posm_message_receipts (
    message_id    UUID         NOT NULL REFERENCES posm_messages (id) ON DELETE CASCADE,
    pharmacist_id VARCHAR(64)  NOT NULL REFERENCES pharmacists (id) ON DELETE CASCADE,
    shown_at      TIMESTAMPTZ  NOT NULL,
    closed_at     TIMESTAMPTZ,
    pharmacy_id   VARCHAR(64)  NOT NULL,
    device_id     VARCHAR(128),
    PRIMARY KEY (message_id, pharmacist_id),
    CONSTRAINT ck_posm_message_receipts_order CHECK (closed_at IS NULL OR closed_at >= shown_at)
);

CREATE INDEX ix_posm_message_receipts_pharmacist ON posm_message_receipts (pharmacist_id);
