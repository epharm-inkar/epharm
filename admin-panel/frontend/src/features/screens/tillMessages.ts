// Правила вкладки «Уведомления на кассах»: проверка черновика, тело запроса и короткие подписи.
// Чистые функции без React, чтобы их проверяли обычные unit-тесты.

import type {
  CreatePosmMessageRequest,
  PosmAudienceRequest,
  PosmCourseOptionDto,
  PosmMessageAudience,
  PosmMessageDto,
} from '@/lib/api-types'

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
  /** Курс для «Не прошли курс»; пусто, пока не выбран. */
  programId: string
  /** Любым адресатам: только тем, кто за кассой в момент отправки. */
  onlyAtTill: boolean
  days: number
  /**
   * Второй вариант на казахском (Григорий, 26.11.2026: «уведомления на казахском»). Касса покажет его тем, кто выбрал
   * казахский в кабинете; остальным уйдёт русский.
   */
  withKazakh: boolean
  titleKk: string
  textKk: string
}

export const EMPTY_DRAFT: TillMessageDraft = {
  title: '',
  text: '',
  audience: 'all',
  pharmacyIds: [],
  pharmacistIds: [],
  programId: '',
  onlyAtTill: false,
  days: DEFAULT_DAYS,
  withKazakh: false,
  titleKk: '',
  textKk: '',
}

/** Казахский заголовок и текст, если их начали писать. Оба пустые: казахского варианта нет, всем уйдёт русский. */
export function kazakhParts(
  draft: Pick<TillMessageDraft, 'withKazakh' | 'titleKk' | 'textKk'>,
): { title: string; text: string } | null {
  if (!draft.withKazakh) return null
  const title = draft.titleKk.trim()
  const text = draft.textKk.trim()
  return title || text ? { title, text } : null
}

/** Ключ текста ошибки для первой проблемы черновика или null, если можно отправлять. */
export function draftError(draft: TillMessageDraft): string | null {
  const title = draft.title.trim()
  const text = draft.text.trim()
  if (!title) return 'msg.errTitle'
  if ([...title].length > TITLE_MAX) return 'msg.errTitleLong'
  if (!text) return 'msg.errText'
  if ([...text].length > TEXT_MAX) return 'msg.errTextLong'
  const kazakh = kazakhParts(draft)
  if (kazakh) {
    if (!kazakh.title) return 'msg.errTitleKk'
    if ([...kazakh.title].length > TITLE_MAX) return 'msg.errTitleKkLong'
    if (!kazakh.text) return 'msg.errTextKk'
    if ([...kazakh.text].length > TEXT_MAX) return 'msg.errTextKkLong'
  }
  if (draft.audience === 'pharmacies' && draft.pharmacyIds.length === 0) return 'msg.errPharmacies'
  if (draft.audience === 'pharmacists' && draft.pharmacistIds.length === 0) return 'msg.errPharmacists'
  if (draft.audience === 'course' && !draft.programId) return 'msg.errCourse'
  return null
}

/** Кому уйдёт: только нужный адресатам список и переключатель «за кассой». Нет полного выбора: null. */
export function buildAudienceRequest(draft: TillMessageDraft): PosmAudienceRequest | null {
  const switches = draft.onlyAtTill ? { onlyAtTill: true } : {}
  if (draft.audience === 'all') return { audience: 'all', ...switches }
  if (draft.audience === 'pharmacies') {
    return draft.pharmacyIds.length > 0 ? { audience: 'pharmacies', pharmacyIds: [...draft.pharmacyIds], ...switches } : null
  }
  if (draft.audience === 'pharmacists') {
    return draft.pharmacistIds.length > 0 ? { audience: 'pharmacists', pharmacistIds: [...draft.pharmacistIds], ...switches } : null
  }
  return draft.programId ? { audience: 'course', programId: draft.programId, ...switches } : null
}

/** Тело POST /api/admin/posm-messages: только нужный адресатам список. */
export function buildRequest(draft: TillMessageDraft): CreatePosmMessageRequest {
  const audience = buildAudienceRequest(draft) ?? { audience: draft.audience }
  const kazakh = kazakhParts(draft)
  const kk = kazakh ? { titleKk: kazakh.title, textKk: kazakh.text } : {}
  return { title: draft.title.trim(), text: draft.text.trim(), days: draft.days, ...audience, ...kk }
}

/**
 * «Всем фармацевтам», «3 аптеки: Абая 103, Толе би 286 и ещё 1», «2 сотрудника: …», «не прошли курс «…»».
 * Если уведомление ушло только тем, кто был за кассой, это дописано в конце.
 */
export function audienceLabel(
  t: TFn,
  message: Pick<PosmMessageDto, 'audience' | 'targetCount' | 'targetNames'> &
    Partial<Pick<PosmMessageDto, 'onlyAtTill' | 'programName'>>,
): string {
  const suffix = message.onlyAtTill ? t('msg.onlyAtTillSuffix') : ''
  if (message.audience === 'all') return t('msg.toAll') + suffix
  if (message.audience === 'course') return t('msg.toCourse', { name: message.programName ?? '' }) + suffix
  const names = message.targetNames.join(', ')
  const rest = message.targetCount - message.targetNames.length
  const tail = rest > 0 ? t('msg.andMore', { n: rest }) : ''
  const key = message.audience === 'pharmacies' ? 'msg.toPharmacies' : 'msg.toPharmacists'
  return t(key, { n: message.targetCount, names: names + tail }) + suffix
}

/** Название курса в кавычках-ёлочках, не удваивая их, если они уже есть в названии. */
function quoted(name: string): string {
  const trimmed = name.trim()
  return trimmed.startsWith('«') && trimmed.endsWith('»') ? trimmed : `«${trimmed}»`
}

/** 26.11 из 2026-11-26. */
export function shortDate(isoDate: string): string {
  const [, month, day] = isoDate.split('-')
  return `${day}.${month}`
}

/**
 * Заголовок и текст напоминания о курсе (Григорий, 26.11.2026). «до ДД.ММ» пишем только когда у всех открытых заданий
 * один срок и он ещё впереди: иначе дата была бы неверной для части людей или уже прошла.
 */
export function courseReminder(
  course: Pick<PosmCourseOptionDto, 'name' | 'dueOn'>,
  today: string = new Date().toLocaleDateString('sv-SE'),
): { title: string; text: string } {
  const due = course.dueOn && course.dueOn >= today ? ` до ${shortDate(course.dueOn)}` : ''
  return {
    title: 'Напоминание о курсе',
    text: `Пройдите курс ${quoted(course.name)}${due}: Мой кабинет → Обучение`,
  }
}

/**
 * То же напоминание на казахском, для тех, кто выбрал казахский в кабинете. Пункт меню кассы назван как на кассе
 * («Мой кабинет»: окна кассы пока на русском), раздел кабинета как в казахском кабинете («Оқыту»).
 */
export function courseReminderKk(
  course: Pick<PosmCourseOptionDto, 'name' | 'dueOn'>,
  today: string = new Date().toLocaleDateString('sv-SE'),
): { title: string; text: string } {
  const due = course.dueOn && course.dueOn >= today ? ` ${shortDate(course.dueOn)} дейін` : ''
  return {
    title: 'Курс туралы еске салу',
    text: `${quoted(course.name)} курсын${due} өтіңіз: «Мой кабинет» → Оқыту`,
  }
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
