package kz.epharm.posm.commands

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kz.epharm.posm.commands.TillCommandRules.ResultDecision
import kz.epharm.shared.error.AppException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

class TillCommandRulesTest {
    private val zip = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00)
    private val emptyZip = byteArrayOf(0x50, 0x4B, 0x05, 0x06) + ByteArray(18)

    @Test fun `only the fixed actions are accepted`() {
        assertEquals(TillCommandAction.DIAGNOSTICS, TillCommandRules.action("diagnostics"))
        assertEquals(TillCommandAction.RESTART, TillCommandRules.action(" restart "))
        for (raw in listOf(null, "", "Restart", "shell", "diagnostics;restart", "cmd /c")) {
            assertEquals(400, assertThrows<AppException> { TillCommandRules.action(raw) }.status.value(), raw)
        }
    }

    @Test fun `a till may only answer done or failed`() {
        assertEquals(TillCommandStatus.DONE, TillCommandRules.resultStatus("done"))
        assertEquals(TillCommandStatus.FAILED, TillCommandRules.resultStatus("failed"))
        for (raw in listOf(null, "", "pending", "delivered", "expired", "ok")) {
            assertEquals(400, assertThrows<AppException> { TillCommandRules.resultStatus(raw) }.status.value(), raw)
        }
    }

    @Test fun `ids are trimmed, required and bounded`() {
        assertEquals("ph_1", TillCommandRules.requiredId("  ph_1 ", "pharmacyId", 64))
        assertThrows<AppException> { TillCommandRules.requiredId(" ", "pharmacyId", 64) }
        assertThrows<AppException> { TillCommandRules.requiredId("x".repeat(65), "pharmacyId", 64) }
        assertThrows<AppException> { TillCommandRules.requiredId("KASSA\u0000", "deviceId", 128) }
        assertNull(TillCommandRules.optionalId("  ", "deviceId", 128))
    }

    @Test fun `list limit defaults to 50 and stays within 1 to 500`() {
        assertEquals(50, TillCommandRules.listLimit(null))
        assertEquals(1, TillCommandRules.listLimit(0))
        assertEquals(1, TillCommandRules.listLimit(-5))
        assertEquals(500, TillCommandRules.listLimit(10_000))
        assertEquals(20, TillCommandRules.listLimit(20))
    }

    @Test fun `message keeps line breaks, drops control characters and is cut to 2000 characters`() {
        assertNull(TillCommandRules.message(null))
        assertNull(TillCommandRules.message(" \u0000 \t "))
        assertEquals("Журнал собран\nверсия 1.0.94", TillCommandRules.message("  Журнал\u0000 собран\nверсия 1.0.94\u0007 "))
        val long = "я".repeat(2500)
        assertEquals(2000, TillCommandRules.message(long)!!.length)
        // A character outside the basic plane is two UTF-16 units: the cut never splits it.
        val emoji = "😀"
        val cut = TillCommandRules.message("a".repeat(1999) + emoji + "tail")!!
        assertEquals(2000, cut.codePointCount(0, cut.length))
        assertTrue(cut.endsWith(emoji))
        assertEquals("ab", TillCommandRules.message("a\uD800b"))
    }

    @Test fun `archive signature is checked`() {
        assertTrue(TillCommandRules.looksLikeZip(zip))
        assertTrue(TillCommandRules.looksLikeZip(emptyZip))
        assertFalse(TillCommandRules.looksLikeZip(byteArrayOf(0x50, 0x4B)))
        assertFalse(TillCommandRules.looksLikeZip("MZ\u0090\u0000".toByteArray()))
        assertFalse(TillCommandRules.looksLikeZip(ByteArray(0)))
    }

    @Test fun `archive only for diagnostics, up to exactly 5 MB`() {
        val max = TillCommandRules.MAX_ARCHIVE_BYTES
        assertDoesNotThrow { TillCommandRules.checkArchive(TillCommandAction.DIAGNOSTICS, max, zip) }
        assertEquals(413, assertThrows<AppException> { TillCommandRules.checkArchive(TillCommandAction.DIAGNOSTICS, max + 1, zip) }.status.value())
        assertEquals(400, assertThrows<AppException> { TillCommandRules.checkArchive(TillCommandAction.DIAGNOSTICS, 10, "text".toByteArray()) }.status.value())
        assertEquals(400, assertThrows<AppException> { TillCommandRules.checkArchive(TillCommandAction.RESTART, 10, zip) }.status.value())
    }

    @Test fun `a result is accepted only for a taken command before its deadline`() {
        assertEquals(ResultDecision.ACCEPT, TillCommandRules.resultDecision(TillCommandStatus.DELIVERED, overdue = false))
        assertEquals(ResultDecision.EXPIRED, TillCommandRules.resultDecision(TillCommandStatus.DELIVERED, overdue = true))
        assertEquals(ResultDecision.NOT_TAKEN, TillCommandRules.resultDecision(TillCommandStatus.PENDING, overdue = false))
        assertEquals(ResultDecision.EXPIRED, TillCommandRules.resultDecision(TillCommandStatus.PENDING, overdue = true))
        assertEquals(ResultDecision.ALREADY_FINISHED, TillCommandRules.resultDecision(TillCommandStatus.DONE, overdue = true))
        assertEquals(ResultDecision.ALREADY_FINISHED, TillCommandRules.resultDecision(TillCommandStatus.FAILED, overdue = false))
        assertEquals(ResultDecision.EXPIRED, TillCommandRules.resultDecision(TillCommandStatus.EXPIRED, overdue = true))
        assertEquals(ResultDecision.NOT_FOUND, TillCommandRules.resultDecision(null, overdue = false))
    }

    @Test fun `HQ sees an elapsed waiting or taken command as expired at its deadline`() {
        val deadline = Instant.parse("2026-11-25T06:30:00Z")
        assertEquals(TillCommandStatus.EXPIRED, TillCommandRules.visibleStatus(TillCommandStatus.PENDING, overdue = true))
        assertEquals(TillCommandStatus.EXPIRED, TillCommandRules.visibleStatus(TillCommandStatus.DELIVERED, overdue = true))
        assertEquals(TillCommandStatus.PENDING, TillCommandRules.visibleStatus(TillCommandStatus.PENDING, overdue = false))
        // A finished command keeps its own status and time whatever the deadline says.
        assertEquals(TillCommandStatus.DONE, TillCommandRules.visibleStatus(TillCommandStatus.DONE, overdue = true))
        assertEquals(deadline, TillCommandRules.visibleFinishedAt(TillCommandStatus.DELIVERED, true, null, deadline))
        val finished = Instant.parse("2026-11-25T06:10:00Z")
        assertEquals(finished, TillCommandRules.visibleFinishedAt(TillCommandStatus.DONE, true, finished, deadline))
        assertNull(TillCommandRules.visibleFinishedAt(TillCommandStatus.PENDING, false, null, deadline))
    }

    @Test fun `archive keys are generated and recognised under the diagnostics prefix only`() {
        val key = TillCommandRules.archiveKey(UUID.randomUUID())
        assertTrue(key.startsWith("till-diagnostics/"))
        assertTrue(TillCommandRules.isArchiveKey(key))
        assertFalse(TillCommandRules.isArchiveKey("screens/${UUID.randomUUID()}.png"))
        assertFalse(TillCommandRules.isArchiveKey("till-diagnostics/../secret.zip"))
        assertFalse(TillCommandRules.isArchiveKey(key.uppercase()))
    }

    @Test fun `download name is safe ASCII with the Almaty time`() {
        val at = Instant.parse("2026-11-25T04:05:00Z")
        val time = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(ZoneId.of("Asia/Almaty")).format(at)
        assertEquals("epharm-diagnostics-ph_1-KASSA-1-$time.zip", TillCommandRules.archiveFileName("ph_1", "KASSA-1", at))
        assertEquals("epharm-diagnostics-sloc_x-DESKTOP_7.zip", TillCommandRules.archiveFileName("sloc x", "DESKTOP\\7", null))
        assertEquals("epharm-diagnostics-till-till.zip", TillCommandRules.archiveFileName("Аптека", "..", null))
    }
}
