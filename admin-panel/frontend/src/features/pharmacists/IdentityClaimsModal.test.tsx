import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ToastHost } from '@/ui'
import { useUiStore } from '@/app/store'
import type { IdentityClaimDto } from '@/lib/api-types'
import { IdentityClaimsModal } from './IdentityClaimsModal'

const hooks = vi.hoisted(() => ({
  useIdentityClaims: vi.fn(),
  useConfirmIdentityClaim: vi.fn(),
  useRejectIdentityClaim: vi.fn(),
}))
vi.mock('@/lib/queries/identityClaims', () => hooks)

const confirmClaim = vi.fn()
const rejectClaim = vi.fn()
const retry = vi.fn()
const originalLanguage = useUiStore.getState().language

function claim(over: Partial<IdentityClaimDto> = {}): IdentityClaimDto {
  return {
    id: 'c1',
    kind: 'person',
    status: 'pending',
    pharmacyId: 'ph_1',
    pharmacyName: 'Бузурбаева 23',
    standardNUserId: '18',
    standardNUserName: 'Бузурбаева Л',
    iin: '900115300013',
    fullName: 'Бузурбаева Лаура Ермековна',
    pharmacistId: null,
    pharmacistName: null,
    conflicts: [],
    directoryIin: null,
    decidedByName: null,
    decidedAt: null,
    decisionReason: null,
    createdAt: '2026-11-26T10:00:00Z',
    ...over,
  }
}

function claims(rows: IdentityClaimDto[], extra: Record<string, unknown> = {}) {
  hooks.useIdentityClaims.mockReturnValue({
    data: rows,
    isLoading: false,
    isError: false,
    isFetching: false,
    refetch: retry,
    ...extra,
  })
}

function view() {
  return (
    <ToastHost>
      <IdentityClaimsModal onClose={vi.fn()} />
    </ToastHost>
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  act(() => useUiStore.setState({ language: 'ru' }))
  vi.spyOn(window, 'confirm').mockReturnValue(true)
  claims([claim()])
  hooks.useConfirmIdentityClaim.mockReturnValue({ mutate: confirmClaim, isPending: false })
  hooks.useRejectIdentityClaim.mockReturnValue({ mutate: rejectClaim, isPending: false })
})

afterEach(() => {
  vi.restoreAllMocks()
  act(() => useUiStore.setState({ language: originalLanguage }))
})

describe('HQ list «ИИН указали сами»', () => {
  it('shows the open claim with the IIN hidden until HQ asks for it, and what the till reported', async () => {
    render(view())
    const row = screen.getByTestId('identity-claim-c1')
    expect(within(row).getByText('••••••••0013')).toBeInTheDocument()
    expect(within(row).queryByText('900115300013')).not.toBeInTheDocument()
    expect(within(row).getByText('Бузурбаева Лаура Ермековна')).toBeInTheDocument()
    expect(within(row).getByText('Пользователь Стандарт-Н 18')).toBeInTheDocument()
    expect(within(row).getByText('в Стандарт-Н: Бузурбаева Л')).toBeInTheDocument()
    expect(within(row).getByText('Бузурбаева 23')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Показать ИИН' }))
    expect(within(row).getByText('900115300013')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Скрыть ИИН' }))
    expect(within(row).queryByText('900115300013')).not.toBeInTheDocument()
  })

  it('counts the tabs and shows only open claims first', () => {
    claims([
      claim(),
      claim({
        id: 'c2',
        status: 'confirmed',
        decidedAt: '2026-11-26T11:00:00Z',
        decidedByName: 'HQ Иванов',
      }),
      claim({
        id: 'c3',
        status: 'rejected',
        decidedAt: '2026-11-26T11:30:00Z',
        decisionReason: 'Нет',
      }),
    ])
    render(view())
    expect(screen.getByTestId('identity-claim-c1')).toBeInTheDocument()
    expect(screen.queryByTestId('identity-claim-c2')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Ждут решения\s*1/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Решённые\s*2/ })).toBeInTheDocument()
  })

  it('confirms exactly what was shown, after one question that carries the full IIN', async () => {
    render(view())
    await userEvent.click(screen.getByRole('button', { name: 'Подтвердить' }))
    expect(window.confirm).toHaveBeenCalledOnce()
    const question = vi.mocked(window.confirm).mock.calls[0][0] as string
    expect(question).toContain('ИИН: 900115300013')
    expect(question).toContain('ФИО: Бузурбаева Лаура Ермековна')
    expect(question).toContain('Аптека: Бузурбаева 23')
    expect(confirmClaim).toHaveBeenCalledWith(
      { id: 'c1', request: { iin: '900115300013', fullName: 'Бузурбаева Лаура Ермековна' } },
      expect.any(Object),
    )
  })

  it('writes nothing when the question is declined', async () => {
    vi.mocked(window.confirm).mockReturnValue(false)
    render(view())
    await userEvent.click(screen.getByRole('button', { name: 'Подтвердить' }))
    expect(confirmClaim).not.toHaveBeenCalled()
  })

  it('lists the contradictions in the row and again in the question', async () => {
    claims([
      claim({
        conflicts: [
          {
            code: 'iin_other_card',
            pharmacistName: 'Другая Карточка',
            pharmacyName: 'Ауэзова 134',
            pharmacistStatus: 'active',
          },
          { code: 'iin_in_directory', directoryName: 'Из Справочника Иванович' },
        ],
      }),
    ])
    render(view())
    const row = screen.getByTestId('identity-claim-c1')
    expect(
      within(row).getByText('ИИН уже у другой карточки: Другая Карточка, Ауэзова 134, Активен'),
    ).toBeInTheDocument()
    expect(
      within(row).getByText('Такой ИИН есть в справочнике Стандарт-Н у Из Справочника Иванович'),
    ).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Подтвердить' }))
    expect(vi.mocked(window.confirm).mock.calls[0][0]).toContain(
      '- ИИН уже у другой карточки: Другая Карточка',
    )
  })

  it('shows a name claim with the name now on the card', async () => {
    claims([
      claim({
        id: 'n1',
        kind: 'name',
        standardNUserId: null,
        standardNUserName: null,
        pharmacistId: 'ph_card',
        pharmacistName: 'Бузурбаева',
      }),
    ])
    render(view())
    const row = screen.getByTestId('identity-claim-n1')
    expect(within(row).getByText('ФИО из кабинета')).toBeInTheDocument()
    expect(within(row).getByText('Сейчас в карточке: Бузурбаева')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Подтвердить' }))
    expect(vi.mocked(window.confirm).mock.calls[0][0]).toContain(
      'Будет: Бузурбаева Лаура Ермековна',
    )
  })

  it('rejects with a reason the pharmacist will read, and not without one', async () => {
    render(view())
    await userEvent.click(screen.getByRole('button', { name: 'Отклонить' }))
    const dialog = screen.getByRole('dialog', { name: 'Отклонить заявку' })
    const send = within(dialog).getByRole('button', { name: 'Отклонить' })
    expect(send).toBeDisabled()
    await userEvent.type(within(dialog).getByRole('textbox'), '  ИИН не совпадает с документом ')
    expect(send).toBeEnabled()
    await userEvent.click(send)
    expect(rejectClaim).toHaveBeenCalledWith(
      { id: 'c1', reason: 'ИИН не совпадает с документом' },
      expect.any(Object),
    )
    act(() => rejectClaim.mock.calls[0][1].onSuccess())
    expect(screen.queryByRole('dialog', { name: 'Отклонить заявку' })).not.toBeInTheDocument()
  })

  it('keeps the reject dialog open and shows the server answer when the decision fails', async () => {
    render(view())
    await userEvent.click(screen.getByRole('button', { name: 'Отклонить' }))
    await userEvent.type(screen.getByRole('textbox'), 'Нет')
    await userEvent.click(
      within(screen.getByRole('dialog', { name: 'Отклонить заявку' })).getByRole('button', {
        name: 'Отклонить',
      }),
    )
    act(() => rejectClaim.mock.calls[0][1].onError(new Error('Заявка уже обработана')))
    expect(screen.getByRole('dialog', { name: 'Отклонить заявку' })).toBeInTheDocument()
  })

  it('disables the decisions while one is being sent', () => {
    hooks.useConfirmIdentityClaim.mockReturnValue({ mutate: confirmClaim, isPending: true })
    render(view())
    expect(screen.getByRole('button', { name: 'Подтвердить' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Отклонить' })).toBeDisabled()
  })

  it('shows decided claims with who decided and the reason of a rejection', async () => {
    claims([
      claim({
        id: 'c2',
        status: 'confirmed',
        decidedAt: '2026-11-26T11:00:00Z',
        decidedByName: 'HQ Иванов',
      }),
      claim({
        id: 'c3',
        status: 'rejected',
        decidedAt: '2026-11-26T11:30:00Z',
        decidedByName: 'HQ Иванов',
        decisionReason: 'Проверьте цифры',
      }),
    ])
    render(view())
    await userEvent.click(screen.getByRole('button', { name: /Решённые/ }))
    expect(
      within(screen.getByTestId('identity-claim-c2')).getByText('Подтверждено'),
    ).toBeInTheDocument()
    const rejected = screen.getByTestId('identity-claim-c3')
    expect(within(rejected).getByText('Отклонено')).toBeInTheDocument()
    expect(within(rejected).getByText('Причина: Проверьте цифры')).toBeInTheDocument()
    expect(within(rejected).getByText(/HQ Иванов/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Подтвердить' })).not.toBeInTheDocument()
  })

  it('shows a contradiction with the directory in its own tab, and what the directory brought', async () => {
    claims([
      claim({
        id: 'f1',
        status: 'superseded',
        decidedAt: new Date().toISOString(),
        directoryIin: '850615400016',
        conflicts: [{ code: 'directory_iin_differs', directoryIin: '850615400016' }],
      }),
    ])
    render(view())
    expect(screen.getByText('Нет заявок, которые ждут решения')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /Расхождения\s*1/ }))
    const row = screen.getByTestId('identity-claim-f1')
    expect(within(row).getByText(/В Стандарт-Н другой ИИН: 850615400016/)).toBeInTheDocument()
    expect(within(row).getByText('Заменено данными Стандарт-Н')).toBeInTheDocument()
  })

  it('says plainly when nothing waits, and shows a retryable error rather than stale rows', async () => {
    claims([])
    const { unmount } = render(view())
    expect(screen.getByText('Нет заявок, которые ждут решения')).toBeInTheDocument()
    unmount()
    claims([claim()], { isError: true, error: new Error('Synthetic failure') })
    render(view())
    expect(screen.getByRole('alert')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Подтвердить' })).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Повторить' }))
    expect(retry).toHaveBeenCalledOnce()
  })

  it('speaks Kazakh when the interface is in Kazakh', () => {
    act(() => useUiStore.setState({ language: 'kk' }))
    render(view())
    expect(screen.getByRole('button', { name: 'Растау' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Қабылдамау' })).toBeInTheDocument()
    expect(screen.getByText('Кассадан ЖСН және аты-жөні')).toBeInTheDocument()
  })
})
