import { act, cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useUiStore } from '@/app/store'
import LearnerTrainingPage from './LearnerTrainingPage'

const oldTokens = { accessToken: 'synthetic-old-access', refreshToken: 'synthetic-old-refresh' }
const newTokens = { accessToken: 'synthetic-new-access', refreshToken: 'synthetic-new-refresh' }
const assignment = {
  id: 'old-assignment',
  programName: 'Old private course',
  pharmacyName: 'Synthetic pharmacy',
  city: '',
  status: 'not_started',
  format: 'online',
  progressPct: 0,
  stages: [],
}
const startPath = '/api/mobile/training/assignments/old-assignment/start'
const emptyOverview = { total: 0, inProgress: 0, completed: 0, overdue: 0, assignments: [] }

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}
function deferred() {
  let resolve!: (response: Response) => void
  const promise = new Promise<Response>((done) => {
    resolve = done
  })
  return { promise, resolve }
}
function fixture(refreshPending = false) {
  const mutation = deferred()
  const refresh = deferred()
  const calls: Array<{ path: string; authorization: string | null }> = []
  const fetch = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const path = String(input)
    const authorization = new Headers(init?.headers).get('Authorization')
    calls.push({ path, authorization })
    if (path === startPath) return mutation.promise
    if (path === '/api/mobile/auth/refresh')
      return refreshPending ? refresh.promise : json({ tokens: oldTokens })
    if (path === '/api/mobile/auth/login')
      return json({
        tokens: newTokens,
        pharmacist: { id: 'new', name: 'New learner', status: 'active' },
      })
    if (path === '/api/mobile/auth/logout') return json({})
    if (path === '/api/mobile/auth/me')
      return json({
        id: authorization === `Bearer ${newTokens.accessToken}` ? 'new' : 'old',
        name: authorization === `Bearer ${newTokens.accessToken}` ? 'New learner' : 'Old learner',
        status: 'active',
        city: '',
        pharmacyName: '',
      })
    if (path === '/api/mobile/training') return json(emptyOverview)
    if (path === '/api/mobile/training/assignments/old-assignment') return json(assignment)
    if (path === '/api/mobile/me/balance') return json({ balanceKzt: 0, currency: 'KZT' })
    if (path.startsWith('/api/mobile/me/recommendations'))
      return json({ items: [], page: 0, size: 20, total: 0, currency: 'KZT' })
    return json({ message: `Unexpected ${path}` }, 500)
  })
  const view = render(
    <MemoryRouter initialEntries={['/learn/course/old-assignment']}>
      <Routes>
        <Route path="/learn" element={<LearnerTrainingPage />} />
        <Route path="/learn/course/:assignmentId" element={<LearnerTrainingPage />} />
      </Routes>
    </MemoryRouter>,
  )
  return { ...view, mutation, refresh, calls, fetch }
}

beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
  localStorage.setItem('epharm.learner.tokens', JSON.stringify(oldTokens))
  useUiStore.setState({ language: 'ru' })
  vi.stubGlobal('scrollTo', vi.fn())
})
afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

async function start(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole('button', { name: 'Начать курс' }))
}
async function changeAccount(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('button', { name: 'Выйти', exact: true }))
  await user.type(
    await screen.findByRole('textbox', { name: 'ИИН или номер телефона' }),
    '000000000002',
  )
  await user.type(screen.getByLabelText('Пароль'), 'SyntheticPassword123!')
  await user.click(screen.getByRole('button', { name: 'Войти', exact: true }))
  await screen.findByRole('heading', { name: 'New learner' })
  await waitFor(() => expect(screen.queryByText('Загружаем обучение…')).not.toBeInTheDocument())
}

describe('authenticated learner mutation lifetime', () => {
  it.each([200, 401, 500])(
    'ignores late mutation status %s after logout and login as another learner',
    async (status) => {
      const user = userEvent.setup()
      const f = fixture()
      await start(user)
      await changeAccount(user)
      const boundary = f.calls.length
      await act(async () => {
        f.mutation.resolve(
          json(
            status === 200
              ? { ...assignment, status: 'in_progress' }
              : { message: 'Old private failure' },
            status,
          ),
        )
        await f.mutation.promise
      })
      expect(screen.getByRole('heading', { name: 'New learner' })).toBeVisible()
      expect(screen.queryByText('Old private course')).not.toBeInTheDocument()
      expect(screen.queryByText('Old private failure')).not.toBeInTheDocument()
      expect(localStorage.getItem('epharm.learner.tokens')).toBe(JSON.stringify(newTokens))
      expect(
        f.calls
          .slice(boundary)
          .filter(
            (call) =>
              call.authorization === `Bearer ${oldTokens.accessToken}` ||
              call.path === '/api/mobile/auth/refresh',
          ),
      ).toEqual([])
    },
  )

  it.each([200, 401, 500])(
    'does not follow late mutation status %s with requests after unmount',
    async (status) => {
      const user = userEvent.setup()
      const f = fixture()
      await start(user)
      f.unmount()
      const boundary = f.calls.length
      await act(async () => {
        f.mutation.resolve(
          json(status === 200 ? assignment : { message: 'Old private failure' }, status),
        )
        await f.mutation.promise
      })
      expect(f.calls.slice(boundary)).toEqual([])
      expect(localStorage.getItem('epharm.learner.tokens')).toBe(JSON.stringify(oldTokens))
    },
  )

  it.each(['account', 'unmount'])(
    'does not replay a mutation when refresh finishes after %s boundary',
    async (boundary) => {
      const user = userEvent.setup()
      const f = fixture(true)
      await start(user)
      await act(async () => {
        f.mutation.resolve(json({}, 401))
        await f.mutation.promise
      })
      await waitFor(() =>
        expect(f.calls.some((call) => call.path === '/api/mobile/auth/refresh')).toBe(true),
      )
      if (boundary === 'account') await changeAccount(user)
      else f.unmount()
      const count = f.calls.length
      await act(async () => {
        f.refresh.resolve(
          json({ tokens: { accessToken: 'stale-refreshed', refreshToken: 'stale-rotated' } }),
        )
        await f.refresh.promise
      })
      expect(f.calls.slice(count)).toEqual([])
      expect(localStorage.getItem('epharm.learner.tokens')).toBe(
        JSON.stringify(boundary === 'account' ? newTokens : oldTokens),
      )
    },
  )

  it('still displays a real mutation error for the current learner', async () => {
    const user = userEvent.setup()
    const f = fixture()
    await start(user)
    await act(async () => {
      f.mutation.resolve(json({ message: 'Current operation failed' }, 500))
      await f.mutation.promise
    })
    expect(await screen.findByText('Current operation failed')).toBeVisible()
    expect(screen.getByRole('button', { name: 'Начать курс' })).toBeEnabled()
  })
})
