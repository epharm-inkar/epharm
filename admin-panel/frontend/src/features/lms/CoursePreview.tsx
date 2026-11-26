import { useMemo, useState } from 'react'
import { BookOpen } from 'lucide-react'
import type { CourseDto } from '@/lib/api-types'
import { Empty, IconButton, Select } from '@/ui'
import { IconChevLeft, IconChevRight } from '@/ui/icons'
import { CoursePreviewScreen } from '@/features/learner/course/CoursePreviewScreen'
import { PhoneFrame } from './PhoneFrame'

const COURSE_SCREEN = 'course'

/**
 * «Как видит фармацевт» (owner, 26.11.2026): the course as the pharmacist's cabinet shows it, on a
 * phone screen, instead of a bare list of lessons with a black video and raw text. The lesson
 * screens are the cabinet's own components in their preview mode: nothing is saved, the video can
 * be wound forward, a test is graded on the spot. Only the open lesson loads its media.
 */
export default function CoursePreview({
  course,
  loading = false,
}: {
  course: CourseDto
  loading?: boolean
}) {
  const lessons = useMemo(
    () => [...(course.lessonItems ?? [])].sort((a, b) => a.order - b.order),
    [course.lessonItems],
  )
  const [chosen, setChosen] = useState<string | null>(null)
  // A lesson deleted while the preview is open falls back to the course screen.
  const lessonId = chosen && lessons.some((lesson) => lesson.id === chosen) ? chosen : null

  if (lessons.length === 0) {
    return loading ? (
      <div className="py-16 text-center text-[13px] text-ink-500">Загружаем курс…</div>
    ) : (
      <Empty
        title="В курсе пока нет уроков"
        body="Предпросмотр появится, когда в курсе будет хотя бы один урок."
        icon={<BookOpen size={26} />}
      />
    )
  }

  // The screens in order: the course, then each lesson. The arrows and the list walk the same line
  // as the buttons inside the phone.
  const steps: Array<string | null> = [null, ...lessons.map((lesson) => lesson.id)]
  const position = steps.indexOf(lessonId)
  const go = (next: string | null) => setChosen(next)

  return (
    <div className="flex flex-col gap-4" data-testid="course-preview">
      <div className="flex items-center gap-2">
        <IconButton
          tip="Назад"
          aria-label="Предыдущий экран"
          disabled={position <= 0}
          onClick={() => go(steps[position - 1])}
        >
          <IconChevLeft size={18} />
        </IconButton>
        <Select
          className="min-w-0 flex-1"
          ariaLabel="Экран предпросмотра"
          value={lessonId ?? COURSE_SCREEN}
          onChange={(value) => go(value === COURSE_SCREEN ? null : value)}
          options={[
            { value: COURSE_SCREEN, label: 'Содержание курса' },
            ...lessons.map((lesson, index) => ({
              value: lesson.id,
              label: `${index + 1}. ${lesson.title}`,
            })),
          ]}
        />
        <IconButton
          tip="Дальше"
          aria-label="Следующий экран"
          disabled={position >= steps.length - 1}
          onClick={() => go(steps[position + 1])}
        >
          <IconChevRight size={18} />
        </IconButton>
      </div>

      <PhoneFrame title={`Предпросмотр курса «${course.title}» на телефоне фармацевта`}>
        <CoursePreviewScreen
          course={course}
          lessonId={lessonId}
          onOpenLesson={go}
          onBack={() => go(null)}
        />
      </PhoneFrame>

      <p className="mx-auto max-w-[410px] text-center text-[12px] leading-5 text-ink-500">
        Так курс выглядит в кабинете фармацевта на телефоне. Это просмотр: прогресс и ответы теста
        не сохраняются, видео можно перематывать, текст не ждёт времени чтения.
      </p>
    </div>
  )
}
