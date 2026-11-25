import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ToastHost } from '@/ui'
import type { PosmMessageDto } from '@/lib/api-types'
import { TillMessagesPanel } from './TillMessagesPanel'

const messageHooks = vi.hoisted(() => ({
  usePosmMessages: vi.fn(),
  useSendPosmMessage: vi.fn(),
  useWithdrawPosmMessage: vi.fn(),
}))
vi.mock('@/lib/queries/posmMessages', () => messageHooks)
const pharmacyHooks = vi.hoisted(() => ({ usePharmacies: vi.fn() }))
vi.mock('@/lib/queries/pharmacies', () => pharmacyHooks)
const pharmacistHooks = vi.hoisted(() => ({ usePharmacists: vi.fn() }))
vi.mock('@/lib/queries/pharmacists', () => pharmacistHooks)

const sendMutate = vi.fn()
const withdrawMutate = vi.fn()

function sent(overrides: Partial<PosmMessageDto> = {}): PosmMessageDto {
  return {
    id: 'm1',
    title: 'Инвентаризация',
    text: 'Завтра в 9:00 инвентаризация.',
    audience: 'pharmacies',
    targetCount: 2,
    targetNames: ['Аптека со склада, Абая 103', 'Аптека со склада, Толе би 286'],
    recipientsTotal: 8,
    shownCount: 6,
    closedCount: 4,
    createdAt: '2026-11-25T09:00:00Z',
    createdBy: 'hq@example.test',
    expiresAt: '2026-12-02T09:00:00Z',
    withdrawnAt: null,
    withdrawnBy: null,
    active: true,
    ...overrides,
  }
}

function renderPanel() {
  return render(
    <ToastHost>
      <TillMessagesPanel />
    </ToastHost>,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  messageHooks.usePosmMessages.mockReturnValue({ data: [sent()], isLoading: false, isError: false })
  messageHooks.useSendPosmMessage.mockReturnValue({ mutate: sendMutate, isPending: false })
  messageHooks.useWithdrawPosmMessage.mockReturnValue({ mutate: withdrawMutate, isPending: false })
  pharmacyHooks.usePharmacies.mockReturnValue({
    data: [
      { id: 'ph_abay', name: 'Аптека со склада', city: 'Алматы', addr: 'Абая 103' },
      { id: 'ph_tole', name: 'Аптека со склада', city: 'Алматы', addr: 'Толе би 286' },
    ],
    isLoading: false,
    isError: false,
  })
  pharmacistHooks.usePharmacists.mockReturnValue({
    data: [
      { id: 'ph_aigerim', name: 'Сапарова Айгерим', pharmacyName: 'Аптека со склада, Абая 103', city: 'Алматы' },
      { id: 'ph_erbol', name: 'Тұрсынов Ербол', pharmacyName: 'Аптека со склада, Толе би 286', city: 'Алматы' },
    ],
    isLoading: false,
    isError: false,
  })
})

describe('TillMessagesPanel', () => {
  it('отправляет уведомление всем после подтверждения', async () => {
    const user = userEvent.setup()
    renderPanel()
    await user.type(screen.getByTestId('till-message-title'), 'Инвентаризация в субботу')
    await user.type(screen.getByTestId('till-message-text'), 'Приходите к 8:30.')
    expect(screen.getByTestId('till-message-preview')).toHaveTextContent('Инвентаризация в субботу')
    await user.click(screen.getByTestId('till-message-send'))

    const dialog = screen.getByRole('dialog')
    expect(dialog).toHaveTextContent('Кому: всем фармацевтам. Показывать на кассах: 7 дней.')
    expect(dialog).toHaveTextContent('Его увидят фармацевты на всех кассах с ePharm 1.0.99 и новее')
    expect(screen.getByText(/Уведомления видны только на кассах с ePharm 1\.0\.99 и новее/)).toBeInTheDocument()
    expect(sendMutate).not.toHaveBeenCalled()
    await user.click(within(dialog).getByTestId('till-message-confirm'))
    expect(sendMutate).toHaveBeenCalledWith(
      { title: 'Инвентаризация в субботу', text: 'Приходите к 8:30.', audience: 'all', days: 7 },
      expect.anything(),
    )
  })

  it('не отправляет без заголовка и текста и говорит, чего не хватает', async () => {
    const user = userEvent.setup()
    renderPanel()
    await user.click(screen.getByTestId('till-message-send'))
    expect(screen.getByTestId('till-message-error')).toHaveTextContent('Введите заголовок')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(sendMutate).not.toHaveBeenCalled()
  })

  it('аптекам: выбор аптек и срок показа уходят в запрос', async () => {
    const user = userEvent.setup()
    renderPanel()
    await user.type(screen.getByTestId('till-message-title'), 'Проверка')
    await user.type(screen.getByTestId('till-message-text'), 'Завтра проверка.')
    await user.click(screen.getByTestId('till-message-audience-pharmacies'))
    await user.click(screen.getByTestId('till-message-send'))
    expect(screen.getByTestId('till-message-error')).toHaveTextContent('Выберите хотя бы одну аптеку')

    await user.click(screen.getByTestId('till-message-pharmacies-ph_tole'))
    await user.selectOptions(screen.getByRole('combobox', { name: 'Показывать на кассах' }), '3')
    await user.click(screen.getByTestId('till-message-send'))
    expect(screen.getByRole('dialog')).toHaveTextContent('аптеки (1): Аптека со склада')
    await user.click(screen.getByTestId('till-message-confirm'))
    expect(sendMutate).toHaveBeenCalledWith(
      { title: 'Проверка', text: 'Завтра проверка.', audience: 'pharmacies', days: 3, pharmacyIds: ['ph_tole'] },
      expect.anything(),
    )
  })

  it('сотрудникам: поиск по имени и выбор', async () => {
    const user = userEvent.setup()
    renderPanel()
    await user.type(screen.getByTestId('till-message-title'), 'Ербол')
    await user.type(screen.getByTestId('till-message-text'), 'Подойдите к заведующей.')
    await user.click(screen.getByTestId('till-message-audience-pharmacists'))
    await user.type(screen.getByPlaceholderText('Найти сотрудника: имя, аптека, город'), 'ербол')
    const picker = screen.getByTestId('till-message-pharmacists')
    expect(within(picker).queryByText('Сапарова Айгерим')).not.toBeInTheDocument()
    await user.click(screen.getByTestId('till-message-pharmacists-ph_erbol'))
    await user.click(screen.getByTestId('till-message-send'))
    await user.click(screen.getByTestId('till-message-confirm'))
    expect(sendMutate).toHaveBeenCalledWith(
      expect.objectContaining({ audience: 'pharmacists', pharmacistIds: ['ph_erbol'] }),
      expect.anything(),
    )
    expect(pharmacistHooks.usePharmacists).toHaveBeenLastCalledWith({ status: 'active' }, true)
  })

  it('показывает, сколько увидели и закрыли, и снимает уведомление с касс', async () => {
    const user = userEvent.setup()
    renderPanel()
    const row = screen.getByTestId('till-message-m1')
    expect(row).toHaveTextContent('аптеки (2): Аптека со склада, Абая 103, Аптека со склада, Толе би 286')
    expect(screen.getByTestId('till-message-m1-shown')).toHaveTextContent('6 из 8')
    expect(screen.getByTestId('till-message-m1-closed')).toHaveTextContent('4 из 8')
    await user.click(within(row).getByRole('button', { name: 'Снять с касс' }))
    await user.click(screen.getByTestId('till-message-withdraw-confirm'))
    expect(withdrawMutate).toHaveBeenCalledWith('m1', expect.anything())
  })

  it('снятое уведомление нельзя снять ещё раз, пустой список подписан', () => {
    messageHooks.usePosmMessages.mockReturnValue({
      data: [sent({ active: false, withdrawnAt: '2026-11-25T10:00:00Z', withdrawnBy: 'hq@example.test' })],
      isLoading: false,
      isError: false,
    })
    const { unmount } = renderPanel()
    const row = screen.getByTestId('till-message-m1')
    expect(row).toHaveTextContent('Снято')
    expect(within(row).queryByRole('button', { name: 'Снять с касс' })).not.toBeInTheDocument()
    unmount()

    messageHooks.usePosmMessages.mockReturnValue({ data: [], isLoading: false, isError: false })
    renderPanel()
    expect(screen.getByText('Уведомлений пока не было.')).toBeInTheDocument()
  })
})
