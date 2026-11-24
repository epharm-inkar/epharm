// Правила вкладки «Все кассы»: фильтры, поиск, сортировка и короткие подписи статусов.
// Чистые функции без React, чтобы их проверяли обычные unit-тесты.

import type { FleetRegisterState, ScreenFleetRegisterDto } from '@/lib/api-types'

export type TFn = (key: string, vars?: Record<string, string | number>) => string

export type FleetFilter =
  | 'all'
  | 'online'
  | 'offline'
  | 'stale'
  | 'clientScreen'
  | 'standardN'
  | 'noSalesToday'
  | 'gaps'

export type FleetSort = 'pharmacy' | 'state' | 'version' | 'sales'
export type Tone = 'green' | 'amber' | 'red' | 'ink'

const STATE_ORDER: Record<FleetRegisterState, number> = { online: 0, offline: 1, stale: 2 }
const DATABASE_UNAVAILABLE = 'database_unavailable'

/** Сравнение версий POSM по числам: 1.0.100 новее 1.0.99; пустая версия всегда последняя. */
export function compareVersions(a?: string | null, b?: string | null): number {
  if (!a && !b) return 0
  if (!a) return 1
  if (!b) return -1
  const left = a.split(/[.+-]/).map((part) => Number.parseInt(part, 10))
  const right = b.split(/[.+-]/).map((part) => Number.parseInt(part, 10))
  for (let i = 0; i < Math.max(left.length, right.length); i += 1) {
    const l = Number.isNaN(left[i]) ? -1 : (left[i] ?? -1)
    const r = Number.isNaN(right[i]) ? -1 : (right[i] ?? -1)
    if (l !== r) return l - r
  }
  return 0
}

/** «только что», «5 мин назад», «3 ч назад», «12 дн назад» относительно времени снимка сервера. */
export function relativeAgo(t: TFn, at: string | null, observedAt: string): string {
  if (!at) return t('fleet.never')
  const minutes = Math.max(0, Math.floor((Date.parse(observedAt) - Date.parse(at)) / 60_000))
  if (minutes < 3) return t('fleet.justNow')
  if (minutes < 60) return t('fleet.minAgo', { n: minutes })
  const hours = Math.floor(minutes / 60)
  if (hours < 48) return t('fleet.hoursAgo', { n: hours })
  return t('fleet.daysAgo', { n: Math.floor(hours / 24) })
}

const almatyTime = new Intl.DateTimeFormat('ru-RU', {
  timeZone: 'Asia/Almaty',
  day: '2-digit',
  month: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
})

/** Дата и время по Алматы: «24.11, 14:05». */
export function almatyDateTime(at: string): string {
  return almatyTime.format(new Date(at))
}

export function isStandardNUnavailable(row: ScreenFleetRegisterDto): boolean {
  return row.state === 'online' && row.staffSourceStatus === DATABASE_UNAVAILABLE
}

/** Короткая подпись состояния Стандарт-Н для таблицы; полная подсказка в staffSourceStatusLabel. */
export function standardNShort(status: string | null): { key: string; tone: Tone } {
  switch (status) {
    case 'resolved':
      return { key: 'fleet.sn.ok', tone: 'green' }
    case 'no_session':
      return { key: 'fleet.sn.noSession', tone: 'ink' }
    case 'no_process':
      return { key: 'fleet.sn.noProcess', tone: 'amber' }
    case DATABASE_UNAVAILABLE:
      return { key: 'fleet.sn.noDatabase', tone: 'red' }
    case 'ambiguous_session':
      // Стандарт-Н работает, но открыто несколько сессий кассира: продавец не определяется.
      return { key: 'fleet.sn.ambiguous', tone: 'amber' }
    case 'service_disabled':
    case 'local_disabled':
      // Вход фармацевта выключен (сервером или в настройках кассы): касса базу не проверяет, это не ошибка.
      return { key: 'fleet.sn.disabled', tone: 'ink' }
    case null:
    case 'initializing':
    case 'unknown':
      return { key: 'fleet.sn.unknown', tone: 'ink' }
    default:
      return { key: 'fleet.sn.other', tone: 'amber' }
  }
}

export function matchesFilter(row: ScreenFleetRegisterDto, filter: FleetFilter): boolean {
  switch (filter) {
    case 'online':
    case 'offline':
    case 'stale':
      return row.state === filter
    case 'clientScreen':
      return row.state === 'online' && row.hasClientScreen === true
    case 'standardN':
      return isStandardNUnavailable(row)
    case 'noSalesToday':
      return row.state === 'online' && row.pharmacySalesToday === 0
    default:
      return true
  }
}

export function matchesSearch(row: ScreenFleetRegisterDto, query: string): boolean {
  const needle = query.trim().toLowerCase()
  if (!needle) return true
  return [row.pharmacyName, row.city, row.address, row.chainName, row.deviceId, row.appVersion, row.pharmacyId]
    .some((value) => value?.toLowerCase().includes(needle))
}

function pharmacyKey(row: ScreenFleetRegisterDto): string {
  return [row.city, row.address, row.pharmacyName, row.deviceId].map((v) => v ?? '').join('\u0001')
}

/** Новый массив в нужном порядке; исходный список не меняется. */
export function sortRows(
  rows: ScreenFleetRegisterDto[],
  sort: FleetSort,
  descending: boolean,
): ScreenFleetRegisterDto[] {
  const compare = (a: ScreenFleetRegisterDto, b: ScreenFleetRegisterDto): number => {
    switch (sort) {
      case 'state':
        return (
          STATE_ORDER[a.state] - STATE_ORDER[b.state] ||
          Date.parse(b.lastSeen ?? '1970-01-01') - Date.parse(a.lastSeen ?? '1970-01-01')
        )
      case 'version':
        // Новые версии сверху, кассы без версии (не на связи) в конце.
        if (!a.appVersion || !b.appVersion) return (a.appVersion ? 0 : 1) - (b.appVersion ? 0 : 1)
        return compareVersions(b.appVersion, a.appVersion)
      case 'sales':
        return b.salesToday - a.salesToday || b.sales7d - a.sales7d
      default:
        return pharmacyKey(a).localeCompare(pharmacyKey(b), 'ru')
    }
  }
  const sorted = [...rows].sort((a, b) => compare(a, b) || pharmacyKey(a).localeCompare(pharmacyKey(b), 'ru'))
  return descending ? sorted.reverse() : sorted
}
