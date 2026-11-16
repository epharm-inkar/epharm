-- Offline receipts need historical source proof, not reusable authentication credentials.
-- Cleanup moves only expired verified grants here; no token, process ID or raw personal proof is retained.
CREATE TABLE posm_staff_source_proofs (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pharmacist_id VARCHAR(64) NOT NULL REFERENCES pharmacists(id) ON DELETE CASCADE,
    pharmacy_id VARCHAR(64) NOT NULL REFERENCES pharmacies(id) ON DELETE CASCADE,
    device_id VARCHAR(128) NOT NULL,
    source_namespace VARCHAR(64) NOT NULL CHECK (source_namespace ~ '^[a-f0-9]{64}$'),
    standard_n_user_id VARCHAR(128) NOT NULL,
    standard_n_session_id VARCHAR(128) NOT NULL,
    workstation_id VARCHAR(128) NOT NULL,
    password_fingerprint VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    source_verified_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    source_proof_method VARCHAR(8) NOT NULL CHECK (source_proof_method IN ('IIN','NAME')),
    source_proof_hash VARCHAR(64) NOT NULL CHECK (source_proof_hash ~ '^[a-f0-9]{64}$'),
    retained_until TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_staff_source_proof_scope ON posm_staff_source_proofs
    (pharmacist_id, pharmacy_id, device_id, source_namespace, standard_n_user_id, standard_n_session_id, workstation_id);
CREATE INDEX ix_staff_source_proof_retention ON posm_staff_source_proofs(retained_until);
