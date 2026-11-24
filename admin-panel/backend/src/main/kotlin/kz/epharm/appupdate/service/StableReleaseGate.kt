package kz.epharm.appupdate.service

import java.time.Duration
import java.time.Instant

/**
 * A till build reaches every till only after a checked pilot (owner's rule, 24.11.2026: releases to the
 * tills are tested thoroughly before they go out, whoever publishes them).
 *
 * A stable registration is accepted when the same build (platform, version, sha256) was stable before
 * (going back to a build that already ran everywhere), or when it was published to the pilot channel at
 * least [StableReleaseGate.refusal] `minPilotMinutes` ago, a pilot till is online with that version now
 * and a pilot till sent a receipt after the pilot started.
 */
internal object StableReleaseGate {
    data class Evidence(
        val previouslyStable: Boolean,
        val pilotRegisteredAt: Instant?,
        val pilotTillsOnVersion: Int,
        val pilotTillsWithReceipts: Int,
    )

    /** null: the stable release may go out; otherwise the reason in the admin's language. */
    fun refusal(evidence: Evidence, now: Instant, minPilotMinutes: Long, version: String): String? {
        if (evidence.previouslyStable) return null
        val pilotAt = evidence.pilotRegisteredAt
            ?: return "Сборку $version сначала выпустите пилотом (channel: pilot) и проверьте на пилотных кассах"
        val minutes = Duration.between(pilotAt, now).toMinutes()
        if (minutes < minPilotMinutes) {
            return "Пилот $version идёт $minutes мин: на все кассы не раньше чем через $minPilotMinutes мин после пилота"
        }
        if (evidence.pilotTillsOnVersion == 0) {
            return "Ни одна пилотная касса сейчас не на связи с версией $version: проверьте, что пилот обновился"
        }
        if (evidence.pilotTillsWithReceipts == 0) {
            return "С пилотных касс после выпуска пилота $version не пришло ни одного чека: проверьте сбор продаж"
        }
        return null
    }
}
