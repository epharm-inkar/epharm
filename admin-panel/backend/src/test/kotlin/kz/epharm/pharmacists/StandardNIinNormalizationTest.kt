package kz.epharm.pharmacists

import kz.epharm.pharmacists.service.StandardNPharmacistSyncService.Companion.normalizeIin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** INK-209: Standard-N user cards where MAS typed the IIN with spaces still reach the directory. Synthetic IINs. */
class StandardNIinNormalizationTest {
    @Test fun `twelve digits are kept as they are`() {
        assertEquals("000101500011", normalizeIin("000101500011"))
        assertEquals("000101500011", normalizeIin(" 000101500011 "))
    }

    @Test fun `spaces typed for readability are dropped`() {
        assertEquals("000101500011", normalizeIin("00 0101 500 011"))
        assertEquals("000101500011", normalizeIin("000101\t500011"))
    }

    @Test fun `anything that is not twelve digits stays out of the directory`() {
        listOf(null, "", "  ", "01", "0001015000111", "00010150001", "00010150001A", "000-101-500-011").forEach {
            assertNull(normalizeIin(it), "for <$it>")
        }
    }
}
