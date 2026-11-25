import { describe, expect, it } from 'vitest'
import type { ScreenFleetRegisterDto, TillCommandDto } from '@/lib/api-types'
import { translate } from '@/i18n'
import {
  TILL_COMMANDS_ACTIVE_POLL_MS,
  TILL_COMMANDS_IDLE_POLL_MS,
  isActiveTillCommand,
  tillCommandsPollInterval,
} from '@/lib/queries/tillCommands'
import {
  commandBlocker,
  commandStatusView,
  fallbackArchiveName,
  formatArchiveSize,
  latestCommandByTill,
  tillKey,
} from './tillCommands'

const t = (key: string, vars?: Record<string, string | number>) => translate('ru', key, vars)

function command(overrides: Partial<TillCommandDto>): TillCommandDto {
  return {
    id: 'c1',
    pharmacyId: 'ph_a',
    deviceId: 'KASSA1',
    action: 'diagnostics',
    status: 'pending',
    requestedBy: 'hq@example.test',
    createdAt: '2026-11-25T09:00:00Z',
    expiresAt: '2026-11-25T09:30:00Z',
    deliveredAt: null,
    finishedAt: null,
    resultMessage: null,
    artifactAvailable: false,
    artifactBytes: null,
    ...overrides,
  }
}

function row(overrides: Partial<ScreenFleetRegisterDto>): ScreenFleetRegisterDto {
  return {
    deviceId: 'KASSA1',
    pharmacyId: 'ph_a',
    pharmacyName: 'Аптека',
    chainName: null,
    city: null,
    address: null,
    pharmacyActive: true,
    registered: true,
    registeredAt: null,
    state: 'online',
    lastSeen: null,
    appVersion: '1.0.94',
    monitorCount: 2,
    hasClientScreen: true,
    staffAuthStatus: null,
    staffSourceStatus: null,
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

describe('latestCommandByTill', () => {
  it('берёт самое новое задание каждой кассы, в каком бы порядке ни пришёл список', () => {
    const older = command({ id: 'old', createdAt: '2026-11-25T08:00:00Z', status: 'done' })
    const newer = command({ id: 'new', createdAt: '2026-11-25T09:00:00Z' })
    const other = command({ id: 'other', deviceId: 'KASSA2', createdAt: '2026-11-24T08:00:00Z' })
    const latest = latestCommandByTill([older, other, newer])
    expect(latest.get(tillKey('ph_a', 'KASSA1'))?.id).toBe('new')
    expect(latest.get(tillKey('ph_a', 'KASSA2'))?.id).toBe('other')
    expect(latest.size).toBe(2)
    expect(latestCommandByTill(undefined).size).toBe(0)
  })

  it('одинаковое имя кассы в разных аптеках не путает', () => {
    const latest = latestCommandByTill([command({ id: 'a' }), command({ id: 'b', pharmacyId: 'ph_b' })])
    expect(latest.get(tillKey('ph_a', 'KASSA1'))?.id).toBe('a')
    expect(latest.get(tillKey('ph_b', 'KASSA1'))?.id).toBe('b')
  })
})

describe('commandBlocker', () => {
  it('кассе на связи и не на связи со своим ключом можно дать задание', () => {
    expect(commandBlocker(row({}))).toBeNull()
    expect(commandBlocker(row({ state: 'offline' }))).toBeNull()
  })

  it('без своего ключа, без аптеки, в закрытой аптеке и давно молчащей кассе нельзя', () => {
    expect(commandBlocker(row({ registered: false }))).toBe('tillCmd.blocked.noKey')
    expect(commandBlocker(row({ pharmacyId: null }))).toBe('tillCmd.blocked.noPharmacy')
    expect(commandBlocker(row({ pharmacyActive: false }))).toBe('tillCmd.blocked.pharmacyClosed')
    expect(commandBlocker(row({ state: 'stale' }))).toBe('tillCmd.blocked.stale')
  })
})

describe('commandStatusView', () => {
  it('подписи и цвета статусов', () => {
    expect(commandStatusView(command({ status: 'pending' }))).toEqual({ key: 'tillCmd.status.pending', tone: 'amber' })
    expect(commandStatusView(command({ status: 'delivered' })).key).toBe('tillCmd.status.delivered')
    expect(commandStatusView(command({ status: 'done' }))).toEqual({ key: 'tillCmd.status.doneDiagnostics', tone: 'green' })
    expect(commandStatusView(command({ status: 'done', action: 'restart' })).key).toBe('tillCmd.status.doneRestart')
    expect(commandStatusView(command({ status: 'failed' })).tone).toBe('red')
    expect(commandStatusView(command({ status: 'expired' }))).toEqual({ key: 'tillCmd.status.expired', tone: 'ink' })
  })
})

describe('опрос и подписи', () => {
  it('10 с, пока задание ждёт кассу или выполняется, иначе раз в минуту', () => {
    expect(isActiveTillCommand({ status: 'pending' })).toBe(true)
    expect(isActiveTillCommand({ status: 'delivered' })).toBe(true)
    expect(isActiveTillCommand({ status: 'done' })).toBe(false)
    expect(tillCommandsPollInterval([command({ status: 'done' }), command({ status: 'delivered' })])).toBe(
      TILL_COMMANDS_ACTIVE_POLL_MS,
    )
    expect(tillCommandsPollInterval([command({ status: 'expired' })])).toBe(TILL_COMMANDS_IDLE_POLL_MS)
    expect(tillCommandsPollInterval(undefined)).toBe(TILL_COMMANDS_IDLE_POLL_MS)
  })

  it('размер архива в КБ и МБ', () => {
    expect(formatArchiveSize(t, 200)).toBe('1 КБ')
    expect(formatArchiveSize(t, 350 * 1024)).toBe('350 КБ')
    expect(formatArchiveSize(t, Math.round(1.25 * 1024 * 1024))).toBe('1,3 МБ')
    expect(formatArchiveSize(t, 5 * 1024 * 1024)).toBe('5 МБ')
  })

  it('запасное имя файла только из безопасных символов', () => {
    expect(fallbackArchiveName({ pharmacyId: 'sloc 1', deviceId: 'DESKTOP\\7' })).toBe(
      'epharm-diagnostics-sloc_1-DESKTOP_7.zip',
    )
    expect(fallbackArchiveName({ pharmacyId: 'Аптека', deviceId: '..' })).toBe('epharm-diagnostics-till-till.zip')
  })
})
