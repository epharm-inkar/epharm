// «Сейчас за кассой»: короткие подписи времени и места. Чистые функции без React, чтобы их проверяли обычные
// unit-тесты. Время показываем в поясе того, кто смотрит админку (без timeZone), как везде в админке.

import type { AtTillItemDto } from '@/lib/api-types'
import type { TFn } from './tillMessages'

const HOUR_MIN = 60

/** «09:15», а если не в тот же день, что и [reference], то «25.11, 21:40». */
export function clockLabel(at: string, reference: string): string {
  const date = new Date(at)
  const time = date.toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' })
  const sameDay = date.toLocaleDateString('sv-SE') === new Date(reference).toLocaleDateString('sv-SE')
  if (sameDay) return time
  return `${date.toLocaleDateString('ru-RU', { day: '2-digit', month: '2-digit' })}, ${time}`
}

/** «26.11 14:05»: дата и время последнего раза за кассой. */
export function dateTimeLabel(at: string): string {
  const date = new Date(at)
  return `${date.toLocaleDateString('ru-RU', { day: '2-digit', month: '2-digit' })} ${date.toLocaleTimeString('ru-RU', {
    hour: '2-digit',
    minute: '2-digit',
  })}`
}

/** Сколько прошло: «только что», «7 мин назад», «1 ч 05 мин назад». */
export function agoLabel(t: TFn, at: string, reference: string): string {
  const minutes = Math.max(0, Math.floor((new Date(reference).getTime() - new Date(at).getTime()) / 60_000))
  if (minutes < 1) return t('att.justNow')
  return t('att.ago', { span: spanLabel(t, minutes) })
}

/** «45 мин», «1 ч 05 мин». */
export function spanLabel(t: TFn, minutes: number): string {
  if (minutes < HOUR_MIN) return t('att.minutes', { n: minutes })
  const rest = minutes % HOUR_MIN
  return rest === 0
    ? t('att.hours', { n: Math.floor(minutes / HOUR_MIN) })
    : t('att.hoursMinutes', { h: Math.floor(minutes / HOUR_MIN), m: String(rest).padStart(2, '0') })
}

/** «с 09:15 (1 ч 20 мин)»: с какого времени человек за этой кассой. */
export function sinceLabel(t: TFn, item: Pick<AtTillItemDto, 'since'>, observedAt: string): string {
  const minutes = Math.max(0, Math.floor((new Date(observedAt).getTime() - new Date(item.since).getTime()) / 60_000))
  return t('att.since', { time: clockLabel(item.since, observedAt), span: spanLabel(t, minutes) })
}

/** «Аптека со склада, Абая 103»: название аптеки и адрес, если адрес не повторяет название. */
export function placeLabel(item: Pick<AtTillItemDto, 'pharmacyName' | 'address'>): string {
  const address = item.address.trim()
  return address && !item.pharmacyName.includes(address) ? `${item.pharmacyName}, ${address}` : item.pharmacyName
}
