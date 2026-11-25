// Pure helpers of the pharmacist registry: filters, counters and labels (no React).

import type {
  PharmacistDto,
  PharmacistStatus,
  PharmacyDto,
  TrainingFormat,
  TrainingPreferenceDto,
} from '@/lib/api-types'

export type StatusFilter = 'all' | PharmacistStatus
export type FormatFilter = TrainingFormat | 'all' | 'unset'

export interface RegistryFilters {
  status: StatusFilter
  chain: string
  city: string
  format: FormatFilter
  query: string
}

export const ALL = 'all'

/** «[ДЕМО] …», «[ТЕСТ] …» rows stay until the owner decides; they are marked, never deleted here. */
export function isTestRecord(name: string): boolean {
  return /^\s*\[(ДЕМО|ТЕСТ|DEMO|TEST)\]/i.test(name)
}

/** Older backends do not send hasPhone: fall back to the number when it is visible. */
export function hasPhone(pharmacist: PharmacistDto): boolean {
  return pharmacist.hasPhone ?? Boolean(pharmacist.phone?.trim())
}

export const SOURCE_LABEL: Record<string, string> = {
  standardn: 'Стандарт-Н',
  registration: 'Регистрация',
}

export function sourceLabel(pharmacist: PharmacistDto): string | null {
  return pharmacist.accountSource ? (SOURCE_LABEL[pharmacist.accountSource] ?? null) : null
}

export interface RegistryCounters {
  total: number
  withPhone: number
  withPassword: number
  fromStandardN: number
  byStatus: Record<PharmacistStatus, number>
}

export function countRegistry(pharmacists: readonly PharmacistDto[]): RegistryCounters {
  const counters: RegistryCounters = {
    total: pharmacists.length,
    withPhone: 0,
    withPassword: 0,
    fromStandardN: 0,
    byStatus: { active: 0, pending: 0, blocked: 0 },
  }
  for (const pharmacist of pharmacists) {
    if (hasPhone(pharmacist)) counters.withPhone++
    if (pharmacist.hasPassword) counters.withPassword++
    if (pharmacist.accountSource === 'standardn') counters.fromStandardN++
    counters.byStatus[pharmacist.status]++
  }
  return counters
}

export function chainOf(pharmacist: PharmacistDto, pharmacies: ReadonlyMap<string, PharmacyDto>): string {
  return pharmacies.get(pharmacist.pharmacyId)?.chainName ?? ''
}

/** Distinct non-empty values in Russian order, for the toolbar selects. */
export function distinctSorted(values: Iterable<string>): string[] {
  return [...new Set([...values].map((value) => value.trim()).filter(Boolean))].sort((a, b) =>
    a.localeCompare(b, 'ru'),
  )
}

export function filterRegistry(
  pharmacists: readonly PharmacistDto[],
  filters: RegistryFilters,
  pharmacies: ReadonlyMap<string, PharmacyDto>,
  preferences: ReadonlyMap<string, TrainingPreferenceDto>,
): PharmacistDto[] {
  const query = filters.query.trim().toLowerCase()
  return pharmacists.filter((pharmacist) => {
    if (filters.status !== ALL && pharmacist.status !== filters.status) return false
    if (filters.chain !== ALL && chainOf(pharmacist, pharmacies) !== filters.chain) return false
    if (filters.city !== ALL && pharmacist.city !== filters.city) return false
    const preference = preferences.get(pharmacist.id)
    if (filters.format === 'unset' && preference) return false
    if (filters.format !== ALL && filters.format !== 'unset' && preference?.defaultFormat !== filters.format)
      return false
    if (!query) return true
    // Hidden IIN/phone are null: never search the literal word «null».
    return [
      pharmacist.name,
      pharmacist.pharmacyName,
      pharmacist.city,
      chainOf(pharmacist, pharmacies),
      pharmacist.iin ?? '',
      pharmacist.phone ?? '',
    ]
      .join(' ')
      .toLowerCase()
      .includes(query)
  })
}

export function isFiltered(filters: RegistryFilters): boolean {
  return (
    filters.status !== ALL ||
    filters.chain !== ALL ||
    filters.city !== ALL ||
    filters.format !== ALL ||
    filters.query.trim() !== ''
  )
}
