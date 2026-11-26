// ФИО, которое фармацевт предлагает в кабинете (26.11.2026): не меньше двух слов, только буквы, дефис и апостроф.
// Сервер проверяет те же правила (IdentityClaimRules.kt) и решает сам; здесь только быстрый ответ без запроса.

/** A word: letters of any script, a hyphen or an apostrophe inside, at least two letters. */
const WORD = /^\p{L}[\p{L}\p{M}'’ʼ-]*\p{L}$/u
const MAX_LENGTH = 255
const MAX_WORDS = 6

/** Trimmed, one space between words, composed form; control characters become spaces. */
export function normalizeName(raw: string): string {
  return raw
    .normalize('NFC')
    .replace(/[\p{Cc}\p{Cf}]/gu, ' ')
    .replace(/\s+/gu, ' ')
    .trim()
}

/** A card name of fewer than two words (a surname alone, a nickname) is the case the cabinet lets a person fix. */
export function isIncompleteName(name: string | null | undefined): boolean {
  return (
    normalizeName(name ?? '')
      .split(' ')
      .filter(Boolean).length < 2
  )
}

export type NameProblem = 'words' | 'letters' | 'long'

/** Why [raw] is not a full name, or null when it is one. */
export function nameProblem(raw: string): NameProblem | null {
  const name = normalizeName(raw)
  if (name.length > MAX_LENGTH) return 'long'
  const words = name.split(' ').filter(Boolean)
  if (words.length < 2 || words.length > MAX_WORDS) return 'words'
  if (words.some((word) => !WORD.test(word))) return 'letters'
  return null
}
