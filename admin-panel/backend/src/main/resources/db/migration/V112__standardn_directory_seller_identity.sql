-- Seller identity from the central Standard-N directory, without ePharm registration.
-- A receipt carries the Standard-N USER_ID of the pharmacy database; USERS.ID is unique only within
-- one Standard-N profile (G$PROFILES.ID), so every pharmacy needs its profile to find the person.

-- Pharmacy <-> Standard-N profile. 'caption' rows are recomputed after each directory sync from the
-- profile caption; 'manual' rows are set by HQ and never overwritten.
ALTER TABLE pharmacies
    ADD COLUMN standardn_profile_id BIGINT,
    ADD COLUMN standardn_profile_source VARCHAR(16);
ALTER TABLE pharmacies
    ADD CONSTRAINT ck_pharmacies_standardn_profile CHECK (
        (standardn_profile_id IS NULL AND standardn_profile_source IS NULL) OR
        (standardn_profile_id IS NOT NULL AND standardn_profile_source IS NOT NULL
         AND standardn_profile_source IN ('caption', 'manual'))
    );
CREATE INDEX ix_pharmacies_standardn_profile
    ON pharmacies (standardn_profile_id)
    WHERE standardn_profile_id IS NOT NULL;

-- IIN of the Standard-N user who printed the receipt. Analytics identity: it never authorizes money.
ALTER TABLE pos_sales ADD COLUMN seller_iin VARCHAR(12);
ALTER TABLE pos_sales
    ADD CONSTRAINT ck_pos_sales_seller_iin CHECK (seller_iin IS NULL OR seller_iin ~ '^[0-9]{12}$') NOT VALID;
CREATE INDEX ix_pos_sales_seller_iin_printed
    ON pos_sales (seller_iin, printed_at)
    WHERE seller_iin IS NOT NULL;

ALTER TABLE pos_sales DROP CONSTRAINT ck_pos_sales_pharmacist_source;
ALTER TABLE pos_sales ADD CONSTRAINT ck_pos_sales_pharmacist_source CHECK (pharmacist_source IN
    ('legacy','posm_internal','standardn_explicit_mapping','standardn_rps_binding','standardn_name_match',
     'standardn_directory','standardn_directory_unregistered','standardn_unmapped','unresolved')) NOT VALID;
ALTER TABLE pos_sales VALIDATE CONSTRAINT ck_pos_sales_pharmacist_source;
