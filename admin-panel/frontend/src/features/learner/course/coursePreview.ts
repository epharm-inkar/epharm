// The admin's «Как видит фармацевт» (26.11.2026): a course as the pharmacist's cabinet shows it. The
// admin API and the learner API describe a course with nearly the same fields, so the preview feeds
// the real lesson screen from the admin course instead of drawing its own copy of that screen.

import type { CourseDto, CourseLessonDto } from '@/lib/api-types'
import type { Assignment, Lesson, Stage } from '../LearnerTrainingPage'
import type { TestResult } from './FinalTest'
import { seededOrder } from './shuffle'

export const PREVIEW_ASSIGNMENT_ID = 'course-preview'
export const PREVIEW_STAGE_ID = 'course-preview-stage'

type Question = CourseLessonDto['quizQuestions'][number]

const isGradedTest = (lesson: Pick<CourseLessonDto, 'kind'>) =>
  lesson.kind === 'quiz' || lesson.kind === 'test'

/**
 * The questions the pharmacist is asked. A test with a draw asks that many out of its bank, as the
 * server does for one attempt; the preview shows one such draw, the same on every render.
 */
export function previewQuestions(lesson: CourseLessonDto): Question[] {
  const bank = lesson.quizQuestions ?? []
  const draw = lesson.quizDrawCount
  if (!isGradedTest(lesson) || !draw || draw <= 0 || draw >= bank.length) return bank
  return seededOrder(bank, `${lesson.id}:preview`).slice(0, draw)
}

/** One lesson in the learner API's shape. A pharmacist's own progress is left out: nothing is done yet. */
export function previewLesson(lesson: CourseLessonDto): Lesson {
  return {
    id: lesson.id,
    title: lesson.title,
    description: lesson.description,
    content: lesson.content,
    kind: lesson.kind,
    videoUrl: lesson.videoUrl,
    externalUrl: lesson.externalUrl,
    liveProvider: lesson.liveProvider,
    liveStatus: lesson.liveStatus,
    liveStartAt: lesson.liveStartAt,
    liveEndAt: lesson.liveEndAt,
    liveChatEnabled: lesson.liveChatEnabled,
    liveQaEnabled: lesson.liveQaEnabled,
    liveReactionsEnabled: lesson.liveReactionsEnabled,
    required: lesson.required,
    minimumWatchPct: lesson.minimumWatchPct,
    quizQuestions: previewQuestions(lesson).map((question) => ({
      id: question.id,
      prompt: question.prompt,
      options: question.options,
      correctOption: question.correctOption,
      explanation: question.explanation,
      lessonId: question.lessonId,
    })),
    quizPassingScore: lesson.quizPassingScore,
    quizDrawCount: lesson.quizDrawCount,
    durationMin: lesson.durationMin,
    order: lesson.order,
    attachments: lesson.attachments ?? [],
  }
}

/** The course as one assignment with one online stage, which is what the lesson screen is given. */
export function previewAssignment(course: CourseDto): Assignment {
  const lessons = [...(course.lessonItems ?? [])]
    .sort((a, b) => a.order - b.order)
    .map(previewLesson)
  const stage: Stage = {
    id: PREVIEW_STAGE_ID,
    title: course.title,
    type: 'online_course',
    status: 'in_progress',
    progressPct: 0,
    course: {
      id: course.id,
      title: course.title,
      description: course.description,
      durationMin: course.durationMin,
      lessons,
    },
  }
  return {
    id: PREVIEW_ASSIGNMENT_ID,
    programName: course.title,
    programShortDescription: course.description,
    pharmacyName: '',
    city: '',
    status: 'in_progress',
    format: 'online',
    required: true,
    progressPct: 0,
    stages: [stage],
  }
}

/**
 * Grades a test the way the server does, so the preview shows the same result card: the score is
 * rounded, but a pass compares the exact ratio, so rounding never turns a failure into a pass; a
 * failed test names the lessons of the wrong answers, in the order of the course.
 */
export function gradePreviewTest(args: {
  lesson: CourseLessonDto
  answers: Record<string, number>
  attempt: number
  lessonOrder: Record<string, number>
}): TestResult {
  const asked = previewQuestions(args.lesson)
  const wrong = asked.filter((question) => args.answers[question.id] !== question.correctOption)
  const correctAnswers = asked.length - wrong.length
  const passing = args.lesson.quizPassingScore ?? 80
  const weakLessonIds = [
    ...new Set(wrong.map((question) => question.lessonId ?? '').filter((id) => id.trim() !== '')),
  ].sort((a, b) => (args.lessonOrder[a] ?? Infinity) - (args.lessonOrder[b] ?? Infinity))
  return {
    score: asked.length === 0 ? 0 : Math.round((correctAnswers * 100) / asked.length),
    passed: asked.length > 0 && correctAnswers * 100 >= passing * asked.length,
    correctAnswers,
    totalQuestions: asked.length,
    attempt: args.attempt,
    weakLessonIds,
  }
}
