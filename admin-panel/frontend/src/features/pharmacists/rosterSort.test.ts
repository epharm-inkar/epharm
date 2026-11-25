import { describe, expect, it } from 'vitest'
import type { PharmacistDto } from '@/lib/api-types'
import { compactKzt, sortRoster, sumOrNull } from './rosterSort'

const row = (name: string, extra: Partial<PharmacistDto> = {}) =>
  ({ id: name, name, sales30d: 0, earned30d: 0, lastActiveAt: null, ...extra }) as PharmacistDto

describe('roster sorting', () => {
  it('puts the largest sales first, hidden figures last, ties by name', () => {
    const rows = [
      row('Борисова', { sales30d: 500 }),
      row('Абаева', { sales30d: 500 }),
      row('Ванина', { sales30d: null }),
      row('Гаева', { sales30d: 9_000 }),
    ]
    expect(sortRoster(rows, 'sales').map((p) => p.name)).toEqual([
      'Гаева',
      'Абаева',
      'Борисова',
      'Ванина',
    ])
    // The name order is the server's: untouched, and the input is never mutated.
    expect(sortRoster(rows, 'name')).toEqual(rows)
    expect(rows[0].name).toBe('Борисова')
  })

  it('orders by the last time in ePharm, never active last', () => {
    const rows = [
      row('Старая', { lastActiveAt: '2026-11-20T10:00:00Z' }),
      row('Никогда'),
      row('Свежая', { lastActiveAt: '2026-11-25T10:00:00Z' }),
    ]
    expect(sortRoster(rows, 'activity').map((p) => p.name)).toEqual(['Свежая', 'Старая', 'Никогда'])
  })
})

describe('roster totals', () => {
  it('compacts large sums for a tile and keeps small ones exact', () => {
    const units = { million: 'млн', thousand: 'тыс.' }
    expect(compactKzt(113_630_315, units)).toBe('113,6 млн ₸')
    expect(compactKzt(45_200, units)).toBe('45,2 тыс. ₸')
    expect(compactKzt(900, units)).toBe('900 ₸')
  })

  it('is unknown when any row hides the figure', () => {
    expect(
      sumOrNull([row('А', { sales30d: 10 }), row('Б', { sales30d: 5 })], (p) => p.sales30d),
    ).toBe(15)
    expect(
      sumOrNull([row('А', { sales30d: 10 }), row('Б', { sales30d: null })], (p) => p.sales30d),
    ).toBe(null)
  })
})
