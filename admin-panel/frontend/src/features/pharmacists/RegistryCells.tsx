// Small cell pieces of the dense pharmacist registry.

import type { ReactNode } from 'react'
import { IconAlert, IconCheck, IconInfo } from '@/ui/icons'
import type { PharmacistDto } from '@/lib/api-types'

const TAG = 'inline-flex h-[18px] flex-none items-center rounded px-1.5 text-[11px] font-semibold leading-none'

const TONE = {
  ink: 'bg-ink-100 text-ink-600',
  amber: 'bg-surface-warning text-surface-warning-strong',
  red: 'bg-surface-danger text-surface-danger-strong',
} as const

export function Tag({ tone, children }: { tone: keyof typeof TONE; children: ReactNode }) {
  return <span className={`${TAG} ${TONE[tone]}`}>{children}</span>
}

/** A tick for «yes»; the label is what a screen reader and the test read. */
export function Present({ label }: { label: string }) {
  return (
    <span role="img" aria-label={label} className="inline-flex align-middle text-ink-600">
      <IconCheck size={14} stroke={2.2} />
    </span>
  )
}

/** Standard-N diagnostics are rare: an icon with the full explanation on hover instead of a second line. */
export function IinDiagnostic({
  pharmacist,
  t,
}: {
  pharmacist: PharmacistDto
  t: (key: string) => string
}) {
  const iinMismatch =
    pharmacist.standardNIinMatchStatus === 'MISMATCH' ||
    (pharmacist.standardNIdentityMatchMethod === 'IIN' &&
      pharmacist.standardNIdentityMatchStatus === 'MISMATCH')
  const byName = pharmacist.standardNIdentityMatchMethod === 'NAME'
  const kind = iinMismatch
    ? 'iin'
    : byName && pharmacist.standardNIdentityMatchStatus === 'MISMATCH'
      ? 'nameMismatch'
      : byName && pharmacist.standardNIdentityMatchStatus === 'MATCH'
        ? 'nameMatch'
        : null
  if (!kind) return null
  const label = {
    iin: ['phc.standardNIinMismatch', 'phc.standardNIinMismatchHint'],
    nameMismatch: ['phc.standardNNameMismatch', 'phc.standardNNameMismatchHint'],
    nameMatch: ['phc.standardNNameMatch', 'phc.standardNNameMatchHint'],
  }[kind]
  return (
    <span
      role="img"
      aria-label={t(label[0])}
      title={t(label[1])}
      className={`inline-flex flex-none ${kind === 'nameMatch' ? 'text-ink-500' : 'text-accent-warning'}`}
    >
      {kind === 'nameMatch' ? <IconInfo size={13} /> : <IconAlert size={13} />}
    </span>
  )
}
