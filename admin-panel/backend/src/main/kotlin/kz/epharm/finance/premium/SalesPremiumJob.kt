package kz.epharm.finance.premium

import jakarta.annotation.PreDestroy
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** What one tick of the daily job did. */
enum class JobOutcome {
    /** `SALES_PREMIUM_JOB_ENABLED` is not `true`: nothing is read, nothing is written. */
    DISABLED,

    /** Outside the time window, the day is already done, or the last attempt was too recent. */
    NOT_DUE,

    /** Another calculation (an administrator's) is running: nothing was read. */
    BUSY,

    /** The read was complete and there was no sale of the matrix at all, so nothing was sent. */
    NOTHING_TO_SEND,

    /** The figures were credited. */
    DONE,

    /** The database did not open or the read or the import failed: the next attempt comes after the retry interval. */
    FAILED,
}

/**
 * When the daily job runs: the first attempt at [start] and, while the database does not open, another one every
 * [retry] until [deadline], all on Asia/Almaty time. The database is closed at night, so the morning is the window.
 */
data class SalesPremiumJobSettings(val enabled: Boolean, val start: LocalTime, val deadline: LocalTime, val retry: Duration) {
    companion object {
        val DEFAULT_START: LocalTime = LocalTime.of(6, 40)
        val DEFAULT_DEADLINE: LocalTime = LocalTime.of(11, 0)
        val DEFAULT_RETRY: Duration = Duration.ofMinutes(30)
        private val log = LoggerFactory.getLogger(SalesPremiumJobSettings::class.java)

        /** A mistyped value must not stop the server from starting: it is logged and the default is used. */
        fun parse(enabled: Boolean, start: String, deadline: String, retryMinutes: Long): SalesPremiumJobSettings {
            var from = time("SALES_PREMIUM_JOB_START", start, DEFAULT_START)
            var until = time("SALES_PREMIUM_JOB_DEADLINE", deadline, DEFAULT_DEADLINE)
            if (!from.isBefore(until)) {
                log.error("The start {} is not before the deadline {}: using {} and {}", from, until, DEFAULT_START, DEFAULT_DEADLINE)
                from = DEFAULT_START
                until = DEFAULT_DEADLINE
            }
            val retry = if (retryMinutes in MIN_RETRY_MINUTES..MAX_RETRY_MINUTES) {
                Duration.ofMinutes(retryMinutes)
            } else {
                log.error("SALES_PREMIUM_JOB_RETRY_MINUTES={} is outside {}..{}: using {}", retryMinutes, MIN_RETRY_MINUTES, MAX_RETRY_MINUTES, DEFAULT_RETRY)
                DEFAULT_RETRY
            }
            return SalesPremiumJobSettings(enabled, from, until, retry)
        }

        private fun time(name: String, raw: String, default: LocalTime): LocalTime = try {
            LocalTime.parse(raw.trim())
        } catch (_: DateTimeParseException) {
            log.error("{}='{}' is not a time of day (HH:mm): using {}", name, raw, default)
            default
        }

        private const val MIN_RETRY_MINUTES = 5L
        private const val MAX_RETRY_MINUTES = 240L
    }
}

/**
 * The daily collection of the sales premium (off unless `SALES_PREMIUM_JOB_ENABLED=true`). A scheduler asks it every
 * minute whether a run is due; it never runs twice in a day once it has succeeded, and after a restart it looks at the
 * audit table for a run of today before it starts another. A run covers the month of yesterday through yesterday.
 * Failures only log: the figures are cumulative, so a missed day is made up by the next one.
 */
@Service
class SalesPremiumJob(
    private val collector: SalesPremiumCollector,
    private val runLog: SalesPremiumRunLog,
    @Value("\${app.finance.sales-premium.job.enabled:false}") enabled: Boolean,
    @Value("\${app.finance.sales-premium.job.start:06:40}") start: String,
    @Value("\${app.finance.sales-premium.job.deadline:11:00}") deadline: String,
    @Value("\${app.finance.sales-premium.job.retry-minutes:30}") retryMinutes: Long,
    private val clock: Clock = Clock.system(SalesPremiumRules.ALMATY),
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val settings = SalesPremiumJobSettings.parse(enabled, start, deadline, retryMinutes)

    @Volatile private var doneOn: LocalDate? = null
    @Volatile private var lastAttempt: Instant? = null

    fun tick(): JobOutcome {
        if (!settings.enabled) return JobOutcome.DISABLED
        val now = ZonedDateTime.now(clock)
        val today = now.toLocalDate()
        val time = now.toLocalTime()
        if (time.isBefore(settings.start) || !time.isBefore(settings.deadline)) return JobOutcome.NOT_DUE
        if (doneOn == today) return JobOutcome.NOT_DUE
        if (collectedToday(today)) {
            doneOn = today
            return JobOutcome.NOT_DUE
        }
        val previous = lastAttempt
        if (previous != null && Duration.between(previous, now.toInstant()) < settings.retry) return JobOutcome.NOT_DUE
        lastAttempt = now.toInstant()
        return run(today)
    }

    private fun run(today: LocalDate): JobOutcome = try {
        val result = collector.tryCollect(null, dryRun = false, actor = PremiumActor.SYSTEM_JOB)
        when {
            result == null -> {
                log.info("Sales premium job: a calculation is already running, skipped")
                JobOutcome.BUSY
            }
            else -> {
                doneOn = today
                if (result.imported == null) {
                    log.warn("Sales premium job: no sale of the matrix products in {} through {}, nothing was sent", result.period, result.through)
                    JobOutcome.NOTHING_TO_SEND
                } else {
                    JobOutcome.DONE
                }
            }
        }
    } catch (e: StandardNSourceException) {
        log.warn("Sales premium job: Standard-N is not available ({}), next attempt in {} until {}", e.message, settings.retry, settings.deadline)
        JobOutcome.FAILED
    } catch (e: Exception) {
        log.error("Sales premium job failed, next attempt in {} until {}", settings.retry, settings.deadline, e)
        JobOutcome.FAILED
    }

    /** After a restart the audit table is the proof that today's run was done; if it cannot be read, run again. */
    private fun collectedToday(day: LocalDate): Boolean = try {
        runLog.collectedBy(PremiumActor.SYSTEM_JOB, day)
    } catch (e: Exception) {
        log.warn("Sales premium job: the audit table could not be read, assuming no run today: {}", e.javaClass.simpleName)
        false
    }
}

/** Reads the audit table of real imports: a real run of the job always leaves a row with the fixed system actor. */
@Service
class SalesPremiumRunLog(private val jdbc: NamedParameterJdbcTemplate) {
    /** True when [actor] loaded something on the Almaty calendar [day]. */
    @Transactional(readOnly = true)
    fun collectedBy(actor: PremiumActor, day: LocalDate): Boolean {
        val dayStart = day.atStartOfDay(SalesPremiumRules.ALMATY).toOffsetDateTime()
        return jdbc.queryForObject(
            """SELECT EXISTS (SELECT 1 FROM sales_premium_imports
                WHERE actor_id = :actor AND created_at >= :from AND created_at < :until)""",
            MapSqlParameterSource().addValue("actor", actor.id)
                .addValue("from", dayStart).addValue("until", dayStart.plusDays(1)),
            Boolean::class.java,
        ) == true
    }
}

/**
 * Not part of the `test` profile: the tests call [SalesPremiumJob.tick] with their own clock. The tick runs on a thread
 * of its own: a slow Standard-N must not hold the shared scheduler thread that the payouts and the other jobs need.
 */
@Component
@Profile("!test")
class SalesPremiumJobScheduler(private val job: SalesPremiumJob) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "sales-premium-job").apply { isDaemon = true } }
    private val ticking = AtomicBoolean(false)

    @Scheduled(
        fixedDelayString = "\${app.finance.sales-premium.job.tick-ms:60000}",
        initialDelayString = "\${app.finance.sales-premium.job.initial-delay-ms:120000}",
    )
    fun run() {
        if (!ticking.compareAndSet(false, true)) return
        try {
            worker.execute {
                try {
                    job.tick()
                } catch (e: Exception) {
                    log.error("Sales premium job tick failed: {}", e.javaClass.simpleName)
                } finally {
                    ticking.set(false)
                }
            }
        } catch (e: RejectedExecutionException) {
            ticking.set(false)
        }
    }

    @PreDestroy
    fun stop() {
        worker.shutdown()
    }
}
