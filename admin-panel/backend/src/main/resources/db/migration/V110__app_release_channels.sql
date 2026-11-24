-- V110: POSM pilot channel (24.11.2026). A client release can be published to the pilot channel first: only the
-- tills listed in POSM_UPDATE_PILOT_DEVICES receive it, every other till keeps the current stable release. The
-- fleet rollout is a separate stable publication of the same signed package. Existing rows are stable releases.
ALTER TABLE app_releases ADD COLUMN channel VARCHAR(16) NOT NULL DEFAULT 'stable';
ALTER TABLE app_releases ADD CONSTRAINT ck_app_releases_channel CHECK (channel IN ('stable', 'pilot'));

-- One current release per platform and channel (was: per platform, V099).
DROP INDEX uq_app_releases_current_platform;
CREATE UNIQUE INDEX uq_app_releases_current_platform_channel
    ON app_releases(platform, channel) WHERE is_current;
