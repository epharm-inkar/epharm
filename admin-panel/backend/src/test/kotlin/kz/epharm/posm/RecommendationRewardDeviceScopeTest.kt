package kz.epharm.posm

import kz.epharm.posm.service.PosmDeviceIdentity
import kz.epharm.posm.service.RecommendationRewardDeviceScope
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RecommendationRewardDeviceScopeTest {
    @Test fun `empty rollout admits no device including legacy`() {
        val scope = RecommendationRewardDeviceScope("")
        assertFalse(scope.allows(PosmDeviceIdentity("CASH-1", "ph-1", false)))
        assertFalse(scope.allows(PosmDeviceIdentity("CASH-1", "ph-1", true)))
        assertFalse(scope.allowsId(null))
    }
    @Test fun `pilot matches exact authenticated device only`() {
        val scope = RecommendationRewardDeviceScope(" CASH-1, CASH-2 ")
        assertTrue(scope.allows(PosmDeviceIdentity("CASH-1", "ph-1", false)))
        assertFalse(scope.allows(PosmDeviceIdentity("CASH-1", "ph-1", true)))
        assertFalse(scope.allowsId("cash-1"))
        assertFalse(scope.allowsId("CASH-10"))
    }
    @Test fun `fleet wildcard remains explicit and excludes legacy and missing IDs`() {
        val scope = RecommendationRewardDeviceScope("*")
        assertTrue(scope.allows(PosmDeviceIdentity("CASH-3", "ph-1", false)))
        assertFalse(scope.allows(PosmDeviceIdentity("CASH-3", "ph-1", true)))
        assertFalse(scope.allowsId(""))
        assertThrows(IllegalArgumentException::class.java) { RecommendationRewardDeviceScope("*,CASH-1") }
    }
}
