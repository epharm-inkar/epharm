import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api'
import { reconcileKeys } from '@/lib/queries/reconcile'
import { pharmacistKeys } from '@/lib/queries/pharmacists'
import { payoutKeys } from '@/lib/queries/finance'

export type RewardState =
  | 'waiting_evidence'
  | 'not_eligible'
  | 'review_required'
  | 'ready'
  | 'credited'
  | 'rejected'
  | 'refund_review'
export interface RecommendationReward {
  id: string
  canonicalReceiptId: string
  pharmacyId: string
  pharmacistId: string | null
  deviceId: string | null
  sourceSaleId: string
  productId: string
  eventId: string | null
  promoId: string | null
  state: RewardState
  reasonCode: string
  unitBonus: number
  eligiblePackages: number
  totalBonus: number
  pendingBonusId: string | null
  receiptId: string | null
  budgetReserved: boolean
  createdAt: string
  updatedAt: string
  pharmacyName?: string
  pharmacistName?: string
  productName?: string
}
export interface RewardDetail extends RecommendationReward {
  sale?: Record<string, unknown> | null
  rewardEvidence?: Record<string, unknown> | null
  eligiblePharmacists?: { id: string; name: string }[]
}
export interface RewardFilter {
  state?: RewardState
  from?: string
  to?: string
}
export interface RewardPage {
  items: RecommendationReward[]
  total: number
  offset: number
  limit: number
}
export interface RewardDecision {
  action: 'approve' | 'reject' | 'acknowledge_refund'
  reason: string
  pharmacistId?: string
  eligiblePackages?: number
  confirmedPayment?: boolean
  confirmedPackages?: boolean
}
const base = '/api/admin/reconcile/recommendation-rewards'
export const rewardKeys = { all: ['recommendation-rewards'] as const }

function exactMoney<T extends RecommendationReward>(value: T): T {
  if (
    ![value.unitBonus, value.eligiblePackages, value.totalBonus].every(
      (n) => Number.isSafeInteger(n) && n >= 0,
    )
  ) {
    throw new Error(
      'Сумма или количество не могут быть отображены точно. Используйте серверную выгрузку Excel.',
    )
  }
  return value
}

export const rewardApi = {
  list: async (filter: RewardFilter, offset: number, signal: AbortSignal) => {
    const { data } = await api.get<RewardPage>(base, {
      params: { ...filter, offset, limit: 50 },
      signal,
    })
    if (
      !Array.isArray(data.items) ||
      !Number.isSafeInteger(data.total) ||
      data.total < 0 ||
      data.offset !== offset ||
      data.limit !== 50
    ) {
      throw new Error('Получена неполная очередь начислений. Повторите загрузку.')
    }
    data.items.forEach(exactMoney)
    return data
  },
  detail: async (id: string, signal: AbortSignal) => {
    const { data } = await api.get<RewardDetail>(`${base}/${encodeURIComponent(id)}`, { signal })
    if (data.id !== id) throw new Error('Получена другая запись. Решение не отправлено.')
    return exactMoney(data)
  },
  decide: async (id: string, decision: RewardDecision) => {
    const { data } = await api.post<RewardDetail>(
      `${base}/${encodeURIComponent(id)}/decision`,
      decision,
    )
    return data
  },
}

const fresh = {
  staleTime: 0,
  refetchOnMount: 'always' as const,
  refetchOnWindowFocus: true,
  placeholderData: undefined,
  retry: false,
}

export function useRecommendationRewards(filter: RewardFilter, offset: number) {
  return useQuery({
    queryKey: [...rewardKeys.all, 'list', filter, offset],
    queryFn: ({ signal }) => rewardApi.list(filter, offset, signal),
    ...fresh,
    refetchInterval: 20_000,
  })
}
export function useRecommendationReward(id: string | null) {
  return useQuery({
    queryKey: [...rewardKeys.all, 'detail', id],
    queryFn: ({ signal }) => rewardApi.detail(id!, signal),
    enabled: !!id,
    ...fresh,
  })
}
export function useRewardDecision() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ id, decision }: { id: string; decision: RewardDecision }) =>
      rewardApi.decide(id, decision),
    retry: false,
    onSettled: async () => {
      await Promise.all(
        [
          rewardKeys.all,
          reconcileKeys.all,
          pharmacistKeys.all,
          payoutKeys.all,
          ['dashboard'],
          ['ledger'],
        ].map((queryKey) => client.invalidateQueries({ queryKey })),
      )
    },
  })
}
