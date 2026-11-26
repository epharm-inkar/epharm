// «Как видит фармацевт» in the admin: the selector and the arrows outside the phone, the cabinet inside it.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useUiStore } from '@/app/store'
import { courseFixture, lessonFixture, mixedCourse } from '@/test/courseFixtures'
import { scrollToTopOf } from '@/features/learner/course/frameWindow'
import CoursePreview from './CoursePreview'

// jsdom does not implement scrolling in the frame's own window and says so on every screen change.
vi.mock('@/features/learner/course/frameWindow', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/features/learner/course/frameWindow')>()),
  scrollToTopOf: vi.fn(),
}))

beforeEach(() => {
  useUiStore.setState({ language: 'ru' })
  vi.stubGlobal('scrollTo', vi.fn())
  vi.spyOn(globalThis, 'fetch').mockRejectedValue(
    new Error('the preview must not call the network'),
  )
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

/** The cabinet inside the phone: its body, once the frame has loaded and drawn the screen. */
async function phone() {
  const frame = (await screen.findByTitle(/Предпросмотр курса/)) as HTMLIFrameElement
  await waitFor(() =>
    expect(frame.contentDocument?.getElementById('phone-root')?.childElementCount).toBeGreaterThan(
      0,
    ),
  )
  return within(frame.contentDocument!.body)
}

const select = () =>
  screen.getByRole('combobox', { name: 'Экран предпросмотра' }) as HTMLSelectElement
const previous = () => screen.getByRole('button', { name: 'Предыдущий экран' })
const next = () => screen.getByRole('button', { name: 'Следующий экран' })

describe('CoursePreview', () => {
  it('shows the course in a phone frame, as the cabinet shows it', async () => {
    render(<CoursePreview course={mixedCourse()} />)
    const inside = await phone()
    expect(inside.getByRole('button', { name: 'Начать курс' })).toBeInTheDocument()
    expect(inside.getByText('Уроки курса')).toBeInTheDocument()
    // The cabinet is not drawn in the admin page itself.
    expect(screen.queryByRole('button', { name: 'Начать курс' })).not.toBeInTheDocument()
    expect(screen.getByTestId('phone-frame')).toBeInTheDocument()
  })

  it('lists the course and every lesson in order in the selector, the course first', () => {
    render(<CoursePreview course={mixedCourse()} />)
    expect(
      within(select())
        .getAllByRole('option')
        .map((option) => option.textContent),
    ).toEqual([
      'Содержание курса',
      '1. Введение',
      '2. Видеоурок',
      '3. Итоговый тест',
      '4. Прямой эфир',
    ])
    expect(select().value).toBe('course')
  })

  it('opens a lesson in the phone from the selector', async () => {
    const user = userEvent.setup()
    render(<CoursePreview course={mixedCourse()} />)
    await phone()
    await user.selectOptions(select(), 'l-test')
    const inside = await phone()
    await waitFor(() => expect(inside.getByText('Урок 3 из 4')).toBeInTheDocument())
    expect(inside.getByRole('heading', { level: 2, name: 'Итоговый тест' })).toBeInTheDocument()
    expect(select().value).toBe('l-test')
    // And back to the course from the same list.
    await user.selectOptions(select(), 'course')
    await waitFor(() =>
      expect(inside.getByRole('button', { name: 'Начать курс' })).toBeInTheDocument(),
    )
  })

  it('walks the screens with the arrows: the course, then each lesson, and stops at the ends', async () => {
    const user = userEvent.setup()
    render(<CoursePreview course={mixedCourse()} />)
    const inside = await phone()
    expect(previous()).toBeDisabled()
    expect(next()).toBeEnabled()

    await user.click(next())
    await waitFor(() => expect(inside.getByText('Урок 1 из 4')).toBeInTheDocument())
    expect(select().value).toBe('l-text')
    expect(previous()).toBeEnabled()

    await user.click(next())
    await user.click(next())
    await user.click(next())
    await waitFor(() => expect(inside.getByText('Урок 4 из 4')).toBeInTheDocument())
    expect(select().value).toBe('l-live')
    expect(next()).toBeDisabled()

    await user.click(previous())
    await waitFor(() => expect(inside.getByText('Урок 3 из 4')).toBeInTheDocument())
  })

  it('keeps the selector in step with what is done inside the phone', async () => {
    const user = userEvent.setup()
    render(<CoursePreview course={mixedCourse()} />)
    const inside = await phone()
    await user.click(inside.getByRole('button', { name: 'Начать курс' }))
    await waitFor(() => expect(select().value).toBe('l-text'))
    await user.click(inside.getByRole('button', { name: /Урок изучен, дальше/ }))
    await waitFor(() => expect(select().value).toBe('l-video'))
    await user.click(inside.getByRole('button', { name: /К содержанию курса/ }))
    await waitFor(() => expect(select().value).toBe('course'))
  })

  it('opens any lesson without finishing the ones before it', async () => {
    const user = userEvent.setup()
    render(<CoursePreview course={mixedCourse()} />)
    const inside = await phone()
    await user.selectOptions(select(), 'l-live')
    await waitFor(() =>
      expect(inside.getByTestId('learner-live-lesson-preview')).toBeInTheDocument(),
    )
    expect(inside.queryByText('Урок пока недоступен')).not.toBeInTheDocument()
  })

  it('names the frame after the course and says what it is', async () => {
    render(<CoursePreview course={mixedCourse()} />)
    expect(
      await screen.findByTitle('Предпросмотр курса «Основы ePharm» на телефоне фармацевта'),
    ).toBeInTheDocument()
    expect(
      screen.getByText(/Так курс выглядит в кабинете фармацевта на телефоне/),
    ).toBeInTheDocument()
    expect(screen.getByText(/прогресс и ответы теста не сохраняются/)).toBeInTheDocument()
  })

  it('falls back to the course screen when the open lesson is deleted', async () => {
    const user = userEvent.setup()
    const course = mixedCourse()
    const { rerender } = render(<CoursePreview course={course} />)
    await phone()
    await user.selectOptions(select(), 'l-video')
    expect(select().value).toBe('l-video')
    rerender(
      <CoursePreview
        course={{
          ...course,
          lessonItems: course.lessonItems.filter((lesson) => lesson.id !== 'l-video'),
        }}
      />,
    )
    expect(select().value).toBe('course')
    expect(within(select()).queryByRole('option', { name: /Видеоурок/ })).not.toBeInTheDocument()
  })

  it('draws no frame for a course without lessons, and says why', () => {
    render(<CoursePreview course={courseFixture({ lessonItems: [] })} />)
    expect(screen.getByText('В курсе пока нет уроков')).toBeInTheDocument()
    expect(
      screen.getByText(/Предпросмотр появится, когда в курсе будет хотя бы один урок/),
    ).toBeInTheDocument()
    expect(screen.queryByTestId('phone-frame')).not.toBeInTheDocument()
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument()
  })

  it('says it is loading while the lessons are not here yet, not that there are none', () => {
    render(<CoursePreview course={courseFixture({ lessonItems: [] })} loading />)
    expect(screen.getByText('Загружаем курс…')).toBeInTheDocument()
    expect(screen.queryByText('В курсе пока нет уроков')).not.toBeInTheDocument()
  })

  it('sorts lessons by their order, not by the order they came in', () => {
    const course = courseFixture({
      lessonItems: [
        lessonFixture({ id: 'second', title: 'Второй', order: 1 }),
        lessonFixture({ id: 'first', title: 'Первый', order: 0 }),
      ],
    })
    render(<CoursePreview course={course} />)
    expect(
      within(select())
        .getAllByRole('option')
        .map((option) => option.textContent),
    ).toEqual(['Содержание курса', '1. Первый', '2. Второй'])
  })

  it('starts every new screen at the top of the phone window, not the admin page', async () => {
    const user = userEvent.setup()
    render(<CoursePreview course={mixedCourse()} />)
    await phone()
    vi.mocked(scrollToTopOf).mockClear()
    await user.selectOptions(select(), 'l-video')
    await waitFor(() => expect(scrollToTopOf).toHaveBeenCalled())
    const shownIn = vi.mocked(scrollToTopOf).mock.calls.at(-1)?.[0]?.ownerDocument
    // Drawn in the frame's document, so that is the window it scrolls, not the admin page's.
    expect(shownIn).toBeDefined()
    expect(shownIn).not.toBe(document)
  })

  it('does not touch the network', async () => {
    const user = userEvent.setup()
    render(<CoursePreview course={mixedCourse()} />)
    await phone()
    await user.click(next())
    await user.click(next())
    expect(globalThis.fetch).not.toHaveBeenCalled()
  })
})
