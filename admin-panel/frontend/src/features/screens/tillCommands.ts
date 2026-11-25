// Задания кассам в таблице «Все кассы»: последнее задание каждой кассы, можно ли дать новое
// и короткие подписи статуса. Чистые функции без React, их проверяют обычные unit-тесты.

import type { ScreenFleetRegisterDto, TillCommandDto } from '@/lib/api-types'
import type { TFn, Tone } from './fleetFormat'

/** Тот же ключ, что у строки таблицы: аптека + касса. */
export function tillKey(pharmacyId: string | null, deviceId: string): string {
  return `${pharmacyId ?? 'none'}-${deviceId}`
}

/** Последнее по времени задание каждой кассы; исходный список не меняется. */
export function latestCommandByTill(
  commands: readonly TillCommandDto[] | undefined,
): Map<string, TillCommandDto> {
  const latest = new Map<string, TillCommandDto>()
  for (const command of commands ?? []) {
    const key = tillKey(command.pharmacyId, command.deviceId)
    const current = latest.get(key)
    if (!current || Date.parse(command.createdAt) > Date.parse(current.createdAt)) latest.set(key, command)
  }
  return latest
}

/** Почему кассе сейчас нельзя дать задание (ключ словаря подсказки); null: можно. */
export function commandBlocker(row: ScreenFleetRegisterDto): string | null {
  if (!row.pharmacyId) return 'tillCmd.blocked.noPharmacy'
  if (!row.registered) return 'tillCmd.blocked.noKey'
  if (row.pharmacyActive === false) return 'tillCmd.blocked.pharmacyClosed'
  if (row.state === 'stale') return 'tillCmd.blocked.stale'
  return null
}

export function commandStatusView(command: TillCommandDto): { key: string; tone: Tone } {
  switch (command.status) {
    case 'pending':
      return { key: 'tillCmd.status.pending', tone: 'amber' }
    case 'delivered':
      return { key: 'tillCmd.status.delivered', tone: 'amber' }
    case 'done':
      return {
        key: command.action === 'restart' ? 'tillCmd.status.doneRestart' : 'tillCmd.status.doneDiagnostics',
        tone: 'green',
      }
    case 'failed':
      return { key: 'tillCmd.status.failed', tone: 'red' }
    default:
      return { key: 'tillCmd.status.expired', tone: 'ink' }
  }
}

/** «340 КБ», «1,2 МБ». */
export function formatArchiveSize(t: TFn, bytes: number): string {
  if (bytes < 1024 * 1024) return t('tillCmd.kb', { n: Math.max(1, Math.round(bytes / 1024)) })
  const mb = (bytes / (1024 * 1024)).toLocaleString('ru-RU', { maximumFractionDigits: 1 })
  return t('tillCmd.mb', { n: mb })
}

/** Имя файла, если браузер не показал Content-Disposition (другой origin в разработке). */
export function fallbackArchiveName(command: Pick<TillCommandDto, 'pharmacyId' | 'deviceId'>): string {
  const safe = (value: string) => value.replace(/[^A-Za-z0-9._-]+/g, '_').replace(/^[_.]+|[_.]+$/g, '') || 'till'
  return `epharm-diagnostics-${safe(command.pharmacyId)}-${safe(command.deviceId)}.zip`
}
