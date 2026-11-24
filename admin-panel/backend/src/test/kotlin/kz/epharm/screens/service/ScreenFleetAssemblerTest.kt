package kz.epharm.screens.service

import kz.epharm.pharmacies.entity.PharmacyEntity
import kz.epharm.posm.service.DevicePresenceService.Presence
import kz.epharm.screens.dto.ActivePlaylistDto
import kz.epharm.screens.dto.ActiveSlideDto
import kz.epharm.screens.dto.FleetRegisterState
import kz.epharm.screens.dto.ResolvedScreenPlaylist
import kz.epharm.screens.dto.ScreenPlaylistResolution
import kz.epharm.screens.dto.ScreenPlaylistSource
import kz.epharm.screens.entity.SlideKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class ScreenFleetAssemblerTest {
    private val now = Instant.parse("2026-11-24T09:00:00Z")

    @Test
    fun `registered tills get online, offline and stale states and online diagnostics`() {
        val fleet = assemble(
            registered = listOf(
                device("KASSA1", "ph_a", lastSeen = now.minusSeconds(30)),
                device("KASSA2", "ph_a", lastSeen = now.minus(Duration.ofHours(5))),
                device("OLD-PC", "ph_a", lastSeen = now.minus(Duration.ofDays(40))),
            ),
            online = listOf(
                Presence("KASSA1", "ph_a", now.minusSeconds(20), monitorCount = 2, appVersion = "1.0.90",
                    staffSourceStatus = "resolved"),
            ),
        )

        val byDevice = fleet.registers.associateBy { it.deviceId }
        assertEquals(FleetRegisterState.online, byDevice.getValue("KASSA1").state)
        assertEquals("1.0.90", byDevice.getValue("KASSA1").appVersion)
        assertEquals(true, byDevice.getValue("KASSA1").hasClientScreen)
        assertEquals(now.minusSeconds(20), byDevice.getValue("KASSA1").lastSeen)
        assertEquals(FleetRegisterState.offline, byDevice.getValue("KASSA2").state)
        assertNull(byDevice.getValue("KASSA2").appVersion)
        assertNull(byDevice.getValue("KASSA2").hasClientScreen)
        assertEquals(FleetRegisterState.stale, byDevice.getValue("OLD-PC").state)
        assertEquals(1, fleet.summary.online)
        assertEquals(1, fleet.summary.offline)
        assertEquals(1, fleet.summary.stale)
        assertEquals(1, fleet.summary.clientScreens)
        assertEquals(7, fleet.staleAfterDays)
    }

    @Test
    fun `heartbeat in another letter case still matches the registered till once`() {
        val fleet = assemble(
            registered = listOf(device("Kassa1", "ph_a", lastSeen = now.minus(Duration.ofDays(2)))),
            online = listOf(Presence("KASSA1", "ph_a", now.minusSeconds(10), monitorCount = 1)),
        )

        assertEquals(1, fleet.registers.size)
        val row = fleet.registers.single()
        assertEquals("Kassa1", row.deviceId)
        assertEquals(FleetRegisterState.online, row.state)
        assertEquals(false, row.hasClientScreen)
        assertTrue(row.registered)
    }

    @Test
    fun `online till without its own key is listed separately as unregistered`() {
        val fleet = assemble(
            registered = listOf(device("KASSA1", "ph_a")),
            online = listOf(
                Presence("KASSA1", "ph_a", now.minusSeconds(10)),
                Presence("posm", null, now.minusSeconds(15), appVersion = "1.0.46"),
            ),
        )

        val legacy = fleet.registers.single { it.deviceId == "posm" }
        assertFalse(legacy.registered)
        assertNull(legacy.registeredAt)
        assertEquals(FleetRegisterState.online, legacy.state)
        assertEquals(ScreenPlaylistSource.broadcast, legacy.playlistSource)
        assertEquals(2, fleet.summary.online)
        assertEquals(2, fleet.summary.registers)
    }

    @Test
    fun `pharmacy sales, closed pharmacies and pharmacies without tills`() {
        val fleet = assemble(
            registered = listOf(device("KASSA1", "ph_a"), device("KASSA1", "ph_closed")),
            online = listOf(
                Presence("KASSA1", "ph_a", now.minusSeconds(10), staffSourceStatus = "database_unavailable"),
                Presence("KASSA1", "ph_closed", now.minusSeconds(10)),
            ),
            sales = SalesSnapshot(
                byPharmacy = mapOf("ph_a" to PharmacySales(lastSaleAt = now.minus(Duration.ofDays(3)), today = 0, week = 4)),
                // Номер кассы в чеке другим регистром: чеки всё равно её.
                byDevice = mapOf(
                    ScreenFleetAssembler.deviceKey("ph_a", "kassa1") to
                        PharmacySales(lastSaleAt = now.minus(Duration.ofDays(3)), today = 0, week = 3),
                ),
            ),
        )

        val a = fleet.registers.single { it.pharmacyId == "ph_a" }
        assertEquals(0, a.pharmacySalesToday)
        assertEquals(4, a.pharmacySales7d)
        assertEquals(now.minus(Duration.ofDays(3)), a.pharmacyLastSaleAt)
        assertEquals(3, a.sales7d)
        assertEquals(0, a.salesToday)
        assertEquals(now.minus(Duration.ofDays(3)), a.lastSaleAt)
        val closed = fleet.registers.single { it.pharmacyId == "ph_closed" }
        assertEquals(0, closed.sales7d)
        assertNull(closed.lastSaleAt)
        assertEquals("Абая 1", a.address)
        assertEquals(true, a.pharmacyActive)
        assertEquals(false, fleet.registers.single { it.pharmacyId == "ph_closed" }.pharmacyActive)
        assertEquals(1, fleet.summary.standardNUnavailable)
        // ph_a и закрытая аптека: на связи, чеков сегодня нет.
        assertEquals(2, fleet.summary.onlinePharmaciesWithoutSalesToday)
        assertEquals(listOf("ph_gap"), fleet.pharmaciesWithoutRegisters.map { it.pharmacyId })
        assertEquals(2, fleet.summary.activePharmacies)
        assertEquals(1, fleet.summary.pharmaciesWithRegisters)
        assertTrue(fleet.salesAvailable)
    }

    @Test
    fun `unreadable sales are reported as unavailable, not as zero sales`() {
        val fleet = assemble(
            registered = listOf(device("KASSA1", "ph_a")),
            online = listOf(Presence("KASSA1", "ph_a", now.minusSeconds(10))),
            sales = null,
        )

        assertFalse(fleet.salesAvailable)
        assertEquals(0, fleet.summary.onlinePharmaciesWithoutSalesToday)
    }

    @Test
    fun `each till shows its resolved playlist and the playlist lists its slots`() {
        val fleet = assemble(
            registered = listOf(device("KASSA1", "ph_a"), device("KASSA1", "ph_snrg")),
            online = emptyList(),
        )

        assertEquals("pl_broadcast", fleet.registers.single { it.pharmacyId == "ph_a" }.playlistId)
        val snrg = fleet.registers.single { it.pharmacyId == "ph_snrg" }
        assertEquals("pl_snrg", snrg.playlistId)
        assertEquals(ScreenPlaylistSource.pharmacy, snrg.playlistSource)
        val broadcast = fleet.playlists.single { it.id == "pl_broadcast" }
        assertEquals(35, broadcast.durationSec)
        assertEquals(listOf(0, 1), broadcast.slides.map { it.position })
    }

    private fun assemble(
        registered: List<RegisteredDevice>,
        online: List<Presence>,
        sales: SalesSnapshot? = SalesSnapshot(emptyMap(), emptyMap()),
    ) = ScreenFleetAssembler.assemble(
        now = now,
        registered = registered,
        online = online,
        activePharmacies = listOf(pharmacy("ph_a", "Аптека А", "Абая 1"), pharmacy("ph_gap", "Без кассы", "Сатпаева 2")),
        pharmacies = listOf(
            pharmacy("ph_a", "Аптека А", "Абая 1"),
            pharmacy("ph_gap", "Без кассы", "Сатпаева 2"),
            pharmacy("ph_snrg", "SNRG", "Титова 33б"),
            pharmacy("ph_closed", "Закрытая", "Райымбека 5", active = false),
        ).associateBy { it.id },
        sales = sales,
        resolution = ScreenPlaylistResolution(
            byPharmacy = mapOf(
                "ph_a" to ResolvedScreenPlaylist("pl_broadcast", ScreenPlaylistSource.broadcast),
                "ph_snrg" to ResolvedScreenPlaylist("pl_snrg", ScreenPlaylistSource.pharmacy),
                "ph_closed" to ResolvedScreenPlaylist("pl_broadcast", ScreenPlaylistSource.broadcast),
                null to ResolvedScreenPlaylist("pl_broadcast", ScreenPlaylistSource.broadcast),
            ),
            playlists = mapOf(
                "pl_broadcast" to ActivePlaylistDto("pl_broadcast", "Эфир касс", listOf(slide(0, 20), slide(1, 15))),
                "pl_snrg" to ActivePlaylistDto("pl_snrg", "SNRG", listOf(slide(0, 30))),
            ),
        ),
    )

    private fun device(deviceId: String, pharmacyId: String, lastSeen: Instant = now.minus(Duration.ofDays(1))) =
        RegisteredDevice(deviceId, pharmacyId, registeredAt = now.minus(Duration.ofDays(60)), lastSeenAt = lastSeen)

    private fun pharmacy(id: String, name: String, addr: String, active: Boolean = true) = PharmacyEntity(
        id = id,
        name = name,
        chainId = "ch",
        chainName = "Аптека со склада",
        city = "Алматы",
        addr = addr,
        active = active,
    )

    private fun slide(position: Int, durationSec: Int) = ActiveSlideDto(
        id = "sl_$position",
        url = "https://example.test/$position.mp4",
        kind = SlideKind.video,
        durationSec = durationSec,
        title = "Ролик ${position + 1}",
        position = position,
    )
}
