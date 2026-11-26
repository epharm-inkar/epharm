import { describe, expect, it } from 'vitest'
import type { ScreenFleetRegisterDto } from '@/lib/api-types'
import { compareVersions, matchesFilter, relativeAgo, sortRows, standardNShort } from './fleetFormat'

const t = (key: string, vars?: Record<string, string | number>) =>
  vars ? `${key}:${Object.values(vars).join(',')}` : key

function row(overrides: Partial<ScreenFleetRegisterDto>): ScreenFleetRegisterDto {
  return {
    deviceId: 'K1',
    pharmacyId: 'ph',
    pharmacyName: 'Аптека',
    chainName: null,
    city: 'Алматы',
    address: 'Абая 1',
    pharmacyActive: true,
    registered: true,
    registeredAt: null,
    state: 'online',
    lastSeen: '2026-11-24T09:00:00Z',
    appVersion: '1.0.90',
    monitorCount: 2,
    hasClientScreen: true,
    staffAuthStatus: null,
    staffSourceStatus: 'resolved',
    playlistId: null,
    playlistSource: 'none',
    salesToday: 0,
    sales7d: 0,
    lastSaleAt: null,
    pharmacyLastSaleAt: null,
    pharmacySalesToday: 0,
    pharmacySales7d: 0,
    ...overrides,
  }
}

describe('fleetFormat', () => {
  it('сравнивает версии по числам, пустые в конце', () => {
    expect(compareVersions('1.0.100', '1.0.99')).toBeGreaterThan(0)
    expect(compareVersions('1.0.90', '1.0.90.0')).toBeLessThan(0)
    expect(compareVersions(null, '1.0.1')).toBeGreaterThan(0)
    expect(compareVersions('1.0.1', null)).toBeLessThan(0)
  })

  it('пишет, как давно касса была на связи', () => {
    const now = '2026-11-24T09:00:00Z'
    expect(relativeAgo(t, '2026-11-24T08:59:00Z', now)).toBe('fleet.justNow')
    expect(relativeAgo(t, '2026-11-24T08:30:00Z', now)).toBe('fleet.minAgo:30')
    expect(relativeAgo(t, '2026-11-24T04:00:00Z', now)).toBe('fleet.hoursAgo:5')
    expect(relativeAgo(t, '2026-11-14T09:00:00Z', now)).toBe('fleet.daysAgo:10')
    expect(relativeAgo(t, null, now)).toBe('fleet.never')
  })

  it('фильтры не считают выключенную кассу проблемой Стандарт-Н или продаж', () => {
    const offline = row({ state: 'offline', staffSourceStatus: 'database_unavailable' })
    expect(matchesFilter(offline, 'standardN')).toBe(false)
    expect(matchesFilter(offline, 'noSalesToday')).toBe(false)
    expect(matchesFilter(row({ staffSourceStatus: 'database_unavailable' }), 'standardN')).toBe(true)
    expect(matchesFilter(row({ hasClientScreen: false }), 'clientScreen')).toBe(false)
  })

  it('сортирует новой копией и не меняет исходный список', () => {
    const rows = [
      row({ deviceId: 'A', appVersion: '1.0.89' }),
      row({ deviceId: 'B', appVersion: '1.0.91' }),
      row({ deviceId: 'C', appVersion: null }),
    ]
    const sorted = sortRows(rows, 'version', false)
    expect(sorted.map((r) => r.deviceId)).toEqual(['B', 'A', 'C'])
    expect(rows.map((r) => r.deviceId)).toEqual(['A', 'B', 'C'])
    expect(sortRows(rows, 'version', true).map((r) => r.deviceId)).toEqual(['C', 'A', 'B'])
  })

  it('коротко подписывает состояние Стандарт-Н', () => {
    expect(standardNShort('database_unavailable')).toEqual({ key: 'fleet.sn.noDatabase', tone: 'red' })
    expect(standardNShort('resolved').tone).toBe('green')
    // 26.11: no_process and manager_only (ePharm on the manager's computer, not on a till) are both a problem to fix.
    expect(standardNShort('no_process')).toEqual({ key: 'fleet.sn.noProcess', tone: 'amber' })
    expect(standardNShort('manager_only')).toEqual({ key: 'fleet.sn.managerOnly', tone: 'amber' })
    // 24.11: with the pharmacist login switched off every till reports service_disabled; that is not a read error.
    expect(standardNShort('service_disabled')).toEqual({ key: 'fleet.sn.disabled', tone: 'ink' })
    expect(standardNShort('local_disabled')).toEqual({ key: 'fleet.sn.disabled', tone: 'ink' })
    expect(standardNShort('initializing')).toEqual({ key: 'fleet.sn.unknown', tone: 'ink' })
    expect(standardNShort('stale_session')).toEqual({ key: 'fleet.sn.other', tone: 'amber' })
    expect(standardNShort('stale_session').key).toBe('fleet.sn.other')
    expect(standardNShort('ambiguous_session')).toEqual({ key: 'fleet.sn.ambiguous', tone: 'amber' })
    expect(standardNShort(null).key).toBe('fleet.sn.unknown')
  })
})
