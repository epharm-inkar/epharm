-- Preserve V102 identity history while distinguishing a new contradiction from repeated live checks.
ALTER TABLE posm_source_staff_binding_audit ADD COLUMN transition_order BIGSERIAL;
CREATE INDEX ix_source_binding_audit_transition ON posm_source_staff_binding_audit(binding_id, transition_order DESC);
DO $$ DECLARE constraint_name text; BEGIN
    FOR constraint_name IN SELECT conname FROM pg_constraint c
        WHERE conrelid='posm_source_staff_binding_audit'::regclass AND contype='u'
          AND (SELECT string_agg(a.attname,',' ORDER BY array_position(c.conkey,a.attnum))
               FROM pg_attribute a WHERE a.attrelid=c.conrelid AND a.attnum=ANY(c.conkey))
              = 'binding_id,claimant_id,action,reason_code,proof_hash'
    LOOP
        EXECUTE format('ALTER TABLE posm_source_staff_binding_audit DROP CONSTRAINT %I', constraint_name);
    END LOOP;
END $$;
