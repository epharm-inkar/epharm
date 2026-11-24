package kz.epharm.posm

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kz.epharm.pharmacies.entity.PharmacyEntity
import kz.epharm.pharmacies.repository.PharmacyRepository
import kz.epharm.posm.service.TillNoticeService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Optional

/** Server side of «Состояние ePharm»: the HQ notice and the pharmacy name ride on the heartbeat answer. */
class TillNoticeServiceTest {
    private val pharmacies = mockk<PharmacyRepository>()

    private fun service(id: String = "", ru: String = "", kk: String = "", level: String = "info") =
        TillNoticeService(pharmacies, id, ru, kk, level)

    @Test
    fun `no notice without an id or Russian text`() {
        assertThat(service().notice).isNull()
        assertThat(service(id = "n1").notice).isNull()
        assertThat(service(ru = "Текст").notice).isNull()
        assertThat(service(id = "  ", ru = "Текст").notice).isNull()
    }

    @Test
    fun `notice is trimmed and the level is info unless it says warning`() {
        val notice = service(id = " 2026-11-25-maintenance ", ru = " Идут работы ", kk = " Жұмыс жүріп жатыр ", level = "WARNING").notice!!

        assertThat(notice.id).isEqualTo("2026-11-25-maintenance")
        assertThat(notice.ru).isEqualTo("Идут работы")
        assertThat(notice.kk).isEqualTo("Жұмыс жүріп жатыр")
        assertThat(notice.level).isEqualTo("warning")
        assertThat(service(id = "n2", ru = "x", level = "loud").notice!!.level).isEqualTo("info")
        assertThat(service(id = "n3", ru = "я".repeat(900)).notice!!.ru).hasSize(600)
    }

    @Test
    fun `pharmacy name is read once and cached for ten minutes`() {
        every { pharmacies.findById("sloc_1") } returns Optional.of(PharmacyEntity(id = "sloc_1", name = " Аспект-траст г.Алматы Бузурбаева 23 "))
        val notices = service()

        assertThat(notices.pharmacyName("sloc_1", nowMillis = 0)).isEqualTo("Аспект-траст г.Алматы Бузурбаева 23")
        assertThat(notices.pharmacyName("sloc_1", nowMillis = 9 * 60_000L)).isEqualTo("Аспект-траст г.Алматы Бузурбаева 23")
        verify(exactly = 1) { pharmacies.findById("sloc_1") }

        notices.pharmacyName("sloc_1", nowMillis = 11 * 60_000L)
        verify(exactly = 2) { pharmacies.findById("sloc_1") }
    }

    @Test
    fun `unknown or broken lookups give no name and never fail the heartbeat`() {
        every { pharmacies.findById("missing") } returns Optional.empty()
        every { pharmacies.findById("broken") } throws IllegalStateException("database is down")
        val notices = service()

        assertThat(notices.pharmacyName("missing")).isNull()
        assertThat(notices.pharmacyName("broken")).isNull()
        assertThat(notices.pharmacyName(null)).isNull()
        assertThat(notices.pharmacyName("  ")).isNull()
    }
}
