import { describe, expect, it } from 'vitest'
import { staffSourceStatusLabel } from './staffSourceStatus'

describe('native cashier context diagnostic', () => {
  it.each([
    'initializing', 'unknown', 'local_disabled', 'service_disabled', 'resolved', 'no_process', 'manager_only',
    'process_unavailable', 'ambiguous_process', 'process_changing', 'database_disabled',
    'database_unavailable', 'no_session', 'ambiguous_session', 'unknown_workstation',
    'unsupported_session_time', 'future_session_time', 'stale_session', 'invalid_session_time',
    'unsupported_process_time', 'session_predates_process',
  ])('explains known code %s without leaking the raw code', (code) => {
    expect(staffSourceStatusLabel(code)).toMatch(/^Источник Standard-N: /)
    expect(staffSourceStatusLabel(code)).not.toContain('обновите POSM')
    expect(staffSourceStatusLabel(code)).not.toContain(code)
  })
  it.each([undefined, null, '', 'private unexpected server value'])('safely handles unsupported diagnostics %j', (code) => {
    expect(staffSourceStatusLabel(code)).toBe('Источник Standard-N: нет диагностики контекста (обновите POSM)')
  })
  it('says that ePharm stands on the manager computer when only the manager program runs', () => {
    expect(staffSourceStatusLabel('manager_only')).toBe(
      'Источник Standard-N: Только «Менеджер» Стандарт-Н: ePharm стоит не на кассе',
    )
    // The fleet table and the tooltip must not read as a till that works or as a missing Standard-N.
    expect(staffSourceStatusLabel('manager_only')).not.toBe(staffSourceStatusLabel('no_process'))
    expect(staffSourceStatusLabel('manager_only')).not.toContain(String.fromCharCode(0x2014))
    expect(staffSourceStatusLabel('manager_only')).not.toContain(String.fromCharCode(0x2013))
  })
  it('never treats a resolved source as financial confirmation or login', () => {
    expect(staffSourceStatusLabel('resolved')).toContain('личность для бонусов не подтверждена этим статусом')
    expect(staffSourceStatusLabel('resolved')).not.toContain('вход подтверждён')
  })
})
