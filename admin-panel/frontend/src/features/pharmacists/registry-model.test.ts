import { describe, expect, it } from 'vitest'
import type { PharmacistDto, PharmacyDto, TrainingPreferenceDto } from '@/lib/api-types'
import { countRegistry, distinctSorted, filterRegistry, hasPhone, isFiltered, isTestRecord } from './registry-model'

function person(over: Partial<PharmacistDto>): PharmacistDto {
  return {
    id: 'u', name: 'Касенова Айгерим', iin: null, phone: null, pharmacyId: 'ph_1', pharmacyName: 'Абая 10',
    city: 'Алматы', tier: 'Silver', receipts30d: 0, rulesAccepted30d: 0, earned30d: 0, balance: 0,
    coursesDone: 0, coursesTotal: 0, status: 'active', hasPassword: false, joinedAt: '2026-11-24',
    createdAt: '2026-11-24T00:00:00Z', updatedAt: '2026-11-24T00:00:00Z', ...over,
  }
}

const pharmacies = new Map<string, PharmacyDto>([
  ['ph_1', { id: 'ph_1', chainName: 'Аптека со склада' } as PharmacyDto],
  ['ph_2', { id: 'ph_2', chainName: 'Лимон Фарм' } as PharmacyDto],
])

const people = [
  person({ id: 'a', name: '[ДЕМО] Фармацевт ePharm', hasPhone: true, hasPassword: true, accountSource: 'registration' }),
  person({ id: 'b', name: 'Бекова Жанар', accountSource: 'standardn', pharmacyId: 'ph_2', city: 'Астана', iin: '900101400011' }),
  person({ id: 'c', name: 'Ким Ольга', status: 'blocked', phone: '+7 701 555 10 10', accountSource: 'standardn' }),
  person({ id: 'd', name: 'Омарова Дана', status: 'pending', pharmacyId: '', pharmacyName: '', city: '' }),
]

const filters = { status: 'all', chain: 'all', city: 'all', format: 'all', query: '' } as const
const noPreferences = new Map<string, TrainingPreferenceDto>()

describe('registry model', () => {
  it('marks only bracketed demo and test cards as test records', () => {
    expect(isTestRecord('[ДЕМО] Фармацевт ePharm')).toBe(true)
    expect(isTestRecord(' [ТЕСТ] Фармацевт LMS')).toBe(true)
    expect(isTestRecord('[test] synthetic')).toBe(true)
    expect(isTestRecord('Тестова Алия')).toBe(false)
    expect(isTestRecord('Фармацевт [ТЕСТ]')).toBe(false)
  })

  it('counts phone, password, Standard-N cards and statuses for the counter line', () => {
    expect(countRegistry(people)).toEqual({
      total: 4,
      withPhone: 2,
      withPassword: 1,
      fromStandardN: 2,
      byStatus: { active: 2, pending: 1, blocked: 1 },
    })
    // An older backend without hasPhone: the visible number decides, a hidden one counts as unknown.
    expect(hasPhone(person({ phone: '+7 701' }))).toBe(true)
    expect(hasPhone(person({ phone: null }))).toBe(false)
    expect(hasPhone(person({ phone: null, hasPhone: true }))).toBe(true)
  })

  it('filters by status, chain, city, training format and search without matching hidden values', () => {
    const ids = (over: Partial<typeof filters> | Record<string, string>, preferences = noPreferences) =>
      filterRegistry(people, { ...filters, ...over } as never, pharmacies, preferences).map((p) => p.id)
    expect(ids({})).toEqual(['a', 'b', 'c', 'd'])
    expect(ids({ status: 'blocked' })).toEqual(['c'])
    expect(ids({ chain: 'Лимон Фарм' })).toEqual(['b'])
    expect(ids({ city: 'Астана' })).toEqual(['b'])
    expect(ids({ query: 'лимон' })).toEqual(['b'])
    expect(ids({ query: '400011' })).toEqual(['b'])
    expect(ids({ query: 'null' })).toEqual([])
    const preferences = new Map([['b', { pharmacistId: 'b', defaultFormat: 'online' } as TrainingPreferenceDto]])
    expect(ids({ format: 'online' }, preferences)).toEqual(['b'])
    expect(ids({ format: 'unset' }, preferences)).toEqual(['a', 'c', 'd'])
  })

  it('reports active filters and sorts select options in Russian order', () => {
    expect(isFiltered(filters)).toBe(false)
    expect(isFiltered({ ...filters, query: '  ' })).toBe(false)
    expect(isFiltered({ ...filters, city: 'Астана' })).toBe(true)
    expect(distinctSorted(['Шымкент', '', 'Алматы', 'Астана', 'Алматы', ' '])).toEqual(['Алматы', 'Астана', 'Шымкент'])
  })
})
