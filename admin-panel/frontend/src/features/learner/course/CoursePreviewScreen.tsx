import { useEffect, useMemo, useRef, useState } from 'react'
import { PlayCircle } from 'lucide-react'
import type { CourseDto } from '@/lib/api-types'
import { useLearnerT } from '../learner-i18n'
import {
  LessonPage,
  type Lesson,
  type QuizSubmissionResult,
  type Stage,
  type TokenLifecycle,
  type Tokens,
} from '../LearnerTrainingPage'
import { CoursePath } from './CoursePath'
import { gradePreviewTest, previewAssignment } from './coursePreview'
import { scrollToTopOf } from './frameWindow'

// The live room is the only part that needs a pharmacist's session, and the preview shows a card
// instead of it: these are never used, they only complete the lesson screen's props.
const NO_TOKENS: Tokens = { accessToken: '', refreshToken: '' }
const NO_LIFECYCLE: TokenLifecycle = {
  isCurrent: () => true,
  onRefreshed: () => undefined,
  onExpired: () => undefined,
}

/**
 * What the pharmacist's cabinet shows for a course (26.11.2026): the course screen with its path of
 * lessons and the lesson screen itself, the real one in its preview mode. Rendered inside a phone
 * frame by the admin's course drawer; the open lesson is the caller's, so a selector outside the
 * phone and the buttons inside it stay in step.
 */
export function CoursePreviewScreen({
  course,
  lessonId,
  onOpenLesson,
  onBack,
}: {
  course: CourseDto
  /** The open lesson, or null for the course screen. */
  lessonId: string | null
  onOpenLesson: (lessonId: string) => void
  onBack: () => void
}) {
  const t = useLearnerT()
  const assignment = useMemo(() => previewAssignment(course), [course])
  const lessons = assignment.stages[0].course?.lessons ?? []
  const [attempts, setAttempts] = useState<Record<string, number>>({})
  const [descriptionOpen, setDescriptionOpen] = useState(false)
  const rootRef = useRef<HTMLElement | null>(null)

  useEffect(() => {
    // A new screen starts at the top of the frame, as the cabinet's does.
    scrollToTopOf(rootRef.current)
  }, [lessonId])

  const submitQuiz = async (
    _stage: Stage,
    lesson: Lesson,
    answers: Record<string, number>,
  ): Promise<QuizSubmissionResult | null> => {
    const source = course.lessonItems.find((item) => item.id === lesson.id)
    if (!source) return null
    const attempt = (attempts[lesson.id] ?? 0) + 1
    setAttempts((current) => ({ ...current, [lesson.id]: attempt }))
    const lessonOrder = Object.fromEntries(course.lessonItems.map((item) => [item.id, item.order]))
    return { ...gradePreviewTest({ lesson: source, answers, attempt, lessonOrder }), assignment }
  }

  const minutes =
    course.durationMin ?? lessons.reduce((sum, lesson) => sum + (lesson.durationMin ?? 0), 0)

  return (
    <main
      ref={rootRef}
      className="learner-portal-shell min-h-full w-full overflow-x-hidden px-3 py-3 pb-8"
    >
      <header className="learner-page-header">
        <div className="min-w-0">
          <h1 className="mt-1 truncate text-2xl font-extrabold tracking-tight text-ink-900">
            {course.title}
          </h1>
        </div>
      </header>

      {lessonId ? (
        <LessonPage
          preview
          assignment={assignment}
          lessonId={lessonId}
          busy={false}
          tokens={NO_TOKENS}
          lifecycle={NO_LIFECYCLE}
          onBack={onBack}
          onOpenLesson={onOpenLesson}
          onSaveProgress={async () => null}
          onSubmitQuiz={submitQuiz}
        />
      ) : (
        <div className="space-y-4">
          <section className="rounded-3xl bg-white p-5 shadow-card">
            <h2 className="mb-3 text-2xl font-extrabold leading-8 text-ink-900">{course.title}</h2>
            {course.description && (
              <div>
                <p
                  className={`whitespace-pre-line text-sm leading-6 text-ink-600 ${descriptionOpen ? '' : 'line-clamp-3'}`}
                >
                  {course.description}
                </p>
                <button
                  type="button"
                  className="mt-1 text-xs font-extrabold text-brand-green-700 hover:underline"
                  onClick={() => setDescriptionOpen((open) => !open)}
                >
                  {descriptionOpen ? t('Свернуть') : t('О курсе подробнее')}
                </button>
              </div>
            )}
            <div className="mt-5 flex items-center gap-3">
              <div className="h-2.5 flex-1 overflow-hidden rounded-full bg-paper-input" />
              <strong className="shrink-0 text-sm text-brand-green-700">
                {t('{done} из {total} уроков', { done: 0, total: lessons.length })}
              </strong>
            </div>
            {lessons[0] && (
              <button
                className="btn btn-primary btn-lg mt-5 w-full"
                onClick={() => onOpenLesson(lessons[0].id)}
              >
                <PlayCircle size={18} /> {t('Начать курс')}
              </button>
            )}
          </section>

          {lessons.length > 0 && (
            <section className="overflow-hidden rounded-2xl bg-white shadow-card">
              <div className="border-b border-ink-100 p-5">
                <span className="text-xs font-extrabold uppercase tracking-wider text-brand-green-700">
                  {t('Уроки курса')}
                </span>
                <div className="mt-3 text-xs font-semibold text-ink-400">
                  {t('{count} урока · {minutes} мин', { count: lessons.length, minutes })}
                </div>
              </div>
              <div className="p-3">
                <CoursePath
                  lessons={lessons.map((lesson) => ({ ...lesson, done: false, locked: false }))}
                  certificateReady={false}
                  onOpenLesson={onOpenLesson}
                />
              </div>
            </section>
          )}
        </div>
      )}
    </main>
  )
}
