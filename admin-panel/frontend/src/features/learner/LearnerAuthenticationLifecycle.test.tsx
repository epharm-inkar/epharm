import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { MemoryRouter } from 'react-router-dom'
import { useUiStore } from '@/app/store'
import LearnerTrainingPage from './LearnerTrainingPage'

const IIN = '000000000001'
const OTHER_IIN = '000000000002'
const PASSWORD = 'SyntheticPassword123!'

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
  useUiStore.setState({ language: 'ru' })
  vi.stubGlobal('scrollTo', vi.fn())
})
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals() })

describe('learner authentication request lifetime', () => {
  it.each(['status', 'login', 'request', 'verify', 'password'] as const)(
    'ignores a late %s response after the learner changes IIN', async (step) => {
      const user = userEvent.setup()
      const paths = {
        status: '/api/mobile/auth/activation/status',
        login: '/api/mobile/auth/login',
        request: '/api/mobile/auth/activation/sms/request',
        verify: '/api/mobile/auth/activation/sms/verify',
        password: '/api/mobile/auth/activation/password',
      }
      let finish!: (response: Response) => void
      let pendingSignal: AbortSignal | null | undefined
      const pending = new Promise<Response>((resolve) => { finish = resolve })
      const calls: string[] = []
      vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
        const path = String(input)
        calls.push(path)
        if (path === paths[step]) { pendingSignal = init?.signal; return pending }
        if (path === paths.status) return json({ passwordSet: step === 'login', phoneMasked: '' })
        if (path === paths.request) return json({ phoneMasked: '+7 (***) ***-**-01' })
        if (path === paths.verify) return json({ verified: true })
        if (path === '/api/mobile/auth/me') return json({ id: 'old-person', name: 'Old synthetic person' })
        if (path === '/api/mobile/training') return json({ total: 0, assignments: [] })
        return json({ items: [] })
      })
      render(<MemoryRouter initialEntries={['/learn']}><LearnerTrainingPage /></MemoryRouter>)
      await user.type(screen.getByRole('textbox', { name: 'ИИН' }), IIN)
      await user.click(screen.getByRole('button', { name: 'Продолжить' }))
      if (step === 'login') {
        await user.type(await screen.findByLabelText('Пароль'), PASSWORD)
        await user.click(screen.getByRole('button', { name: 'Войти' }))
      } else if (step !== 'status') {
        await user.type(await screen.findByRole('textbox', { name: 'Номер телефона' }), '7000000001')
        await user.click(screen.getByRole('button', { name: 'Получить код' }))
        if (step === 'verify' || step === 'password') {
          await user.type(await screen.findByRole('textbox', { name: 'Код из SMS' }), '1234')
          await user.click(screen.getByRole('button', { name: 'Подтвердить код' }))
        }
        if (step === 'password') {
          await user.type(await screen.findByLabelText('Новый пароль', { exact: true }), PASSWORD)
          await user.type(screen.getByLabelText('Повторите пароль', { exact: true }), PASSWORD)
          await user.click(screen.getByRole('button', { name: 'Создать пароль и войти' }))
        }
      }
      await waitFor(() => expect(calls).toContain(paths[step]))
      if (step !== 'status') await user.click(screen.getByRole('button', { name: 'Изменить' }))
      fireEvent.change(screen.getByRole('textbox', { name: 'ИИН' }), { target: { value: OTHER_IIN } })
      const response = step === 'status' ? { passwordSet: true, phoneMasked: '' }
        : step === 'login' || step === 'password'
          ? { tokens: { accessToken: 'old-access', refreshToken: 'old-refresh' }, pharmacist: { id: 'old-person', name: 'Old synthetic person' } }
          : { verified: true, phoneMasked: '+7 (***) ***-**-01' }
      await act(async () => { finish(json(response)); await pending })

      expect(screen.getByRole('textbox', { name: 'ИИН' })).toHaveValue('000000 000002')
      expect(screen.getByRole('button', { name: 'Продолжить' })).toBeEnabled()
      expect(localStorage.getItem('epharm.learner.tokens')).toBeNull()
      expect(sessionStorage.getItem('epharm.learner.tokens')).toBeNull()
      expect(pendingSignal?.aborted).toBe(true)
      expect(calls.filter((path) => path === paths[step])).toHaveLength(1)
    },
  )

  it.each(['login', 'password'] as const)('ignores a successful %s response after unmount', async (step) => {
    const user = userEvent.setup()
    const pathToHold = step === 'login' ? '/api/mobile/auth/login' : '/api/mobile/auth/activation/password'
    let finish!: (response: Response) => void
    let pendingSignal: AbortSignal | null | undefined
    const pending = new Promise<Response>((resolve) => { finish = resolve })
    const calls: string[] = []
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const path = String(input)
      calls.push(path)
      // Intentionally ignore cancellation: the generation guard must also reject a late success.
      if (path === pathToHold) { pendingSignal = init?.signal; return pending }
      if (path.endsWith('/status')) return json({ passwordSet: step === 'login', phoneMasked: '' })
      if (path.endsWith('/sms/request')) return json({ phoneMasked: '+7 (***) ***-**-01' })
      if (path.endsWith('/sms/verify')) return json({ verified: true })
      return json({ items: [] })
    })
    const { unmount } = render(<MemoryRouter initialEntries={['/learn']}><LearnerTrainingPage /></MemoryRouter>)
    await user.type(screen.getByRole('textbox', { name: 'ИИН' }), IIN)
    await user.click(screen.getByRole('button', { name: 'Продолжить' }))
    if (step === 'login') {
      await user.type(await screen.findByLabelText('Пароль'), PASSWORD)
      await user.click(screen.getByRole('button', { name: 'Войти' }))
    } else {
      await user.type(await screen.findByRole('textbox', { name: 'Номер телефона' }), '7000000001')
      await user.click(screen.getByRole('button', { name: 'Получить код' }))
      await user.type(await screen.findByRole('textbox', { name: 'Код из SMS' }), '1234')
      await user.click(screen.getByRole('button', { name: 'Подтвердить код' }))
      await user.type(await screen.findByLabelText('Новый пароль', { exact: true }), PASSWORD)
      await user.type(screen.getByLabelText('Повторите пароль', { exact: true }), PASSWORD)
      await user.click(screen.getByRole('button', { name: 'Создать пароль и войти' }))
    }
    await waitFor(() => expect(calls).toContain(pathToHold))
    unmount()
    expect(pendingSignal?.aborted).toBe(true)
    const callsAtUnmount = [...calls]
    await act(async () => {
      finish(json({
        tokens: { accessToken: 'old-access', refreshToken: 'old-refresh' },
        pharmacist: { id: 'old-person', name: 'Old synthetic person' },
      }))
      await pending
    })
    expect(localStorage.getItem('epharm.learner.tokens')).toBeNull()
    expect(sessionStorage.getItem('epharm.learner.tokens')).toBeNull()
    expect(calls).toEqual(callsAtUnmount)
    expect(calls.filter((path) => path === pathToHold)).toHaveLength(1)
  })

  it.each(['request', 'verify'] as const)('ignores an old activation %s after changing the phone', async (step) => {
    const user = userEvent.setup()
    let finish!: (response: Response) => void
    let pendingSignal: AbortSignal | null | undefined
    const pending = new Promise<Response>((resolve) => { finish = resolve })
    const pathToHold = `/api/mobile/auth/activation/sms/${step}`
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const path = String(input)
      if (path === pathToHold) { pendingSignal = init?.signal; return pending }
      if (path.endsWith('/status')) return json({ passwordSet: false, phoneMasked: '' })
      return json({ phoneMasked: '+7 (***) ***-**-01' })
    })
    render(<MemoryRouter initialEntries={['/learn']}><LearnerTrainingPage /></MemoryRouter>)
    await user.type(screen.getByRole('textbox', { name: 'ИИН' }), IIN)
    await user.click(screen.getByRole('button', { name: 'Продолжить' }))
    await user.type(await screen.findByRole('textbox', { name: 'Номер телефона' }), '7000000001')
    await user.click(screen.getByRole('button', { name: 'Получить код' }))
    if (step === 'verify') {
      await user.type(await screen.findByRole('textbox', { name: 'Код из SMS' }), '1234')
      await user.click(screen.getByRole('button', { name: 'Подтвердить код' }))
      await user.click(screen.getByRole('button', { name: 'Изменить номер' }))
    }
    fireEvent.change(screen.getByRole('textbox', { name: 'Номер телефона' }), { target: { value: '7000000002' } })
    await act(async () => { finish(json({ verified: true, phoneMasked: '+7 (***) ***-**-01' })); await pending })
    expect(screen.getByRole('textbox', { name: 'Номер телефона' })).toHaveValue('7000000002')
    expect(screen.getByRole('button', { name: 'Получить код' })).toBeEnabled()
    expect(screen.queryByLabelText('Новый пароль', { exact: true })).not.toBeInTheDocument()
    expect(pendingSignal?.aborted).toBe(true)
  })
})
