package kz.epharm.appupdate.service

import kz.epharm.appupdate.dto.AppReleaseDto
import kz.epharm.appupdate.dto.AppVersionDto
import kz.epharm.appupdate.dto.RegisterReleaseRequest
import kz.epharm.appupdate.entity.AppReleaseEntity
import kz.epharm.appupdate.repository.AppReleaseRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.jdbc.core.JdbcTemplate
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class AppReleaseService(
    private val appReleaseRepository: AppReleaseRepository,
    private val manifestVerifier: AppUpdateManifestVerifier,
    private val jdbc: JdbcTemplate,
    @Value("\${app.posm.update-pilot-devices:}") pilotDevices: String = "",
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
        // Serializes publishers even when there is no existing release row to lock yet.
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 718096))", platform)
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
                version = req.version.trim(),
                url = req.url.trim(),
                sha256 = req.sha256.trim(),
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

    private companion object {
        const val STABLE = "stable"
        const val PILOT = "pilot"
        val CHANNELS = setOf(STABLE, PILOT)
    }
}
