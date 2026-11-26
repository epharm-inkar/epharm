package kz.epharm.training

import kz.epharm.training.controller.CertificateVerificationPage
import kz.epharm.training.controller.PublicTrainingController
import kz.epharm.training.domain.CertificateStatus
import kz.epharm.training.domain.TrainingFormat
import kz.epharm.training.dto.CertificateVerificationDto
import kz.epharm.training.service.CertificateWording
import kz.epharm.training.service.CertificateWording.Gender
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class CertificateWordingTest {
    @Test
    fun `verb follows the patronymic, then the surname, and stays neutral when unclear`() {
        val cases = mapOf(
            "Лузгина Валентина Петровна" to Gender.female,
            "Грущак Василий Григорьевич" to Gender.male,
            "Бабич Анна Сергеевна" to Gender.female,
            "Иванов Пётр Кузьмич" to Gender.male,
            "Смирнова Ольга Ильинична" to Gender.female,
            "Сейткали Айгерим Болатқызы" to Gender.female,
            "Сейткали Айгерим Болат кызы" to Gender.female,
            "Асқар Ахметұлы" to Gender.male,
            "Ахметова Айгерим" to Gender.female,
            "Нурланов Ерлан" to Gender.male,
            "Бабич Анна" to Gender.unknown,
            "Айжан Фармацевт" to Gender.unknown,
            "  " to Gender.unknown,
        )
        cases.forEach { (name, gender) -> assertThat(CertificateWording.genderOf(name)).describedAs(name).isEqualTo(gender) }
        assertThat(CertificateWording.completedProgram("Лузгина Валентина Петровна")).isEqualTo("завершила программу обучения")
        assertThat(CertificateWording.completedProgram("Грущак Василий Григорьевич")).isEqualTo("завершил программу обучения")
        assertThat(CertificateWording.completedProgram("Айжан Фармацевт")).isEqualTo("завершил(а) программу обучения")
    }

    @Test
    fun `browsers get the verification page, API clients keep JSON`() {
        assertThat(PublicTrainingController.wantsHtml("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")).isTrue()
        assertThat(PublicTrainingController.wantsHtml("application/json, text/plain, */*")).isFalse()
        assertThat(PublicTrainingController.wantsHtml("*/*")).isFalse()
        assertThat(PublicTrainingController.wantsHtml(null)).isFalse()
    }

    @Test
    fun `verification page shows status, names and no score, with every value escaped`() {
        val token = UUID.fromString("48c242c5-3c9d-4b48-880c-84b3e30d7866")
        val certificate = CertificateVerificationDto(
            number = "EPH-2026-7F8B2E849C",
            pharmacistName = "Лузгина Валентина Петровна <script>",
            programName = "Курс «Пожарная безопасность» & склад",
            format = TrainingFormat.online,
            issuedAt = Instant.parse("2026-11-25T14:04:22Z"),
            expiresAt = null,
            score = 90,
            signerName = "Руководитель учебного центра",
            partnerCompanyName = "Компания-партнёр",
            coverUrl = null,
            epharmLogoUrl = null,
            partnerLogoUrl = null,
            templateName = "modern_ribbon",
            status = CertificateStatus.valid,
            valid = true,
        )

        val html = CertificateVerificationPage.render(token, certificate)

        assertThat(html).contains(
            "Сертификат действителен",
            "Лузгина Валентина Петровна &lt;script&gt;",
            "завершила программу обучения",
            "Курс «Пожарная безопасность» &amp; склад",
            "25.11.2026",
            "бессрочно",
            "/api/public/training/certificates/$token/pdf",
        )
        assertThat(html).doesNotContain("<script>", "90%")
        assertThat(CertificateVerificationPage.render(token, certificate.copy(status = CertificateStatus.revoked, valid = false)))
            .contains("Сертификат отозван")
        assertThat(CertificateVerificationPage.notFound()).contains("Сертификат не найден")
    }
}

class VideoGuardSecondsTest {
    @Test
    fun `measured clip length is used when plausible, planned minutes otherwise`() {
        assertThat(kz.epharm.training.service.videoGuardSeconds(2, 95)).isEqualTo(95)
        assertThat(kz.epharm.training.service.videoGuardSeconds(2, null)).isEqualTo(120)
        assertThat(kz.epharm.training.service.videoGuardSeconds(2, 1)).isEqualTo(120)
        assertThat(kz.epharm.training.service.videoGuardSeconds(2, 10_000)).isEqualTo(120)
        assertThat(kz.epharm.training.service.videoGuardSeconds(0, 50)).isEqualTo(50)
    }

    @Test
    fun `the length the server read from the clip wins over the planned minutes and the browser`() {
        // 26.11.2026: a 6 s clip set as «2 min» could never be finished because 6 s is below half of 120 s.
        assertThat(kz.epharm.training.service.videoGuardSeconds(2, 6, 6)).isEqualTo(6)
        assertThat(kz.epharm.training.service.videoGuardSeconds(2, null, 6)).isEqualTo(6)
        assertThat(kz.epharm.training.service.videoGuardSeconds(2, 6, null)).isEqualTo(120)
        // The browser cannot shorten the clip the server measured, nor claim another length.
        assertThat(kz.epharm.training.service.videoGuardSeconds(2, 1, 95)).isEqualTo(95)
        assertThat(kz.epharm.training.service.videoGuardSeconds(2, 60, 95)).isEqualTo(95)
        // A clip longer than the planned minutes is watched in full too.
        assertThat(kz.epharm.training.service.videoGuardSeconds(1, null, 600)).isEqualTo(600)
        // A length of nothing is no length: the former rule applies.
        assertThat(kz.epharm.training.service.videoGuardSeconds(2, null, 0)).isEqualTo(120)
    }

    @Test
    fun `a short clip counts as watched at once, a long one only as time passes`() {
        val grace = 15L
        // The 6 s clip of the demo course: watched whole, the server accepts 100%.
        assertThat(kz.epharm.training.service.allowedVideoProgress(6, 6, grace)).isEqualTo(100)
        // Without the length it would have asked for the planned two minutes and refused.
        assertThat(kz.epharm.training.service.allowedVideoProgress(6, 120, grace)).isEqualTo(17)
        // A 90 s clip cannot be skipped: nothing but the grace has passed, so only a sixth counts.
        assertThat(kz.epharm.training.service.allowedVideoProgress(0, 90, grace)).isEqualTo(16)
        assertThat(kz.epharm.training.service.allowedVideoProgress(45, 90, grace)).isEqualTo(66)
        assertThat(kz.epharm.training.service.allowedVideoProgress(75, 90, grace)).isEqualTo(100)
        assertThat(kz.epharm.training.service.allowedVideoProgress(-5, 90, grace)).isEqualTo(11)
        assertThat(kz.epharm.training.service.allowedVideoProgress(0, 0, grace)).isEqualTo(100)
    }

    @Test
    fun `a refusal names the time that is missing, and waiting exactly that long is enough`() {
        val grace = 15L
        val cases = listOf(
            Triple(100, 6L, 120L), Triple(95, 30L, 90L), Triple(100, 0L, 90L), Triple(60, 10L, 600L), Triple(100, 0L, 3_600L),
        )
        for ((requested, elapsed, guard) in cases) {
            val wait = kz.epharm.training.service.videoWaitSeconds(requested, elapsed, guard, grace)
            assertThat(wait).describedAs("wait for $requested% of $guard s after $elapsed s").isPositive()
            assertThat(kz.epharm.training.service.allowedVideoProgress(elapsed, guard, grace)).isLessThan(requested)
            assertThat(kz.epharm.training.service.allowedVideoProgress(elapsed + wait, guard, grace)).isGreaterThanOrEqualTo(requested)
            assertThat(kz.epharm.training.service.allowedVideoProgress(elapsed + wait - 1, guard, grace)).isLessThan(requested)
        }
        assertThat(kz.epharm.training.service.videoWaitSeconds(100, 6, 120, grace)).isEqualTo(99)
    }

    @Test
    fun `the message says how long to wait in plain words`() {
        assertThat(kz.epharm.training.service.videoNotCountedMessage(42)).isEqualTo("Видео засчитается через 42 сек. Подождите и повторите")
        assertThat(kz.epharm.training.service.videoNotCountedMessage(1)).isEqualTo("Видео засчитается через 1 сек. Подождите и повторите")
        assertThat(kz.epharm.training.service.videoNotCountedMessage(119)).contains("119 сек.")
        assertThat(kz.epharm.training.service.videoNotCountedMessage(120)).isEqualTo("Видео засчитается через 2 мин. Подождите и повторите")
        assertThat(kz.epharm.training.service.videoNotCountedMessage(3_485)).contains("59 мин.")
        // The learner is not told to watch again what the page shows as watched.
        assertThat(kz.epharm.training.service.videoNotCountedMessage(42)).doesNotContain("Досмотрите", "—", "–")
    }
}
