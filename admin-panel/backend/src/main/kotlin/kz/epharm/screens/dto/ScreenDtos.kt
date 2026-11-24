package kz.epharm.screens.dto

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import kz.epharm.screens.entity.PlaylistEntity
import kz.epharm.screens.entity.PlaylistStatus
import kz.epharm.screens.entity.SlideEntity
import kz.epharm.screens.entity.SlideKind
import java.time.Instant

/** Создание плейлиста. Слайды добавляются отдельно (assign). */
data class CreatePlaylistRequest(
    @field:NotBlank @field:Size(max = 255)
    val name: String,
    val status: PlaylistStatus? = PlaylistStatus.draft,
)

data class UpdatePlaylistRequest(
    @field:Size(max = 255)
    val name: String? = null,
    val status: PlaylistStatus? = null,
    // Назначение (V016). Трёхзначная семантика через флаг setTarget, т.к. null=«не менять»
    // конфликтует с null=«все экраны». setTarget=true → применить targetPharmacyId
    // (в т.ч. null = сделать глобальным). setTarget=false/отсутствует → назначение не трогаем.
    val setTarget: Boolean? = null,
    @field:Size(max = 64)
    val targetPharmacyId: String? = null,
)

/**
 * Привязка слайда к плейлисту (или открепление при playlistId=null).
 * position — порядок в ротации.
 */
data class AssignSlideRequest(
    val playlistId: String? = null,
    @field:Min(0)
    val position: Int = 0,
)

data class PlaylistDto(
    val id: String,
    val name: String,
    val status: PlaylistStatus,
    val slidesCount: Int,
    val durationSec: Int,
    val pharmacies: Int,
    // V016 — назначение: null = все экраны (глобальный), иначе id конкретной аптеки.
    val pharmacyId: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun of(e: PlaylistEntity): PlaylistDto = PlaylistDto(
            id = e.id,
            name = e.name,
            status = e.status,
            slidesCount = e.slidesCount,
            durationSec = e.durationSec,
            pharmacies = e.pharmacies,
            pharmacyId = e.pharmacyId,
            createdAt = e.createdAt,
            updatedAt = e.updatedAt,
        )
    }
}

/**
 * Активный плейлист для 2-го монитора кассы (Stage 3). POSM-клиент получает упорядоченный
 * список media-URL (MinIO) и крутит их в цикле вместо захардкоженного promo.mp4.
 */
data class ActivePlaylistDto(
    val playlistId: String?,
    val name: String,
    val slides: List<ActiveSlideDto>,
)

data class ActiveSlideDto(
    val id: String,
    val url: String,
    val kind: SlideKind,
    val durationSec: Int,
    val title: String,
    /** Нулевая позиция в плейлисте. В админке позиции 0..11 отображаются как слоты 1..12. */
    val position: Int,
    /** Только для admin UI: true, если слот унаследован из основного плейлиста. */
    val inherited: Boolean = false,
)

data class BroadcastProfileSummaryDto(
    val id: String,
    val name: String,
    val defaultProfile: Boolean,
    val assignedPharmacies: Int,
)

data class BroadcastProfileDto(
    val id: String,
    val name: String,
    val defaultProfile: Boolean,
    val assignedPharmacyIds: List<String>,
    /** Эффективные слоты: собственные overrides + наследуемые позиции основного профиля. */
    val slides: List<ActiveSlideDto>,
)

data class SetBroadcastProfilePharmaciesRequest(
    @field:Size(max = 1_000)
    val pharmacyIds: List<String>,
)

/** Подключённые кассы (T4): сколько устройств онлайн + детали (для админ-виджета на экранах). */
data class ConnectedRegistersDto(
    val total: Int,
    val devices: List<RegisterPresenceDto>,
)

/**
 * Лёгкий live-срез для счётчика в админке. В отличие от ConnectedRegistersDto не требует
 * обращения к PostgreSQL и не передаёт сотни карточек устройств каждые 30 секунд.
 */
data class ConnectedRegistersSummaryDto(
    val total: Int,
    val observedAt: Instant,
)

/**
 * Операционный охват POSM. Кампании целятся во все активные аптеки, но показ
 * рекомендации технически возможен только там, где зарегистрирована POSM-касса.
 * Этот DTO не смешивает целевой охват с live-heartbeat и даёт операциям точный gap-list.
 */
data class PosmCoverageDto(
    val activePharmacies: Int,
    val provisionedPharmacies: Int,
    val onlinePharmacies: Int,
    val onlineRegisters: Int,
    val unprovisionedPharmacies: List<PosmCoverageGapDto>,
    val observedAt: Instant,
)

data class PosmCoverageGapDto(
    val pharmacyId: String,
    val pharmacyName: String,
    val city: String,
    val address: String,
)

data class RegisterPresenceDto(
    val deviceId: String,
    val pharmacyId: String?,
    // Название и адрес аптеки, резолвятся из справочника по pharmacyId (null, если аптека не
    // найдена/не задана) — чтобы в админ-виджете читалась «Аспект-траст», а не «sloc_…».
    val pharmacyName: String?,
    val pharmacyAddress: String?,
    val pharmacyCity: String?,
    val pharmacyStreetAddress: String?,
    /** null означает, что установленный POSM ещё не прислал топологию мониторов. */
    val monitorCount: Int?,
    val hasClientScreen: Boolean?,
    /** Версия установленного POSM; null у клиентов старого формата heartbeat. */
    val appVersion: String?,
    val lastSeen: Instant,
    /** Self-reported operational state, not an authorization or financial identity. */
    val staffAuthStatus: String? = null,
    val staffSourceStatus: String? = null,
)

data class SlideDto(
    val id: String,
    val title: String,
    val kind: SlideKind,
    val durationSec: Int,
    val mediaUrl: String,
    val playlistId: String?,
    val position: Int,
) {
    companion object {
        fun of(e: SlideEntity): SlideDto = SlideDto(
            id = e.id,
            title = e.title,
            kind = e.kind,
            durationSec = e.durationSec,
            mediaUrl = e.mediaUrl,
            playlistId = e.playlistId,
            position = e.position,
        )
    }
}

/** Откуда касса берёт эфир экрана покупателя, в порядке приоритета выбора. */
enum class ScreenPlaylistSource { pharmacy, profile, broadcast, legacy_global, none }

data class ResolvedScreenPlaylist(val playlistId: String?, val source: ScreenPlaylistSource)

/** Выбранный эфир каждой аптеки и собранные слоты этих эфиров (свои и унаследованные). */
data class ScreenPlaylistResolution(
    val byPharmacy: Map<String?, ResolvedScreenPlaylist>,
    val playlists: Map<String, ActivePlaylistDto>,
)

/** online: пульс не старше TTL; offline: была на связи за последние дни; stale: давно не выходила. */
enum class FleetRegisterState { online, offline, stale }

/** Страница «Кассы и экраны»: все кассы сети одним снимком. */
data class ScreenFleetDto(
    val observedAt: Instant,
    /** Через сколько дней без связи касса считается давно не выходившей (stale). */
    val staleAfterDays: Int,
    /** false: продажи прочитать не удалось, нули в строках тогда не значат «продаж нет». */
    val salesAvailable: Boolean,
    val summary: ScreenFleetSummaryDto,
    val registers: List<ScreenFleetRegisterDto>,
    val playlists: List<ScreenFleetPlaylistDto>,
    val pharmaciesWithoutRegisters: List<PosmCoverageGapDto>,
)

data class ScreenFleetSummaryDto(
    val activePharmacies: Int,
    val pharmaciesWithRegisters: Int,
    val registers: Int,
    val online: Int,
    val offline: Int,
    val stale: Int,
    /** Кассы на связи со вторым монитором: там открыт экран покупателя и идут ролики. */
    val clientScreens: Int,
    /** Кассы на связи, которые не открывают базу Стандарт-Н: продажи с них не приходят. */
    val standardNUnavailable: Int,
    /** Аптеки, где касса на связи, а чеков за сегодня (Алматы) нет. */
    val onlinePharmaciesWithoutSalesToday: Int,
)

data class ScreenFleetRegisterDto(
    val deviceId: String,
    val pharmacyId: String?,
    val pharmacyName: String?,
    val chainName: String?,
    val city: String?,
    val address: String?,
    /** false: аптека закрыта в справочнике, а ключ кассы ещё действует. */
    val pharmacyActive: Boolean?,
    /** false: касса на связи без своего ключа в базе (старый общий ключ). */
    val registered: Boolean,
    val registeredAt: Instant?,
    val state: FleetRegisterState,
    val lastSeen: Instant?,
    /** Версия, мониторы и статусы известны только у касс на связи. */
    val appVersion: String?,
    val monitorCount: Int?,
    val hasClientScreen: Boolean?,
    val staffAuthStatus: String?,
    val staffSourceStatus: String?,
    val playlistId: String?,
    val playlistSource: ScreenPlaylistSource,
    /** Чеки этой кассы за сегодня (Алматы) и за 7 дней: касса пишет свой номер в каждый чек. */
    val salesToday: Int,
    val sales7d: Int,
    /** Последний чек этой кассы за 7 дней; null: за неделю чеков с неё не было. */
    val lastSaleAt: Instant?,
    /** Чеки аптеки целиком, со всех её касс: видно, продаёт ли аптека через другую кассу. */
    val pharmacyLastSaleAt: Instant?,
    val pharmacySalesToday: Int,
    val pharmacySales7d: Int,
)

data class ScreenFleetPlaylistDto(
    val id: String,
    val name: String,
    val durationSec: Int,
    val slides: List<ActiveSlideDto>,
)
