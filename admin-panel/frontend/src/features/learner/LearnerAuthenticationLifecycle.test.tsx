import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { MemoryRouter } from 'react-router-dom'
import { useUiStore } from '@/app/store'
import LearnerTrainingPage from './LearnerTrainingPage'

const IIN = '000000000001'
const PHONE = '+7 701 000 00 01'
const PASSWORD = 'SyntheticPassword123!'
const LOGIN_FIELD = { name: 'ИИН или номер телефона' }

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
  useUiStore.setState({ language: 'ru' })
  vi.stubGlobal('scrollTo', vi.fn())
})
afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('learner authentication request lifetime', () => {
  it('keeps an existing active learner signed in without a phone or assigned pharmacy', async () => {
    const user = userEvent.setup()
    const tokens = {
      accessToken: 'synthetic-active-access',
      refreshToken: 'synthetic-active-refresh',
    }
    const pharmacist = {
      id: 'synthetic-active-unassigned',
      name: 'Synthetic Active Learner',
      status: 'active',
      phone: '',
      pharmacyId: null,
      pharmacyName: '',
      city: '',
    }
    let finishLogin!: (response: Response) => void
    let loginSignal: AbortSignal | null | undefined
    const pendingLogin = new Promise<Response>((resolve) => {
      finishLogin = resolve
    })
    const calls: Array<{ path: string; init?: RequestInit }> = []
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const path = String(input)
      calls.push({ path, init })
      if (path === '/api/mobile/auth/login') {
        loginSignal = init?.signal
        return pendingLogin
      }
      if (path === '/api/mobile/auth/me') return json(pharmacist)
      if (path === '/api/mobile/training')
        return json({ total: 0, inProgress: 0, completed: 0, overdue: 0, assignments: [] })
      if (path === '/api/mobile/me/balance') return json({ balanceKzt: 0, currency: 'KZT' })
      if (path.startsWith('/api/mobile/me/recommendations?'))
        return json({ items: [], page: 0, size: 20, total: 0, currency: 'KZT' })
      return json({ message: `Unexpected request: ${path}` }, 500)
    })
    render(
      <MemoryRouter initialEntries={['/learn']}>
        <LearnerTrainingPage />
      </MemoryRouter>,
    )
    await user.type(screen.getByRole('textbox', LOGIN_FIELD), IIN)
    await user.type(screen.getByLabelText('Пароль'), PASSWORD)
    await user.click(screen.getByRole('button', { name: 'Войти' }))
    expect(loginSignal?.aborted).toBe(false)
    expect(localStorage.getItem('epharm.learner.tokens')).toBeNull()

    await act(async () => {
      finishLogin(json({ tokens, pharmacist }))
      await pendingLogin
    })
    expect(await screen.findByRole('heading', { name: pharmacist.name })).toBeVisible()
    await waitFor(() => expect(screen.queryByText('Загружаем обучение…')).not.toBeInTheDocument())
    for (const path of ['/api/mobile/auth/me', '/api/mobile/training']) {
      const call = calls.find((entry) => entry.path === path)
      expect(call).toBeDefined()
      expect(new Headers(call?.init?.headers).get('Authorization')).toBe(
        `Bearer ${tokens.accessToken}`,
      )
    }
    expect(localStorage.getItem('epharm.learner.tokens')).toBe(JSON.stringify(tokens))
    expect(sessionStorage.getItem('epharm.learner.tokens')).toBeNull()

    await user.click(screen.getByRole('button', { name: 'Профиль', exact: true }))
    expect(await screen.findByRole('heading', { name: 'Профиль', exact: true })).toBeVisible()
    expect(screen.getByText('Телефон', { exact: true }).closest('div')).toHaveTextContent(
      'Не указан',
    )
    expect(screen.getByText('Аптека', { exact: true }).closest('div')).toHaveTextContent(
      'Не назначена',
    )
    expect(screen.queryByRole('textbox', LOGIN_FIELD)).not.toBeInTheDocument()
    expect(
      screen.queryByText('Вход выполнен. Аккаунт ожидает подтверждения HQ.'),
    ).not.toBeInTheDocument()
    expect(calls.filter(({ path }) => path === '/api/mobile/auth/login')).toHaveLength(1)
    expect(calls.some(({ path }) => /\/auth\/(activation|password-reset)/.test(path))).toBe(false)
    expect(localStorage.getItem('epharm.learner.tokens')).toBe(JSON.stringify(tokens))
  })

  it.each([
    ['IIN', IIN, '000000000002'],
    ['phone', PHONE, '8 701 000 00 02'],
  ] as const)(
    'ignores a late %s sign-in response after the learner changes the login',
    async (_kind, first, second) => {
      const user = userEvent.setup()
      let finish!: (response: Response) => void
      let pendingSignal: AbortSignal | null | undefined
      const pending = new Promise<Response>((resolve) => {
        finish = resolve
      })
      const calls: string[] = []
      vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
        const path = String(input)
        calls.push(path)
        if (path === '/api/mobile/auth/login') {
          pendingSignal = init?.signal
          return pending
        }
        return json({ items: [] })
      })
      render(
        <MemoryRouter initialEntries={['/learn']}>
          <LearnerTrainingPage />
        </MemoryRouter>,
      )
      await user.type(screen.getByRole('textbox', LOGIN_FIELD), first)
      await user.type(screen.getByLabelText('Пароль'), PASSWORD)
      await user.click(screen.getByRole('button', { name: 'Войти' }))
      await waitFor(() => expect(calls).toContain('/api/mobile/auth/login'))
      fireEvent.change(screen.getByRole('textbox', LOGIN_FIELD), { target: { value: second } })
      await act(async () => {
        finish(
          json({
            tokens: { accessToken: 'old-access', refreshToken: 'old-refresh' },
            pharmacist: { id: 'old-person', name: 'Old synthetic person' },
          }),
        )
        await pending
      })

      expect(screen.getByRole('textbox', LOGIN_FIELD)).toHaveValue(second)
      // The typed password stays for the corrected login; nothing was signed in.
      expect(screen.getByLabelText('Пароль')).toHaveValue(PASSWORD)
      expect(screen.getByRole('button', { name: 'Войти' })).toBeEnabled()
      expect(localStorage.getItem('epharm.learner.tokens')).toBeNull()
      expect(sessionStorage.getItem('epharm.learner.tokens')).toBeNull()
      expect(pendingSignal?.aborted).toBe(true)
      expect(calls.filter((path) => path === '/api/mobile/auth/login')).toHaveLength(1)
    },
  )

  it.each([
    ['IIN', IIN],
    ['phone', PHONE],
  ] as const)('ignores a successful %s sign-in response after unmount', async (_kind, login) => {
    const user = userEvent.setup()
    let finish!: (response: Response) => void
    let pendingSignal: AbortSignal | null | undefined
    const pending = new Promise<Response>((resolve) => {
      finish = resolve
    })
    const calls: string[] = []
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const path = String(input)
      calls.push(path)
      // Intentionally ignore cancellation: the generation guard must also reject a late success.
      if (path === '/api/mobile/auth/login') {
        pendingSignal = init?.signal
        return pending
      }
      return json({ items: [] })
    })
    const { unmount } = render(
      <MemoryRouter initialEntries={['/learn']}>
        <LearnerTrainingPage />
      </MemoryRouter>,
    )
    await user.type(screen.getByRole('textbox', LOGIN_FIELD), login)
    await user.type(screen.getByLabelText('Пароль'), PASSWORD)
    await user.click(screen.getByRole('button', { name: 'Войти' }))
    await waitFor(() => expect(calls).toContain('/api/mobile/auth/login'))
    unmount()
    expect(pendingSignal?.aborted).toBe(true)
    const callsAtUnmount = [...calls]
    await act(async () => {
      finish(
        json({
          tokens: { accessToken: 'old-access', refreshToken: 'old-refresh' },
          pharmacist: { id: 'old-person', name: 'Old synthetic person' },
        }),
      )
      await pending
    })
    expect(localStorage.getItem('epharm.learner.tokens')).toBeNull()
    expect(sessionStorage.getItem('epharm.learner.tokens')).toBeNull()
    expect(calls).toEqual(callsAtUnmount)
    expect(calls.filter((path) => path === '/api/mobile/auth/login')).toHaveLength(1)
  })
})
