package kz.epharm.posm.service

/**
 * `fulfillment_devices.last_seen_at` used to be rewritten on every authenticated POSM request
 * (50 million updates of an 839-row table in three weeks). A device counts as online while its
 * last_seen_at is younger than `app.posm.heartbeat-ttl-seconds` (150 s), so one write a minute
 * keeps the presence fallback exact.
 */
object DeviceLastSeen {
    /** SQL condition for `UPDATE fulfillment_devices`: touch only a row not refreshed within the last minute. */
    const val STALE_CONDITION = "(last_seen_at IS NULL OR last_seen_at < now() - interval '60 seconds')"
}
