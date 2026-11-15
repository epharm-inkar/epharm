-- Additive source-scoped identity proof. No historical profile, mapping or balance is rewritten.
ALTER TABLE posm_staff_sessions ADD COLUMN source_namespace VARCHAR(64);
ALTER TABLE posm_device_staff_sessions ADD COLUMN source_namespace VARCHAR(64);
ALTER TABLE posm_device_staff_sessions ADD COLUMN source_verified_at TIMESTAMPTZ;
ALTER TABLE posm_device_staff_sessions ADD COLUMN source_proof_method VARCHAR(8);
ALTER TABLE posm_device_staff_sessions ADD COLUMN source_proof_hash VARCHAR(64);

ALTER TABLE pos_sales DROP CONSTRAINT ck_pos_sales_pharmacist_source;
ALTER TABLE pos_sales ADD CONSTRAINT ck_pos_sales_pharmacist_source CHECK (pharmacist_source IN
    ('legacy','posm_internal','standardn_explicit_mapping','standardn_rps_binding','standardn_name_match','standardn_unmapped','unresolved'));
ALTER TABLE posm_pharmacist_mapping_audit DROP CONSTRAINT ck_posm_mapping_audit_action;
ALTER TABLE posm_pharmacist_mapping_audit ADD CONSTRAINT ck_posm_mapping_audit_action
    CHECK (action IN ('created','remapped','reactivated','revoked','reviewed'));

CREATE TABLE posm_source_staff_bindings (
    id UUID PRIMARY KEY,
    pharmacy_id VARCHAR(64) NOT NULL REFERENCES pharmacies(id) ON DELETE RESTRICT,
    device_id VARCHAR(128) NOT NULL,
    source_namespace VARCHAR(64) NOT NULL CHECK (source_namespace ~ '^[a-f0-9]{64}$'),
    native_user_id VARCHAR(128) NOT NULL,
    pharmacist_id VARCHAR(64) NOT NULL REFERENCES pharmacists(id) ON DELETE RESTRICT,
    proof_method VARCHAR(8) NOT NULL CHECK (proof_method IN ('IIN','NAME')),
    proof_hash VARCHAR(64) NOT NULL CHECK (proof_hash ~ '^[a-f0-9]{64}$'),
    state VARCHAR(16) NOT NULL CHECK (state IN ('active','conflict')),
    reason_code VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    confirmed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    conflict_at TIMESTAMPTZ,
    UNIQUE (pharmacy_id, device_id, source_namespace, native_user_id)
);
CREATE INDEX ix_source_staff_bindings_person ON posm_source_staff_bindings(pharmacist_id);

-- Only identifiers and normalized proof digests are retained; no raw IIN, phone, name or password copy.
CREATE TABLE posm_source_staff_binding_audit (
    id UUID PRIMARY KEY,
    binding_id UUID NOT NULL REFERENCES posm_source_staff_bindings(id) ON DELETE RESTRICT,
    claimant_id VARCHAR(64) NOT NULL REFERENCES pharmacists(id) ON DELETE RESTRICT,
    action VARCHAR(16) NOT NULL CHECK (action IN ('created','conflict','reconfirmed')),
    reason_code VARCHAR(64) NOT NULL,
    proof_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (binding_id, claimant_id, action, reason_code, proof_hash)
);
CREATE INDEX ix_source_staff_binding_audit_binding ON posm_source_staff_binding_audit(binding_id, created_at);
CREATE INDEX ix_device_staff_source_proof ON posm_device_staff_sessions
    (pharmacy_id, device_id, source_namespace, standard_n_user_id, standard_n_session_id, workstation_id, created_at);
