// «За кассой сейчас» или «был за кассой 26.11 14:05» (26.11.2026): виден в списке фармацевтов и при выборе людей
// для уведомления. Ничего не рисует, пока касса ни разу не называла этого человека (или backend старый).

import { useT } from '@/i18n'
import { dateTimeLabel } from '@/features/screens/atTillFormat'

export function TillSeenBadge({
  atTillNow,
  lastSeenAt,
}: {
  atTillNow?: boolean
  lastSeenAt?: string | null
}) {
  const t = useT()
  if (atTillNow) {
    return (
      <span
        className="inline-flex items-center gap-1 whitespace-nowrap rounded-full bg-emerald-50 px-2 py-0.5 text-[11px] font-bold text-emerald-700"
        data-testid="till-seen-now"
      >
        <span className="h-1.5 w-1.5 rounded-full bg-emerald-500" aria-hidden="true" />
        {t('phc.atTillNow')}
      </span>
    )
  }
  if (lastSeenAt) {
    return (
      <span className="whitespace-nowrap text-[11px] text-ink-400" data-testid="till-seen-last">
        {t('phc.wasAtTill', { date: dateTimeLabel(lastSeenAt) })}
      </span>
    )
  }
  return null
}
