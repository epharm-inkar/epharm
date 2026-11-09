import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ToastHost } from '@/ui'
import RecommendationRewardsQueue from './RecommendationRewardsQueue'
import type { RewardDetail } from './recommendation-rewards-api'

const mocks = vi.hoisted(() => ({
  access: vi.fn(),
  list: vi.fn(),
  detail: vi.fn(),
  decision: vi.fn(),
  mutate: vi.fn(),
  get: vi.fn(),
  download: vi.fn(),
  save: vi.fn(),
}))
vi.mock('@/app/useAdministrativeCapabilities', () => ({
  useAdministrativeCapabilities: mocks.access,
}))
vi.mock('./recommendation-rewards-api', () => ({
  useRecommendationRewards: mocks.list,
  useRecommendationReward: mocks.detail,
  useRewardDecision: mocks.decision,
}))
vi.mock('@/lib/api', () => ({ api: { get: mocks.get } }))
vi.mock('@/features/reports/ledgerReports', () => ({
  downloadReport: mocks.download,
  saveReport: mocks.save,
}))
const detail = (patch: Partial<RewardDetail> = {}): RewardDetail => ({
  id: 'reward-1',
  canonicalReceiptId: 'physical-1',
  pharmacyId: 'ph-1',
  pharmacistId: 'person-1',
  deviceId: 'KASSA',
  sourceSaleId: 'sale-1',
  productId: 'sku-1',
  eventId: 'rec-1',
  promoId: 'promo-1',
  state: 'review_required',
  reasonCode: 'package_unit_unverified',
  unitBonus: 200,
  eligiblePackages: 2,
  totalBonus: 400,
  pendingBonusId: null,
  receiptId: null,
  budgetReserved: false,
  createdAt: '2026-11-09T10:00:00Z',
  updatedAt: '2026-11-09T10:00:00Z',
  eligiblePharmacists: [{ id: 'person-1', name: 'Тестовый Фармацевт' }],
  ...patch,
})
const refetch = vi.fn()
function setDetail(value = detail(), flags = {}) {
  mocks.detail.mockImplementation((id: string | null) => ({
    data: id ? value : undefined,
    isLoading: false,
    isError: false,
    isFetching: false,
    isFetchedAfterMount: true,
    refetch,
    ...flags,
  }))
}
function renderQueue() {
  return render(
    <ToastHost>
      <RecommendationRewardsQueue />
    </ToastHost>,
  )
}
beforeEach(() => {
  vi.clearAllMocks()
  mocks.access.mockReturnValue({
    isPending: false,
    isError: false,
    canViewRecommendationRewards: true,
    canReviewRecommendationRewards: true,
    canExportRecommendationRewards: false,
  })
  mocks.list.mockReturnValue({
    data: { items: [detail()], total: 125, limit: 50, offset: 0 },
    isLoading: false,
    isError: false,
    isFetching: false,
    refetch,
  })
  setDetail()
  mocks.decision.mockReturnValue({ mutate: mocks.mutate, isPending: false, isError: false })
})

describe('recommendation monetary review queue', () => {
  it('fails closed without explicit fresh view capability and never fetches the queue', () => {
    mocks.access.mockReturnValue({ isPending: false, canViewRecommendationRewards: false })
    renderQueue()
    expect(screen.getByText('Нет доступа к продажам по рекомендациям')).toBeInTheDocument()
    expect(mocks.list).not.toHaveBeenCalled()
    expect(mocks.detail).not.toHaveBeenCalled()
  })
  it('shows review reasons and does not present calculated400 as already credited', () => {
    renderQueue()
    expect(screen.getByText('Количество полных упаковок не подтверждено')).toBeInTheDocument()
    expect(screen.getByText('Не начислено')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Excel: продажи по рекомендациям' })).toBeDisabled()
  })
  it('uses exact state and real offset for pagination; state changes reset offset', async () => {
    const user = userEvent.setup()
    renderQueue()
    await user.click(screen.getByRole('button', { name: 'Далее' }))
    expect(mocks.list).toHaveBeenLastCalledWith({ state: 'review_required' }, 50)
    await user.selectOptions(screen.getByLabelText('Статус начисления'), 'credited')
    expect(mocks.list).toHaveBeenLastCalledWith({ state: 'credited' }, 0)
  })
  it('does not confuse a failed refresh with an empty or payable queue', async () => {
    mocks.list.mockReturnValue({
      data: { items: [detail()], total: 1, offset: 0, limit: 50 },
      isLoading: false,
      isError: true,
      error: new Error('offline'),
      refetch,
    })
    const user = userEvent.setup()
    renderQueue()
    expect(screen.queryByRole('button', { name: 'Открыть проверку' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Повторить загрузку' }))
    expect(refetch).toHaveBeenCalledOnce()
  })
  it('exports only the applied scope/filter through the normal blob download path', async () => {
    mocks.access.mockReturnValue({
      isPending: false,
      canViewRecommendationRewards: true,
      canReviewRecommendationRewards: false,
      canExportRecommendationRewards: true,
    })
    const blob = new Blob(['synthetic-xlsx'])
    mocks.download.mockResolvedValueOnce(blob)
    const user = userEvent.setup()
    renderQueue()
    await user.selectOptions(screen.getByLabelText('Статус начисления'), 'credited')
    await user.click(screen.getByRole('button', { name: 'Excel: продажи по рекомендациям' }))
    expect(mocks.download).toHaveBeenCalledWith(
      '/api/admin/reconcile/recommendation-rewards/export.xlsx',
      { state: 'credited', from: undefined, to: undefined },
      expect.any(AbortSignal),
    )
    expect(mocks.save).toHaveBeenCalledWith(blob, 'epharm-recommendation-rewards.xlsx')
  })
  it('does not preselect a recipient who is absent from the fresh eligible list', async () => {
    setDetail(detail({ eligiblePharmacists: [] }))
    const user = userEvent.setup()
    renderQueue()
    await user.click(screen.getByRole('button', { name: 'Открыть проверку' }))
    await user.type(screen.getByLabelText('Основание решения'), 'Проверен чек')
    await user.click(screen.getByLabelText('Оплату этого чека проверил(а)'))
    await user.click(screen.getByLabelText('Количество именно полных упаковок проверил(а)'))
    expect(screen.getByRole('button', { name: 'Сохранить решение' })).toBeDisabled()
  })
  it('requires reason, real recipient, whole positive quantity and both manual confirmations', async () => {
    const user = userEvent.setup()
    renderQueue()
    await user.click(screen.getByRole('button', { name: 'Открыть проверку' }))
    const submit = screen.getByRole('button', { name: 'Сохранить решение' })
    expect(submit).toBeDisabled()
    await user.type(
      screen.getByLabelText('Основание решения'),
      'Проверен оплаченный чек и две полные упаковки',
    )
    await user.click(screen.getByLabelText('Оплату этого чека проверил(а)'))
    expect(submit).toBeDisabled()
    await user.click(screen.getByLabelText('Количество именно полных упаковок проверил(а)'))
    expect(submit).toBeEnabled()
    await user.clear(screen.getByLabelText('Подтверждённые полные упаковки'))
    await user.type(screen.getByLabelText('Подтверждённые полные упаковки'), '1.5')
    expect(submit).toBeDisabled()
    await user.clear(screen.getByLabelText('Подтверждённые полные упаковки'))
    await user.type(screen.getByLabelText('Подтверждённые полные упаковки'), '2')
    await user.click(submit)
    expect(mocks.mutate).toHaveBeenCalledWith(
      {
        id: 'reward-1',
        decision: {
          action: 'approve',
          reason: 'Проверен оплаченный чек и две полные упаковки',
          pharmacistId: 'person-1',
          eligiblePackages: 2,
          confirmedPayment: true,
          confirmedPackages: true,
        },
      },
      expect.anything(),
    )
  })
  it.each(['not_eligible', 'waiting_evidence', 'credited', 'rejected', 'refund_review'] as const)(
    'never offers approval for %s',
    async (state) => {
      setDetail(detail({ state }))
      const user = userEvent.setup()
      renderQueue()
      await user.click(screen.getByRole('button', { name: 'Открыть проверку' }))
      expect(
        screen.queryByRole('option', { name: 'Подтвердить и начислить' }),
      ).not.toBeInTheDocument()
      expect(screen.queryByLabelText('Оплату этого чека проверил(а)')).not.toBeInTheDocument()
      expect(mocks.mutate).not.toHaveBeenCalled()
    },
  )
  it.each([
    { reason: 'ОК', packages: '2', enabled: false },
    { reason: 'Чек', packages: '1000001', enabled: false },
    { reason: 'Чек', packages: '1000000', enabled: true },
  ])(
    'matches backend reason/quantity bounds: $reason / $packages',
    async ({ reason, packages, enabled }) => {
      const user = userEvent.setup()
      renderQueue()
      await user.click(screen.getByRole('button', { name: 'Открыть проверку' }))
      await user.type(screen.getByLabelText('Основание решения'), reason)
      await user.clear(screen.getByLabelText('Подтверждённые полные упаковки'))
      await user.type(screen.getByLabelText('Подтверждённые полные упаковки'), packages)
      await user.click(screen.getByLabelText('Оплату этого чека проверил(а)'))
      await user.click(screen.getByLabelText('Количество именно полных упаковок проверил(а)'))
      const submit = screen.getByRole('button', { name: 'Сохранить решение' })
      if (enabled) expect(submit).toBeEnabled()
      else expect(submit).toBeDisabled()
      expect(mocks.mutate).not.toHaveBeenCalled()
    },
  )
  it('refund acknowledgment carries only audit action and reason, with no money/recipient override', async () => {
    setDetail(detail({ state: 'refund_review' }))
    const user = userEvent.setup()
    renderQueue()
    await user.click(screen.getByRole('button', { name: 'Открыть проверку' }))
    expect(
      screen.getByText(/Деньги автоматически не начисляются и не списываются/),
    ).toBeInTheDocument()
    expect(screen.getByText('Ранее начисленное требует сверки')).toBeInTheDocument()
    await user.type(screen.getByLabelText('Основание решения'), 'Проверен возврат')
    await user.click(screen.getByRole('button', { name: 'Сохранить решение' }))
    expect(mocks.mutate).toHaveBeenCalledWith(
      { id: 'reward-1', decision: { action: 'acknowledge_refund', reason: 'Проверен возврат' } },
      expect.anything(),
    )
  })
  it('viewer can inspect whitelisted proof without credentials or decision actions', async () => {
    mocks.access.mockReturnValue({
      isPending: false,
      canViewRecommendationRewards: true,
      canReviewRecommendationRewards: false,
      canExportRecommendationRewards: false,
    })
    setDetail(
      detail({
        sale: { password: 'secret-never-render', otp: 'code-never-render' },
        rewardEvidence: {
          completion: {
            status: 'paid',
            finalDetailsRead: true,
            signedTotal: -4000,
            identity: { rawUserId: 'USER-7' },
          },
          changes: [],
        },
      }),
    )
    const user = userEvent.setup()
    renderQueue()
    await user.click(screen.getByRole('button', { name: 'Открыть проверку' }))
    expect(screen.getByText('USER-7')).toBeInTheDocument()
    expect(screen.queryByText('secret-never-render')).not.toBeInTheDocument()
    expect(screen.queryByText('code-never-render')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Основание решения')).not.toBeInTheDocument()
  })
  it('cached or refreshing details cannot grant a monetary decision', async () => {
    setDetail(detail(), { isFetchedAfterMount: false, isFetching: true })
    const user = userEvent.setup()
    renderQueue()
    await user.click(screen.getByRole('button', { name: 'Открыть проверку' }))
    expect(screen.queryByLabelText('Основание решения')).not.toBeInTheDocument()
  })
  it('ambiguous mutation error disables retry until the record is reopened and freshly checked', async () => {
    mocks.decision.mockReturnValue({
      mutate: mocks.mutate,
      isPending: false,
      isError: true,
      error: new Error('timeout'),
    })
    const user = userEvent.setup()
    renderQueue()
    await user.click(screen.getByRole('button', { name: 'Открыть проверку' }))
    expect(screen.getByRole('button', { name: 'Сохранить решение' })).toBeDisabled()
    expect(
      screen.getByRole('button', { name: 'Закрыть и проверить запись заново' }),
    ).toBeInTheDocument()
    expect(mocks.mutate).not.toHaveBeenCalled()
  })
})
