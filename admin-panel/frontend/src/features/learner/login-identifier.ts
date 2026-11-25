/** What a pharmacist typed in «ИИН или номер телефона», as the sign-in request sends it. */
export type LoginIdentifier = { iin: string } | { phone: string }

/**
 * Twelve digits are an IIN. A Kazakhstan number in a usual format (`+7 7XX…`, `87XX…`, `7XX…`, with spaces,
 * brackets or dashes) is a phone, sent as `+7XXXXXXXXXX`. Anything else is not a sign-in yet (null).
 */
export function parseLoginIdentifier(value: string): LoginIdentifier | null {
  const typed = value.trim()
  if (!/^\+?[\d\s()-]+$/.test(typed)) return null
  const digits = typed.replace(/\D/g, '')
  if (typed.startsWith('+')) {
    return digits.length === 11 && digits.startsWith('7') ? { phone: `+${digits}` } : null
  }
  if (digits.length === 12) return { iin: digits }
  if (digits.length === 11 && /^[78]/.test(digits)) return { phone: `+7${digits.slice(1)}` }
  if (digits.length === 10) return { phone: `+7${digits}` }
  return null
}
