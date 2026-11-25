package kz.epharm.training.controller

import kz.epharm.training.domain.CertificateStatus
import kz.epharm.training.domain.TrainingFormat
import kz.epharm.training.dto.CertificateVerificationDto
import kz.epharm.training.service.CertificateWording
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * The page a phone opens from the certificate QR code. Browsers ask for HTML and get this
 * page; API clients keep getting JSON from the same address. Self-contained: no scripts,
 * no external styles, every value escaped.
 */
object CertificateVerificationPage {
    private val dateFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(ZoneId.of("Asia/Almaty"))

    fun render(token: UUID, certificate: CertificateVerificationDto): String {
        val (statusClass, statusText) = when (certificate.status) {
            CertificateStatus.valid -> "ok" to "Сертификат действителен"
            CertificateStatus.expired -> "warn" to "Срок действия сертификата истёк"
            CertificateStatus.replaced -> "warn" to "Сертификат заменён новым"
            CertificateStatus.revoked -> "bad" to "Сертификат отозван"
        }
        val format = when (certificate.format) {
            TrainingFormat.online -> "онлайн"
            TrainingFormat.hybrid -> "гибридный"
            TrainingFormat.offline -> "очный"
        }
        val validUntil = certificate.expiresAt?.let { dateFormatter.format(it) } ?: "бессрочно"
        val body = """
            <div class="status $statusClass"><span class="dot"></span>${escape(statusText)}</div>
            <h1>${escape(certificate.pharmacistName)}</h1>
            <p class="muted">${escape(CertificateWording.completedProgram(certificate.pharmacistName))}</p>
            <p class="course">${escape(certificate.programName)}</p>
            <dl>
              <div><dt>Номер</dt><dd>${escape(certificate.number)}</dd></div>
              <div><dt>Выдан</dt><dd>${dateFormatter.format(certificate.issuedAt)}</dd></div>
              <div><dt>Действителен до</dt><dd>${escape(validUntil)}</dd></div>
              <div><dt>Формат</dt><dd>${escape(format)}</dd></div>
            </dl>
            <a class="button" href="/api/public/training/certificates/$token/pdf">Открыть сертификат (PDF)</a>
        """.trimIndent()
        return page("Проверка сертификата ePharm", body)
    }

    fun notFound(): String = page(
        "Сертификат не найден",
        """
            <div class="status bad"><span class="dot"></span>Сертификат не найден</div>
            <p class="muted">Проверьте ссылку или отсканируйте QR-код на сертификате ещё раз.</p>
        """.trimIndent(),
    )

    private fun page(title: String, body: String): String = """
        <!doctype html>
        <html lang="ru">
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <meta name="robots" content="noindex">
        <title>${escape(title)}</title>
        <style>
          :root { color-scheme: light; }
          * { box-sizing: border-box; }
          body { margin: 0; min-height: 100vh; background: #f2f7f6; color: #0a2d34;
                 font-family: -apple-system, "Segoe UI", Roboto, "Noto Sans", Arial, sans-serif;
                 display: flex; align-items: flex-start; justify-content: center; padding: 24px 16px; }
          main { width: 100%; max-width: 520px; background: #fff; border: 1px solid #dae6e5; border-radius: 16px; overflow: hidden; }
          header { background: #044b51; color: #fff; padding: 18px 22px; }
          header b { font-size: 22px; letter-spacing: .2px; }
          header span { display: block; font-size: 11px; letter-spacing: 1.2px; text-transform: uppercase; color: #bbe7e3; margin-top: 2px; }
          section { padding: 22px; }
          .status { display: inline-flex; align-items: center; gap: 8px; font-weight: 700; font-size: 15px;
                    padding: 8px 14px; border-radius: 999px; }
          .status .dot { width: 10px; height: 10px; border-radius: 50%; background: currentColor; }
          .ok { background: #dcf7f3; color: #047a70; }
          .warn { background: #fff4d6; color: #8a5a00; }
          .bad { background: #fde8e8; color: #b42318; }
          h1 { font-size: 24px; line-height: 1.25; margin: 18px 0 4px; }
          .muted { color: #5c7480; margin: 0; font-size: 15px; }
          .course { font-size: 18px; font-weight: 700; color: #00897f; margin: 10px 0 18px; line-height: 1.35; }
          dl { margin: 0 0 20px; display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
          dl div { background: #f5f9f8; border-radius: 10px; padding: 10px 12px; }
          dt { font-size: 12px; color: #5c7480; }
          dd { margin: 2px 0 0; font-weight: 600; font-size: 14px; word-break: break-word; }
          .button { display: block; text-align: center; background: #00897f; color: #fff; text-decoration: none;
                    font-weight: 700; padding: 14px; border-radius: 12px; }
          footer { padding: 0 22px 18px; font-size: 12px; color: #8aa0a8; }
        </style>
        </head>
        <body>
        <main>
        <header><b>ePharm</b><span>Обучение для фармацевтов</span></header>
        <section>
        $body
        </section>
        <footer>Проверка подлинности сертификата ePharm</footer>
        </main>
        </body>
        </html>
    """.trimIndent()

    private fun escape(value: String): String = buildString(value.length) {
        value.forEach { char ->
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(char)
            }
        }
    }
}
