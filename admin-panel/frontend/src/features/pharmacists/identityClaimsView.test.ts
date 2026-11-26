import { describe, expect, it } from 'vitest'
import { translate } from '@/i18n'
import type { IdentityClaimDto } from '@/lib/api-types'
import { confirmQuestion, conflictText, splitClaims } from './identityClaimsView'
import { identityIsSelfConfirmed, identitySourceLine } from './identitySource'

const ru = (key: string, vars?: Record<string, string | number>) => translate('ru', key, vars)
const kk = (key: string, vars?: Record<string, string | number>) => translate('kk', key, vars)

function claim(over: Partial<IdentityClaimDto> = {}): IdentityClaimDto {
  return {
    id: 'c1',
    kind: 'person',
    status: 'pending',
    pharmacyId: 'ph_1',
    pharmacyName: 'Бузурбаева 23',
    standardNUserId: '18',
    standardNUserName: 'Бузурбаева Л',
    iin: '900115300013',
    fullName: 'Бузурбаева Лаура Ермековна',
    pharmacistId: null,
    pharmacistName: null,
    conflicts: [],
    directoryIin: null,
    decidedByName: null,
    decidedAt: null,
    decisionReason: null,
    createdAt: '2026-11-26T10:00:00Z',
    ...over,
  }
}

const NOW = new Date('2026-11-26T12:00:00Z').getTime()
const DAY = 24 * 60 * 60 * 1000

describe('splitClaims', () => {
  it('puts open claims first tab, decided ones in the third, and a directory contradiction of the last month in the second', () => {
    const open = claim({ id: 'open' })
    const flagged = claim({
      id: 'flagged',
      status: 'superseded',
      decidedAt: new Date(NOW - 2 * DAY).toISOString(),
      conflicts: [{ code: 'directory_iin_differs', directoryIin: '850615400016' }],
    })
    const oldFlag = claim({
      id: 'old',
      status: 'superseded',
      decidedAt: new Date(NOW - 45 * DAY).toISOString(),
      conflicts: [{ code: 'directory_iin_differs', directoryIin: '850615400016' }],
    })
    const confirmed = claim({
      id: 'done',
      status: 'confirmed',
      decidedAt: new Date(NOW - DAY).toISOString(),
    })
    const split = splitClaims([open, flagged, oldFlag, confirmed], NOW)
    expect(split.open.map((row) => row.id)).toEqual(['open'])
    expect(split.flags.map((row) => row.id)).toEqual(['flagged'])
    expect(split.done.map((row) => row.id)).toEqual(['flagged', 'old', 'done'])
  })

  it('handles no claims', () => {
    expect(splitClaims([], NOW)).toEqual({ open: [], flags: [], done: [] })
  })
})

describe('conflictText', () => {
  it('names the other card, its pharmacy and its status', () => {
    const text = conflictText(ru, {
      code: 'iin_other_card',
      pharmacistName: 'Другая Карточка',
      pharmacyName: 'Ауэзова 134',
      pharmacistStatus: 'blocked',
    })
    expect(text).toBe('ИИН уже у другой карточки: Другая Карточка, Ауэзова 134, Заблокирован')
  })

  it('names the directory IIN that contradicts the typed one, in both languages', () => {
    const conflict = { code: 'directory_iin_differs' as const, directoryIin: '850615400016' }
    expect(conflictText(ru, conflict)).toContain('850615400016')
    expect(conflictText(kk, conflict)).toContain('850615400016')
    expect(conflictText(kk, conflict)).not.toBe(conflictText(ru, conflict))
  })

  it('says who has the same IIN in the directory and shows an unknown code from a newer server as it is', () => {
    expect(
      conflictText(ru, { code: 'iin_in_directory', directoryName: 'Из Справочника Иванович' }),
    ).toBe('Такой ИИН есть в справочнике Стандарт-Н у Из Справочника Иванович')
    expect(conflictText(ru, { code: 'brand_new_code' as never })).toBe('brand_new_code')
  })
})

describe('confirmQuestion', () => {
  it('shows the full IIN, the name and the pharmacy before a person claim is confirmed', () => {
    const text = confirmQuestion(ru, claim())
    expect(text).toContain('Аптека: Бузурбаева 23')
    expect(text).toContain('ИИН: 900115300013')
    expect(text).toContain('ФИО: Бузурбаева Лаура Ермековна')
    expect(text).not.toContain('Есть вопросы')
  })

  it('lists every contradiction so that it is read before the button is pressed', () => {
    const text = confirmQuestion(
      ru,
      claim({
        conflicts: [
          {
            code: 'iin_other_card',
            pharmacistName: 'Другая Карточка',
            pharmacyName: 'Ауэзова 134',
            pharmacistStatus: 'active',
          },
          { code: 'iin_other_claim' },
        ],
      }),
    )
    expect(text).toContain('Есть вопросы:')
    expect(text).toContain('- ИИН уже у другой карточки: Другая Карточка, Ауэзова 134, Активен')
    expect(text).toContain('- Такой ИИН указан и в другой заявке')
  })

  it('asks about the card name for a name claim', () => {
    const text = confirmQuestion(ru, claim({ kind: 'name', pharmacistName: 'Бузурбаева' }))
    expect(text).toContain('Сейчас: Бузурбаева')
    expect(text).toContain('Будет: Бузурбаева Лаура Ермековна')
  })
})

describe('identity source line', () => {
  it('joins one source for both, and names each when they differ', () => {
    expect(identitySourceLine(ru, 'standardn', 'standardn')).toBe('ИИН и ФИО: из Стандарт-Н')
    expect(identitySourceLine(ru, 'self', 'self')).toBe('ИИН и ФИО: указал(а) сам(а), подтверждено')
    expect(identitySourceLine(ru, 'standardn', 'self')).toBe(
      'ИИН: из Стандарт-Н · ФИО: указал(а) сам(а), подтверждено',
    )
    expect(identitySourceLine(kk, 'standardn', 'standardn')).toBe('ЖСН және аты-жөні: Стандарт-Нан')
  })

  it('is empty for an older backend and ignores a source it does not know', () => {
    expect(identitySourceLine(ru, undefined, undefined)).toBeNull()
    expect(identitySourceLine(ru, null, null)).toBeNull()
    expect(identitySourceLine(ru, 'weird' as never, 'weird' as never)).toBeNull()
    expect(identitySourceLine(ru, 'hq', undefined)).toBe('ИИН: внесено в офисе')
  })

  it('marks a card the pharmacist typed and the office confirmed', () => {
    expect(identityIsSelfConfirmed('self', 'standardn')).toBe(true)
    expect(identityIsSelfConfirmed('standardn', 'self')).toBe(true)
    expect(identityIsSelfConfirmed('standardn', 'standardn')).toBe(false)
    expect(identityIsSelfConfirmed(undefined, undefined)).toBe(false)
  })
})
