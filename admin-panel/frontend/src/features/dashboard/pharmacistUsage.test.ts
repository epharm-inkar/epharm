import { describe, expect, it } from 'vitest'
import type { PharmacistCoverageDayDto, PharmacistCoverageDto } from '@/lib/api-types'
import {
  COVERAGE_SINCE_LAUNCH_DAYS,
  countRu,
  hasDailyCounts,
  percentRu,
  ruDate,
  sinceLaunch,
  usageFileName,
  usageNumbers,
  usagePoints,
} from './pharmacistUsage'

function day(date: string, over: Partial<PharmacistCoverageDayDto> = {}): PharmacistCoverageDayDto {
  return {
    date,
    total: 100,
    active7d: 40,
    sellers7d: 20,
    coveragePct: 40,
    joined: 0,
    active1d: 10,
    coverage1dPct: 10,
    ...over,
  }
}

function coverage(over: Partial<PharmacistCoverageDto> = {}): PharmacistCoverageDto {
  return {
    date: '2026-11-26',
    total: 5320,
    fromStandardN: 5308,
    registered: 12,
    active7d: 1880,
    sellers7d: 900,
    cabinet7d: 30,
    coveragePct: 35.3,
    daily: [day('2026-11-25'), day('2026-11-26', { active1d: 412, coverage1dPct: 7.7 })],
    generatedAt: '2026-11-26T09:00:00Z',
    activeToday: 412,
    coverageTodayPct: 7.7,
    ...over,
  }
}

describe('sinceLaunch', () => {
  it('drops the empty days before the first pharmacist and keeps everything after', () => {
    const rows = [
      day('2026-07-01', { total: 0, active7d: 0, active1d: 0 }),
      day('2026-07-02', { total: 0, active7d: 0, active1d: 0 }),
      day('2026-07-03', { total: 3, active7d: 0, active1d: 0 }),
      day('2026-07-04', { total: 3, active7d: 0, active1d: 0 }),
      day('2026-07-05', { total: 5 }),
    ]
    expect(sinceLaunch(rows).map((row) => row.date)).toEqual([
      '2026-07-03',
      '2026-07-04',
      '2026-07-05',
    ])
  })

  it('keeps a quiet day after the launch: only leading empties go', () => {
    const rows = [day('a', { total: 2, active7d: 0, active1d: 0 }), day('b', { total: 2 })]
    expect(sinceLaunch(rows)).toHaveLength(2)
  })

  it('keeps today when nobody is in the system yet, so the block still has a date', () => {
    const rows = [day('a', { total: 0 }), day('b', { total: 0 })]
    expect(sinceLaunch(rows).map((row) => row.date)).toEqual(['b'])
    expect(sinceLaunch([])).toEqual([])
  })

  it('asks the server for a window longer than the system has existed', () => {
    expect(COVERAGE_SINCE_LAUNCH_DAYS).toBe(366)
  })
})

describe('usageNumbers', () => {
  it('takes the headline figures from the response', () => {
    expect(usageNumbers(coverage())).toEqual({
      total: 5320,
      registered: 12,
      fromStandardN: 5308,
      today: 412,
      todayPct: 7.7,
      week: 1880,
      weekPct: 35.3,
    })
  })

  it('falls back to the last day row when only the daily figures are present', () => {
    const data = coverage({ activeToday: undefined, coverageTodayPct: undefined })
    expect(usageNumbers(data)).toMatchObject({ today: 412, todayPct: 7.7 })
  })

  it('has no «today» for a backend older than the figure, never a made-up zero', () => {
    const old = coverage({
      activeToday: undefined,
      coverageTodayPct: undefined,
      daily: [day('2026-11-26', { active1d: undefined, coverage1dPct: undefined })],
    })
    expect(usageNumbers(old)).toMatchObject({ today: null, todayPct: null, week: 1880 })
  })

  it('keeps a real zero: nobody worked yet today', () => {
    const quiet = coverage({ activeToday: 0, coverageTodayPct: 0 })
    expect(usageNumbers(quiet)).toMatchObject({ today: 0, todayPct: 0 })
  })
})

describe('chart rows', () => {
  it('knows whether every day carries the «that very day» figure', () => {
    expect(hasDailyCounts([day('a'), day('b')])).toBe(true)
    expect(hasDailyCounts([day('a'), day('b', { active1d: undefined })])).toBe(false)
    expect(hasDailyCounts([])).toBe(false)
  })

  it('maps rows to the four series the two charts read', () => {
    expect(usagePoints([day('2026-11-26', { active1d: 412, coverage1dPct: 7.7 })])).toEqual([
      {
        date: '2026-11-26',
        values: { active1d: 412, active7d: 40, coverage1dPct: 7.7, coveragePct: 40 },
      },
    ])
  })
})

describe('formatting', () => {
  it('writes dates as dd.mm.yyyy', () => {
    expect(ruDate('2026-11-26')).toBe('26.11.2026')
  })

  it('writes the share with a decimal comma and one digit', () => {
    expect(percentRu(7.7)).toBe('7,7%')
    expect(percentRu(35)).toBe('35%')
    expect(percentRu(0)).toBe('0%')
  })

  it('groups thousands (a non-breaking space, whatever the engine picks)', () => {
    expect(countRu(5320).replace(/\s/g, ' ')).toBe('5 320')
    expect(countRu(12)).toBe('12')
  })

  it('names the picture after the day the figures are for', () => {
    expect(usageFileName('2026-11-26')).toBe('pharmacists-in-epharm-2026-11-26.png')
  })
})
