package kz.epharm.screens.service

import kz.epharm.pharmacies.entity.PharmacyEntity
import kz.epharm.pharmacies.repository.PharmacyRepository
import kz.epharm.posm.service.DevicePresenceService
import kz.epharm.screens.dto.FleetRegisterState
import kz.epharm.screens.dto.PosmCoverageGapDto
import kz.epharm.screens.dto.ScreenFleetDto
import kz.epharm.screens.dto.ScreenFleetPlaylistDto
import kz.epharm.screens.dto.ScreenFleetRegisterDto
import kz.epharm.screens.dto.ScreenFleetSummaryDto
import kz.epharm.screens.dto.ScreenPlaylistResolution
import kz.epharm.screens.dto.ScreenPlaylistSource
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.stereotype.Service
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Страница «Кассы и экраны»: все кассы сети одним снимком. Сводит ключи касс из базы,
 * live-присутствие (Redis и база), эфир экрана покупателя и продажи аптеки за сегодня и неделю.
 * Число запросов постоянное, сколько бы ни было касс: ключи, аптеки, два запроса продаж, три эфира.
 */
@Service
class ScreenFleetService(
    private val devicePresenceService: DevicePresenceService,
    private val pharmacyRepository: PharmacyRepository,
    private val screenService: ScreenService,
    private val jdbc: JdbcTemplate,
) {
    private val log = LoggerFactory.getLogger(ScreenFleetService::class.java)

    fun fleet(now: Instant = Instant.now()): ScreenFleetDto {
        val registered = registeredDevices()
        val online = devicePresenceService.connected(now)
        val activePharmacies = pharmacyRepository.findAllByActiveTrueOrderByNameAsc()
        val activeIds = activePharmacies.mapTo(hashSetOf()) { it.id }
        val otherIds = (registered.map { it.pharmacyId } + online.mapNotNull { it.pharmacyId })
            .filterNot { it in activeIds }
            .distinct()
        val pharmacies = (activePharmacies + if (otherIds.isEmpty()) emptyList() else pharmacyRepository.findAllById(otherIds))
            .associateBy { it.id }
        val sales = salesSnapshot(now)
        val resolution = screenService.resolveScreenPlaylists(
            (registered.map { it.pharmacyId } + online.map { it.pharmacyId }).distinct(),
        )
        return ScreenFleetAssembler.assemble(
            now = now,
            registered = registered,
            online = online,
            activePharmacies = activePharmacies,
            pharmacies = pharmacies,
            sales = sales,
            resolution = resolution,
        )
    }

    private fun registeredDevices(): List<RegisteredDevice> = jdbc.query(
        """
        SELECT device_id, pharmacy_id, registered_at, last_seen_at
        FROM fulfillment_devices
        WHERE active = true
        """.trimIndent(),
    ) { rs, _ ->
        RegisteredDevice(
            deviceId = rs.getString("device_id"),
            pharmacyId = rs.getString("pharmacy_id"),
            registeredAt = rs.getTimestamp("registered_at").toInstant(),
            lastSeenAt = rs.getTimestamp("last_seen_at").toInstant(),
        )
    }

    /**
     * Чеки аптек и касс. Последний чек аптеки ищется по индексу (pharmacy_id, printed_at) одним шагом
     * на аптеку; счётчики и чеки касс берут только последнюю неделю (около 20 тысяч строк).
     * null: продажи прочитать не удалось.
     */
    private fun salesSnapshot(now: Instant): SalesSnapshot? = try {
        val startOfToday = LocalDate.ofInstant(now, ALMATY).atStartOfDay(ALMATY).toInstant()
        val weekAgo = now.minus(SALES_WINDOW)
        val lastSale = HashMap<String, Instant>()
        jdbc.query(
            """
            SELECT p.id AS pharmacy_id,
                   (SELECT max(s.printed_at) FROM pos_sales s WHERE s.pharmacy_id = p.id) AS last_sale_at
            FROM pharmacies p
            WHERE p.active = true
               OR EXISTS (SELECT 1 FROM fulfillment_devices d WHERE d.pharmacy_id = p.id AND d.active = true)
            """.trimIndent(),
            RowCallbackHandler { rs ->
                rs.getTimestamp("last_sale_at")?.let { lastSale[rs.getString("pharmacy_id")] = it.toInstant() }
            },
        )
        val counts = HashMap<String, Pair<Int, Int>>()
        val byDevice = HashMap<String, PharmacySales>()
        jdbc.query(
            """
            SELECT pharmacy_id, device_id,
                   max(printed_at) AS last_sale_at,
                   count(*) FILTER (WHERE printed_at >= ?) AS today,
                   count(*) AS week
            FROM pos_sales
            WHERE printed_at >= ? AND device_id IS NOT NULL
            GROUP BY pharmacy_id, device_id
            """.trimIndent(),
            RowCallbackHandler { rs ->
                val key = ScreenFleetAssembler.deviceKey(rs.getString("pharmacy_id"), rs.getString("device_id"))
                val previous = byDevice[key]
                val current = PharmacySales(
                    lastSaleAt = rs.getTimestamp("last_sale_at")?.toInstant(),
                    today = rs.getInt("today"),
                    week = rs.getInt("week"),
                )
                // Одна касса под разным регистром имени складывается в одну строку.
                byDevice[key] = previous?.let {
                    PharmacySales(
                        lastSaleAt = listOfNotNull(it.lastSaleAt, current.lastSaleAt).maxOrNull(),
                        today = it.today + current.today,
                        week = it.week + current.week,
                    )
                } ?: current
            },
            Timestamp.from(startOfToday),
            Timestamp.from(weekAgo),
        )
        jdbc.query(
            """
            SELECT pharmacy_id,
                   count(*) FILTER (WHERE printed_at >= ?) AS today,
                   count(*) AS week
            FROM pos_sales
            WHERE printed_at >= ?
            GROUP BY pharmacy_id
            """.trimIndent(),
            RowCallbackHandler { rs -> counts[rs.getString("pharmacy_id")] = rs.getInt("today") to rs.getInt("week") },
            Timestamp.from(startOfToday),
            Timestamp.from(weekAgo),
        )
        SalesSnapshot(
            byPharmacy = (lastSale.keys + counts.keys).associateWith { id ->
                PharmacySales(
                    lastSaleAt = lastSale[id],
                    today = counts[id]?.first ?: 0,
                    week = counts[id]?.second ?: 0,
                )
            },
            byDevice = byDevice,
        )
    } catch (ex: Exception) {
        // Страница касс полезна и без продаж: показываем кассы, а продажи помечаем недоступными.
        log.warn("Fleet page: pharmacy sales are unavailable: {}", ex.message)
        null
    }

    private companion object {
        val ALMATY: ZoneId = ZoneId.of("Asia/Almaty")
        val SALES_WINDOW: Duration = Duration.ofDays(7)
    }
}

internal data class RegisteredDevice(
    val deviceId: String,
    val pharmacyId: String,
    val registeredAt: Instant,
    val lastSeenAt: Instant,
)

internal data class PharmacySales(val lastSaleAt: Instant?, val today: Int, val week: Int)

/** Чеки по аптекам и по кассам; ключ кассы из [ScreenFleetAssembler.deviceKey]. */
internal data class SalesSnapshot(
    val byPharmacy: Map<String, PharmacySales>,
    val byDevice: Map<String, PharmacySales>,
)

/** Сборка снимка без базы и Redis, чтобы правила статусов проверялись обычными unit-тестами. */
internal object ScreenFleetAssembler {
    const val STALE_AFTER_DAYS = 7
    private const val DATABASE_UNAVAILABLE = "database_unavailable"
    private const val KEY_SEPARATOR = "\u001F"

    fun assemble(
        now: Instant,
        registered: List<RegisteredDevice>,
        online: List<DevicePresenceService.Presence>,
        activePharmacies: List<PharmacyEntity>,
        pharmacies: Map<String, PharmacyEntity>,
        sales: SalesSnapshot?,
        resolution: ScreenPlaylistResolution,
    ): ScreenFleetDto {
        val staleBefore = now.minus(Duration.ofDays(STALE_AFTER_DAYS.toLong()))
        // Имя компьютера могут прислать в другом регистре (KASSA1 и kassa1): сначала точное
        // совпадение ключа, потом без учёта регистра; каждый пульс достаётся одной строке.
        val presenceByKey = online.groupBy { key(it.pharmacyId, it.deviceId).lowercase() }
        val used = HashSet<DevicePresenceService.Presence>()
        fun takePresence(pharmacyId: String, deviceId: String): DevicePresenceService.Presence? {
            val exactKey = key(pharmacyId, deviceId)
            val candidates = presenceByKey[exactKey.lowercase()].orEmpty().filterNot { it in used }
            val match = candidates.firstOrNull { key(it.pharmacyId, it.deviceId) == exactKey } ?: candidates.firstOrNull()
            match?.let(used::add)
            return match
        }

        fun row(
            deviceId: String,
            pharmacyId: String?,
            device: RegisteredDevice?,
            presence: DevicePresenceService.Presence?,
        ): ScreenFleetRegisterDto {
            val pharmacy = pharmacyId?.let(pharmacies::get)
            val lastSeen = listOfNotNull(presence?.lastSeen, device?.lastSeenAt).maxOrNull()
            val state = when {
                presence != null -> FleetRegisterState.online
                lastSeen != null && !lastSeen.isBefore(staleBefore) -> FleetRegisterState.offline
                else -> FleetRegisterState.stale
            }
            val playlist = resolution.byPharmacy[pharmacyId]
            val pharmacySales = pharmacyId?.let { sales?.byPharmacy?.get(it) }
            val deviceSales = sales?.byDevice?.get(deviceKey(pharmacyId, deviceId))
            return ScreenFleetRegisterDto(
                deviceId = deviceId,
                pharmacyId = pharmacyId,
                pharmacyName = pharmacy?.name?.takeIf(String::isNotBlank),
                chainName = pharmacy?.chainName?.takeIf(String::isNotBlank),
                city = pharmacy?.city?.takeIf(String::isNotBlank),
                address = pharmacy?.addr?.takeIf(String::isNotBlank),
                pharmacyActive = pharmacy?.active,
                registered = device != null,
                registeredAt = device?.registeredAt,
                state = state,
                lastSeen = lastSeen,
                appVersion = presence?.appVersion,
                monitorCount = presence?.monitorCount,
                hasClientScreen = presence?.monitorCount?.let { it >= 2 },
                staffAuthStatus = presence?.staffAuthStatus,
                staffSourceStatus = presence?.staffSourceStatus,
                playlistId = playlist?.playlistId,
                playlistSource = playlist?.source ?: ScreenPlaylistSource.none,
                salesToday = deviceSales?.today ?: 0,
                sales7d = deviceSales?.week ?: 0,
                lastSaleAt = deviceSales?.lastSaleAt,
                pharmacyLastSaleAt = pharmacySales?.lastSaleAt,
                pharmacySalesToday = pharmacySales?.today ?: 0,
                pharmacySales7d = pharmacySales?.week ?: 0,
            )
        }

        val registeredRows = registered.map { device ->
            row(device.deviceId, device.pharmacyId, device, takePresence(device.pharmacyId, device.deviceId))
        }
        val unregisteredRows = online.filterNot { it in used }.map { presence ->
            row(presence.deviceId, presence.pharmacyId, device = null, presence = presence)
        }
        val rows = (registeredRows + unregisteredRows).sortedWith(
            compareBy<ScreenFleetRegisterDto>(
                { it.city.orEmpty() },
                { it.address.orEmpty() },
                { it.pharmacyName.orEmpty() },
                { it.deviceId },
            ),
        )

        val registeredPharmacyIds = registered.mapTo(hashSetOf()) { it.pharmacyId }
        val gaps = activePharmacies
            .filterNot { it.id in registeredPharmacyIds }
            .sortedWith(compareBy<PharmacyEntity>({ it.city }, { it.addr }, { it.name }))
            .map { PosmCoverageGapDto(pharmacyId = it.id, pharmacyName = it.name, city = it.city, address = it.addr) }
        val onlineRows = rows.filter { it.state == FleetRegisterState.online }
        val summary = ScreenFleetSummaryDto(
            activePharmacies = activePharmacies.size,
            pharmaciesWithRegisters = activePharmacies.count { it.id in registeredPharmacyIds },
            registers = rows.size,
            online = onlineRows.size,
            offline = rows.count { it.state == FleetRegisterState.offline },
            stale = rows.count { it.state == FleetRegisterState.stale },
            clientScreens = onlineRows.count { it.hasClientScreen == true },
            standardNUnavailable = onlineRows.count { it.staffSourceStatus == DATABASE_UNAVAILABLE },
            onlinePharmaciesWithoutSalesToday = if (sales == null) 0 else onlineRows
                .mapNotNull { it.pharmacyId }
                .distinct()
                .count { (sales.byPharmacy[it]?.today ?: 0) == 0 },
        )
        val playlists = resolution.playlists.values
            .mapNotNull { playlist ->
                playlist.playlistId?.let { id ->
                    ScreenFleetPlaylistDto(
                        id = id,
                        name = playlist.name,
                        durationSec = playlist.slides.sumOf { it.durationSec },
                        slides = playlist.slides,
                    )
                }
            }
            .sortedBy { it.name }

        return ScreenFleetDto(
            observedAt = now,
            staleAfterDays = STALE_AFTER_DAYS,
            salesAvailable = sales != null,
            summary = summary,
            registers = rows,
            playlists = playlists,
            pharmaciesWithoutRegisters = gaps,
        )
    }

    private fun key(pharmacyId: String?, deviceId: String) = "${pharmacyId.orEmpty()}$KEY_SEPARATOR$deviceId"

    /** Ключ кассы для чеков: без учёта регистра, как сопоставляются и пульсы. */
    fun deviceKey(pharmacyId: String?, deviceId: String) = key(pharmacyId, deviceId).lowercase()
}
