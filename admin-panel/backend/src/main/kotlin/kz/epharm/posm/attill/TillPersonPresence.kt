package kz.epharm.posm.attill

import kz.epharm.posm.service.PosmDeviceIdentity
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Who each till last named (owner, 26.11.2026: «активен ли этот фармацевт сейчас»). The till names the Standard-N user
 * signed in at it with its 15-minute check, on a cashier change and when the personal window opens; the server resolves
 * the user to a pharmacist and records it here, next to the device presence of DevicePresenceService:
 *
 * - Redis hash `epharm:posm:presence:person`, one field per till (pharmacy and computer name, as the device presence
 *   keys it, lower case), the value the pharmacist, the pharmacy, the computer, when this person appeared and when the
 *   till last named them. A till keeps one person: the latest.
 * - An in-memory copy, the fail-safe when Redis is down or switched off (the test profile), as in DevicePresenceService.
 * - Table `pharmacist_till_seen`, the last time each pharmacist was seen at a till, written at most once in five minutes
 *   per pharmacist, for «был за кассой 26.11 14:05» on the roster. It survives a Redis loss.
 *
 * Recording never fails a till request: a Redis or database error is logged (once a minute) and the request goes on.
 * Whether a person is at a till right now is decided by [AtTillService] from these records and the device presence.
 */
@Service
class TillPersonPresence(
    @Value("\${app.posm.person-presence-minutes:20}") windowMinutes: Long,
    private val redisProvider: ObjectProvider<StringRedisTemplate>? = null,
    private val jdbc: JdbcTemplate? = null,
) {
    /** How long after the till's last check its person still counts as at the till. */
    val window: Duration = AtTillRules.window(windowMinutes)

    private val local = ConcurrentHashMap<String, TillPerson>()
    private val lastDatabaseWriteMs = ConcurrentHashMap<String, Long>()
    private val lastWarningAt = AtomicLong(0)
    private val log = LoggerFactory.getLogger(TillPersonPresence::class.java)

    /**
     * The till [device] named [pharmacistId] as its Standard-N user. A till without its own key (the old fleet key) has
     * no identity to remember and is skipped.
     */
    fun record(device: PosmDeviceIdentity, pharmacistId: String, now: Instant = Instant.now()) {
        val deviceId = device.deviceId?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val pharmacyId = device.pharmacyId?.trim()?.takeIf { it.isNotEmpty() } ?: return
        try {
            val key = AtTillRules.tillKey(pharmacyId, deviceId)
            val previous = AtTillRules.newest(local[key], readRedis(key))
            val person = AtTillRules.next(previous, pharmacistId, pharmacyId, deviceId, now, window)
            local[key] = person
            withRedis { it.opsForHash<String, String>().put(PERSON_KEY, key, AtTillRules.encode(person)) }
            rememberLastSeen(person)
        } catch (ex: RuntimeException) {
            warn("POSM person presence was not recorded", ex)
        }
    }

    /**
     * Every till's latest person seen in the last two days, newest record per till. The caller applies the presence
     * window and the device presence; records older than the retention are dropped here so the hash stays small.
     */
    fun latest(now: Instant = Instant.now()): List<TillPerson> {
        val cutoff = now.minus(AtTillRules.RETENTION)
        val merged = HashMap<String, TillPerson>()
        withRedis { redis ->
            val hash = redis.opsForHash<String, String>()
            val stale = ArrayList<String>()
            for ((key, value) in hash.entries(PERSON_KEY)) {
                val person = AtTillRules.decode(value)
                if (person == null || person.seenAt.isBefore(cutoff)) stale += key else merged[key] = person
            }
            if (stale.isNotEmpty()) hash.delete(PERSON_KEY, *stale.toTypedArray())
        }
        for ((key, person) in local) {
            if (person.seenAt.isBefore(cutoff)) {
                local.remove(key)
                continue
            }
            AtTillRules.newest(merged[key], person)?.let { merged[key] = it }
        }
        return merged.values.toList()
    }

    /** Forgets every record, in memory and in Redis (the table is left alone). For tests and for a reset by hand. */
    fun forgetAll() {
        local.clear()
        lastDatabaseWriteMs.clear()
        withRedis { it.delete(PERSON_KEY) }
    }

    private fun readRedis(key: String): TillPerson? {
        var result: TillPerson? = null
        withRedis { result = AtTillRules.decode(it.opsForHash<String, String>().get(PERSON_KEY, key)) }
        return result
    }

    /** The durable «last seen at a till», written at most once in five minutes per pharmacist by this instance. */
    private fun rememberLastSeen(person: TillPerson) {
        val template = jdbc ?: return
        val atMs = person.seenAt.toEpochMilli()
        val last = lastDatabaseWriteMs[person.pharmacistId]
        if (last != null && atMs - last < DATABASE_WRITE_EVERY_MS) return
        // Marked before the write: a failed write is tried again after the interval, not on every request.
        lastDatabaseWriteMs[person.pharmacistId] = atMs
        template.update(
            """
            INSERT INTO pharmacist_till_seen (pharmacist_id, seen_at, pharmacy_id, device_id)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (pharmacist_id) DO UPDATE
                SET seen_at = EXCLUDED.seen_at, pharmacy_id = EXCLUDED.pharmacy_id, device_id = EXCLUDED.device_id
                WHERE pharmacist_till_seen.seen_at < EXCLUDED.seen_at
            """.trimIndent(),
            person.pharmacistId, Timestamp.from(person.seenAt), person.pharmacyId, person.deviceId.take(DEVICE_ID_MAX),
        )
    }

    private inline fun withRedis(block: (StringRedisTemplate) -> Unit) {
        val redis = try {
            redisProvider?.getIfAvailable()
        } catch (ex: RuntimeException) {
            warn("POSM person presence Redis is not available", ex)
            null
        } ?: return
        try {
            block(redis)
        } catch (ex: RuntimeException) {
            warn("POSM person presence Redis failed; the in-memory copy stands in", ex)
        }
    }

    private fun warn(what: String, ex: RuntimeException) {
        val now = System.currentTimeMillis()
        val previous = lastWarningAt.get()
        if (now - previous >= WARNING_INTERVAL_MS && lastWarningAt.compareAndSet(previous, now)) {
            log.warn("{}: {}", what, ex.message)
        }
    }

    private companion object {
        const val PERSON_KEY = "epharm:posm:presence:person"
        const val DATABASE_WRITE_EVERY_MS = 5 * 60 * 1000L
        const val WARNING_INTERVAL_MS = 60_000L
        const val DEVICE_ID_MAX = 128
    }
}
