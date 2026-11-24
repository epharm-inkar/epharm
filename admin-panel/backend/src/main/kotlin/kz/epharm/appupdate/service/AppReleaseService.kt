package kz.epharm.appupdate.service

import kz.epharm.appupdate.dto.AppReleaseDto
import kz.epharm.appupdate.dto.AppVersionDto
import kz.epharm.appupdate.dto.RegisterReleaseRequest
import kz.epharm.appupdate.entity.AppReleaseEntity
import kz.epharm.appupdate.repository.AppReleaseRepository
import kz.epharm.posm.service.DevicePresenceService
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.jdbc.core.JdbcTemplate
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Service
class AppReleaseService(
    private val appReleaseRepository: AppReleaseRepository,
    private val manifestVerifier: AppUpdateManifestVerifier,
    private val jdbc: JdbcTemplate,
    @Value("\${app.posm.update-pilot-devices:}") pilotDevices: String = "",
    /** Off only in tests that publish stable builds directly; production always checks the pilot. */
    @Value("\${app.posm.require-pilot-before-stable:true}") private val requirePilotBeforeStable: Boolean = true,
    @Value("\${app.posm.stable-after-pilot-minutes:60}") private val stableAfterPilotMinutes: Long = 60,
    private val devicePresenceService: DevicePresenceService? = null,
) {
    private val pilot = PosmUpdatePilotScope(pilotDevices)

    /** Текущий стабильный релиз для платформы (для кассы). Нет — AppVersionDto.none(). */
    @Transactional(readOnly = true)
    fun currentFor(platform: String): AppVersionDto =
        current(platform, STABLE)?.let(AppVersionDto::of) ?: AppVersionDto.none()

    /**
     * Релиз для конкретной кассы. Касса из пилотного списка получает пилотный релиз, если он новее
     * стабильного; иначе и всем остальным кассам отдаётся стабильный. Старый пилот после перевода
     * версии на всех никого не откатывает: выигрывает более новая версия.
     */
    @Transactional(readOnly = true)
    fun currentFor(platform: String, pharmacyId: String?, deviceId: String?, legacy: Boolean): AppVersionDto {
        val stable = current(platform, STABLE)
        if (!pilot.isEmpty && pilot.allows(pharmacyId, deviceId, legacy)) {
            val candidate = current(platform, PILOT)
            if (candidate != null && (stable == null || ReleaseVersions.compare(candidate.version, stable.version) > 0)) {
                return AppVersionDto.of(candidate)
            }
        }
        return stable?.let(AppVersionDto::of) ?: AppVersionDto.none()
    }

    @Transactional(readOnly = true)
    fun list(): List<AppReleaseDto> =
        appReleaseRepository.findAllByOrderByCreatedAtDesc().map(AppReleaseDto::of)

    /**
     * Регистрирует релиз и делает его текущим в своём канале: снимает is_current со всех релизов этой
     * платформы и канала, ставит новому. Так на платформе всегда ровно один текущий стабильный и не
     * больше одного текущего пилотного.
     */
    @Transactional
    fun register(req: RegisterReleaseRequest): AppReleaseDto {
        manifestVerifier.validate(req)
        val platform = req.platform.trim()
        if (platform.isEmpty()) throw AppException(ErrorCode.VALIDATION_FAILED, "Укажите платформу релиза")
        val channel = req.channel.trim().ifEmpty { STABLE }
        if (channel !in CHANNELS) throw AppException(ErrorCode.VALIDATION_FAILED, "Канал релиза: stable или pilot")
        val version = req.version.trim()
        val sha256 = req.sha256.trim()
        // Serializes publishers even when there is no existing release row to lock yet.
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 718096))", platform)
        if (channel == STABLE && requirePilotBeforeStable) {
            StableReleaseGate.refusal(stableEvidence(platform, version, sha256), Instant.now(), stableAfterPilotMinutes, version)
                ?.let { throw AppException(ErrorCode.CONFLICT, it, HttpStatus.CONFLICT) }
        }
        appReleaseRepository.findAllByPlatformAndChannelAndIsCurrentTrue(platform, channel).forEach {
            it.isCurrent = false
            appReleaseRepository.save(it)
        }
        // Hibernate can insert before flushing updates; release the unique current slot first.
        appReleaseRepository.flush()
        val entity = appReleaseRepository.save(
            AppReleaseEntity(
                id = "rel_${UUID.randomUUID().toString().substring(0, 8)}",
                platform = platform,
                version = version,
                url = req.url.trim(),
                sha256 = sha256,
                manifestSignature = req.manifestSignature.trim(),
                mandatory = req.mandatory,
                notes = req.notes.trim(),
                isCurrent = true,
                channel = channel,
            ),
        )
        return AppReleaseDto.of(entity)
    }

    private fun current(platform: String, channel: String): AppReleaseEntity? =
        appReleaseRepository.findFirstByPlatformAndChannelAndIsCurrentTrue(platform, channel)

    /** What the pilot of this exact build proved so far (see [StableReleaseGate]). */
    private fun stableEvidence(platform: String, version: String, sha256: String): StableReleaseGate.Evidence {
        val sameBuild = appReleaseRepository.findAllByPlatformAndVersionAndSha256(platform, version, sha256)
        val pilotAt = sameBuild.filter { it.channel == PILOT }.minOfOrNull { it.createdAt }
        val onVersion = if (pilotAt == null) 0 else devicePresenceService?.connected().orEmpty().count { till ->
            pilot.allows(till.pharmacyId, till.deviceId, false) &&
                till.appVersion?.let { ReleaseVersions.compare(it, version) == 0 } == true
        }
        val withReceipts = if (pilotAt == null) 0 else pilotTillsWithReceipts(maxOf(pilotAt, Instant.now().minus(RECEIPT_LOOKBACK)))
        return StableReleaseGate.Evidence(
            previouslyStable = sameBuild.any { it.channel == STABLE },
            pilotRegisteredAt = pilotAt,
            pilotTillsOnVersion = onVersion,
            pilotTillsWithReceipts = withReceipts,
        )
    }

    /** Pilot tills that sent a receipt since [since]; uses the printed_at index, at most one day of rows. */
    private fun pilotTillsWithReceipts(since: Instant): Int = jdbc.query(
        "SELECT DISTINCT pharmacy_id, device_id FROM pos_sales WHERE printed_at >= ? AND device_id IS NOT NULL",
        { rs, _ -> rs.getString("pharmacy_id") to rs.getString("device_id") },
        Timestamp.from(since),
    ).count { (pharmacyId, deviceId) -> pilot.allows(pharmacyId, deviceId, false) }

    private companion object {
        const val STABLE = "stable"
        const val PILOT = "pilot"
        val CHANNELS = setOf(STABLE, PILOT)
        val RECEIPT_LOOKBACK: Duration = Duration.ofDays(1)
    }
}
