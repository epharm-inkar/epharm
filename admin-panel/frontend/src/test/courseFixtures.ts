// Course fixtures shared by the course preview tests (admin DTO shape).
import type { CourseDto, CourseLessonDto } from '@/lib/api-types'

export type QuestionDto = CourseLessonDto['quizQuestions'][number]

export function questionFixture(id: string, over: Partial<QuestionDto> = {}): QuestionDto {
  return {
    id,
    prompt: `Вопрос ${id}`,
    options: ['А', 'Б', 'В'],
    correctOption: 1,
    explanation: `Потому что ${id}`,
    ...over,
  }
}

export function lessonFixture(over: Partial<CourseLessonDto> = {}): CourseLessonDto {
  return {
    id: 'l1',
    title: 'Урок',
    description: '',
    content: '',
    kind: 'text',
    videoUrl: null,
    externalUrl: null,
    liveProvider: null,
    liveStatus: null,
    liveStartAt: null,
    liveEndAt: null,
    liveChatEnabled: true,
    liveQaEnabled: true,
    liveReactionsEnabled: true,
    livePolls: [],
    required: true,
    minimumWatchPct: null,
    quizQuestions: [],
    quizPassingScore: 80,
    attachments: [],
    durationMin: 5,
    order: 0,
    createdAt: '2026-11-01T00:00:00Z',
    updatedAt: '2026-11-01T00:00:00Z',
    ...over,
  }
}

export function courseFixture(over: Partial<CourseDto> = {}): CourseDto {
  return {
    id: 'course-1',
    title: 'Основы ePharm',
    description: 'Как работает подсказка на кассе',
    status: 'published',
    category: 'Продажи',
    lessons: 4,
    durationMin: 24,
    enrolled: 0,
    completed: 0,
    bonus: 0,
    lessonItems: [],
    createdAt: '2026-11-01T00:00:00Z',
    updatedAt: '2026-11-01T00:00:00Z',
    ...over,
  }
}

/**
 * A course with one lesson of each kind the preview has to draw: a text read in two parts with a
 * self-check, a video, a graded test over the first two lessons, and a live lesson.
 */
export function mixedCourse(): CourseDto {
  return courseFixture({
    lessonItems: [
      lessonFixture({
        id: 'l-text',
        title: 'Введение',
        order: 0,
        content:
          '1.1. Что такое ePharm\nePharm подсказывает фармацевту, что предложить покупателю.\n' +
          '1.2. Как это работает\nКасса показывает подсказку во время продажи.',
        quizQuestions: [
          questionFixture('sc1', {
            prompt: 'Что показывает касса?',
            options: ['Подсказку', 'Рекламу'],
            correctOption: 0,
            explanation: 'Подсказку покупателю',
          }),
        ],
      }),
      lessonFixture({
        id: 'l-video',
        title: 'Видеоурок',
        kind: 'video',
        order: 1,
        videoUrl: '/s3/lesson.mp4',
        durationMin: 3,
      }),
      lessonFixture({
        id: 'l-test',
        title: 'Итоговый тест',
        kind: 'test',
        order: 2,
        quizPassingScore: 50,
        quizQuestions: [
          questionFixture('t1', {
            prompt: 'Первый вопрос теста',
            options: ['Неверно', 'Верно'],
            correctOption: 1,
            lessonId: 'l-text',
          }),
          questionFixture('t2', {
            prompt: 'Второй вопрос теста',
            options: ['Неверно', 'Верно'],
            correctOption: 1,
            lessonId: 'l-video',
          }),
        ],
      }),
      lessonFixture({
        id: 'l-live',
        title: 'Прямой эфир',
        kind: 'live',
        order: 3,
        liveProvider: 'youtube',
        liveStatus: 'scheduled',
        liveStartAt: '2026-12-05T10:00:00Z',
        externalUrl: 'https://www.youtube.com/watch?v=demo',
      }),
    ],
  })
}
