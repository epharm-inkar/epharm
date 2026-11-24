package kz.epharm.appupdate

import kz.epharm.appupdate.service.PosmUpdatePilotScope
import kz.epharm.appupdate.service.ReleaseVersions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PosmUpdatePilotScopeTest {

    @Test
    fun `a pilot entry names a pharmacy and one till or all its tills`() {
        val scope = PosmUpdatePilotScope(" ph_a|KASSA1 , ph_b|* ")
        assertTrue(scope.allows("ph_a", "KASSA1", legacy = false))
        assertTrue(scope.allows("ph_a", "kassa1", legacy = false))
        assertFalse(scope.allows("ph_a", "KASSA2", legacy = false))
        assertTrue(scope.allows("ph_b", "MANAGER", legacy = false))
        assertFalse(scope.allows("ph_c", "KASSA1", legacy = false))
    }

    @Test
    fun `the retired fleet key and unknown identities never join a pilot`() {
        val scope = PosmUpdatePilotScope("ph_a|*")
        assertFalse(scope.allows("ph_a", "KASSA1", legacy = true))
        assertFalse(scope.allows(null, "KASSA1", legacy = false))
        assertFalse(scope.allows("ph_a", null, legacy = false))
    }

    @Test
    fun `empty admits nobody and malformed entries are refused`() {
        assertTrue(PosmUpdatePilotScope("").isEmpty)
        assertFalse(PosmUpdatePilotScope("").allows("ph_a", "KASSA1", legacy = false))
        assertThrows(IllegalArgumentException::class.java) { PosmUpdatePilotScope("KASSA1") }
        assertThrows(IllegalArgumentException::class.java) { PosmUpdatePilotScope("*|*") }
        assertThrows(IllegalArgumentException::class.java) { PosmUpdatePilotScope("ph_a|") }
    }

    @Test
    fun `versions compare numerically part by part`() {
        assertTrue(ReleaseVersions.compare("1.0.91", "1.0.90") > 0)
        assertTrue(ReleaseVersions.compare("1.0.100", "1.0.99") > 0)
        assertTrue(ReleaseVersions.compare("1.0.9", "1.0.10") < 0)
        assertEquals(0, ReleaseVersions.compare("1.1", "1.1.0"))
    }
}
