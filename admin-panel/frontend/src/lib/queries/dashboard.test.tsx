import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { api } from '@/lib/api'
import { useDashboardSummary, useRecommendationAnalytics } from './dashboard'

vi.mock('@/lib/api', () => ({ api: { get: vi.fn() } }))

beforeEach(() => {
  vi.useFakeTimers()
  vi.clearAllMocks()
})

afterEach(() => vi.useRealTimers())

it('refreshes both summary and sales journal every 15 seconds and stops after unmount', async () => {
  let version = 1
  vi.mocked(api.get).mockImplementation(async (url) => ({
    data: url.endsWith('/summary') ? { impressions: version } : { shown: version },
  }))
  // Match production defaults: focus changes do not refetch and data stays fresh 30s.
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false, staleTime: 30_000 } },
  })
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  )
  const { result, unmount } = renderHook(() => ({
    summary: useDashboardSummary(),
    journal: useRecommendationAnalytics(),
  }), { wrapper })
  try {
    await act(async () => { await vi.advanceTimersByTimeAsync(1) })
    expect(result.current.summary.data?.impressions).toBe(1)
    expect(result.current.journal.data?.shown).toBe(1)

    version = 2
    await act(async () => { await vi.advanceTimersByTimeAsync(15_000) })
    expect(result.current.summary.data?.impressions).toBe(2)
    expect(result.current.journal.data?.shown).toBe(2)
    expect(api.get).toHaveBeenCalledTimes(4)

    unmount()
    await act(async () => { await vi.advanceTimersByTimeAsync(30_000) })
    expect(api.get).toHaveBeenCalledTimes(4)
  } finally {
    unmount()
    client.clear()
  }
})
