package kz.epharm.finance.premium

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SalesPremiumJobSchedulerTest {
    private val job = mockk<SalesPremiumJob>()

    private fun until(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("Not reached in 10 seconds: $what")
    }

    @Test
    fun `a tick runs on a thread of its own and a tick that is still running is not started twice`() {
        val threads = CopyOnWriteArrayList<String>()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { job.tick() } answers {
            threads += Thread.currentThread().name
            started.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "the test did not release the tick" }
            JobOutcome.DONE
        } andThenAnswer {
            threads += Thread.currentThread().name
            JobOutcome.NOT_DUE
        }
        val scheduler = SalesPremiumJobScheduler(job)
        try {
            scheduler.run()
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue()

            scheduler.run()
            scheduler.run()
            verify(exactly = 1) { job.tick() }

            release.countDown()
            until("the next tick after the first one finished") {
                scheduler.run()
                threads.size >= 2
            }
        } finally {
            release.countDown()
            scheduler.stop()
        }

        assertThat(threads).allMatch { it == "sales-premium-job" }
        assertThat(threads).doesNotContain(Thread.currentThread().name)
    }

    @Test
    fun `a tick that fails never stops the next one`() {
        every { job.tick() } throws IllegalStateException("boom") andThen JobOutcome.DONE
        val scheduler = SalesPremiumJobScheduler(job)
        try {
            until("two ticks, the first of which failed") {
                scheduler.run()
                runCatching { verify(atLeast = 2) { job.tick() } }.isSuccess
            }
        } finally {
            scheduler.stop()
        }
    }

    @Test
    fun `after the server stops no tick is started and nothing is thrown`() {
        every { job.tick() } returns JobOutcome.DONE
        val scheduler = SalesPremiumJobScheduler(job)
        scheduler.stop()

        scheduler.run()
        scheduler.run()

        verify(exactly = 0) { job.tick() }
    }
}
