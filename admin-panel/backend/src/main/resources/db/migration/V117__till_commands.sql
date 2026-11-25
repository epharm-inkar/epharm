-- V117: tills managed without AnyDesk (Grigoriy, 25.11.2026). HQ presses a button in the admin, the till takes the
-- task with its own device key, runs one action from a list compiled into the till program (no parameters, no
-- arbitrary code) and reports back. A waiting task lives 30 minutes, so a switched-off till never runs it a week
-- later; a taken task has 10 minutes for the answer. Diagnostics archives (zip, at most 5 MB) are kept 14 days in
-- the private object storage bucket; artifact_bytes stays after the archive is removed.
CREATE TABLE till_commands (
    id             UUID          PRIMARY KEY,
    pharmacy_id    VARCHAR(64)   NOT NULL,
    device_id      VARCHAR(128)  NOT NULL,
    action         VARCHAR(16)   NOT NULL CHECK (action IN ('diagnostics', 'restart')),
    status         VARCHAR(16)   NOT NULL CHECK (status IN ('pending', 'delivered', 'done', 'failed', 'expired')),
    requested_by   VARCHAR(255)  NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    expires_at     TIMESTAMPTZ   NOT NULL,
    delivered_at   TIMESTAMPTZ,
    finished_at    TIMESTAMPTZ,
    result_message VARCHAR(2000),
    artifact_key   VARCHAR(255),
    artifact_bytes BIGINT        CHECK (artifact_bytes BETWEEN 1 AND 5242880),
    CONSTRAINT ck_till_commands_lifecycle CHECK (
        (status = 'pending' AND delivered_at IS NULL AND finished_at IS NULL) OR
        (status = 'delivered' AND delivered_at IS NOT NULL AND finished_at IS NULL) OR
        (status IN ('done', 'failed') AND delivered_at IS NOT NULL AND finished_at IS NOT NULL) OR
        (status = 'expired' AND finished_at IS NOT NULL)
    ),
    CONSTRAINT ck_till_commands_artifact CHECK (
        (artifact_key IS NULL OR artifact_bytes IS NOT NULL) AND
        (artifact_bytes IS NULL OR (action = 'diagnostics' AND status IN ('done', 'failed')))
    )
);

-- One task in work per till: a second request gets 409 while the previous one waits or runs.
CREATE UNIQUE INDEX uq_till_commands_active ON till_commands (pharmacy_id, device_id)
    WHERE status IN ('pending', 'delivered');
CREATE INDEX ix_till_commands_till_time ON till_commands (pharmacy_id, device_id, created_at DESC);
CREATE INDEX ix_till_commands_time ON till_commands (created_at DESC);
CREATE INDEX ix_till_commands_archives ON till_commands (finished_at) WHERE artifact_key IS NOT NULL;
