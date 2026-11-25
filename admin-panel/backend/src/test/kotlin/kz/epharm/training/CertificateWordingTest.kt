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
}
