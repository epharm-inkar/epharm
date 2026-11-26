import { describe, expect, it } from 'vitest'
import { isIncompleteName, nameProblem, normalizeName } from './identityName'

// The same vectors as IdentityClaimRulesTest.kt (server) and IdentityClaimRulesTests.cs (till).
describe('full name typed in the cabinet', () => {
  it.each([
    'Ахметова Айгерим Нурлановна',
    'Нұрсая Әбдіқалық Серікқызы',
    'Ким Алексей',
    'Мухамедьярова-Ли Мадина Бекзатовна',
    "Д'Артаньян Шарль",
    'Aigerim Nurlanovna',
  ])('accepts %s', (name) => {
    expect(nameProblem(name)).toBeNull()
  })

  it.each(['', '   ', 'Айгерим', 'Бузурбаева'])('asks for two words instead of %j', (name) => {
    expect(nameProblem(name)).toBe('words')
  })

  it.each(['А. Б.', 'Иванов 2 Иван', 'Иванов Иван 87771234567', 'Иванов Иван!!!', 'а б'])(
    'refuses initials, digits and symbols in %j',
    (name) => {
      expect(nameProblem(name)).toBe('letters')
    },
  )

  it('refuses seven words and a very long name', () => {
    expect(nameProblem('Аа Бб Вв Гг Дд Ее Жж')).toBe('words')
    expect(nameProblem('Иванов ' + 'а'.repeat(260))).toBe('long')
  })

  it('collapses spaces, tabs, non-breaking spaces and control characters', () => {
    expect(normalizeName('  Иванов \tИван \n Иванович  ')).toBe('Иванов Иван Иванович')
    expect(normalizeName('Иванов​ Иван')).toBe('Иванов Иван')
    expect(nameProblem('  Иванов   Иван ')).toBeNull()
  })

  it('calls a card name of fewer than two words incomplete, initials count as words', () => {
    expect(isIncompleteName('Бузурбаева')).toBe(true)
    expect(isIncompleteName('  Лаура  ')).toBe(true)
    expect(isIncompleteName('')).toBe(true)
    expect(isIncompleteName(null)).toBe(true)
    expect(isIncompleteName('Бузурбаева Лаура')).toBe(false)
    expect(isIncompleteName('Иванова А.')).toBe(false)
  })
})
