// «ИИН указали сами»: что показать HQ. Чистые функции без React: вкладки и тексты расхождений.

import type { IdentityClaimConflictDto, IdentityClaimDto } from '@/lib/api-types'

type Translate = (key: string, vars?: Record<string, string | number>) => string

export type ClaimTab = 'open' | 'flags' | 'done'

/** A contradiction with the Standard-N directory stays in «Расхождения» for a month after the directory spoke. */
const FLAG_WINDOW_MS = 30 * 24 * 60 * 60 * 1000

export interface SplitClaims {
  /** Waiting for HQ. */
  open: IdentityClaimDto[]
  /** The directory brought another IIN than the one typed: HQ looks at it, nothing was merged. */
  flags: IdentityClaimDto[]
  /** Everything that is decided, confirmed, rejected or superseded. */
  done: IdentityClaimDto[]
}

export function splitClaims(rows: IdentityClaimDto[], now: number): SplitClaims {
  const decided = rows.filter((row) => row.status !== 'pending')
  return {
    open: rows.filter((row) => row.status === 'pending'),
    flags: decided.filter(
      (row) =>
        row.conflicts.some((conflict) => conflict.code === 'directory_iin_differs') &&
        !!row.decidedAt &&
        now - new Date(row.decidedAt).getTime() <= FLAG_WINDOW_MS,
    ),
    done: decided,
  }
}

function statusLabel(t: Translate, status: string | null | undefined): string {
  if (status === 'active') return t('phc.sActive')
  if (status === 'pending') return t('phc.sPending')
  if (status === 'blocked') return t('phc.sBlocked')
  return status ?? ''
}

/** One line about a reason to look closer. An unknown code from a newer server is shown as its code, not hidden. */
export function conflictText(t: Translate, conflict: IdentityClaimConflictDto): string {
  const key = `idc.conflict.${conflict.code}`
  const text = t(key, {
    name: conflict.pharmacistName ?? conflict.directoryName ?? '',
    pharmacy: conflict.pharmacyName ?? t('idc.noPharmacy'),
    status: statusLabel(t, conflict.pharmacistStatus),
    iin: conflict.directoryIin ?? '',
  })
  return text === key ? conflict.code : text
}

/** The question HQ answers with «Подтвердить», with the full IIN and every contradiction listed. */
export function confirmQuestion(t: Translate, claim: IdentityClaimDto): string {
  const warnings = claim.conflicts.length
    ? t('idc.warnings', {
        list: claim.conflicts.map((conflict) => `- ${conflictText(t, conflict)}`).join('\n'),
      })
    : ''
  return claim.kind === 'name'
    ? t('idc.confirmAskName', {
        current: claim.pharmacistName ?? '',
        name: claim.fullName,
        warnings,
      })
    : t('idc.confirmAsk', {
        pharmacy: claim.pharmacyName ?? claim.pharmacyId ?? t('idc.noPharmacy'),
        iin: claim.iin,
        name: claim.fullName,
        warnings,
      })
}
