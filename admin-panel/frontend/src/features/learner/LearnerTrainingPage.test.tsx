import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import LearnerTrainingPage from './LearnerTrainingPage'
import { useUiStore } from '@/app/store'

const tokens = { accessToken: 'access-token', refreshToken: 'refresh-token' }

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function renderPortal(path = '/learn') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/learn" element={<LearnerTrainingPage />} />
        <Route path="/learn/training" element={<LearnerTrainingPage />} />
        <Route path="/learn/recommendations" element={<LearnerTrainingPage />} />
        <Route path="/learn/certificates" element={<LearnerTrainingPage />} />
        <Route path="/learn/profile" element={<LearnerTrainingPage />} />
        <Route path="/learn/course/:assignmentId" element={<LearnerTrainingPage />} />
        <Route
          path="/learn/course/:assignmentId/lesson/:lessonId"
          element={<LearnerTrainingPage />}
        />
      </Routes>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  sessionStorage.clear()
  localStorage.clear()
  useUiStore.setState({ language: 'ru' })
  vi.restoreAllMocks()
  vi.stubGlobal('scrollTo', vi.fn())
})

afterEach(() => {
  document.body.classList.remove('learner-portal')
  vi.unstubAllGlobals()
})

describe('learner training portal', () => {
  it.each([
    ['ru', 'Вход выполнен. Аккаунт ожидает подтверждения HQ.', 'Повторная регистрация не нужна.'],
    ['kk', 'Кіру орындалды. Аккаунт HQ растауын күтуде.', 'Қайта тіркелудің қажеті жоқ.'],
  ] as const)(
    'explains pending approval after successful login in %s without discarding the session',
    async (language, heading, nextStep) => {
      useUiStore.setState({ language })
      sessionStorage.setItem('epharm.learner.tokens', JSON.stringify(tokens))
      vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
        const path = String(input)
        if (path === '/api/mobile/auth/me')
          return json({
            id: 'pending-1',
            name: 'Synthetic pending',
            status: 'pending',
            pharmacyName: null,
            city: 'Алматы',
          })
        if (path === '/api/mobile/training')
          return json({ total: 0, inProgress: 0, completed: 0, overdue: 0, assignments: [] })
        if (path === '/api/mobile/promotions') return json([])
        if (path.startsWith('/api/mobile/me/')) return json({ code: 'USER_NOT_ACTIVE' }, 403)
        return json({ items: [] })
      })
      renderPortal()
      expect(await screen.findByText(heading)).toBeVisible()
      expect(screen.getByRole('status', { name: heading })).toHaveTextContent(nextStep)
      expect(localStorage.getItem('epharm.learner.tokens')).toBe(JSON.stringify(tokens))
      expect(sessionStorage.getItem('epharm.learner.tokens')).toBeNull()
      expect(screen.queryByRole('textbox', { name: /ИИН|ЖСН/ })).not.toBeInTheDocument()
    },
  )

  it('renders Kazakh actions and dates without mixed Russian or locale placeholders', async () => {
    useUiStore.setState({ language: 'kk' })
    localStorage.setItem('epharm.learner.tokens', JSON.stringify(tokens))
    const assignment = {
      id: 'assignment-kk',
      programName: 'Қауіпсіз кеңес беру',
      pharmacyName: 'Дәріхана',
      city: 'Алматы',
      status: 'not_started',
      format: 'online',
      progressPct: 0,
      dueAt: '2026-10-03T12:00:00+05:00',
      stages: [
        {
          id: 'stage-kk',
          title: 'Материалдар',
          type: 'online_course',
          status: 'available',
          progressPct: 0,
          course: {
            id: 'course-kk',
            title: 'Материалдар',
            lessons: [{ id: 'lesson-kk', title: 'Кіріспе', kind: 'text', progressPct: 0 }],
          },
        },
      ],
    }
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const path = String(input)
      if (path === '/api/mobile/auth/me')
        return json({ id: 'ph-kk', name: 'Айжан', pharmacyName: 'Дәріхана', city: 'Алматы' })
      if (path === '/api/mobile/training')
        return json({
          total: 1,
          inProgress: 0,
          completed: 0,
          overdue: 0,
          assignments: [assignment],
        })
      if (path === '/api/mobile/me/balance') return json({ balanceKzt: 0 })
      if (path === '/api/mobile/promotions') return json([])
      return json({ items: [] })
    })

    renderPortal('/learn/training')
    expect(await screen.findByRole('button', { name: 'Курсты бастау' })).toBeVisible()
    expect(screen.getByText('03 қазан 2026 ж. дейін')).toBeVisible()
    await userEvent.click(screen.getByRole('button', { name: 'Басты бет' }))
    expect(screen.getByRole('button', { name: 'Дәріхананың фотоесебі' })).toBeVisible()
    expect(screen.queryByText(/M10|Начать курс|Фотоотчёт аптеки/)).not.toBeInTheDocument()
  })

  it('keeps a live lesson locked until the server marks the broadcast ended', async () => {
    sessionStorage.setItem('epharm.learner.tokens', JSON.stringify(tokens))
    const assignment = {
      id: 'assignment-live',
      programName: 'Обучение',
      pharmacyName: 'Аптека',
      city: 'Алматы',
      status: 'in_progress',
      format: 'online',
      progressPct: 0,
      stages: [
        {
          id: 'stage-live',
          title: 'Онлайн-курс',
          type: 'online_course',
          status: 'in_progress',
          progressPct: 0,
          course: {
            id: 'course-live',
            title: 'Онлайн-курс',
            lessons: [
              {
                id: 'lesson-live',
                title: 'Эфир',
                kind: 'live',
                order: 0,
                required: true,
                progressPct: 0,
                liveStatus: 'scheduled',
                attachments: [],
              },
            ],
          },
        },
      ],
    }
    const room = {
      lessonId: 'lesson-live',
      title: 'Эфир',
      description: '',
      provider: 'custom',
      status: 'ended',
      startsAt: '2026-10-12T10:00:00Z',
      endsAt: null,
      streamUrl: 'https://epharm.inkar.kz/live/demo',
      chatEnabled: true,
      qaEnabled: false,
      reactionsEnabled: false,
      participantCount: 0,
      messages: [],
      polls: [],
      reactions: {},
      serverTime: '2026-10-12T11:00:00Z',
    }
    let resolveRoom!: (response: Response) => void
    const roomResponse = new Promise<Response>((resolve) => {
      resolveRoom = resolve
    })
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const path = String(input)
      if (path === '/api/mobile/auth/me') {
        return json({
          id: 'ph-1',
          name: 'Айжан',
          phone: '+77070000000',
          pharmacyName: 'Аптека',
          city: 'Алматы',
        })
      }
      if (path === '/api/mobile/training/assignments/assignment-live') return json(assignment)
      if (path === '/api/mobile/training') {
        return json({
          total: 1,
          inProgress: 1,
          completed: 0,
          overdue: 0,
          assignments: [assignment],
        })
      }
      if (path.endsWith('/lessons/lesson-live/live') && !init?.method) return roomResponse
      if (path.endsWith('/lessons/lesson-live/live/messages') && init?.method === 'POST') {
        return json({ message: 'Не удалось отправить сообщение' }, 503)
      }
      if (path.endsWith('/lessons/lesson-live') && init?.method === 'PATCH') return json(assignment)
      return json({ message: `Unexpected request: ${path}` }, 500)
    })

    renderPortal('/learn/course/assignment-live/lesson/lesson-live')
    const complete = await screen.findByRole('button', { name: 'Дождитесь окончания эфира' })
    expect(complete).toBeDisabled()
    expect(
      fetchMock.mock.calls.some(
        ([path, init]) => String(path).endsWith('/lesson-live') && init?.method === 'PATCH',
      ),
    ).toBe(false)

    resolveRoom(json(room))
    expect(await screen.findByTestId('learner-live-lesson')).toBeInTheDocument()
    expect(screen.getByText('Участников чата: 0')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Открыть эфир' })).toHaveAttribute(
      'href',
      'https://epharm.inkar.kz/live/demo',
    )
    expect(screen.queryByTitle('Эфир')).not.toBeInTheDocument()
    await waitFor(() => expect(complete).toBeEnabled())
    const message = screen.getByPlaceholderText('Напишите комментарий…')
    await userEvent.type(message, 'Вопрос к ведущему')
    await userEvent.click(screen.getByRole('button', { name: 'Отправить' }))
    expect(await screen.findByText('Не удалось отправить сообщение')).toBeInTheDocument()
    expect(message).toHaveValue('Вопрос к ведущему')
    await userEvent.click(complete)
    expect(
      fetchMock.mock.calls.some(
        ([path, init]) => String(path).endsWith('/lesson-live') && init?.method === 'PATCH',
      ),
    ).toBe(true)
  })

  it('keeps all lessons of a completed legacy course accessible without lesson progress rows', async () => {
    sessionStorage.setItem('epharm.learner.tokens', JSON.stringify(tokens))
    const assignment = {
      id: 'assignment-1',
      programName: 'Безопасная работа',
      pharmacyName: 'Ауэзова 134',
      city: 'Алматы',
      status: 'completed',
      format: 'online',
      progressPct: 100,
      stages: [
        {
          id: 'stage-1',
          title: 'Основы',
          type: 'online_course',
          status: 'completed',
          progressPct: 100,
          course: {
            id: 'course-1',
            title: 'Основы',
            lessons: [
              { id: 'lesson-1', title: 'Первый', order: 0, attachments: [] },
              { id: 'lesson-2', title: 'Второй', order: 1, attachments: [] },
            ],
          },
        },
      ],
    }
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const path = String(input)
      if (path === '/api/mobile/auth/me') {
        return json({
          id: 'ph-1',
          name: 'Айжан',
          phone: '+77070000000',
          pharmacyName: 'Ауэзова 134',
          city: 'Алматы',
        })
      }
      if (path === '/api/mobile/training/assignments/assignment-1') return json(assignment)
      if (path === '/api/mobile/training') {
        return json({
          total: 1,
          inProgress: 0,
          completed: 1,
          overdue: 0,
          assignments: [assignment],
        })
      }
      return json({ message: `Unexpected request: ${path}` }, 500)
    })

    renderPortal('/learn/course/assignment-1')
    const secondLesson = await screen.findByRole('button', { name: /Второй/ })
    expect(secondLesson).toBeEnabled()
    await userEvent.click(secondLesson)
    expect(await screen.findByRole('heading', { name: 'Второй' })).toBeInTheDocument()
    expect(screen.queryByText('Урок пока недоступен')).not.toBeInTheDocument()
    expect(fetchMock.mock.calls.every(([, init]) => init?.method !== 'PATCH')).toBe(true)
  })

  it('lets a content-URL-only online course complete through the stage endpoint', async () => {
    sessionStorage.setItem('epharm.learner.tokens', JSON.stringify(tokens))
    const assignment = {
      id: 'assignment-1',
      programName: 'Безопасная работа',
      pharmacyName: 'Ауэзова 134',
      city: 'Алматы',
      status: 'waiting_online',
      format: 'online',
      progressPct: 0,
      startedAt: '2026-09-01T10:00:00Z',
      stages: [
        {
          id: 'stage-1',
          title: 'Внешний онлайн-курс',
          type: 'online_course',
          status: 'available',
          progressPct: 0,
          contentUrl: 'https://example.org/course',
          course: null,
        },
      ],
    }
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const path = String(input)
      if (path === '/api/mobile/auth/me') {
        return json({
          id: 'ph-1',
          name: 'Айжан',
          phone: '+77070000000',
          pharmacyName: 'Ауэзова 134',
          city: 'Алматы',
        })
      }
      if (path === '/api/mobile/training/assignments/assignment-1') return json(assignment)
      if (path === '/api/mobile/training') {
        return json({
          total: 1,
          inProgress: 1,
          completed: 0,
          overdue: 0,
          assignments: [assignment],
        })
      }
      if (
        path === '/api/mobile/training/assignments/assignment-1/stages/stage-1' &&
        init?.method === 'PATCH'
      ) {
        return json({
          ...assignment,
          stages: [{ ...assignment.stages[0], status: 'completed', progressPct: 100 }],
        })
      }
      return json({ message: `Unexpected request: ${path}` }, 500)
    })

    renderPortal('/learn/course/assignment-1')
    expect(await screen.findByRole('link', { name: 'Открыть материал' })).toHaveAttribute(
      'href',
      'https://example.org/course',
    )
    await userEvent.click(screen.getByRole('button', { name: 'Завершить этап' }))
    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(
        '/api/mobile/training/assignments/assignment-1/stages/stage-1',
        expect.objectContaining({
          method: 'PATCH',
          body: JSON.stringify({ progressPct: 100 }),
        }),
      )
    })
  })
  it('switches the pharmacist interface to Kazakh and remembers the language', async () => {
    const user = userEvent.setup()
    renderPortal()

    await user.click(screen.getByRole('button', { name: 'Қаз' }))

    expect(screen.getByRole('heading', { name: 'Фармацевтке кіру' })).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: 'ЖСН немесе телефон нөмірі' })).toBeInTheDocument()
    expect(screen.getByRole('note')).toHaveTextContent('Құпиясөз жоқ па?')
    expect(localStorage.getItem('epharm.lang')).toBe('kk')
    expect(document.documentElement.lang).toBe('kk')
  })

  it.each(['1234', 'Farm123!'])(
    'enters the pharmacist portal with IIN and password %s',
    async (password) => {
      const user = userEvent.setup()
      const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
        const path = String(input)
        if (path === '/api/mobile/auth/login') {
          return json({
            tokens,
            pharmacist: {
              id: 'ph-1',
              name: 'Айжан',
              phone: '+77070000000',
              pharmacyName: 'Ауэзова 134',
              city: 'Алматы',
            },
          })
        }
        if (path === '/api/mobile/auth/me') {
          return json({
            id: 'ph-1',
            name: 'Айжан',
            phone: '+77070000000',
            pharmacyName: 'Ауэзова 134',
            city: 'Алматы',
          })
        }
        if (path === '/api/mobile/training')
          return json({ total: 0, inProgress: 0, completed: 0, overdue: 0, assignments: [] })
        if (path === '/api/mobile/promotions') return json([])
        return json({ message: `Unexpected request: ${path}` }, 500)
      })
      renderPortal()

      // One form: the IIN and the password together, no IIN check step before the password.
      await user.type(
        screen.getByRole('textbox', { name: 'ИИН или номер телефона' }),
        '061007 500024',
      )
      expect(screen.getByLabelText('Пароль')).toHaveAttribute('minlength', '4')
      await user.type(screen.getByLabelText('Пароль'), password.slice(0, 3))
      expect(screen.getByRole('button', { name: 'Войти' })).toBeDisabled()
      await user.type(screen.getByLabelText('Пароль'), password.slice(3))
      expect(screen.getByRole('button', { name: 'Войти' })).toBeEnabled()
      await user.click(screen.getByRole('button', { name: 'Войти' }))

      await waitFor(() => {
        expect(fetchMock).toHaveBeenCalledWith(
          '/api/mobile/auth/login',
          expect.objectContaining({
            method: 'POST',
            body: JSON.stringify({ iin: '061007500024', password }),
          }),
        )
      })
      expect(await screen.findByRole('heading', { name: 'Айжан' })).toBeInTheDocument()
      expect(JSON.parse(localStorage.getItem('epharm.learner.tokens') || '{}')).toEqual(tokens)
      const activation = fetchMock.mock.calls.some(([path]) =>
        String(path).includes('/activation/'),
      )
      expect(activation).toBe(false)
    },
  )

  it('migrates an existing tab session to persistent browser storage', async () => {
    sessionStorage.setItem('epharm.learner.tokens', JSON.stringify(tokens))
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const path = String(input)
      if (path === '/api/mobile/auth/me') {
        return json({
          id: 'ph-1',
          name: 'Айжан',
          phone: '+77070000000',
          pharmacyName: 'Ауэзова 134',
          city: 'Алматы',
        })
      }
      if (path === '/api/mobile/training') {
        return json({ total: 0, inProgress: 0, completed: 0, overdue: 0, assignments: [] })
      }
      return json({ message: `Unexpected request: ${path}` }, 500)
    })

    renderPortal()

    expect(await screen.findByRole('heading', { name: 'Айжан' })).toBeInTheDocument()
    expect(JSON.parse(localStorage.getItem('epharm.learner.tokens') || '{}')).toEqual(tokens)
    expect(sessionStorage.getItem('epharm.learner.tokens')).toBeNull()
  })

  it('shows a simple current course card on the pharmacist home page', async () => {
    localStorage.setItem('epharm.learner.tokens', JSON.stringify(tokens))
    const assignment = {
      id: 'assignment-live',
      programName: 'Современная фармация',
      pharmacyName: 'Ауэзова 134',
      city: 'Алматы',
      status: 'in_progress',
      format: 'online',
      progressPct: 25,
      stages: [
        {
          id: 'stage-live',
          title: 'Онлайн-модуль',
          type: 'online_course',
          status: 'in_progress',
          progressPct: 25,
          course: {
            id: 'course-live',
            title: 'Онлайн-модуль',
            lessons: [
              {
                id: 'lesson-video',
                title: 'Вводный видеоурок',
                kind: 'video',
                durationMin: 12,
                progressPct: 50,
                attachments: [],
              },
              {
                id: 'lesson-live',
                title: 'Конференция с экспертом',
                kind: 'live',
                liveStatus: 'scheduled',
                liveStartAt: '2026-10-20T09:00:00Z',
                progressPct: 0,
                attachments: [],
              },
            ],
          },
        },
      ],
    }
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const path = String(input)
      if (path === '/api/mobile/auth/me')
        return json({
          id: 'ph-1',
          name: 'Айжан',
          phone: '+77070000000',
          pharmacyName: 'Ауэзова 134',
          city: 'Алматы',
        })
      if (path === '/api/mobile/training')
        return json({
          total: 1,
          inProgress: 1,
          completed: 0,
          overdue: 0,
          assignments: [assignment],
          cityRanking: { city: 'Алматы', position: 4, total: 27, score: 25 },
        })
      if (path === '/api/mobile/promotions') return json([])
      return json({ message: `Unexpected request: ${path}` }, 500)
    })

    renderPortal('/learn')

    expect(await screen.findByRole('heading', { name: 'Айжан' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Ваши курсы' })).toBeInTheDocument()
    // Grigoriy, 25.11.2026: no «Ваш рейтинг ePharm» on the home page.
    expect(screen.queryByText('Ваш рейтинг ePharm')).not.toBeInTheDocument()
    expect(screen.queryByRole('meter')).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Современная фармация' })).toBeInTheDocument()
    expect(screen.queryByText('Что вы получите')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Продолжить обучение' })).toBeInTheDocument()
    expect(screen.queryByRole('tab')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Каталог' })).not.toBeInTheDocument()
  })

  it('loads personal balance and active recommendations with the pharmacist token', async () => {
    localStorage.setItem('epharm.learner.tokens', JSON.stringify(tokens))
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const path = String(input)
      if (path === '/api/mobile/auth/me')
        return json({
          id: 'ph-1',
          name: 'Айжан',
          phone: '+77070000000',
          pharmacyName: 'Ауэзова 134',
          city: 'Алматы',
        })
      if (path === '/api/mobile/training')
        return json({ total: 0, inProgress: 0, completed: 0, overdue: 0, assignments: [] })
      if (path === '/api/mobile/me/balance') return json({ balanceKzt: 42000, currency: 'KZT' })
      if (path === '/api/mobile/me/recommendations?page=0&size=20') {
        return json({
          items: [
            {
              campaign: { id: 'promo-1', title: 'Пример кампании', dateStart: null, dateEnd: null },
              type: 'substitution',
              trigger: { kind: 'acc_group', value: 'group-1', label: 'Название группы' },
              triggerProducts: [],
              offers: [
                {
                  ruleId: 'rule-1',
                  product: {
                    id: 'product-1',
                    name: 'Товар-пример',
                    brand: 'Производитель',
                    mnn: 'МНН',
                    volume: '20 таблеток',
                    barcodes: ['0123456789012'],
                    ipartId: null,
                    catalogPriceKzt: 2500,
                    catalogUpdatedAt: '2026-10-21T00:00:00Z',
                  },
                  bonusKzt: 500,
                  script: 'Подсказка фармацевту',
                  advantages: ['Преимущество'],
                },
              ],
            },
          ],
          page: 0,
          size: 20,
          total: 1,
          currency: 'KZT',
        })
      }
      return json({ code: 'NOT_FOUND', message: `Unexpected request: ${path}` }, 500)
    })

    renderPortal()

    expect(await screen.findByText('42 000 ₸')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Что предлагать покупателю' })).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Советы' }))
    expect(screen.getByRole('heading', { name: 'Что предлагать покупателю' })).toBeInTheDocument()
    expect(screen.getByText('Пример кампании')).toBeInTheDocument()
    expect(screen.getByText(/Название группы/)).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Товар-пример' })).toBeInTheDocument()
    expect(screen.getByText('Бонус: 500 ₸')).toBeInTheDocument()

    const balanceCall = fetchMock.mock.calls.find(
      ([input]) => String(input) === '/api/mobile/me/balance',
    )
    const recommendationsCall = fetchMock.mock.calls.find(
      ([input]) => String(input) === '/api/mobile/me/recommendations?page=0&size=20',
    )
    expect(new Headers(balanceCall?.[1]?.headers).get('Authorization')).toBe(
      `Bearer ${tokens.accessToken}`,
    )
    expect(new Headers(balanceCall?.[1]?.headers).get('Accept')).toBe('application/json')
    expect(balanceCall?.[1]?.cache).toBe('no-store')
    expect(new Headers(recommendationsCall?.[1]?.headers).get('Authorization')).toBe(
      `Bearer ${tokens.accessToken}`,
    )
  })

  it('refreshes the credited balance on focus without reloading courses or overlapping requests', async () => {
    localStorage.setItem('epharm.learner.tokens', JSON.stringify(tokens))
    let balanceCalls = 0
    let releaseBalance: ((response: Response) => void) | undefined
    const intervals = vi.spyOn(window, 'setInterval')
    const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const path = String(input)
      if (path === '/api/mobile/auth/me')
        return json({ id: 'ph-1', name: 'Айжан', status: 'active' })
      if (path === '/api/mobile/training')
        return json({ total: 0, inProgress: 0, completed: 0, overdue: 0, assignments: [] })
      if (path === '/api/mobile/me/balance') {
        if (++balanceCalls === 1) return json({ balanceKzt: 42000, currency: 'KZT' })
        return new Promise<Response>((resolve) => {
          releaseBalance = resolve
        })
      }
      return json({ items: [], total: 0 })
    })
    const view = renderPortal()
    expect(await screen.findByText('42 000 ₸')).toBeVisible()
    await waitFor(() =>
      expect(intervals.mock.calls.some(([, delay]) => delay === 30_000)).toBe(true),
    )
    fireEvent.focus(window)
    fireEvent.focus(window)
    await waitFor(() => expect(balanceCalls).toBe(2))
    releaseBalance!(json({ balanceKzt: 42400, currency: 'KZT' }))
    expect(await screen.findByText('42 400 ₸')).toBeVisible()
    expect(
      fetchMock.mock.calls.filter(([input]) => String(input) === '/api/mobile/training'),
    ).toHaveLength(1)
    visibility.mockReturnValue('hidden')
    fireEvent.focus(window)
    expect(balanceCalls).toBe(2)
    view.unmount()
    visibility.mockReturnValue('visible')
    fireEvent.focus(window)
    expect(balanceCalls).toBe(2)
  })

  it.each([
    {
      language: 'ru' as const,
      tab: 'Советы',
      and: 'И',
      mnn: 'МНН АСС',
      sameItem: 'Все условия должны совпасть у одного сканируемого товара.',
    },
    {
      language: 'kk' as const,
      tab: 'Кеңестер',
      and: 'ЖӘНЕ',
      mnn: 'АСС ХПА',
      sameItem: 'Барлық шарт бір сканерленген тауарға сәйкес келуі керек.',
    },
  ])(
    'shows every readonly trigger factor as AND for the same scanned item ($language)',
    async ({ language, tab, and, mnn, sameItem }) => {
      useUiStore.setState({ language })
      localStorage.setItem('epharm.learner.tokens', JSON.stringify(tokens))
      const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
        const path = String(input)
        if (path === '/api/mobile/auth/me')
          return json({
            id: 'ph-1',
            name: 'Айжан',
            phone: '+77070000000',
            pharmacyName: 'Аптека',
            city: 'Алматы',
          })
        if (path === '/api/mobile/training')
          return json({ total: 0, inProgress: 0, completed: 0, overdue: 0, assignments: [] })
        if (path === '/api/mobile/promotions') return json([])
        if (path === '/api/mobile/me/balance') return json({ balanceKzt: 42000, currency: 'KZT' })
        if (path === '/api/mobile/me/recommendations?page=0&size=20')
          return json({
            items: [
              {
                campaign: { id: 'compound', title: 'Точное сочетание' },
                type: 'substitution',
                trigger: {
                  kind: 'acc_group',
                  value: 'group-1',
                  label: 'Выбранная группа',
                  conditions: [
                    { kind: 'acc_mnn', value: 'mnn-1', label: 'Выбранное вещество' },
                    { kind: 'acc_subgroup', value: 'subgroup-fallback' },
                  ],
                },
                triggerProducts: [],
                offers: [],
              },
            ],
            page: 0,
            size: 20,
            total: 1,
          })
        return json({ message: `Unexpected request: ${path}` }, 500)
      })
      renderPortal()
      await userEvent.click(await screen.findByRole('button', { name: tab, exact: true }))
      expect(await screen.findByText('Точное сочетание')).toBeInTheDocument()
      expect(screen.getByText(/Выбранная группа/)).toBeInTheDocument()
      expect(screen.getByText(`${and} ${mnn}: Выбранное вещество`)).toBeInTheDocument()
      expect(screen.getByText(/subgroup-fallback/)).toBeInTheDocument()
      expect(screen.getByText(sameItem)).toBeInTheDocument()
      expect(fetchMock.mock.calls.every(([, init]) => !init?.method || init.method === 'GET')).toBe(
        true,
      )
    },
  )

  it('does not disguise a personal API error as a zero balance or empty feed', async () => {
    localStorage.setItem('epharm.learner.tokens', JSON.stringify(tokens))
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const path = String(input)
      if (path === '/api/mobile/auth/me')
        return json({
          id: 'ph-1',
          name: 'Айжан',
          phone: '+77070000000',
          pharmacyName: 'Ауэзова 134',
          city: 'Алматы',
        })
      if (path === '/api/mobile/training')
        return json({ total: 0, inProgress: 0, completed: 0, overdue: 0, assignments: [] })
      if (path === '/api/mobile/me/balance')
        return json({ code: 'SERVICE_UNAVAILABLE', message: 'Ошибка' }, 503)
      if (path === '/api/mobile/me/recommendations?page=0&size=20')
        return json({ items: [], page: 0, size: 20, total: 0, currency: 'KZT' })
      return json({ code: 'NOT_FOUND', message: `Unexpected request: ${path}` }, 500)
    })

    renderPortal()

    expect(await screen.findByText('Баланс временно недоступен')).toBeInTheDocument()
    expect(screen.queryByText('Активных рекомендаций сейчас нет.')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Советы' }))
    expect(screen.getByText('Активных рекомендаций сейчас нет.')).toBeInTheDocument()
    expect(screen.queryByText('0 ₸')).not.toBeInTheDocument()
  })

  it('does not substitute the profile balance when the dedicated balance endpoint is unavailable', async () => {
    localStorage.setItem('epharm.learner.tokens', JSON.stringify(tokens))
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const path = String(input)
      if (path === '/api/mobile/auth/me') {
        return json({
          id: 'ph-1',
          name: 'Айжан',
          phone: '+77070000000',
          pharmacyName: 'Ауэзова 134',
          city: 'Алматы',
          balance: 17_500,
        })
      }
      if (path === '/api/mobile/training')
        return json({ total: 0, inProgress: 0, completed: 0, overdue: 0, assignments: [] })
      if (path === '/api/mobile/me/balance')
        return json({ code: 'NOT_FOUND', message: 'Не найдено' }, 404)
      if (path === '/api/mobile/me/recommendations?page=0&size=20')
        return json({ items: [], page: 0, size: 20, total: 0, currency: 'KZT' })
      return json({ code: 'NOT_FOUND', message: `Unexpected request: ${path}` }, 500)
    })

    renderPortal()

    expect(await screen.findByText('Баланс временно недоступен')).toBeInTheDocument()
    expect(screen.queryByText('17 500 ₸')).not.toBeInTheDocument()
  })

  it('shows a text lesson of the fire-safety course without a borrowed video', async () => {
    localStorage.setItem('epharm.learner.tokens', JSON.stringify(tokens))
    const scrollTo = vi.fn()
    vi.stubGlobal('scrollTo', scrollTo)
    const assignment = {
      id: 'assignment-fire',
      programName: 'Курс обучения «Пожарная безопасность»',
      pharmacyName: 'Ауэзова 134',
      city: 'Алматы',
      status: 'in_progress',
      format: 'online',
      progressPct: 0,
      stages: [
        {
          id: 'stage-fire',
          title: 'Пожарная безопасность',
          type: 'online_course',
          status: 'in_progress',
          progressPct: 0,
          course: {
            id: 'course-fire',
            title: 'Пожарная безопасность в аптеке',
            lessons: [
              {
                id: 'lesson-fire-1',
                title: 'Основы пожарной безопасности',
                description: 'Вводный урок',
                content:
                  '2.1. Торговый зал аптеки\n\nПеред началом смены сотрудник должен проверить:\n\nсвободны ли проходы;\n\nдоступны ли указатели эвакуации.\n\n2.2. Действия при пожаре\n\nСообщите руководителю и вызовите 112.',
                kind: 'text',
                order: 0,
                durationMin: 5,
                progressPct: 0,
                attachments: [],
              },
            ],
          },
        },
      ],
    }
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const path = String(input)
      if (path === '/api/mobile/auth/me')
        return json({
          id: 'ph-1',
          name: 'Айжан',
          phone: '+77070000000',
          pharmacyName: 'Ауэзова 134',
          city: 'Алматы',
        })
      if (path === '/api/mobile/training')
        return json({
          total: 1,
          inProgress: 1,
          completed: 0,
          overdue: 0,
          assignments: [assignment],
        })
      if (path === '/api/mobile/training/assignments/assignment-fire') return json(assignment)
      return json({ message: `Unexpected request: ${path}` }, 500)
    })

    renderPortal('/learn/course/assignment-fire/lesson/lesson-fire-1')

    await screen.findByRole('heading', { name: '2.1. Торговый зал аптеки' })
    expect(screen.queryByLabelText('Видео урока')).not.toBeInTheDocument()
    expect(screen.getByRole('list')).toHaveTextContent('свободны ли проходы')
    expect(screen.getByRole('list')).toHaveTextContent('доступны ли указатели эвакуации.')
    // One part at a time: the next part is not on the screen, and on the first reading
    // «Дальше» waits a few seconds so the part is actually read.
    expect(screen.queryByText('Сообщите руководителю и вызовите 112.')).not.toBeInTheDocument()
    expect(screen.getByText(/Часть 1 из 2/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Читайте ещё \d+ с/ })).toBeDisabled()
    expect(screen.getByRole('button', { name: /Дочитайте урок/ })).toBeDisabled()
    await waitFor(() =>
      expect(scrollTo).toHaveBeenCalledWith({ top: 0, left: 0, behavior: 'auto' }),
    )
  })

  it.each([
    ['+7 747 079 93 53', '+77470799353'],
    ['8 (747) 079-93-53', '+77470799353'],
    ['7470799353', '+77470799353'],
  ])('enters the pharmacist portal with the phone %s and a password', async (typed, phone) => {
    const user = userEvent.setup()
    const pharmacist = {
      id: 'ph-1',
      name: 'Василий',
      phone,
      pharmacyName: 'Аптека',
      city: 'Рудный',
    }
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const path = String(input)
      if (path === '/api/mobile/auth/login') return json({ tokens, pharmacist })
      if (path === '/api/mobile/auth/me') return json(pharmacist)
      if (path === '/api/mobile/training')
        return json({ total: 0, inProgress: 0, completed: 0, overdue: 0, assignments: [] })
      if (path === '/api/mobile/promotions') return json([])
      return json({ message: `Unexpected request: ${path}` }, 500)
    })
    renderPortal()

    await user.type(screen.getByRole('textbox', { name: 'ИИН или номер телефона' }), typed)
    await user.type(screen.getByLabelText('Пароль'), '1234')
    await user.click(screen.getByRole('button', { name: 'Войти' }))

    expect(await screen.findByRole('heading', { name: 'Василий' })).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/mobile/auth/login',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ phone, password: '1234' }),
      }),
    )
    expect(fetchMock.mock.calls.some(([path]) => String(path).includes('/activation/'))).toBe(false)
    expect(JSON.parse(localStorage.getItem('epharm.learner.tokens') || '{}')).toEqual(tokens)
  })

  it.each([
    [
      'ru',
      'Нет пароля? Задайте его в программе ePharm на кассе: значок ePharm у часов, затем «Мой профиль».',
      'ИИН или номер телефона',
      'Войти',
    ],
    [
      'kk',
      'Құпиясөз жоқ па? Оны кассадағы ePharm бағдарламасында орнатыңыз: сағат жанындағы ePharm белгішесі, содан кейін «Мой профиль».',
      'ЖСН немесе телефон нөмірі',
      'Кіру',
    ],
  ] as const)(
    'points to the till for a missing password in %s and offers no SMS activation or reset',
    async (language, hint, field, submit) => {
      useUiStore.setState({ language })
      const fetchMock = vi.spyOn(globalThis, 'fetch')
      renderPortal()

      expect(screen.getByRole('note')).toHaveTextContent(hint)
      expect(screen.getByRole('textbox', { name: field })).toBeInTheDocument()
      expect(screen.getByRole('button', { name: submit })).toBeDisabled()
      for (const removed of [
        /Забыли пароль|ұмыттыңыз/,
        /Получить код|Код алу/,
        /Продолжить|Жалғастыру/,
        /Создать пароль|Құпиясөз жасап/,
        /SMS/,
      ]) {
        expect(screen.queryByRole('button', { name: removed })).not.toBeInTheDocument()
        expect(screen.queryByText(removed)).not.toBeInTheDocument()
      }
      expect(screen.getAllByRole('textbox')).toHaveLength(1)
      expect(fetchMock).not.toHaveBeenCalled()
    },
  )

  it('keeps «Войти» disabled until the login is an IIN or a phone and the password has 4 characters', () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch')
    renderPortal()
    const login = screen.getByRole('textbox', { name: 'ИИН или номер телефона' })
    const password = screen.getByLabelText('Пароль')
    const submit = screen.getByRole('button', { name: 'Войти' })

    fireEvent.change(password, { target: { value: '1234' } })
    for (const incomplete of ['06100750002', '+7 747 079 93 5', '+8 747 079 93 53', 'abc']) {
      fireEvent.change(login, { target: { value: incomplete } })
      expect(submit).toBeDisabled()
    }
    // Letters are dropped as they are typed.
    expect(login).toHaveValue('')
    fireEvent.change(login, { target: { value: '061007 500024' } })
    expect(submit).toBeEnabled()
    fireEvent.change(login, { target: { value: '+7 747 079 93 53' } })
    expect(submit).toBeEnabled()
    fireEvent.change(password, { target: { value: '123' } })
    expect(submit).toBeDisabled()
    fireEvent.submit(submit.closest('form')!)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('shows the server refusal and keeps the typed login and password', async () => {
    const user = userEvent.setup()
    vi.spyOn(globalThis, 'fetch').mockImplementation(async () =>
      json({ code: 'INVALID_CREDENTIALS', message: 'Неверный номер телефона или пароль' }, 401),
    )
    renderPortal()
    const login = screen.getByRole('textbox', { name: 'ИИН или номер телефона' })

    await user.type(login, '87470799353')
    await user.type(screen.getByLabelText('Пароль'), 'Wrong123!')
    await user.click(screen.getByRole('button', { name: 'Войти' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Неверный номер телефона или пароль')
    expect(login).toHaveValue('87470799353')
    expect(screen.getByLabelText('Пароль')).toHaveValue('Wrong123!')
    expect(screen.getByRole('button', { name: 'Войти' })).toBeEnabled()
    expect(localStorage.getItem('epharm.learner.tokens')).toBeNull()
  })

  it('saves the final lesson through the server progress endpoint', async () => {
    localStorage.setItem('epharm.learner.tokens', JSON.stringify(tokens))
    const assignment = {
      id: 'assignment-1',
      programName: 'Безопасная работа',
      pharmacyName: 'Ауэзова 134',
      city: 'Алматы',
      status: 'in_progress',
      format: 'online',
      progressPct: 50,
      startedAt: '2026-09-01T10:00:00Z',
      stages: [
        {
          id: 'stage-1',
          title: 'Основы',
          type: 'online_course',
          status: 'in_progress',
          progressPct: 50,
          course: {
            id: 'course-1',
            title: 'Основы',
            lessons: [
              {
                id: 'lesson-1',
                title: 'Первый',
                order: 0,
                progressPct: 100,
                completedAt: '2026-09-01T10:05:00Z',
                attachments: [],
              },
              { id: 'lesson-2', title: 'Второй', order: 1, progressPct: 0, attachments: [] },
            ],
          },
        },
      ],
    }
    const completedAssignment = {
      ...assignment,
      progressPct: 100,
      stages: [
        {
          ...assignment.stages[0],
          status: 'completed',
          progressPct: 100,
          course: {
            ...assignment.stages[0].course,
            lessons: [
              assignment.stages[0].course.lessons[0],
              {
                ...assignment.stages[0].course.lessons[1],
                progressPct: 100,
                completedAt: '2026-09-01T10:10:00Z',
              },
            ],
          },
        },
      ],
    }
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const path = String(input)
      if (path === '/api/mobile/auth/me') {
        return json({
          id: 'ph-1',
          name: 'Айжан',
          phone: '+77070000000',
          pharmacyName: 'Ауэзова 134',
          city: 'Алматы',
        })
      }
      if (path === '/api/mobile/training/assignments/assignment-1') return json(assignment)
      if (path === '/api/mobile/training' && (!init?.method || init.method === 'GET')) {
        return json({
          total: 1,
          inProgress: 1,
          completed: 0,
          overdue: 0,
          assignments: [assignment],
        })
      }
      if (path.endsWith('/stages/stage-1/lessons/lesson-2') && init?.method === 'PATCH') {
        return json(completedAssignment)
      }
      return json({ message: `Unexpected request: ${path}` }, 500)
    })

    renderPortal('/learn/course/assignment-1/lesson/lesson-2')
    expect(await screen.findByRole('heading', { name: 'Второй' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Завершить курс' }))

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(
        '/api/mobile/training/assignments/assignment-1/stages/stage-1/lessons/lesson-2',
        expect.objectContaining({
          method: 'PATCH',
          body: JSON.stringify({ progressPct: 100, positionSeconds: 0 }),
        }),
      )
    })
  })

  it('says why a watched video is not counted yet, brings it into view, and drops it once the server accepts', async () => {
    localStorage.setItem('epharm.learner.tokens', JSON.stringify(tokens))
    const lesson = {
      id: 'lesson-video',
      title: 'Видеоурок: как пройти курс',
      kind: 'video',
      videoUrl: '/lesson.mp4',
      durationMin: 2,
      order: 0,
      progressPct: 0,
      attachments: [],
    }
    const assignment = {
      id: 'assignment-1',
      programName: 'Быстрый старт',
      pharmacyName: 'Ауэзова 134',
      city: 'Алматы',
      status: 'in_progress',
      format: 'online',
      progressPct: 0,
      startedAt: '2026-11-26T10:00:00Z',
      stages: [
        {
          id: 'stage-1',
          title: 'Курс',
          type: 'online_course',
          status: 'in_progress',
          progressPct: 0,
          course: { id: 'course-1', title: 'Курс', lessons: [lesson] },
        },
      ],
    }
    const counted = {
      ...assignment,
      progressPct: 100,
      stages: [
        {
          ...assignment.stages[0],
          status: 'completed',
          progressPct: 100,
          course: {
            ...assignment.stages[0].course,
            lessons: [{ ...lesson, progressPct: 100, completedAt: '2026-11-26T10:00:08Z' }],
          },
        },
      ],
    }
    const reason = 'Видео засчитается через 99 сек. Подождите и повторите'
    // The first save comes from the player itself, the second from the button: the server refuses both.
    let refusals = 2
    const saves: Array<{ progressPct: number; mediaDurationSeconds?: number }> = []
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const path = String(input)
      if (path === '/api/mobile/auth/me') {
        return json({
          id: 'ph-1',
          name: 'Айжан',
          phone: '+77070000000',
          pharmacyName: 'Ауэзова 134',
          city: 'Алматы',
        })
      }
      if (path === '/api/mobile/training/assignments/assignment-1') return json(assignment)
      if (path === '/api/mobile/training' && (!init?.method || init.method === 'GET')) {
        return json({
          total: 1,
          inProgress: 1,
          completed: 0,
          overdue: 0,
          assignments: [assignment],
        })
      }
      if (path.endsWith('/stages/stage-1/lessons/lesson-video') && init?.method === 'PATCH') {
        saves.push(JSON.parse(String(init.body)))
        if (refusals > 0) {
          refusals -= 1
          return json({ code: 'CONFLICT', message: reason }, 409)
        }
        return json(counted)
      }
      return json({ message: `Unexpected request: ${path}` }, 500)
    })
    const scrollIntoView = vi.fn()
    Object.defineProperty(Element.prototype, 'scrollIntoView', {
      configurable: true,
      value: scrollIntoView,
    })

    try {
      renderPortal('/learn/course/assignment-1/lesson/lesson-video')
      const video = (await screen.findByLabelText('Видео урока')) as HTMLVideoElement
      // The clip is 6 s long and played to the end.
      Object.defineProperty(video, 'duration', { configurable: true, value: 6 })
      Object.defineProperty(video, 'played', {
        configurable: true,
        value: { length: 1, start: () => 0, end: () => 6 },
      })
      video.currentTime = 6
      fireEvent.timeUpdate(video)

      // Watched in full, not counted yet: the page says what is missing, not «watch the video» again.
      const notice = await screen.findByText(reason)
      expect(notice).toHaveAttribute('role', 'alert')
      expect(screen.getByText('Просмотрено 100%')).toBeInTheDocument()
      expect(screen.queryByText(/Досмотрите/)).not.toBeInTheDocument()
      expect(saves).toEqual([{ progressPct: 100, positionSeconds: 6, mediaDurationSeconds: 6 }])

      // The button far below the banner saves again: refused again, and the reason is brought into view.
      await userEvent.click(screen.getByRole('button', { name: 'Завершить курс' }))
      await waitFor(() => expect(saves).toHaveLength(2))
      await waitFor(() =>
        expect(scrollIntoView).toHaveBeenCalledWith({ behavior: 'smooth', block: 'center' }),
      )
      expect(screen.getByText(reason)).toBeInTheDocument()

      // Accepted at last: the old reason does not stay on the page.
      await userEvent.click(screen.getByRole('button', { name: 'Завершить курс' }))
      await waitFor(() => expect(saves).toHaveLength(3))
      await waitFor(() => expect(screen.queryByText(reason)).not.toBeInTheDocument())
    } finally {
      Reflect.deleteProperty(Element.prototype, 'scrollIntoView')
    }
  })

  it.each([true, false])(
    'shows only the score after a quiz attempt and reload (passed: %s)',
    async (passed) => {
      localStorage.setItem('epharm.learner.tokens', JSON.stringify(tokens))
      let attempt = 0
      const score = passed ? 100 : 50
      let releaseOverview!: () => void
      const overviewReady = new Promise<void>((resolve) => {
        releaseOverview = resolve
      })
      const quizLesson = {
        id: 'quiz-1',
        title: 'Итоговый тест',
        kind: 'quiz',
        order: 0,
        required: true,
        progressPct: 0,
        quizPassingScore: 80,
        quizQuestions: [
          { id: 'q-1', prompt: 'Когда принимать препарат?', options: ['До еды', 'После еды'] },
          { id: 'q-2', prompt: 'Сколько таблеток?', options: ['Одну', 'Две'] },
        ],
        attachments: [],
      }
      const assignment = {
        id: 'assignment-quiz',
        programName: 'Тестирование',
        pharmacyName: 'Ауэзова 134',
        city: 'Алматы',
        status: 'in_progress',
        format: 'online',
        progressPct: 0,
        startedAt: '2026-09-01T10:00:00Z',
        stages: [
          {
            id: 'stage-quiz',
            title: 'Проверка знаний',
            type: 'online_course',
            status: 'in_progress',
            progressPct: 0,
            course: {
              id: 'course-quiz',
              title: 'Проверка знаний',
              lessons: [
                quizLesson,
                { ...quizLesson, id: 'quiz-2', title: 'Следующая проверка', order: 1 },
              ],
            },
          },
        ],
      }
      const completedAssignment = {
        ...assignment,
        progressPct: passed ? 50 : 0,
        stages: [
          {
            ...assignment.stages[0],
            status: 'in_progress',
            progressPct: passed ? 50 : 0,
            course: {
              ...assignment.stages[0].course,
              lessons: [
                {
                  ...quizLesson,
                  progressPct: passed ? 100 : 0,
                  quizScore: score,
                  quizAttempts: 1,
                  completedAt: passed ? '2026-09-01T10:10:00Z' : null,
                },
                assignment.stages[0].course.lessons[1],
              ],
            },
          },
        ],
      }
      const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
        const path = String(input)
        if (path === '/api/mobile/auth/me') {
          return json({
            id: 'ph-1',
            name: 'Айжан',
            phone: '+77070000000',
            pharmacyName: 'Ауэзова 134',
            city: 'Алматы',
          })
        }
        if (path === '/api/mobile/training/assignments/assignment-quiz')
          return json(attempt ? completedAssignment : assignment)
        if (path.endsWith('/stages/stage-quiz/lessons/quiz-1/quiz') && init?.method === 'POST') {
          attempt += 1
          return json({
            score,
            passed,
            correctAnswers: passed ? 2 : 1,
            totalQuestions: 2,
            attempt,
            questions: [
              {
                questionId: 'q-1',
                correct: true,
                correctOption: 0,
                explanation: 'Скрытый разбор ответа',
              },
              { questionId: 'q-2', correct: true, correctOption: 1, explanation: '' },
            ],
            assignment: completedAssignment,
          })
        }
        if (path === '/api/mobile/training') {
          if (!passed && attempt === 1) await overviewReady
          return json({
            total: 1,
            inProgress: 0,
            completed: 1,
            overdue: 0,
            assignments: [completedAssignment],
          })
        }
        return json({ message: `Unexpected request: ${path}` }, 500)
      })

      const portal = renderPortal('/learn/course/assignment-quiz/lesson/quiz-1')
      expect(await screen.findByRole('heading', { name: 'Итоговый тест' })).toBeInTheDocument()

      // One question per screen: rules first, then the questions in a shuffled answer order.
      await userEvent.click(screen.getByRole('button', { name: /Начать тест/ }))
      expect(screen.getByRole('button', { name: /Дальше/ })).toBeDisabled()
      await userEvent.click(screen.getByRole('radio', { name: 'До еды' }))
      await userEvent.click(screen.getByRole('button', { name: /Дальше/ }))
      await userEvent.click(screen.getByRole('radio', { name: 'Две' }))
      await userEvent.click(screen.getByRole('button', { name: /К отправке/ }))
      await userEvent.click(screen.getByRole('button', { name: /Отправить ответы/ }))

      await waitFor(() => {
        expect(fetchMock).toHaveBeenCalledWith(
          '/api/mobile/training/assignments/assignment-quiz/stages/stage-quiz/lessons/quiz-1/quiz',
          expect.objectContaining({
            method: 'POST',
            body: JSON.stringify({ answers: { 'q-1': 0, 'q-2': 1 } }),
          }),
        )
      })
      if (!passed) releaseOverview()
      expect(
        await screen.findByText(passed ? 'Тест пройден!' : 'Пока не получилось'),
      ).toBeInTheDocument()
      expect(screen.getByText(new RegExp(`Верно ${passed ? 2 : 1} из 2`))).toBeInTheDocument()
      // Answers are never revealed: no per-question review, only the score.
      expect(screen.queryByText('Скрытый разбор ответа')).not.toBeInTheDocument()
      expect(screen.queryByRole('radio')).not.toBeInTheDocument()
      if (!passed) {
        await userEvent.click(screen.getByRole('button', { name: /Пройти тест снова/ }))
        expect(screen.getByText(/Попытка 2/)).toBeInTheDocument()
        await userEvent.click(screen.getByRole('button', { name: /Начать тест/ }))
        expect(
          screen
            .getAllByRole('radio')
            .every((radio) => radio.getAttribute('aria-checked') === 'false'),
        ).toBe(true)
      } else {
        await userEvent.click(screen.getByRole('button', { name: 'Следующий урок' }))
        expect(
          await screen.findByRole('heading', { name: 'Следующая проверка' }),
        ).toBeInTheDocument()
        expect(screen.queryByText('Тест пройден!')).not.toBeInTheDocument()
        expect(screen.getByRole('button', { name: /Начать тест/ })).toBeInTheDocument()
      }
      portal.unmount()
      renderPortal('/learn/course/assignment-quiz/lesson/quiz-1')
      if (passed) {
        expect(await screen.findByText('Тест пройден')).toBeInTheDocument()
        expect(screen.getByText('Результат: 100%')).toBeInTheDocument()
      } else {
        expect(await screen.findByText(/Попытка 2/)).toBeInTheDocument()
        expect(screen.getByText(/лучший результат 50%/)).toBeInTheDocument()
      }
      expect(screen.queryByRole('radio')).not.toBeInTheDocument()
      expect(
        screen.queryByText(/Правильный ответ|Неправильный ответ|Скрытый разбор/),
      ).not.toBeInTheDocument()
    },
  )

  it('labels a saved best quiz score separately from the total attempt count', async () => {
    localStorage.setItem('epharm.learner.tokens', JSON.stringify(tokens))
    const assignment = {
      id: 'saved-quiz',
      programName: 'Сохранённое обучение',
      status: 'in_progress',
      pharmacyName: 'Аптека',
      city: 'Алматы',
      format: 'online',
      progressPct: 0,
      stages: [
        {
          id: 'stage',
          title: 'Тест',
          type: 'online_course',
          status: 'in_progress',
          progressPct: 0,
          course: {
            id: 'course',
            title: 'Тест',
            lessons: [
              {
                id: 'quiz',
                title: 'Проверка знаний',
                kind: 'quiz',
                required: true,
                progressPct: 0,
                quizScore: 60,
                quizAttempts: 2,
                quizPassingScore: 80,
                quizQuestions: [
                  { id: 'question', prompt: 'Учебный вопрос', options: ['Вариант А', 'Вариант Б'] },
                ],
              },
            ],
          },
        },
      ],
    }
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const path = String(input)
      if (path === '/api/mobile/auth/me') return json({ id: 'ph-1', name: 'Айжан' })
      if (path === '/api/mobile/training/assignments/saved-quiz') return json(assignment)
      if (path === '/api/mobile/training')
        return json({
          total: 1,
          inProgress: 1,
          completed: 0,
          overdue: 0,
          assignments: [assignment],
        })
      return json({ items: [] })
    })
    renderPortal('/learn/course/saved-quiz/lesson/quiz')
    // Two attempts used: the start card offers the third and shows the best score apart from it.
    expect(await screen.findByText(/Попытка 3/)).toBeInTheDocument()
    expect(screen.getByText(/лучший результат 60%/)).toBeInTheDocument()
    expect(screen.queryByRole('radio')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Начать тест/ })).toBeEnabled()
  })

  it('keeps the home page focused on active training and puts certificates in a separate tab', async () => {
    localStorage.setItem('epharm.learner.tokens', JSON.stringify(tokens))
    const active = {
      id: 'assignment-active',
      programName: 'Активный курс',
      pharmacyName: 'Ауэзова 134',
      city: 'Алматы',
      status: 'in_progress',
      format: 'hybrid',
      progressPct: 40,
      startedAt: '2026-09-23T10:00:00Z',
      stages: [],
    }
    const completed = {
      ...active,
      id: 'assignment-completed',
      programName: 'Завершённый курс',
      status: 'completed',
      progressPct: 100,
    }
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const path = String(input)
      if (path === '/api/mobile/auth/me') {
        return json({
          id: 'ph-1',
          name: 'Айжан',
          phone: '+77070000000',
          pharmacyName: 'Ауэзова 134',
          city: 'Алматы',
          balance: 1500,
        })
      }
      if (path === '/api/mobile/training') {
        return json({
          total: 2,
          inProgress: 1,
          completed: 1,
          overdue: 0,
          defaultFormat: 'hybrid',
          assignments: [active, completed],
          upcomingEvents: [
            {
              id: 'event-1',
              title: 'Практикум',
              startsAt: '2026-09-24T11:00:00Z',
              city: 'Алматы',
              address: 'Учебный центр',
              capacity: 20,
              occupied: 12,
              status: 'scheduled',
            },
          ],
          notifications: [
            {
              id: 'notification-1',
              eventType: 'reminder',
              title: 'Напоминание',
              message: 'Курс ждёт завершения',
              read: false,
              scheduledAt: '2026-09-23T11:00:00Z',
            },
          ],
          certificates: [
            {
              id: 'certificate-1',
              number: 'EPH-1',
              assignmentId: 'assignment-completed',
              programName: 'Завершённый курс',
              format: 'online',
              issuedAt: '2026-09-23T11:00:00Z',
              status: 'valid',
              pdfUrl: '/certificate.pdf',
            },
          ],
        })
      }
      if (path === '/api/mobile/promotions') {
        return json([
          {
            id: 'promo-1',
            productId: 'product-1',
            name: 'Ренни апельсин жев таб №24',
            brand: 'Bayer',
            mnn: 'Кальция карбонат + Магния карбонат',
            rxOtc: 'OTC',
            category: 'Лекарственное средство',
            barcode: '4250369504480',
            tiers: [{ minQty: 1, price: 1740, bonus: 100 }],
          },
        ])
      }
      return json({ message: `Unexpected request: ${path}` }, 500)
    })

    renderPortal()

    expect(await screen.findByRole('heading', { name: 'Айжан' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Активный курс' })).toBeInTheDocument()
    expect(screen.queryByText('Завершённый курс')).not.toBeInTheDocument()
    expect(screen.queryByText('Уведомления')).not.toBeInTheDocument()
    expect(screen.queryByText('Ближайшие события')).not.toBeInTheDocument()
    expect(screen.queryByText('Препараты и бонусы')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Каталог' })).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Что предлагать покупателю' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Советы' })).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Сертификаты' }))
    expect(await screen.findByRole('link', { name: /Завершённый курс/ })).toHaveAttribute(
      'href',
      '/certificate.pdf',
    )
  })

  it('separates active courses from history, preserves actual scores and exposes no bank intake', async () => {
    localStorage.setItem('epharm.learner.tokens', JSON.stringify(tokens))
    const active = {
      id: 'active',
      programName: 'Продолжаем обучение',
      status: 'in_progress',
      pharmacyName: 'Аптека',
      city: 'Алматы',
      format: 'online',
      progressPct: 40,
      stages: [
        {
          id: 'stage',
          title: 'Материалы',
          status: 'in_progress',
          type: 'online_course',
          progressPct: 40,
          course: {
            id: 'course',
            title: 'Материалы',
            lessons: [
              { id: 'lesson', title: 'Практический пример', kind: 'text', progressPct: 0 },
              { id: 'quiz', title: 'Проверка знаний', kind: 'quiz', progressPct: 0 },
            ],
          },
        },
      ],
    }
    const completed = {
      ...active,
      id: 'completed',
      programName: 'Завершено с оценкой',
      status: 'completed',
      progressPct: 100,
      score: null,
      completedAt: '2026-10-29T10:00:00Z',
      certificate: { id: 'cert', number: 'EPH-80', score: 80, pdfUrl: '/certificate-80.pdf' },
    }
    const unscored = {
      ...completed,
      id: 'unscored',
      programName: 'Без аттестации',
      score: null,
      certificate: null,
    }
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const path = String(input)
      if (path === '/api/mobile/auth/me')
        return json({ id: 'ph-1', name: 'Айжан', pharmacyName: 'Аптека', city: 'Алматы' })
      if (path === '/api/mobile/training')
        return json({
          total: 3,
          inProgress: 1,
          completed: 2,
          overdue: 0,
          assignments: [active, completed, unscored],
        })
      if (path === '/api/mobile/me/balance') return json({ balanceKzt: 1500 })
      return json({ items: [] })
    })
    renderPortal('/learn/training')
    expect(await screen.findByRole('heading', { name: active.programName })).toBeInTheDocument()
    expect(screen.getByText('0 из 2 уроков')).toBeInTheDocument()
    expect(screen.getByText('Дальше: Практический пример')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: completed.programName })).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'История обучения 2' }))
    expect(screen.getByRole('button', { name: 'История обучения 2' })).toHaveAttribute(
      'aria-pressed',
      'true',
    )
    expect(screen.queryByRole('heading', { name: active.programName })).not.toBeInTheDocument()
    const scoredCard = screen
      .getByRole('heading', { name: completed.programName })
      .closest('article')!
    expect(within(scoredCard).getByText('Результат: 80%')).toBeInTheDocument()
    expect(within(scoredCard).getByRole('link', { name: 'Скачать сертификат' })).toHaveAttribute(
      'href',
      '/certificate-80.pdf',
    )
    expect(
      within(scoredCard).getByRole('button', { name: 'Повторить материалы' }),
    ).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Мои курсы 1' }))
    expect(screen.getByRole('heading', { name: active.programName })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Профиль' }))
    const bank = await screen.findByRole('region', { name: 'Банковская карта' })
    expect(within(bank).getByText('Не подключена')).toBeInTheDocument()
    await userEvent.click(within(bank).getByRole('button', { name: 'Как подключить карту' }))
    expect(within(bank).getByRole('status')).toHaveTextContent('реквизиты не запрашиваются')
    expect(bank.querySelector('input, textarea, form')).toBeNull()
    expect(fetchMock.mock.calls.every(([, init]) => !init?.method || init.method === 'GET')).toBe(
      true,
    )
  })

  it('selects an offline event through the same mobile training endpoint', async () => {
    localStorage.setItem('epharm.learner.tokens', JSON.stringify(tokens))
    const trainingEvent = {
      id: 'event-1',
      title: 'Практикум',
      startsAt: '2026-09-24T11:00:00Z',
      city: 'Алматы',
      address: 'Учебный центр',
      capacity: 20,
      occupied: 12,
      status: 'scheduled',
    }
    const assignment = {
      id: 'assignment-1',
      programName: 'Гибридный курс',
      pharmacyName: 'Ауэзова 134',
      city: 'Алматы',
      status: 'waiting_event_selection',
      format: 'hybrid',
      progressPct: 0,
      startedAt: '2026-09-23T10:00:00Z',
      event: null,
      stages: [],
    }
    const selectedAssignment = { ...assignment, status: 'in_progress', event: trainingEvent }
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const path = String(input)
      if (path === '/api/mobile/auth/me')
        return json({
          id: 'ph-1',
          name: 'Айжан',
          phone: '+77070000000',
          pharmacyName: 'Ауэзова 134',
          city: 'Алматы',
        })
      if (
        path === '/api/mobile/training/assignments/assignment-1' &&
        (!init?.method || init.method === 'GET')
      )
        return json(assignment)
      if (
        path === '/api/mobile/training/assignments/assignment-1/events' &&
        (!init?.method || init.method === 'GET')
      )
        return json([trainingEvent])
      if (
        path === '/api/mobile/training/assignments/assignment-1/events/event-1' &&
        init?.method === 'POST'
      )
        return json(selectedAssignment)
      if (path === '/api/mobile/training')
        return json({
          total: 1,
          inProgress: 1,
          completed: 0,
          overdue: 0,
          assignments: [selectedAssignment],
        })
      return json({ message: `Unexpected request: ${path}` }, 500)
    })

    renderPortal('/learn/course/assignment-1')
    await userEvent.click(await screen.findByRole('button', { name: 'Выбрать мероприятие' }))

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(
        '/api/mobile/training/assignments/assignment-1/events/event-1',
        expect.objectContaining({ method: 'POST' }),
      )
    })
    expect(await screen.findByText('Отметить посещение по QR')).toBeInTheDocument()
  })

  it('refreshes an expired access token once for concurrent portal requests', async () => {
    localStorage.setItem('epharm.learner.tokens', JSON.stringify(tokens))
    const refreshedTokens = {
      accessToken: 'fresh-access-token',
      refreshToken: 'fresh-refresh-token',
    }
    let refreshCalls = 0
    const protectedCalls: Array<{ path: string; authorization: string | null }> = []
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const path = String(input)
      const authorization = new Headers(init?.headers).get('Authorization')

      if (path === '/api/mobile/auth/refresh') {
        refreshCalls += 1
        expect(init?.method).toBe('POST')
        expect(JSON.parse(String(init?.body))).toEqual({ refreshToken: tokens.refreshToken })
        return json({ tokens: refreshedTokens })
      }

      if (path === '/api/mobile/auth/me' || path === '/api/mobile/training') {
        protectedCalls.push({ path, authorization })
        if (authorization === `Bearer ${tokens.accessToken}`) {
          return json({ message: 'Токен истёк' }, 401)
        }
        expect(authorization).toBe(`Bearer ${refreshedTokens.accessToken}`)
        if (path === '/api/mobile/auth/me') {
          return json({
            id: 'ph-1',
            name: 'Айжан',
            phone: '+77070000000',
            pharmacyName: 'Ауэзова 134',
            city: 'Алматы',
          })
        }
        return json({ total: 0, inProgress: 0, completed: 0, overdue: 0, assignments: [] })
      }

      return json({ message: `Unexpected request: ${path}` }, 500)
    })

    renderPortal()

    expect(await screen.findByRole('heading', { name: 'Айжан' })).toBeInTheDocument()
    expect(refreshCalls).toBe(1)
    expect(JSON.parse(localStorage.getItem('epharm.learner.tokens') || '{}')).toEqual(
      refreshedTokens,
    )
    expect(protectedCalls).toEqual(
      expect.arrayContaining([
        { path: '/api/mobile/auth/me', authorization: `Bearer ${tokens.accessToken}` },
        { path: '/api/mobile/training', authorization: `Bearer ${tokens.accessToken}` },
        { path: '/api/mobile/auth/me', authorization: `Bearer ${refreshedTokens.accessToken}` },
        { path: '/api/mobile/training', authorization: `Bearer ${refreshedTokens.accessToken}` },
      ]),
    )
    expect(fetchMock).toHaveBeenCalledTimes(7)
  })
})
