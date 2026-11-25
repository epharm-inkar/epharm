package kz.epharm.posm

import kz.epharm.posm.notice.PosmNotice
import kz.epharm.posm.notice.PosmNoticeService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** «ePharm на технических работах»: what a till sees, pilot tills included. */
class PosmNoticeTillViewTest {
    private val works = PosmNoticeService.DEFAULT

    @Test fun `during the works an ordinary till shows the notice`() {
        val view = PosmNoticeService.tillView(works, pilotTill = false)
        assertTrue(view.maintenance)
        assertEquals("ePharm на технических работах", view.title)
    }

    @Test fun `a pilot till stays live while pilot_live is on`() {
        assertFalse(PosmNoticeService.tillView(works, pilotTill = true).maintenance)
        assertTrue(PosmNoticeService.tillView(works.copy(pilotLive = false), pilotTill = true).maintenance)
    }

    @Test fun `switched off, every till is live`() {
        val off = PosmNotice(maintenance = false, pilotLive = true, title = "t", message = "m")
        assertFalse(PosmNoticeService.tillView(off, pilotTill = false).maintenance)
        assertFalse(PosmNoticeService.tillView(off, pilotTill = true).maintenance)
    }
}
