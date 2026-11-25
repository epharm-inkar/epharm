import { describe, expect, it } from 'vitest'
import { translate } from '@/i18n'
import type { PosmMessageDto } from '@/lib/api-types'
import {
  EMPTY_DRAFT,
  TEXT_MAX,
  TITLE_MAX,
  audienceLabel,
  buildRequest,
  draftError,
  messageState,
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

describe('подписи отправленных уведомлений', () => {
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
