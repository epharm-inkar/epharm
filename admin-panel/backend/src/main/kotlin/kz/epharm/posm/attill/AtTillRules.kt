package kz.epharm.posm.attill

import java.time.Duration
import java.time.Instant

/**
 * Who is «at a till right now» (owner, 26.11.2026). The till names the Standard-N user signed in at it with its
 * 15-minute check and whenever the cashier changes; the server resolves that user to a pharmacist. A pharmacist is at
 * a till now when their till is online (the heartbeat rule of DevicePresenceService, 150 seconds) and the latest user
 * the till named was named within the presence window: 20 minutes, the 15-minute check and a margin
 * (`app.posm.person-presence-minutes`). A till keeps one person, the latest one: a colleague who signs in takes over.
 * Pure rules, no I/O, unit-tested.
 */
object AtTillRules {
    const val DEFAULT_WINDOW_MINUTES = 20L
    private const val MIN_WINDOW_MINUTES = 5L
    private const val MAX_WINDOW_MINUTES = 180L

    /** How long a till's last person is remembered at all; readers apply the shorter presence window. */
    val RETENTION: Duration = Duration.ofDays(2)

    private const val SEPARATOR = "\u001F"
    private const val FIELDS = 5

    /** The setting, kept between 5 minutes (shorter than the till's own check cannot work) and 3 hours. */
    fun window(minutes: Long): Duration = Duration.ofMinutes(minutes.coerceIn(MIN_WINDOW_MINUTES, MAX_WINDOW_MINUTES))

    /**
     * The key of a till in the person store: the pharmacy and the computer name. The name is compared without regard to
     * case: the heartbeat spells it as the till does and the key as the office wrote it (KASSA1 and kassa1).
     */
    fun tillKey(pharmacyId: String?, deviceId: String): String = "${pharmacyId.orEmpty()}$SEPARATOR$deviceId".lowercase()

    /**
     * The record a till's request leaves. The same person keeps [TillPerson.since] while they keep being named;
     * another person, or the same one after a gap longer than the window, starts a new stretch. The time never goes
     * back (two server instances may disagree by a second).
     */
    fun next(
        previous: TillPerson?,
        pharmacistId: String,
        pharmacyId: String,
        deviceId: String,
        now: Instant,
        window: Duration,
    ): TillPerson {
        val continues = previous != null && previous.pharmacistId == pharmacistId && !previous.seenAt.isBefore(now.minus(window))
        val seenAt = if (previous != null && previous.seenAt.isAfter(now)) previous.seenAt else now
        return TillPerson(
            pharmacistId = pharmacistId,
            pharmacyId = pharmacyId,
            deviceId = deviceId,
            since = if (continues) previous!!.since else now,
            seenAt = seenAt,
        )
    }

    /** The newer of two records of the same till, either may be missing. */
    fun newest(first: TillPerson?, second: TillPerson?): TillPerson? = when {
        first == null -> second
        second == null -> first
        second.seenAt.isAfter(first.seenAt) -> second
        else -> first
    }

    /** At a till right now: the till is online and its person was named within the window. */
    fun isAtTill(person: TillPerson, onlineTillKeys: Set<String>, now: Instant, window: Duration): Boolean =
        !person.seenAt.isBefore(now.minus(window)) && tillKey(person.pharmacyId, person.deviceId) in onlineTillKeys

    fun encode(person: TillPerson): String = listOf(
        person.pharmacistId,
        person.pharmacyId,
        person.deviceId,
        person.since.toEpochMilli().toString(),
        person.seenAt.toEpochMilli().toString(),
    ).joinToString(SEPARATOR)

    /** What [encode] wrote; anything else (an older or damaged value) is no record. */
    fun decode(value: String?): TillPerson? {
        val parts = value?.split(SEPARATOR) ?: return null
        if (parts.size != FIELDS || parts.take(3).any { it.isBlank() }) return null
        val since = parts[3].toLongOrNull() ?: return null
        val seenAt = parts[4].toLongOrNull() ?: return null
        return TillPerson(parts[0], parts[1], parts[2], Instant.ofEpochMilli(since), Instant.ofEpochMilli(seenAt))
    }
}
