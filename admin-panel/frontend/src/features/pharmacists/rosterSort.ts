import type { PharmacistDto } from '@/lib/api-types'

/**
 * Sorting of the pharmacist roster (INK-156, 25.11.2026: «продажи и заработок каждого в админке»).
 * «name» keeps the server order (by name); the money and activity orders put the largest first and the
 * unknown (no access to the figures, never active) last, so a click answers «кто больше всех продал».
 */
export type RosterSort = 'name' | 'sales' | 'earned' | 'activity'

const key: Record<Exclude<RosterSort, 'name'>, (p: PharmacistDto) => number | null> = {
  sales: (p) => p.sales30d ?? null,
  earned: (p) => p.earned30d ?? null,
  activity: (p) => (p.lastActiveAt ? new Date(p.lastActiveAt).getTime() : null),
}

export function sortRoster(rows: readonly PharmacistDto[], sort: RosterSort): PharmacistDto[] {
  if (sort === 'name') return [...rows]
  const value = key[sort]
  return [...rows].sort((left, right) => {
    const a = value(left)
    const b = value(right)
    if (a == null && b == null) return left.name.localeCompare(right.name, 'ru')
    if (a == null) return 1
    if (b == null) return -1
    return b - a || left.name.localeCompare(right.name, 'ru')
  })
}

/** «113,6 млн ₸» for a tile; exact amounts stay in the table. */
export function compactKzt(value: number, units: { million: string; thousand: string }): string {
  const format = (n: number) =>
    new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 1 }).format(n)
  if (Math.abs(value) >= 1_000_000) return `${format(value / 1_000_000)} ${units.million} ₸`
  if (Math.abs(value) >= 10_000) return `${format(value / 1_000)} ${units.thousand} ₸`
  return `${new Intl.NumberFormat('ru-RU').format(value)} ₸`
}

/** Sum of a nullable figure over the roster; null when any row hides it (no finance access). */
export function sumOrNull(
  rows: readonly PharmacistDto[],
  pick: (p: PharmacistDto) => number | null | undefined,
) {
  let total = 0
  for (const row of rows) {
    const value = pick(row)
    if (value == null) return null
    total += value
  }
  return total
}
