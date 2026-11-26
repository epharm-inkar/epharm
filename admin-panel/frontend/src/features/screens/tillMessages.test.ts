import { describe, expect, it } from 'vitest'
import { translate } from '@/i18n'
import type { PosmMessageDto } from '@/lib/api-types'
import {
  EMPTY_DRAFT,
  TEXT_MAX,
  TITLE_MAX,
  audienceLabel,
  buildAudienceRequest,
  buildRequest,
  courseReminder,
  draftError,
  messageState,
  shortDate,
  share,
} from './tillMessages'

const t = (key: string, vars?: Record<string, string | number>) => translate('ru', key, vars)

function message(overrides: Partial<PosmMessageDto> = {}): PosmMessageDto {
  return {
    id: 'm1',
    title: 'Инвентаризация',
    text: 'Завтра в 9:00',
    audience: 'all',
    targetCount: 0,
    targetNames: [],
    recipientsTotal: 40,
    shownCount: 12,
    closedCount: 9,
    createdAt: '2026-11-25T09:00:00Z',
    createdBy: 'hq@example.test',
    expiresAt: '2026-12-02T09:00:00Z',
    withdrawnAt: null,
    withdrawnBy: null,
    active: true,
    ...overrides,
  }
}

describe('черновик уведомления', () => {
  it('нужны заголовок, текст и выбор адресатов', () => {
    expect(draftError(EMPTY_DRAFT)).toBe('msg.errTitle')
    expect(draftError({ ...EMPTY_DRAFT, title: '  ' })).toBe('msg.errTitle')
    expect(draftError({ ...EMPTY_DRAFT, title: 'Заголовок' })).toBe('msg.errText')
    const ready = { ...EMPTY_DRAFT, title: 'Заголовок', text: 'Текст' }
    expect(draftError(ready)).toBeNull()
    expect(draftError({ ...ready, audience: 'pharmacies' })).toBe('msg.errPharmacies')
    expect(draftError({ ...ready, audience: 'pharmacists' })).toBe('msg.errPharmacists')
    expect(draftError({ ...ready, audience: 'pharmacists', pharmacistIds: ['ph_1'] })).toBeNull()
  })

  it('для «не прошли курс» нужен курс', () => {
    const ready = { ...EMPTY_DRAFT, title: 'Заголовок', text: 'Текст', audience: 'course' as const }
    expect(draftError(ready)).toBe('msg.errCourse')
    expect(draftError({ ...ready, programId: 'c1' })).toBeNull()
  })

  it('пределы те же, что на сервере', () => {
    const ready = { ...EMPTY_DRAFT, title: 'Т', text: 'Т' }
    expect(draftError({ ...ready, title: 'я'.repeat(TITLE_MAX) })).toBeNull()
    expect(draftError({ ...ready, title: 'я'.repeat(TITLE_MAX + 1) })).toBe('msg.errTitleLong')
    expect(draftError({ ...ready, text: 'я'.repeat(TEXT_MAX + 1) })).toBe('msg.errTextLong')
  })

  it('в запрос уходит только список выбранной группы и обрезанные тексты', () => {
    const base = { ...EMPTY_DRAFT, title: ' Заголовок ', text: ' Текст ', pharmacyIds: ['ph_a'], pharmacistIds: ['p_1'] }
    expect(buildRequest(base)).toEqual({ title: 'Заголовок', text: 'Текст', audience: 'all', days: 7 })
    expect(buildRequest({ ...base, audience: 'pharmacies', days: 3 })).toEqual({
      title: 'Заголовок',
      text: 'Текст',
      audience: 'pharmacies',
      days: 3,
      pharmacyIds: ['ph_a'],
    })
    expect(buildRequest({ ...base, audience: 'pharmacists' })).toEqual({
      title: 'Заголовок',
      text: 'Текст',
      audience: 'pharmacists',
      days: 7,
      pharmacistIds: ['p_1'],
    })
  })
})

describe('кому уйдёт: запрос подсчёта и отправки', () => {
  const ready = { ...EMPTY_DRAFT, title: ' Курс ', text: ' Пройдите ' }

  it('всем: только audience, переключатель «за кассой» только когда он включён', () => {
    expect(buildAudienceRequest(ready)).toEqual({ audience: 'all' })
    expect(buildAudienceRequest({ ...ready, onlyAtTill: true })).toEqual({ audience: 'all', onlyAtTill: true })
    expect(buildRequest({ ...ready, onlyAtTill: true })).toEqual({
      title: 'Курс',
      text: 'Пройдите',
      days: 7,
      audience: 'all',
      onlyAtTill: true,
    })
  })

  it('курс уходит с programId, переключатель доходит и до курса', () => {
    const course = { ...ready, audience: 'course' as const, programId: 'c1' }
    expect(buildAudienceRequest(course)).toEqual({ audience: 'course', programId: 'c1' })
    expect(buildAudienceRequest({ ...course, onlyAtTill: true })).toEqual({ audience: 'course', programId: 'c1', onlyAtTill: true })
    expect(buildRequest(course)).toEqual({ title: 'Курс', text: 'Пройдите', days: 7, audience: 'course', programId: 'c1' })
  })

  it('пока выбор неполный, подсчёта нет: курс без курса, аптеки и люди без списка', () => {
    expect(buildAudienceRequest({ ...ready, audience: 'course' })).toBeNull()
    expect(buildAudienceRequest({ ...ready, audience: 'pharmacies' })).toBeNull()
    expect(buildAudienceRequest({ ...ready, audience: 'pharmacists' })).toBeNull()
    expect(buildAudienceRequest({ ...ready, audience: 'pharmacies', pharmacyIds: ['a'], onlyAtTill: true })).toEqual({
      audience: 'pharmacies',
      pharmacyIds: ['a'],
      onlyAtTill: true,
    })
    expect(buildAudienceRequest({ ...ready, audience: 'pharmacists', pharmacistIds: ['p'] })).toEqual({
      audience: 'pharmacists',
      pharmacistIds: ['p'],
    })
  })

  it('запрос не делится с черновиком списками: правка черновика не меняет уже построенный запрос', () => {
    const draft = { ...ready, audience: 'pharmacists' as const, pharmacistIds: ['p1'] }
    const request = buildAudienceRequest(draft)
    draft.pharmacistIds.push('p2')
    expect(request).toEqual({ audience: 'pharmacists', pharmacistIds: ['p1'] })
  })
})

describe('напоминание о курсе', () => {
  it('«до ДД.ММ» пишем, когда у всех один срок и он впереди или сегодня', () => {
    expect(courseReminder({ name: 'Витамины группы B', dueOn: '2026-11-30' }, '2026-11-26')).toEqual({
      title: 'Напоминание о курсе',
      text: 'Пройдите курс «Витамины группы B» до 30.11: Мой кабинет → Обучение',
    })
    expect(courseReminder({ name: 'Сорбенты', dueOn: '2026-11-26' }, '2026-11-26').text).toBe(
      'Пройдите курс «Сорбенты» до 26.11: Мой кабинет → Обучение',
    )
  })

  it('без даты, когда сроки разные или их нет, и когда срок уже прошёл', () => {
    expect(courseReminder({ name: 'Сорбенты', dueOn: null }, '2026-11-26').text).toBe('Пройдите курс «Сорбенты»: Мой кабинет → Обучение')
    expect(courseReminder({ name: 'Сорбенты', dueOn: '2026-11-20' }, '2026-11-26').text).toBe(
      'Пройдите курс «Сорбенты»: Мой кабинет → Обучение',
    )
  })

  it('кавычки в названии не удваиваются, текст короче лимита и без длинных тире', () => {
    const text = courseReminder({ name: '  «Пожарная безопасность»  ', dueOn: null }, '2026-11-26').text
    expect(text).toBe('Пройдите курс «Пожарная безопасность»: Мой кабинет → Обучение')
    expect(text).not.toMatch(/[\u2013\u2014]/)
    const long = courseReminder({ name: 'я'.repeat(255), dueOn: '2026-12-31' }, '2026-11-26')
    expect([...long.text].length).toBeLessThanOrEqual(TEXT_MAX)
    expect([...long.title].length).toBeLessThanOrEqual(TITLE_MAX)
  })

  it('дата короткая: 2026-11-26 это 26.11', () => {
    expect(shortDate('2026-11-26')).toBe('26.11')
    expect(shortDate('2027-01-05')).toBe('05.01')
  })
})

describe('подписи отправленных уведомлений', () => {
  it('курс и «только за кассой» дописаны в подпись', () => {
    expect(audienceLabel(t, message({ audience: 'course', programName: 'Витамины группы B', onlyAtTill: true }))).toBe(
      'не прошли курс «Витамины группы B», только тем, кто был за кассой при отправке',
    )
    expect(audienceLabel(t, message({ audience: 'course', programName: 'Сорбенты' }))).toBe('не прошли курс «Сорбенты»')
    expect(audienceLabel(t, message({ onlyAtTill: true }))).toBe('всем фармацевтам, только тем, кто был за кассой при отправке')
    expect(
      audienceLabel(t, message({ audience: 'pharmacists', targetCount: 1, targetNames: ['Сапарова Айгерим'], onlyAtTill: true })),
    ).toBe('сотрудники (1): Сапарова Айгерим, только тем, кто был за кассой при отправке')
  })

  it('кому: всем, аптеки или сотрудники с первыми именами', () => {
    expect(audienceLabel(t, message())).toBe('всем фармацевтам')
    expect(
      audienceLabel(t, message({ audience: 'pharmacies', targetCount: 3, targetNames: ['Абая 103', 'Толе би 286'] })),
    ).toBe('аптеки (3): Абая 103, Толе би 286 и ещё 1')
    expect(audienceLabel(t, message({ audience: 'pharmacists', targetCount: 1, targetNames: ['Сапарова Айгерим'] }))).toBe(
      'сотрудники (1): Сапарова Айгерим',
    )
  })

  it('состояние: на кассах, снято, срок прошёл', () => {
    expect(messageState(message())).toBe('active')
    expect(messageState(message({ active: false, withdrawnAt: '2026-11-25T10:00:00Z' }))).toBe('withdrawn')
    expect(messageState(message({ active: false }))).toBe('expired')
  })

  it('доля для полосы без деления на ноль', () => {
    expect(share(12, 40)).toBe(30)
    expect(share(5, 0)).toBe(0)
    expect(share(50, 40)).toBe(100)
  })
})
