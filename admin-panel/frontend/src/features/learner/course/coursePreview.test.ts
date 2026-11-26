import { describe, expect, it } from 'vitest'
import type { CourseDto, CourseLessonDto } from '@/lib/api-types'
import {
  courseFixture,
  lessonFixture as lesson,
  questionFixture as question,
} from '@/test/courseFixtures'
import {
  gradePreviewTest,
  PREVIEW_ASSIGNMENT_ID,
  PREVIEW_STAGE_ID,
  previewAssignment,
  previewLesson,
  previewQuestions,
} from './coursePreview'

describe('previewQuestions', () => {
  const bank = ['a', 'b', 'c', 'd', 'e', 'f'].map((id) => question(id))

  it('asks the whole bank when there is no draw', () => {
    const test = lesson({ kind: 'test', quizQuestions: bank })
    expect(previewQuestions(test)).toHaveLength(6)
  })

  it('asks the whole bank when the draw is not smaller than it', () => {
    expect(
      previewQuestions(lesson({ kind: 'test', quizQuestions: bank, quizDrawCount: 6 })),
    ).toHaveLength(6)
    expect(
      previewQuestions(lesson({ kind: 'test', quizQuestions: bank, quizDrawCount: 0 })),
    ).toHaveLength(6)
  })

  it('draws that many questions from the bank, the same ones on every render', () => {
    const test = lesson({ kind: 'test', quizQuestions: bank, quizDrawCount: 3 })
    const first = previewQuestions(test).map((item) => item.id)
    expect(first).toHaveLength(3)
    expect(new Set(first).size).toBe(3)
    expect(first.every((id) => bank.some((item) => item.id === id))).toBe(true)
    expect(previewQuestions(test).map((item) => item.id)).toEqual(first)
  })

  it('does not draw self-check questions of a text lesson, whatever the count says', () => {
    const text = lesson({ kind: 'text', quizQuestions: bank, quizDrawCount: 2 })
    expect(previewQuestions(text)).toHaveLength(6)
  })

  it('survives a lesson without questions', () => {
    const bare = lesson() as Partial<CourseLessonDto>
    delete bare.quizQuestions
    expect(previewQuestions(bare as CourseLessonDto)).toEqual([])
  })
})

describe('previewLesson', () => {
  it("carries what the cabinet draws and none of a pharmacist's own progress", () => {
    const source = lesson({
      id: 'v',
      title: 'Видео',
      kind: 'video',
      videoUrl: '/s3/v.mp4',
      content: 'Текст',
      minimumWatchPct: 70,
      order: 3,
      progressPct: 100,
      completedAt: '2026-11-01T00:00:00Z',
      lastPositionSeconds: 30,
      quizQuestions: [question('q', { lessonId: 'l1' })],
    })
    const shown = previewLesson(source)
    expect(shown).toMatchObject({
      id: 'v',
      title: 'Видео',
      kind: 'video',
      videoUrl: '/s3/v.mp4',
      content: 'Текст',
      minimumWatchPct: 70,
      order: 3,
      required: true,
    })
    expect(shown.quizQuestions?.[0]).toMatchObject({ id: 'q', correctOption: 1, lessonId: 'l1' })
    // The admin's own record of progress must not leak into what the preview shows as «done».
    expect(shown.progressPct).toBeUndefined()
    expect(shown.completedAt).toBeUndefined()
    expect(shown.lastPositionSeconds).toBeUndefined()
  })

  it('keeps a missing attachment list an empty one', () => {
    const bare = lesson() as Partial<CourseLessonDto>
    delete bare.attachments
    expect(previewLesson(bare as CourseLessonDto).attachments).toEqual([])
  })
})

describe('previewAssignment', () => {
  const course: CourseDto = courseFixture({
    id: 'c1',
    title: 'Пожарная безопасность',
    description: 'Описание курса',
    durationMin: 12,
    lessonItems: [
      lesson({ id: 'b', order: 1, title: 'Второй' }),
      lesson({ id: 'a', order: 0, title: 'Первый' }),
    ],
  })

  it('is one assignment with one open online stage holding the lessons in course order', () => {
    const assignment = previewAssignment(course)
    expect(assignment.id).toBe(PREVIEW_ASSIGNMENT_ID)
    expect(assignment.programName).toBe('Пожарная безопасность')
    expect(assignment.status).toBe('in_progress')
    expect(assignment.progressPct).toBe(0)
    expect(assignment.stages).toHaveLength(1)
    const [stage] = assignment.stages
    expect(stage.id).toBe(PREVIEW_STAGE_ID)
    expect(stage.type).toBe('online_course')
    // Not «locked» and not «completed»: nothing waits for a previous stage, nothing is done.
    expect(stage.status).toBe('in_progress')
    expect(stage.course?.lessons.map((item) => item.id)).toEqual(['a', 'b'])
    expect(stage.course?.durationMin).toBe(12)
  })

  it('does not change the course it was given', () => {
    const before = course.lessonItems.map((item) => item.id)
    previewAssignment(course)
    expect(course.lessonItems.map((item) => item.id)).toEqual(before)
  })

  it('survives a course whose lessons are not loaded yet', () => {
    const bare = { ...course } as Partial<CourseDto>
    delete bare.lessonItems
    expect(previewAssignment(bare as CourseDto).stages[0].course?.lessons).toEqual([])
  })
})

describe('gradePreviewTest', () => {
  // Four questions, the right answer is always option 1; two of them belong to lesson «b» and one to «a».
  const test = lesson({
    id: 't',
    kind: 'test',
    quizPassingScore: 75,
    quizQuestions: [
      question('q1', { lessonId: 'b' }),
      question('q2', { lessonId: 'a' }),
      question('q3', { lessonId: 'b' }),
      question('q4', { lessonId: '' }),
    ],
  })
  const lessonOrder = { a: 0, b: 1, t: 2 }
  const grade = (answers: Record<string, number>, attempt = 1) =>
    gradePreviewTest({ lesson: test, answers, attempt, lessonOrder })

  it('passes when the exact ratio reaches the passing score', () => {
    // 3 of 4 is 75 %: exactly the passing score.
    const result = grade({ q1: 1, q2: 1, q3: 1, q4: 0 })
    expect(result).toMatchObject({ score: 75, passed: true, correctAnswers: 3, totalQuestions: 4 })
    expect(result.weakLessonIds).toEqual([])
  })

  it('fails below it and names the lessons to repeat, in the order of the course, once each', () => {
    const result = grade({ q1: 0, q2: 0, q3: 0, q4: 1 })
    expect(result).toMatchObject({ score: 25, passed: false, correctAnswers: 1 })
    expect(result.weakLessonIds).toEqual(['a', 'b'])
  })

  it('never names a lesson for a question that has none', () => {
    const result = grade({ q1: 1, q2: 1, q3: 1, q4: 0 })
    expect(result.weakLessonIds).not.toContain('')
  })

  it('counts a missing answer as wrong', () => {
    expect(grade({ q1: 1, q2: 1, q3: 1 }).correctAnswers).toBe(3)
    expect(grade({}).correctAnswers).toBe(0)
  })

  it('keeps the attempt number it was given', () => {
    expect(grade({}, 3).attempt).toBe(3)
  })

  it('compares the exact ratio: a rounded score never turns a failure into a pass', () => {
    // 2 of 3 is 66.7 %, which rounds to 67, but the passing score of 67 needs 2.01 of 3.
    const three = lesson({
      kind: 'test',
      quizPassingScore: 67,
      quizQuestions: [question('a'), question('b'), question('c')],
    })
    const result = gradePreviewTest({
      lesson: three,
      answers: { a: 1, b: 1, c: 0 },
      attempt: 1,
      lessonOrder: {},
    })
    expect(result.score).toBe(67)
    expect(result.passed).toBe(false)
  })

  it('grades only the questions the pharmacist is asked when the test draws from a bank', () => {
    const bank = lesson({
      kind: 'test',
      quizDrawCount: 2,
      quizPassingScore: 100,
      quizQuestions: ['a', 'b', 'c', 'd', 'e'].map((id) => question(id)),
    })
    const asked = previewQuestions(bank)
    const answers = Object.fromEntries(asked.map((item) => [item.id, 1]))
    const result = gradePreviewTest({ lesson: bank, answers, attempt: 1, lessonOrder: {} })
    expect(result).toMatchObject({ totalQuestions: 2, correctAnswers: 2, passed: true })
  })

  it('does not pass an empty test', () => {
    const empty = lesson({ kind: 'test', quizQuestions: [] })
    expect(
      gradePreviewTest({ lesson: empty, answers: {}, attempt: 1, lessonOrder: {} }),
    ).toMatchObject({
      score: 0,
      passed: false,
      totalQuestions: 0,
    })
  })
})
