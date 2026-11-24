package kz.epharm.appupdate.service

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class StableReleaseGateTest {
    private val now = Instant.parse("2026-11-24T10:00:00Z")
    private val pilotHourAgo = now.minus(Duration.ofMinutes(61))

    private fun refusal(evidence: StableReleaseGate.Evidence) = StableReleaseGate.refusal(evidence, now, 60, "1.0.91")

    @Test
    fun `stable without any pilot of the same build is refused`() {
        val reason = refusal(StableReleaseGate.Evidence(false, null, 0, 0))
        assertTrue(reason!!.contains("пилотом"), reason)
    }

    @Test
    fun `a pilot younger than the minimum is refused`() {
        val reason = refusal(StableReleaseGate.Evidence(false, now.minus(Duration.ofMinutes(20)), 2, 5))
        assertTrue(reason!!.contains("20 мин"), reason)
    }

    @Test
    fun `no pilot till online on the version is refused`() {
        val reason = refusal(StableReleaseGate.Evidence(false, pilotHourAgo, 0, 5))
        assertTrue(reason!!.contains("не на связи"), reason)
    }

    @Test
    fun `no receipt from a pilot till since the pilot started is refused`() {
        val reason = refusal(StableReleaseGate.Evidence(false, pilotHourAgo, 1, 0))
        assertTrue(reason!!.contains("чека"), reason)
    }

    @Test
    fun `a checked pilot lets the build reach every till`() {
        assertNull(refusal(StableReleaseGate.Evidence(false, pilotHourAgo, 1, 1)))
    }

    @Test
    fun `going back to a build that was stable before needs no new pilot`() {
        assertNull(refusal(StableReleaseGate.Evidence(true, null, 0, 0)))
    }
}
