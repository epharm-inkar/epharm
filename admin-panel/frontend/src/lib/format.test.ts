import { describe, expect, it } from 'vitest'
import { THIN_SPACE, formatCount, formatDay, formatDayTime, formatTenge } from './format'

const narrow = String.fromCharCode(0x202f)
const noBreak = String.fromCharCode(0x00a0)

describe('dense number formatting', () => {
  it('groups digits with a narrow no-break space', () => {
    expect(THIN_SPACE).toBe(narrow)
    expect(formatCount(1742)).toBe(`1${narrow}742`)
    expect(formatCount(1_842_300)).toBe(`1${narrow}842${narrow}300`)
    expect(formatCount(0)).toBe('0')
    expect(formatCount(999)).toBe('999')
  })

  it('writes money in full tenge, never abbreviated', () => {
    expect(formatTenge(125_000)).toBe(`125${narrow}000${noBreak}₸`)
    expect(formatTenge(0)).toBe(`0${noBreak}₸`)
    expect(formatTenge(12_000_000)).toBe(`12${narrow}000${narrow}000${noBreak}₸`)
  })

  it('formats dates and keeps missing or broken values missing', () => {
    expect(formatDay('2026-11-25T08:30:00Z')).toMatch(/^\d{2}\.\d{2}\.2026$/)
    expect(formatDayTime('2026-11-25T08:30:00Z')).toMatch(/^\d{2}\.\d{2}\.2026, \d{2}:\d{2}$/)
    expect(formatDay(null)).toBeNull()
    expect(formatDay(undefined)).toBeNull()
    expect(formatDay('not a date')).toBeNull()
  })
})
