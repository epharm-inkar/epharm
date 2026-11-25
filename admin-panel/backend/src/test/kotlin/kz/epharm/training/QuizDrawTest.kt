package kz.epharm.training

import kz.epharm.training.service.QuizDraw
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class QuizDrawTest {
    private val bank = (1..24).map { "q$it" }
    private val stage = UUID.fromString("2ca85139-fc60-4385-8c81-e4b1323766ca")

    @Test
    fun `an attempt asks a stable draw of the requested size from the bank`() {
        val first = QuizDraw.questionIds(bank, 10, stage, "lesson", 0)
        assertThat(first).hasSize(10).doesNotHaveDuplicates().allMatch { it in bank }
        assertThat(QuizDraw.questionIds(bank, 10, stage, "lesson", 0)).isEqualTo(first)
    }

    @Test
    fun `the next attempt and another pharmacist get other draws`() {
        val first = QuizDraw.questionIds(bank, 10, stage, "lesson", 0)
        val second = QuizDraw.questionIds(bank, 10, stage, "lesson", 1)
        val otherStage = QuizDraw.questionIds(bank, 10, UUID.randomUUID(), "lesson", 0)
        assertThat(second).isNotEqualTo(first)
        assertThat(otherStage).isNotEqualTo(first)
    }

    @Test
    fun `without a bank size every question is asked in the stored order`() {
        assertThat(QuizDraw.questionIds(bank, null, stage, "lesson", 3)).isEqualTo(bank)
        assertThat(QuizDraw.questionIds(bank, 24, stage, "lesson", 3)).isEqualTo(bank)
        assertThat(QuizDraw.questionIds(bank, 0, stage, "lesson", 3)).isEqualTo(bank)
    }
}
