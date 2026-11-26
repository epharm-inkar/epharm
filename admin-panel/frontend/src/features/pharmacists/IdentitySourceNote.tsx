// Где взяли ИИН и ФИО карточки: одна строка под именем в реестре фармацевтов.
// Зелёным подсвечено то, что человек указал сам и подтвердил офис.

import { useT } from '@/i18n'
import type { IdentitySource } from '@/lib/api-types'
import { identityIsSelfConfirmed, identitySourceLine } from './identitySource'

export function IdentitySourceNote({
  iin,
  name,
}: {
  iin?: IdentitySource | null
  name?: IdentitySource | null
}) {
  const t = useT()
  const line = identitySourceLine(t, iin, name)
  if (!line) return null
  return (
    <div
      data-testid="identity-source"
      className={`text-[11px] ${identityIsSelfConfirmed(iin, name) ? 'font-semibold text-brand-green-700' : 'text-ink-400'}`}
    >
      {line}
    </div>
  )
}
