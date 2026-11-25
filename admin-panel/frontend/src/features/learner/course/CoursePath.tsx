import {
  Award,
  BookOpen,
  Check,
  ChevronRight,
  ListChecks,
  Lock,
  PlayCircle,
  Radio,
} from 'lucide-react'
import { useLearnerT } from '../learner-i18n'
import { lessonMinutes } from './lessonContent'

export type PathLesson = {
  id: string
  title: string
  kind?: string
  videoUrl?: string | null
  durationMin?: number
  content?: string | null
  quizQuestions?: Array<{ id: string }>
  required?: boolean
  done: boolean
  locked: boolean
}

/**
 * The course as a path, like Duolingo or Coursera: every lesson is a stop with its state (done,
 * the next one, locked), the next lesson is the one highlighted card with a button, and the path
 * ends with the certificate.
 */
export function CoursePath({
  lessons,
  certificateReady,
  certificateUrl,
  onOpenLesson,
}: {
  lessons: PathLesson[]
  certificateReady: boolean
  certificateUrl?: string | null
  onOpenLesson: (lessonId: string) => void
}) {
  const t = useLearnerT()
  const nextIndex = lessons.findIndex((lesson) => !lesson.done && !lesson.locked)

  const kindOf = (lesson: PathLesson) => {
    const test = lesson.kind === 'test' || lesson.kind === 'quiz'
    if (test)
      return {
        Icon: ListChecks,
        label: t('Тест · {count} вопросов', { count: lesson.quizQuestions?.length ?? 0 }),
      }
    if (lesson.kind === 'live') return { Icon: Radio, label: t('Прямой эфир') }
    if (lesson.kind === 'video' || lesson.videoUrl)
      return {
        Icon: PlayCircle,
        label: lesson.content?.trim()
          ? t('Видео и текст · {minutes} мин', { minutes: lessonMinutes(lesson) })
          : t('Видео · {minutes} мин', { minutes: lesson.durationMin ?? 0 }),
      }
    return {
      Icon: BookOpen,
      label: t('Чтение · {minutes} мин', { minutes: lesson.durationMin ?? 0 }),
    }
  }

  return (
    <ol className="relative grid gap-2">
      {lessons.map((lesson, index) => {
        const { Icon, label } = kindOf(lesson)
        const current = index === nextIndex
        const selfCheck =
          (lesson.quizQuestions?.length ?? 0) > 0 &&
          lesson.kind !== 'test' &&
          lesson.kind !== 'quiz'
        return (
          <li key={lesson.id} className="relative pl-12">
            <span
              className={`absolute left-[19px] top-0 w-0.5 ${index === 0 ? 'top-6' : ''} ${index === lessons.length - 1 && !certificateReady ? 'h-6' : 'h-full'} ${lesson.done ? 'bg-brand-green-400' : 'bg-ink-100'}`}
              aria-hidden="true"
            />
            <span
              className={`absolute left-0 top-3 flex h-10 w-10 items-center justify-center rounded-full border-4 border-white text-sm font-extrabold shadow-sm ${
                lesson.done
                  ? 'bg-brand-green-600 text-white'
                  : current
                    ? 'bg-white text-brand-green-700 ring-2 ring-brand-green-500'
                    : 'bg-ink-100 text-ink-400'
              }`}
              aria-hidden="true"
            >
              {lesson.done ? (
                <Check size={18} strokeWidth={3} />
              ) : lesson.locked ? (
                <Lock size={15} />
              ) : (
                index + 1
              )}
            </span>
            <button
              type="button"
              disabled={lesson.locked}
              onClick={() => onOpenLesson(lesson.id)}
              className={`flex w-full items-center gap-3 rounded-2xl border p-3.5 text-left transition sm:p-4 ${
                current
                  ? 'border-brand-green-300 bg-brand-green-50 shadow-card'
                  : 'border-transparent hover:border-ink-100 hover:bg-white disabled:hover:border-transparent disabled:hover:bg-transparent'
              } disabled:cursor-not-allowed`}
            >
              <span className="min-w-0 flex-1">
                <span
                  className={`block text-[15px] font-extrabold leading-5 ${lesson.locked ? 'text-ink-400' : 'text-ink-900'}`}
                >
                  {lesson.title}
                </span>
                <span className="mt-1 flex flex-wrap items-center gap-x-2 gap-y-1 text-xs font-semibold text-ink-400">
                  <span className="inline-flex items-center gap-1">
                    <Icon size={13} /> {label}
                  </span>
                  {selfCheck && <span>· {t('самопроверка')}</span>}
                  {lesson.done && <span className="text-brand-green-700">· {t('пройдено')}</span>}
                </span>
              </span>
              {current ? (
                <span className="btn btn-primary btn-sm shrink-0">
                  {index === 0 ? t('Начать') : t('Продолжить')}
                </span>
              ) : lesson.locked ? null : (
                <ChevronRight className="shrink-0 text-ink-300" size={18} />
              )}
            </button>
          </li>
        )
      })}
      <li className="relative pl-12">
        <span
          className={`absolute left-0 top-2 flex h-10 w-10 items-center justify-center rounded-full border-4 border-white shadow-sm ${certificateReady ? 'bg-amber-400 text-white' : 'bg-ink-100 text-ink-400'}`}
          aria-hidden="true"
        >
          <Award size={18} />
        </span>
        {certificateReady && certificateUrl ? (
          <a
            className="flex items-center justify-between gap-3 rounded-2xl border border-amber-200 bg-amber-50 p-3.5 text-[15px] font-extrabold text-amber-900 sm:p-4"
            href={certificateUrl}
            target="_blank"
            rel="noreferrer"
          >
            {t('Сертификат готов: открыть')} <ChevronRight size={18} />
          </a>
        ) : (
          <div className="p-3.5 text-[15px] font-extrabold text-ink-400 sm:p-4">
            {t('Сертификат после итогового теста')}
          </div>
        )}
      </li>
    </ol>
  )
}
