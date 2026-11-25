package kz.epharm.posm.commands

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** Hourly: elapsed commands become expired, diagnostics archives older than 14 days leave the private bucket. */
@Component
class TillCommandCleanup(private val commands: TillCommandService) {
    private val log = LoggerFactory.getLogger(TillCommandCleanup::class.java)

    @Scheduled(cron = "\${app.posm.commands.cleanup-cron:0 23 * * * *}", zone = "Asia/Almaty")
    fun clean() {
        try {
            val expired = commands.expireAllOverdue()
            val removed = commands.removeOldArchives()
            if (expired > 0 || removed > 0) log.info("Till commands: {} expired, {} old archives removed", expired, removed)
        } catch (error: RuntimeException) {
            log.warn("Till command cleanup failed, next run retries: {}", error.message)
        }
    }
}
