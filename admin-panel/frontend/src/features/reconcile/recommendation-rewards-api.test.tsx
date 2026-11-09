import { beforeEach, describe, expect, it, vi } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { rewardApi, useRewardDecision, type RewardDetail } from './recommendation-rewards-api'

const mocks = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('@/lib/api', () => ({ api: mocks }))
const row = (id = 'reward-1'): RewardDetail => ({
  id,
  canonicalReceiptId: 'canonical-1',
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
})
beforeEach(() => vi.resetAllMocks())
describe('reward financial API contract', () => {
  it('sends exact paged filters and an abort signal to the authenticated admin client', async () => {
    const signal = new AbortController().signal
    mocks.get.mockResolvedValue({ data: { items: [row()], total: 81, offset: 50, limit: 50 } })
    await rewardApi.list(
      { state: 'review_required', from: '2026-11-01', to: '2026-11-09' },
      50,
      signal,
    )
    expect(mocks.get).toHaveBeenCalledWith('/api/admin/reconcile/recommendation-rewards', {
      params: {
        state: 'review_required',
        from: '2026-11-01',
        to: '2026-11-09',
        offset: 50,
        limit: 50,
      },
      signal,
    })
  })
  it('rejects mismatched pages and imprecise financial numbers rather than showing them as zero', async () => {
    const signal = new AbortController().signal
    mocks.get.mockResolvedValueOnce({ data: { items: [row()], total: 81, offset: 0, limit: 50 } })
    await expect(rewardApi.list({}, 50, signal)).rejects.toThrow('неполная очередь')
    mocks.get.mockResolvedValueOnce({
      data: {
        items: [{ ...row(), totalBonus: Number.MAX_SAFE_INTEGER + 1 }],
        total: 1,
        offset: 0,
        limit: 50,
      },
    })
    await expect(rewardApi.list({}, 0, signal)).rejects.toThrow('отображены точно')
  })
  it('encodes the case ID and rejects a detail response for a different case', async () => {
    const signal = new AbortController().signal
    mocks.get.mockResolvedValueOnce({ data: row('case/1') })
    await rewardApi.detail('case/1', signal)
    expect(mocks.get).toHaveBeenLastCalledWith(
      '/api/admin/reconcile/recommendation-rewards/case%2F1',
      { signal },
    )
    mocks.get.mockResolvedValueOnce({ data: row('other') })
    await expect(rewardApi.detail('case/1', signal)).rejects.toThrow('другая запись')
  })
  it.each([false, true])(
    'refreshes cases, balances, reconciliation and payouts after a decision (ambiguous failure=%s)',
    async (ambiguous) => {
      const client = new QueryClient({
        defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
      })
      const invalidate = vi.spyOn(client, 'invalidateQueries')
      if (ambiguous) mocks.post.mockRejectedValueOnce(new Error('timeout'))
      else mocks.post.mockResolvedValueOnce({ data: { ...row(), state: 'credited' } })
      const wrapper = ({ children }: { children: ReactNode }) => (
        <QueryClientProvider client={client}>{children}</QueryClientProvider>
      )
      const { result } = renderHook(() => useRewardDecision(), { wrapper })
      const decision = {
        action: 'approve' as const,
        reason: 'Проверен чек',
        pharmacistId: 'person-1',
        eligiblePackages: 2,
        confirmedPayment: true,
        confirmedPackages: true,
      }
      await act(async () => {
        if (ambiguous)
          await expect(result.current.mutateAsync({ id: 'reward-1', decision })).rejects.toThrow(
            'timeout',
          )
        else await result.current.mutateAsync({ id: 'reward-1', decision })
      })
      expect(mocks.post).toHaveBeenCalledTimes(1)
      expect(mocks.post).toHaveBeenCalledWith(
        '/api/admin/reconcile/recommendation-rewards/reward-1/decision',
        decision,
      )
      for (const key of [
        'recommendation-rewards',
        'reconcile',
        'pharmacists',
        'payouts',
        'dashboard',
        'ledger',
      ])
        expect(invalidate).toHaveBeenCalledWith({ queryKey: [key] })
      client.clear()
    },
  )
})
