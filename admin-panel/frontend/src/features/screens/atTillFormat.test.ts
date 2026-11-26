import { describe, expect, it } from 'vitest'
import { translate } from '@/i18n'
import { agoLabel, clockLabel, dateTimeLabel, placeLabel, sinceLabel, spanLabel } from './atTillFormat'

const t = (key: string, vars?: Record<string, string | number>) => translate('ru', key, vars)
// Полдень по UTC: в любом поясе от UTC-11 до UTC+11 это тот же календарный день.
const noon = '2026-11-26T12:00:00Z'
const minutesAfter = (iso: string, minutes: number) => new Date(new Date(iso).getTime() + minutes * 60_000).toISOString()

describe('сколько прошло', () => {
  it('минуты, часы и часы с минутами', () => {
    expect(spanLabel(t, 7)).toBe('7 мин')
    expect(spanLabel(t, 59)).toBe('59 мин')
    expect(spanLabel(t, 60)).toBe('1 ч')
    expect(spanLabel(t, 65)).toBe('1 ч 05 мин')
    expect(spanLabel(t, 200)).toBe('3 ч 20 мин')
  })

  it('«только что» до минуты, потом «N мин назад»; время не идёт назад', () => {
    expect(agoLabel(t, noon, minutesAfter(noon, 0))).toBe('только что')
    expect(agoLabel(t, noon, minutesAfter(noon, 0.5))).toBe('только что')
    expect(agoLabel(t, noon, minutesAfter(noon, 7))).toBe('7 мин назад')
    expect(agoLabel(t, noon, minutesAfter(noon, 65))).toBe('1 ч 05 мин назад')
    expect(agoLabel(t, minutesAfter(noon, 5), noon)).toBe('только что')
  })

  it('с какого времени человек за кассой и сколько уже', () => {
    const label = sinceLabel(t, { since: noon }, minutesAfter(noon, 80))
    expect(label).toMatch(/^с \d{2}:\d{2} \(1 ч 20 мин\)$/)
  })
})

describe('время в поясе того, кто смотрит', () => {
  it('в тот же день только часы и минуты, в другой день с датой', () => {
    expect(clockLabel(noon, minutesAfter(noon, 30))).toMatch(/^\d{2}:\d{2}$/)
    expect(clockLabel('2026-11-25T12:00:00Z', noon)).toMatch(/^25\.11, \d{2}:\d{2}$/)
  })

  it('последний раз за кассой: «26.11 14:05»', () => {
    expect(dateTimeLabel(noon)).toMatch(/^26\.11 \d{2}:\d{2}$/)
  })
})

describe('место кассы', () => {
  it('название и адрес, адрес не повторяет название', () => {
    expect(placeLabel({ pharmacyName: 'Аптека со склада', address: 'Абая 103' })).toBe('Аптека со склада, Абая 103')
    expect(placeLabel({ pharmacyName: 'Аптека со склада, Абая 103', address: 'Абая 103' })).toBe('Аптека со склада, Абая 103')
    expect(placeLabel({ pharmacyName: 'Аптека со склада', address: '  ' })).toBe('Аптека со склада')
  })
})
