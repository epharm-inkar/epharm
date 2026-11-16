-- An inventory observation is independent of POSM registration and live activity.
-- No installation count, source document or remote-access identifier is inferred/backfilled.
ALTER TABLE pharmacies
    ADD COLUMN inventory_device_count INTEGER,
    ADD COLUMN inventory_source VARCHAR(255),
    ADD COLUMN inventory_updated_at TIMESTAMPTZ;

ALTER TABLE pharmacies ADD CONSTRAINT ck_pharmacy_device_inventory CHECK (
    (inventory_device_count IS NULL AND inventory_source IS NULL AND inventory_updated_at IS NULL)
    OR
    (inventory_device_count IS NOT NULL AND inventory_device_count >= 0
        AND inventory_source IS NOT NULL AND length(btrim(inventory_source)) > 0
        AND inventory_source !~ '[[:cntrl:]]' AND inventory_updated_at IS NOT NULL)
);
