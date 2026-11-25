package kz.epharm.training.controller

import kz.epharm.shared.error.AppException
import kz.epharm.training.service.TrainingCertificatePdfService
import kz.epharm.training.service.TrainingService
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.util.UUID

@RestController
@RequestMapping("/api/public/training/certificates")
class PublicTrainingController(
    private val trainingService: TrainingService,
    private val certificatePdfService: TrainingCertificatePdfService,
) {
    /**
     * The certificate QR code points here. A phone browser asks for HTML and gets a readable
     * verification page; API clients keep getting the JSON they always got.
     */
    @GetMapping("/{token}")
    fun verify(
        @PathVariable token: UUID,
        @RequestHeader(HttpHeaders.ACCEPT, required = false) accept: String?,
    ): ResponseEntity<*> {
        if (!wantsHtml(accept)) return ResponseEntity.ok(trainingService.verifyCertificate(token))
        val html = MediaType("text", "html", Charsets.UTF_8)
        return try {
            val certificate = trainingService.verifyCertificate(token)
            ResponseEntity.ok()
                .contentType(html)
                .cacheControl(CacheControl.noStore())
                .body(CertificateVerificationPage.render(token, certificate))
        } catch (error: AppException) {
            if (error.status != HttpStatus.NOT_FOUND) throw error
            ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(html)
                .cacheControl(CacheControl.noStore())
                .body(CertificateVerificationPage.notFound())
        }
    }

    @GetMapping("/{token}/pdf")
    fun pdf(
        @PathVariable token: UUID,
        @RequestParam(name = "download", required = false, defaultValue = "false") download: Boolean,
    ): ResponseEntity<ByteArray> {
        val certificate = trainingService.verifyCertificate(token)
        val disposition = if (download) "attachment" else "inline"
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic())
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                "$disposition; filename=certificate-${certificate.number}.pdf",
            )
            .body(certificatePdfService.render(token, certificate))
    }

    companion object {
        /** True for a browser opening the address (QR scan, link): it lists text/html explicitly. */
        fun wantsHtml(accept: String?): Boolean =
            accept?.split(',')?.any { it.substringBefore(';').trim().equals("text/html", ignoreCase = true) } == true
    }
}
