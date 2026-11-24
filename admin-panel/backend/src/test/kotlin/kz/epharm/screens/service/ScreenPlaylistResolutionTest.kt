package kz.epharm.screens.service

import io.mockk.every
import io.mockk.mockk
import kz.epharm.screens.dto.ResolvedScreenPlaylist
import kz.epharm.screens.dto.ScreenPlaylistSource
import kz.epharm.screens.entity.PlaylistEntity
import kz.epharm.screens.entity.PlaylistPharmacyAssignmentEntity
import kz.epharm.screens.entity.PlaylistStatus
import kz.epharm.screens.entity.SlideEntity
import kz.epharm.screens.entity.SlideKind
import kz.epharm.screens.repository.PlaylistPharmacyAssignmentRepository
import kz.epharm.screens.repository.PlaylistRepository
import kz.epharm.screens.repository.SlideRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Optional

/**
 * Сводка касс выбирает эфир сразу для всех аптек. Выбор обязан совпадать с тем, что касса
 * получает сама (activePlaylistForScreen), иначе страница покажет не то, что крутится.
 */
class ScreenPlaylistResolutionTest {
    private val playlistRepository = mockk<PlaylistRepository>()
    private val slideRepository = mockk<SlideRepository>()
    private val assignmentRepository = mockk<PlaylistPharmacyAssignmentRepository>()
    private val service = ScreenService(
        playlistRepository,
        slideRepository,
        assignmentRepository,
        mockk(relaxed = true),
        mockk(relaxed = true),
    )

    @Test
    fun `batch choice follows the till priority and builds inherited slots`() {
        stub(
            playlists = listOf(
                playlist("pl_direct", pharmacyId = "ph_direct", updatedAt = 30),
                playlist("pl_broadcast_targeted", parentId = "pl_broadcast", updatedAt = 20),
                playlist("pl_broadcast", updatedAt = 10),
                playlist("pl_draft", status = PlaylistStatus.draft, updatedAt = 40),
            ),
            assignments = mapOf(
                "ph_group" to "pl_broadcast_targeted",
                "ph_direct" to "pl_broadcast_targeted",
                "ph_draft" to "pl_draft",
            ),
            slides = listOf(
                slide("s1", "pl_broadcast", 0),
                slide("s2", "pl_broadcast", 1),
                slide("s3", "pl_broadcast_targeted", 1),
                slide("s4", "pl_direct", 0),
            ),
        )
        val ids = listOf("ph_direct", "ph_group", "ph_draft", "ph_plain", " ph_group ", null)

        val result = service.resolveScreenPlaylists(ids)

        assertEquals(ResolvedScreenPlaylist("pl_direct", ScreenPlaylistSource.pharmacy), result.byPharmacy["ph_direct"])
        assertEquals(ResolvedScreenPlaylist("pl_broadcast_targeted", ScreenPlaylistSource.profile), result.byPharmacy["ph_group"])
        assertEquals(ResolvedScreenPlaylist("pl_broadcast_targeted", ScreenPlaylistSource.profile), result.byPharmacy[" ph_group "])
        assertEquals(ResolvedScreenPlaylist("pl_broadcast", ScreenPlaylistSource.broadcast), result.byPharmacy["ph_draft"])
        assertEquals(ResolvedScreenPlaylist("pl_broadcast", ScreenPlaylistSource.broadcast), result.byPharmacy["ph_plain"])
        assertEquals(ResolvedScreenPlaylist("pl_broadcast", ScreenPlaylistSource.broadcast), result.byPharmacy[null])
        assertEquals(
            listOf("s1" to true, "s3" to false),
            result.playlists.getValue("pl_broadcast_targeted").slides.map { it.id to it.inherited },
        )
        assertSameAsSingleTill(ids)
    }

    @Test
    fun `legacy global playlist is used only while the broadcast is not active`() {
        stub(
            playlists = listOf(
                playlist("pl_broadcast", status = PlaylistStatus.draft, updatedAt = 30),
                playlist("pl_old_global", updatedAt = 20),
                playlist("pl_older_global", updatedAt = 10),
            ),
            assignments = emptyMap(),
            slides = listOf(slide("s1", "pl_old_global", 0)),
        )

        val result = service.resolveScreenPlaylists(listOf("ph_any"))

        assertEquals(ResolvedScreenPlaylist("pl_old_global", ScreenPlaylistSource.legacy_global), result.byPharmacy["ph_any"])
        assertSameAsSingleTill(listOf("ph_any"))
    }

    @Test
    fun `nothing active means no playlist`() {
        stub(playlists = listOf(playlist("pl_broadcast", status = PlaylistStatus.archived)), assignments = emptyMap(), slides = emptyList())

        val result = service.resolveScreenPlaylists(listOf("ph_any"))

        assertEquals(ResolvedScreenPlaylist(null, ScreenPlaylistSource.none), result.byPharmacy["ph_any"])
        assertEquals(emptyMap<String, Any>(), result.playlists)
        assertSameAsSingleTill(listOf("ph_any"))
    }

    private fun assertSameAsSingleTill(ids: List<String?>) {
        val batch = service.resolveScreenPlaylists(ids)
        ids.forEach { id ->
            val single = service.activePlaylistForScreen(id)
            val chosen = batch.byPharmacy.getValue(id).playlistId
            assertEquals(single.playlistId, chosen, "playlist for $id")
            assertEquals(
                single.slides.map { it.id to it.inherited },
                chosen?.let { batch.playlists.getValue(it).slides.map { slide -> slide.id to slide.inherited } }.orEmpty(),
                "slides for $id",
            )
        }
    }

    /** Те же данные для пакетного выбора и для запросов одной кассы, как их отдала бы база. */
    private fun stub(playlists: List<PlaylistEntity>, assignments: Map<String, String>, slides: List<SlideEntity>) {
        val ordered = playlists.sortedByDescending { it.updatedAt }
        val active = PlaylistStatus.active.name
        every { playlistRepository.findAllByOrderByUpdatedAtDesc() } returns ordered
        every { playlistRepository.findById(any()) } answers {
            Optional.ofNullable(ordered.firstOrNull { it.id == firstArg<String>() })
        }
        every { playlistRepository.findFirstByStatusRawAndPharmacyIdOrderByUpdatedAtDesc(any(), any()) } answers {
            ordered.firstOrNull { it.statusRaw == firstArg<String>() && it.pharmacyId == secondArg<String>() }
        }
        every { playlistRepository.findFirstByStatusRawAndPharmacyIdIsNullAndParentPlaylistIdIsNullOrderByUpdatedAtDesc(active) } returns
            ordered.firstOrNull { it.statusRaw == active && it.pharmacyId == null && it.parentPlaylistId == null }
        val assignmentEntities = assignments.map { (pharmacyId, playlistId) ->
            PlaylistPharmacyAssignmentEntity(pharmacyId = pharmacyId, playlistId = playlistId)
        }
        every { assignmentRepository.findAll() } returns assignmentEntities
        every { assignmentRepository.findByPharmacyId(any()) } answers {
            assignmentEntities.firstOrNull { it.pharmacyId == firstArg<String>() }
        }
        every { slideRepository.findAll() } returns slides
        every { slideRepository.findAllByPlaylistIdOrderByPositionAsc(any()) } answers {
            slides.filter { it.playlistId == firstArg<String>() }.sortedBy { it.position }
        }
    }

    private fun playlist(
        id: String,
        status: PlaylistStatus = PlaylistStatus.active,
        pharmacyId: String? = null,
        parentId: String? = null,
        updatedAt: Long = 0,
    ) = PlaylistEntity(
        id = id,
        name = id,
        pharmacyId = pharmacyId,
        parentPlaylistId = parentId,
        updatedAt = Instant.ofEpochSecond(updatedAt),
    ).also { it.status = status }

    private fun slide(id: String, playlistId: String, position: Int) = SlideEntity(
        id = id,
        title = id,
        durationSec = 10,
        mediaUrl = "s3://screens/$id.mp4",
        playlistId = playlistId,
        position = position,
    ).also { it.kind = SlideKind.video }
}
