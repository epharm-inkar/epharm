package kz.epharm.finance.premium

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** A clock the test moves by hand; it reads as Almaty time unless told otherwise. */
class MutableClock(start: Instant, private val zone: ZoneId = SalesPremiumRules.ALMATY) : Clock() {
    @Volatile private var now: Instant = start

    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)

    override fun instant(): Instant = now

    fun advance(by: Duration) {
        now = now.plus(by)
    }

    fun set(local: LocalDateTime) {
        now = local.atZone(zone).toInstant()
    }

    companion object {
        /** Almaty wall-clock time, e.g. `almaty(2026, 11, 26, 6, 40)`. */
        fun almaty(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): MutableClock =
            MutableClock(LocalDateTime.of(year, month, day, hour, minute).atZone(SalesPremiumRules.ALMATY).toInstant())
    }
}
