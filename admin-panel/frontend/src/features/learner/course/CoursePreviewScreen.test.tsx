// «Как видит фармацевт»: the real lesson screens in their preview mode, fed from the admin's course.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { useUiStore } from '@/app/store'
import type { CourseDto } from '@/lib/api-types'
import { courseFixture, lessonFixture, mixedCourse } from '@/test/courseFixtures'
import { CoursePreviewScreen } from './CoursePreviewScreen'

function Preview({ course, initial = null }: { course: CourseDto; initial?: string | null }) {
  const [lessonId, setLessonId] = useState<string | null>(initial)
  return (
    <CoursePreviewScreen
      course={course}
      lessonId={lessonId}
      onOpenLesson={setLessonId}
      onBack={() => setLessonId(null)}
    />
  )
}

let fetchSpy: ReturnType<typeof vi.spyOn>

beforeEach(() => {
  useUiStore.setState({ language: 'ru' })
  vi.stubGlobal('scrollTo', vi.fn())
  // Nothing in the preview may reach a server: a pharmacist's session, progress, live rooms.
  fetchSpy = vi
    .spyOn(globalThis, 'fetch')
    .mockRejectedValue(new Error('the preview must not call the network'))
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  useUiStore.setState({ language: 'ru' })
})

describe('the course screen', () => {
  it('shows the course and its path of lessons, every lesson open and none done', () => {
    render(<Preview course={mixedCourse()} />)
    expect(screen.getByRole('heading', { level: 2, name: 'Основы ePharm' })).toBeInTheDocument()
    expect(screen.getByText('Как работает подсказка на кассе')).toBeInTheDocument()
    expect(screen.getByText('0 из 4 уроков')).toBeInTheDocument()
    expect(screen.getByText('Уроки курса')).toBeInTheDocument()
    expect(screen.getByText('4 урока · 24 мин')).toBeInTheDocument()
    for (const title of ['Введение', 'Видеоурок', 'Итоговый тест', 'Прямой эфир']) {
      // Nothing is locked behind a previous lesson, nothing is marked as passed.
      expect(screen.getByRole('button', { name: new RegExp(title) })).toBeEnabled()
    }
    expect(screen.queryByText(/пройдено/)).not.toBeInTheDocument()
    // The path says what each lesson is, as in the cabinet.
    expect(screen.getByText('Чтение · 5 мин')).toBeInTheDocument()
    expect(screen.getByText('Видео · 3 мин')).toBeInTheDocument()
    expect(screen.getByText('Тест · 2 вопросов')).toBeInTheDocument()
    expect(screen.getAllByText('Прямой эфир').length).toBeGreaterThan(0)
    expect(screen.getByText('Сертификат после итогового теста')).toBeInTheDocument()
  })

  it('loads no media at all until a lesson with media is opened', () => {
    const { container } = render(<Preview course={mixedCourse()} />)
    expect(container.querySelector('video, audio, img, iframe')).toBeNull()
    expect(fetchSpy).not.toHaveBeenCalled()
  })

  it('«Начать курс» opens the first lesson', async () => {
    const user = userEvent.setup()
    render(<Preview course={mixedCourse()} />)
    await user.click(screen.getByRole('button', { name: 'Начать курс' }))
    expect(screen.getByText('Урок 1 из 4')).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 2, name: 'Введение' })).toBeInTheDocument()
  })

  it('opens a lesson from the path, also a late one: nothing waits for the lessons before it', async () => {
    const user = userEvent.setup()
    render(<Preview course={mixedCourse()} />)
    await user.click(screen.getByRole('button', { name: /Видеоурок/ }))
    expect(screen.getByText('Урок 2 из 4')).toBeInTheDocument()
    expect(screen.queryByText('Урок пока недоступен')).not.toBeInTheDocument()
  })

  it('folds a long description and opens it on request', async () => {
    const user = userEvent.setup()
    render(
      <Preview
        course={courseFixture({ description: 'Длинное описание', lessonItems: [lessonFixture()] })}
      />,
    )
    const text = screen.getByText('Длинное описание')
    expect(text.className).toContain('line-clamp-3')
    await user.click(screen.getByRole('button', { name: 'О курсе подробнее' }))
    expect(text.className).not.toContain('line-clamp-3')
    await user.click(screen.getByRole('button', { name: 'Свернуть' }))
    expect(screen.getByText('Длинное описание').className).toContain('line-clamp-3')
  })

  it('has no start button and no path for a course without lessons, and does not fail', () => {
    render(<Preview course={courseFixture({ lessonItems: [] })} />)
    expect(screen.queryByRole('button', { name: 'Начать курс' })).not.toBeInTheDocument()
    expect(screen.getByText('0 из 0 уроков')).toBeInTheDocument()
  })

  it("speaks Kazakh with the cabinet's own strings", () => {
    useUiStore.setState({ language: 'kk' })
    render(<Preview course={mixedCourse()} />)
    expect(screen.getByRole('button', { name: 'Курсты бастау' })).toBeInTheDocument()
    expect(screen.getByText('Курс сабақтары')).toBeInTheDocument()
    expect(screen.getByText('Сабақтар: 0/4')).toBeInTheDocument()
  })
})

describe('a text lesson with a self-check', () => {
  it('reads in parts, and «Дальше» does not wait for a reading time', async () => {
    const user = userEvent.setup()
    render(<Preview course={mixedCourse()} initial="l-text" />)
    expect(screen.getByText('Часть 1 из 2')).toBeInTheDocument()
    expect(
      screen.getByText('ePharm подсказывает фармацевту, что предложить покупателю.'),
    ).toBeInTheDocument()
    // The first reading of the real cabinet counts down «Читайте ещё N с» here.
    expect(screen.queryByText(/Читайте ещё/)).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /Дальше/ }))
    expect(screen.getByText('Часть 2 из 2')).toBeInTheDocument()
    expect(screen.getByText('Касса показывает подсказку во время продажи.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /К самопроверке/ })).toBeEnabled()
  })

  it("runs the self-check with its answers and explanations, the right answer from the admin's data", async () => {
    const user = userEvent.setup()
    render(<Preview course={mixedCourse()} initial="l-text" />)
    await user.click(screen.getByRole('button', { name: /Дальше/ }))
    await user.click(screen.getByRole('button', { name: /К самопроверке/ }))

    expect(screen.getByRole('heading', { name: 'Что показывает касса?' })).toBeInTheDocument()
    await user.click(screen.getByRole('radio', { name: /Рекламу/ }))
    await user.click(screen.getByRole('button', { name: 'Проверить' }))
    expect(screen.getByText('Не совсем')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Понятно' }))

    // A wrong answer comes back at the end, as in the cabinet.
    await user.click(screen.getByRole('radio', { name: /Подсказку/ }))
    await user.click(screen.getByRole('button', { name: 'Проверить' }))
    expect(screen.getByText('Верно!')).toBeInTheDocument()
    expect(screen.getByText('Подсказку покупателю')).toBeInTheDocument()
  })

  it('lets the lesson be left at once: the button is on and says where it goes', async () => {
    const user = userEvent.setup()
    render(<Preview course={mixedCourse()} initial="l-text" />)
    // Nothing read, nothing answered: the real button would say «Дочитайте урок» and be off.
    const next = screen.getByRole('button', { name: /Урок изучен, дальше/ })
    expect(next).toBeEnabled()
    await user.click(next)
    expect(screen.getByText('Урок 2 из 4')).toBeInTheDocument()
  })

  it('goes back to the course from the lesson', async () => {
    const user = userEvent.setup()
    render(<Preview course={mixedCourse()} initial="l-text" />)
    await user.click(screen.getByRole('button', { name: /К содержанию курса/ }))
    expect(screen.getByRole('button', { name: 'Начать курс' })).toBeInTheDocument()
  })

  it('never calls the network while a lesson is read and checked', async () => {
    const user = userEvent.setup()
    render(<Preview course={mixedCourse()} initial="l-text" />)
    await user.click(screen.getByRole('button', { name: /Дальше/ }))
    await user.click(screen.getByRole('button', { name: /К самопроверке/ }))
    await user.click(screen.getByRole('radio', { name: /Подсказку/ }))
    await user.click(screen.getByRole('button', { name: 'Проверить' }))
    await user.click(screen.getByRole('button', { name: /Урок изучен, дальше/ }))
    expect(fetchSpy).not.toHaveBeenCalled()
  })
})

describe('a video lesson', () => {
  it('plays its own video and only that one, unrestricted, loading just the metadata', () => {
    const { container } = render(<Preview course={mixedCourse()} initial="l-video" />)
    const videos = container.querySelectorAll('video')
    expect(videos).toHaveLength(1)
    const video = videos[0]
    expect(video).toHaveAttribute('src', '/s3/lesson.mp4')
    expect(video).toHaveAttribute('preload', 'metadata')
    // A pharmacist's video snaps back when wound forward and keeps the speed at 1x: not here.
    const controls = video.getAttribute('controlslist') ?? ''
    expect(controls).not.toContain('noplaybackrate')
    expect(video.hasAttribute('disablepictureinpicture')).toBe(false)
  })

  it('has its button on from the start: no «Досмотрите видео»', () => {
    render(<Preview course={mixedCourse()} initial="l-video" />)
    expect(screen.queryByRole('button', { name: /Досмотрите видео/ })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Урок изучен, дальше/ })).toBeEnabled()
  })

  it('drops the video of the lesson it leaves', async () => {
    const user = userEvent.setup()
    const { container } = render(<Preview course={mixedCourse()} initial="l-video" />)
    expect(container.querySelectorAll('video')).toHaveLength(1)
    await user.click(screen.getByRole('button', { name: /Урок изучен, дальше/ }))
    expect(screen.getByText('Урок 3 из 4')).toBeInTheDocument()
    expect(container.querySelectorAll('video')).toHaveLength(0)
  })
})

describe('a graded test', () => {
  async function takeTest(
    user: ReturnType<typeof userEvent.setup>,
    answers: Array<'Верно' | 'Неверно'>,
  ) {
    await user.click(screen.getByRole('button', { name: /Начать тест/ }))
    for (const [index, answer] of answers.entries()) {
      await user.click(screen.getByRole('radio', { name: answer }))
      await user.click(
        screen.getByRole('button', {
          name: index === answers.length - 1 ? /К отправке/ : /Дальше/,
        }),
      )
    }
    await user.click(screen.getByRole('button', { name: /Отправить ответы/ }))
  }

  it("is graded on the spot from the admin's answers, and passes", async () => {
    const user = userEvent.setup()
    render(<Preview course={mixedCourse()} initial="l-test" />)
    expect(screen.getByText('Тест · 2 вопросов')).toBeInTheDocument()
    expect(screen.getByText('Итоговый тест', { selector: 'div' })).toBeInTheDocument()
    await takeTest(user, ['Верно', 'Верно'])
    expect(await screen.findByText('Тест пройден!')).toBeInTheDocument()
    expect(screen.getByText(/Верно 2 из 2/)).toBeInTheDocument()
    expect(fetchSpy).not.toHaveBeenCalled()
  })

  it('fails with the lessons to repeat, named by the course, and offers another attempt', async () => {
    const user = userEvent.setup()
    render(<Preview course={mixedCourse()} initial="l-test" />)
    await takeTest(user, ['Неверно', 'Неверно'])
    expect(await screen.findByText('Пока не получилось')).toBeInTheDocument()
    expect(screen.getByText(/Верно 0 из 2/)).toBeInTheDocument()
    const repeat = screen.getByText('Повторите эти уроки:').parentElement as HTMLElement
    expect(within(repeat).getByRole('button', { name: /Введение/ })).toBeInTheDocument()
    expect(within(repeat).getByRole('button', { name: /Видеоурок/ })).toBeInTheDocument()
    // A lesson to repeat opens in the preview too.
    await user.click(within(repeat).getByRole('button', { name: /Введение/ }))
    expect(screen.getByText('Урок 1 из 4')).toBeInTheDocument()
    expect(fetchSpy).not.toHaveBeenCalled()
  })

  it('has its button on without taking the test', () => {
    render(<Preview course={mixedCourse()} initial="l-test" />)
    expect(screen.queryByRole('button', { name: /Сначала пройдите тест/ })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Урок изучен, дальше/ })).toBeEnabled()
  })
})

describe('a live lesson', () => {
  it('shows a card with the schedule, not the live room, and asks no server', () => {
    render(<Preview course={mixedCourse()} initial="l-live" />)
    const card = screen.getByTestId('learner-live-lesson-preview')
    expect(card).toHaveTextContent('Прямой эфир')
    expect(card).toHaveTextContent(/декабря/)
    expect(card).toHaveTextContent('В предпросмотре эфир не подключается.')
    expect(screen.queryByTestId('learner-live-lesson')).not.toBeInTheDocument()
    expect(screen.queryByText('Подключаемся к эфиру…')).not.toBeInTheDocument()
    expect(fetchSpy).not.toHaveBeenCalled()
  })

  it('is the last lesson: its button finishes the course and returns to the course screen', async () => {
    const user = userEvent.setup()
    render(<Preview course={mixedCourse()} initial="l-live" />)
    await user.click(screen.getByRole('button', { name: /Завершить курс/ }))
    expect(screen.getByRole('button', { name: 'Начать курс' })).toBeInTheDocument()
  })

  it('says «Дата уточняется» for a live lesson without a date', () => {
    const course = courseFixture({
      lessonItems: [lessonFixture({ id: 'x', kind: 'live', liveStartAt: null })],
    })
    render(<Preview course={course} initial="x" />)
    expect(screen.getByTestId('learner-live-lesson-preview')).toHaveTextContent('Дата уточняется')
  })
})

describe('a lesson that is not there', () => {
  it('says so and leads back to the course', async () => {
    const user = userEvent.setup()
    render(<Preview course={mixedCourse()} initial="gone" />)
    expect(screen.getByText('Урок не найден')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Вернуться к курсу' }))
    expect(screen.getByRole('button', { name: 'Начать курс' })).toBeInTheDocument()
  })
})
