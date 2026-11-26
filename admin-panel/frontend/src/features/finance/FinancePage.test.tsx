// Тесты FinancePage — pending/history Tabs, approve flow, items.

import { describe, expect, it, afterEach, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { ToastHost } from '@/ui'
import { useUiStore } from '@/app/store'
import type { PayoutBatchDto, PayoutItemDto } from '@/lib/api-types'
import FinancePage from './FinancePage'

const financeHooks = vi.hoisted(() => ({
  usePayoutBatches: vi.fn(),
  usePayoutBatch: vi.fn(),
  usePayoutItems: vi.fn(),
  useApproveBatch: vi.fn(),
  useGeneratePayout: vi.fn(),
}))

vi.mock('@/lib/queries/finance', () => financeHooks)

function mkBatch(over: Partial<PayoutBatchDto> = {}): PayoutBatchDto {
  return {
    id: 'pb_pending',
    period: '1–15 мая 2026',
    status: 'pending',
    pharmacists: 100,
    amount: 1_000_000,
    items: 100,
    reviewerId: null,
    reviewerName: null,
    approvedAt: null,
    createdAt: '2026-05-15T00:00:00Z',
    updatedAt: '2026-05-15T00:00:00Z',
    ...over,
  }
}

function mkItem(over: Partial<PayoutItemDto> = {}): PayoutItemDto {
  return {
    id: 'pi_1',
    batchId: 'pb_pending',
    pharmacistId: 'u_1',
    pharmacistName: 'Айгерим',
    pharmacy: 'Europharma №100',
    city: 'Алматы',
    receipts: 50,
    rules: 20,
    amount: 15_000,
    flag: null,
    ...over,
  }
}

function setBatches(list: PayoutBatchDto[] = []) {
  financeHooks.usePayoutBatches.mockReturnValue({
    data: list,
    isLoading: false,
    isError: false,
    refetch: vi.fn(),
  })
}

function setItems(list: PayoutItemDto[] = []) {
  financeHooks.usePayoutItems.mockReturnValue({ data: list, isLoading: false, isError: false })
}

beforeEach(() => {
  vi.clearAllMocks()
  setBatches([])
  setItems([])
  financeHooks.useApproveBatch.mockReturnValue({ mutate: vi.fn(), isPending: false })
  financeHooks.useGeneratePayout.mockReturnValue({ mutate: vi.fn(), isPending: false })
})

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <ToastHost>
          <FinancePage />
        </ToastHost>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('FinancePage — рендер', () => {
  it('H1 + 4 metrics', () => {
    renderPage()
    expect(screen.getByRole('heading', { level: 1, name: /Финансы/i })).toBeInTheDocument()
    expect(screen.getByText('К выплате текущий период')).toBeInTheDocument()
    expect(screen.getByText('Одобрено к выплате')).toBeInTheDocument()
    expect(screen.queryByText('Выплачено всего')).not.toBeInTheDocument()
  })

  it('Empty state на пустом списке', () => {
    renderPage()
    expect(screen.getByText(/Нет открытых батчей/i)).toBeInTheDocument()
  })

  it('метрики идут в 2 колонки на телефоне и в 4 на широком экране', () => {
    renderPage()
    const grid = screen.getByText('К выплате текущий период').closest('.grid') as HTMLElement
    expect(grid.className).toMatch(/\bgrid-cols-2\b.*\blg:grid-cols-4\b/)
  })
})

describe('FinancePage — loading/error', () => {
  it('isLoading', () => {
    financeHooks.usePayoutBatches.mockReturnValue({
      data: undefined,
      isLoading: true,
      isError: false,
      refetch: vi.fn(),
    })
    renderPage()
    expect(screen.getByText(/Загружаем батчи…/i)).toBeInTheDocument()
  })

  it('isError', async () => {
    const refetch = vi.fn()
    financeHooks.usePayoutBatches.mockReturnValue({
      data: undefined,
      isLoading: false,
      isError: true,
      refetch,
    })
    const user = userEvent.setup()
    renderPage()
    expect(screen.getByText(/Не удалось загрузить батчи/i)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /Повторить/ }))
    expect(refetch).toHaveBeenCalled()
  })
})

describe('FinancePage — pending tab', () => {
  it('показывает текущий pending батч + items', () => {
    setBatches([mkBatch()])
    setItems([
      mkItem({ id: 'pi_a', pharmacistName: 'Айгерим', amount: 15_000 }),
      mkItem({ id: 'pi_b', pharmacistName: 'Бауыржан', amount: 30_000, flag: 'Высокий бонус' }),
    ])
    renderPage()
    expect(screen.getByTestId('payout-items-table')).toBeInTheDocument()
    expect(screen.getByTestId('payout-item-pi_a')).toBeInTheDocument()
    expect(screen.getByTestId('payout-item-pi_b')).toBeInTheDocument()
    expect(screen.getByText('Высокий бонус')).toBeInTheDocument()
  })

  it('показывает ошибку сверки, если итог батча не совпадает с позициями', () => {
    setBatches([mkBatch({ pharmacists: 1, items: 1, amount: 15_001 })])
    setItems([mkItem({ amount: 15_000 })])
    renderPage()
    expect(screen.getByRole('alert')).toHaveTextContent('не совпадает')
    expect(screen.getByRole('button', { name: /Утвердить выплату/ })).toBeDisabled()
  })

  it('текст «Утверждаем…» при approving', () => {
    financeHooks.useApproveBatch.mockReturnValue({ mutate: vi.fn(), isPending: true })
    setBatches([mkBatch()])
    renderPage()
    expect(screen.getByRole('button', { name: /Утверждаем…/ })).toBeDisabled()
  })
})

// 26.11.2026: выплаты ещё не подключены. Читать ведомости можно, создавать и утверждать нельзя.
describe('FinancePage: выплаты в разработке', () => {
  const REASON =
    'Функционал выплат находится в разработке: формирование и утверждение пока отключены'

  afterEach(() => useUiStore.setState({ language: 'ru' }))

  it('сверху показывает заметку, что функционал выплат в разработке, без обещания срока', () => {
    renderPage()
    const notice = screen.getByTestId('payouts-dev-notice')
    expect(notice).toHaveAttribute('role', 'status')
    expect(notice).toHaveTextContent('Функционал выплат находится в разработке')
    expect(notice).toHaveTextContent('Выплаты будут включены позже')
    expect(notice.textContent).not.toMatch(/\d{4}|числа|до конца|в течение/)
    // Above the report tools and the tables: the first thing on the page after the title.
    const reportTools = screen.getByRole('heading', { name: 'Отчёт по ведомостям' })
    expect(
      notice.compareDocumentPosition(reportTools) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy()
  })

  it('кнопка «Сформировать выплату» неактивна, причина в подсказке, запрос не уходит', async () => {
    const mutate = vi.fn()
    financeHooks.useGeneratePayout.mockReturnValue({ mutate, isPending: false })
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)
    const user = userEvent.setup()
    renderPage()
    const button = screen.getByTestId('generate-payout')
    expect(button).toBeDisabled()
    expect(button).toHaveAttribute('title', REASON)
    // The hover target of a disabled button is its wrapper: the reason is there too.
    expect(button.closest('[data-testid="payout-action-locked"]')).toHaveAttribute('title', REASON)
    await user.click(button)
    expect(mutate).not.toHaveBeenCalled()
    expect(confirmSpy).not.toHaveBeenCalled()
  })

  it('кнопка «Утвердить выплату» неактивна даже при сходящихся итогах, запрос не уходит', async () => {
    const mutate = vi.fn()
    financeHooks.useApproveBatch.mockReturnValue({ mutate, isPending: false })
    setBatches([mkBatch({ id: 'pb_x', pharmacists: 1, items: 1, amount: 15_000 })])
    setItems([mkItem({ batchId: 'pb_x' })])
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)
    const user = userEvent.setup()
    renderPage()
    // The totals match, so only the payouts switch keeps the button off.
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    const button = screen.getByRole('button', { name: /Утвердить выплату/ })
    expect(button).toBeDisabled()
    expect(button).toHaveAttribute('title', REASON)
    await user.click(button)
    expect(mutate).not.toHaveBeenCalled()
    expect(confirmSpy).not.toHaveBeenCalled()
  })

  it('чтение остаётся: текущий батч, позиции и история видны', async () => {
    setBatches([
      mkBatch({ id: 'pb_pending', pharmacists: 1, items: 1, amount: 15_000 }),
      mkBatch({ id: 'pb_old', status: 'approved', period: '1-15 апреля', reviewerName: 'Айгерим' }),
    ])
    setItems([mkItem({ batchId: 'pb_pending' })])
    const user = userEvent.setup()
    renderPage()
    expect(screen.getByTestId('payout-items-table')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Excel: ведомости' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /История/ }))
    expect(screen.getByTestId('payout-batch-pb_old')).toBeInTheDocument()
  })

  it('на казахском заметка и причина тоже на казахском', () => {
    useUiStore.setState({ language: 'kk' })
    setBatches([mkBatch({ pharmacists: 1, items: 1, amount: 15_000 })])
    setItems([mkItem()])
    renderPage()
    expect(screen.getByTestId('payouts-dev-notice')).toHaveTextContent(
      'Төлемдер функционалы әзірленуде',
    )
    expect(screen.getByTestId('generate-payout')).toHaveAttribute(
      'title',
      'Төлемдер функционалы әзірленуде: қалыптастыру мен бекіту әзірге өшірулі',
    )
    expect(screen.getByRole('button', { name: /Төлемді бекіту/ })).toBeDisabled()
  })
})

describe('FinancePage — history tab', () => {
  it('переключение на «История» показывает approved батчи', async () => {
    setBatches([
      mkBatch({
        id: 'pb_1',
        status: 'approved',
        period: '1–15 апреля',
        reviewerName: 'Айгерим',
        approvedAt: '2026-04-17T00:00:00Z',
      }),
      mkBatch({
        id: 'pb_2',
        status: 'approved',
        period: '16–31 марта',
        reviewerName: 'Айгерим',
        approvedAt: '2026-04-02T00:00:00Z',
      }),
    ])
    const user = userEvent.setup()
    renderPage()
    await user.click(screen.getByRole('button', { name: /История/ }))
    expect(screen.getByTestId('payout-history-table')).toBeInTheDocument()
    expect(screen.getByTestId('payout-batch-pb_1')).toBeInTheDocument()
    expect(screen.getByTestId('payout-batch-pb_2')).toBeInTheDocument()
  })

  it('Empty state когда нет approved', async () => {
    setBatches([mkBatch()]) // только pending
    const user = userEvent.setup()
    renderPage()
    await user.click(screen.getByRole('button', { name: /История/ }))
    expect(screen.getByText(/История пуста/i)).toBeInTheDocument()
  })
})
