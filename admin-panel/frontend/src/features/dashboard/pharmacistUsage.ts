// Numbers behind the block «Фармацевты в ePharm» on the Analytics page (owner, 26.11.2026).
// The data and the rule are those of the pharmacists «Покрытие» tab: GET /api/admin/pharmacists/coverage.

import type { PharmacistCoverageDayDto, PharmacistCoverageDto } from '@/lib/api-types'
import type { CoveragePoint } from '@/features/pharmacists/CoverageChart'

/**
 * The window asked from the server: about a year back, which is longer than the system has existed,
 * so the chart starts at the launch. The server clamps it to the same figure.
 */
export const COVERAGE_SINCE_LAUNCH_DAYS = 366

/**
 * The days from the first one that has a pharmacist in the system. Earlier rows are empty: nothing
 * existed to count, and a flat line at zero would only push the real data to the right.
 */
export function sinceLaunch(daily: PharmacistCoverageDayDto[]): PharmacistCoverageDayDto[] {
  const first = daily.findIndex((day) => day.total > 0)
  return first < 0 ? daily.slice(-1) : daily.slice(first)
}

/** A day counted by an older backend has no «that very day» figure. */
export function hasDailyCounts(days: PharmacistCoverageDayDto[]): boolean {
  return days.length > 0 && days.every((day) => typeof day.active1d === 'number')
}

export interface UsageNumbers {
  total: number
  registered: number
  fromStandardN: number
  /** Null when the backend is older than the «worked today» figure. */
  today: number | null
  todayPct: number | null
  week: number
  weekPct: number
}

export function usageNumbers(data: PharmacistCoverageDto): UsageNumbers {
  const last = data.daily.at(-1)
  const today = data.activeToday ?? last?.active1d ?? null
  const todayPct = data.coverageTodayPct ?? last?.coverage1dPct ?? null
  return {
    total: data.total,
    registered: data.registered,
    fromStandardN: data.fromStandardN,
    today,
    todayPct: today == null ? null : todayPct,
    week: data.active7d,
    weekPct: data.coveragePct,
  }
}

/** The two charts read the same rows: people on the left, their share of the system on the right. */
export function usagePoints(days: PharmacistCoverageDayDto[]): CoveragePoint[] {
  return days.map((day) => ({
    date: day.date,
    values: {
      active1d: day.active1d ?? 0,
      active7d: day.active7d,
      coverage1dPct: day.coverage1dPct ?? 0,
      coveragePct: day.coveragePct,
    },
  }))
}

/** `2026-11-26` to `26.11.2026`. */
export function ruDate(iso: string): string {
  return iso.split('-').reverse().join('.')
}

export const percentRu = (value: number) =>
  `${value.toLocaleString('ru-RU', { maximumFractionDigits: 1 })}%`

export const countRu = (value: number) => value.toLocaleString('ru-RU')

/** `pharmacists-in-epharm-2026-11-26.png`: the date is the one the figures are for. */
export function usageFileName(isoDate: string): string {
  return `pharmacists-in-epharm-${isoDate}.png`
}
