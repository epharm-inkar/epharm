package kz.epharm.training.service

import java.util.Random
import java.util.UUID

/**
 * Which questions of a test bank one attempt asks (Grigoriy, 25.11.2026: «разные вопросы,
 * перемешанный порядок»). The draw is deterministic for an attempt, so the questions a pharmacist
 * sees and the ones the server grades are the same without storing anything; the next attempt,
 * after a failure, draws again and usually asks other questions.
 */
object QuizDraw {
    fun questionIds(
        bankIds: List<String>,
        drawCount: Int?,
        assignmentStageId: UUID,
        lessonId: String,
        attemptsUsed: Int,
    ): List<String> {
        if (drawCount == null || drawCount <= 0 || drawCount >= bankIds.size) return bankIds
        val seed = "$assignmentStageId:$lessonId:$attemptsUsed".hashCode().toLong() * 31L + bankIds.size
        return bankIds.shuffled(Random(seed)).take(drawCount)
    }
}
