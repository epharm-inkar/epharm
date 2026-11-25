// Правила вкладки «Уведомления на кассах»: проверка черновика, тело запроса и короткие подписи.
// Чистые функции без React, чтобы их проверяли обычные unit-тесты.

import type { CreatePosmMessageRequest, PosmMessageAudience, PosmMessageDto } from '@/lib/api-types'

export type TFn = (key: string, vars?: Record<string, string | number>) => string

/** Те же пределы, что на сервере (PosmMessageRules). */
export const TITLE_MAX = 120
export const TEXT_MAX = 1000
export const DAY_OPTIONS = [1, 3, 7, 14, 30] as const
export const DEFAULT_DAYS = 7

export interface TillMessageDraft {
  title: string
  text: string
  audience: PosmMessageAudience
  pharmacyIds: string[]
  pharmacistIds: string[]
  days: number
}

export const EMPTY_DRAFT: TillMessageDraft = {
  title: '',
  text: '',
  audience: 'all',
  pharmacyIds: [],
  pharmacistIds: [],
  days: DEFAULT_DAYS,
}

/** Ключ текста ошибки для первой проблемы черновика или null, если можно отправлять. */
export function draftError(draft: TillMessageDraft): string | null {
  const title = draft.title.trim()
  const text = draft.text.trim()
  if (!title) return 'msg.errTitle'
  if ([...title].length > TITLE_MAX) return 'msg.errTitleLong'
  if (!text) return 'msg.errText'
  if ([...text].length > TEXT_MAX) return 'msg.errTextLong'
  if (draft.audience === 'pharmacies' && draft.pharmacyIds.length === 0) return 'msg.errPharmacies'
  if (draft.audience === 'pharmacists' && draft.pharmacistIds.length === 0) return 'msg.errPharmacists'
  return null
}

/** Тело POST /api/admin/posm-messages: только нужный адресатам список. */
export function buildRequest(draft: TillMessageDraft): CreatePosmMessageRequest {
  const base = {
    title: draft.title.trim(),
    text: draft.text.trim(),
    audience: draft.audience,
    days: draft.days,
  }
  if (draft.audience === 'pharmacies') return { ...base, pharmacyIds: [...draft.pharmacyIds] }
  if (draft.audience === 'pharmacists') return { ...base, pharmacistIds: [...draft.pharmacistIds] }
  return base
}

/** «Всем фармацевтам», «3 аптеки: Абая 103, Толе би 286 и ещё 1», «2 сотрудника: …». */
export function audienceLabel(t: TFn, message: Pick<PosmMessageDto, 'audience' | 'targetCount' | 'targetNames'>): string {
  if (message.audience === 'all') return t('msg.toAll')
  const names = message.targetNames.join(', ')
  const rest = message.targetCount - message.targetNames.length
  const tail = rest > 0 ? t('msg.andMore', { n: rest }) : ''
  const key = message.audience === 'pharmacies' ? 'msg.toPharmacies' : 'msg.toPharmacists'
  return t(key, { n: message.targetCount, names: names + tail })
}

export type MessageState = 'active' | 'withdrawn' | 'expired'

export function messageState(message: Pick<PosmMessageDto, 'active' | 'withdrawnAt'>): MessageState {
  if (message.withdrawnAt) return 'withdrawn'
  return message.active ? 'active' : 'expired'
}

/** Доля в процентах (0-100) для полосы; без адресатов полосы нет. */
export function share(part: number, total: number): number {
  if (total <= 0) return 0
  return Math.max(0, Math.min(100, Math.round((part / total) * 100)))
}

/** Дата и время в поясе того, кто смотрит админку: «25.11, 14:05». */
export function localDateTime(at: string): string {
  return new Date(at).toLocaleString('ru-RU', {
    day: '2-digit',
    month: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}
