// Dense-table formatting: digit groups split by a thin space that never wraps (U+202F),
// money always in full tenge, dates in the viewer's own time zone (no timeZone option).

export const THIN_SPACE = '\u202F'

const integer = new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 0 })

/** 1742 → «1 742» with a narrow no-break space between digit groups. */
export function formatCount(value: number): string {
  return integer.format(value).replace(/[\s\u00A0\u202F]/g, THIN_SPACE)
}

/** 125000 → «125 000 ₸»: thin spaces inside the number, an ordinary no-break space before ₸. */
export function formatTenge(value: number): string {
  return `${formatCount(value)}\u00A0₸`
}

const day = new Intl.DateTimeFormat('ru-RU', { day: '2-digit', month: '2-digit', year: 'numeric' })
const dayTime = new Intl.DateTimeFormat('ru-RU', {
  day: '2-digit',
  month: '2-digit',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
})

function parsed(value: string | null | undefined): Date | null {
  if (!value) return null
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? null : date
}

/** «25.11.2026»; null for a missing or unreadable value. */
export function formatDay(value: string | null | undefined): string | null {
  const date = parsed(value)
  return date ? day.format(date) : null
}

/** «25.11.2026, 14:32»; null for a missing or unreadable value. */
export function formatDayTime(value: string | null | undefined): string | null {
  const date = parsed(value)
  return date ? dayTime.format(date) : null
}
