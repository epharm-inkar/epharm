// Где взяли ИИН и ФИО карточки фармацевта (26.11.2026): из Стандарт-Н, указал(а) сам(а) и подтвердил офис,
// при регистрации или внёс офис. Чистые функции: подписи берутся из словаря (ru и kk).

import type { IdentitySource } from '@/lib/api-types'

type Translate = (key: string, vars?: Record<string, string | number>) => string

const SOURCES: readonly IdentitySource[] = ['registration', 'standardn', 'self', 'hq']

function known(source: IdentitySource | null | undefined): source is IdentitySource {
  return !!source && SOURCES.includes(source)
}

/** The short label of one source, «из Стандарт-Н» or «указал(а) сам(а), подтверждено». */
export function identitySourceLabel(t: Translate, source: IdentitySource): string {
  return t(`idc.src.${source}`)
}

/**
 * One line for the roster: «ИИН и ФИО: из Стандарт-Н» when both came from the same place, otherwise each on its own.
 * Null for an older backend that sends no source.
 */
export function identitySourceLine(
  t: Translate,
  iin: IdentitySource | null | undefined,
  name: IdentitySource | null | undefined,
): string | null {
  if (known(iin) && known(name) && iin === name) {
    return `${t('idc.src.both')}: ${identitySourceLabel(t, iin)}`
  }
  const parts: string[] = []
  if (known(iin)) parts.push(`${t('idc.src.iin')}: ${identitySourceLabel(t, iin)}`)
  if (known(name)) parts.push(`${t('idc.src.name')}: ${identitySourceLabel(t, name)}`)
  return parts.length ? parts.join(' · ') : null
}

/** A card whose data a pharmacist typed and the office confirmed gets the green line. */
export function identityIsSelfConfirmed(
  iin: IdentitySource | null | undefined,
  name: IdentitySource | null | undefined,
): boolean {
  return iin === 'self' || name === 'self'
}
