// Сколько человек получат уведомление, до отправки (26.11.2026): считает сервер, он знает, кто сейчас за кассой.
// Показывает число адресатов, сколько из них за кассой сейчас и, когда людей выбирают при отправке, первых из них.

import { useT } from '@/i18n'
import type { PosmMessagePreviewDto } from '@/lib/api-types'
import { TillSeenBadge } from '@/features/pharmacists/TillSeenBadge'

export function AudiencePreview({
  enabled,
  data,
  loading,
  failed,
}: {
  /** Выбор полный: можно считать. */
  enabled: boolean
  data: PosmMessagePreviewDto | undefined
  loading: boolean
  failed: boolean
}) {
  const t = useT()
  if (!enabled) {
    return (
      <div className="rounded-lg bg-paper-hover px-3 py-2 text-[12px] text-ink-500" data-testid="till-message-count">
        {t('msg.countChoose')}
      </div>
    )
  }
  if (failed && !data) {
    return (
      <div
        className="rounded-lg border border-accent-danger/20 bg-surface-danger px-3 py-2 text-[12px] font-semibold text-surface-danger-strong"
        data-testid="till-message-count"
      >
        {t('msg.countError')}
      </div>
    )
  }
  if (!data) {
    return (
      <div className="rounded-lg bg-paper-hover px-3 py-2 text-[12px] text-ink-500" data-testid="till-message-count">
        {loading ? t('msg.countLoading') : t('msg.countChoose')}
      </div>
    )
  }
  const nobody = data.frozen && data.recipients === 0
  return (
    <div
      className={`rounded-lg px-3 py-2 text-[13px] ${nobody ? 'bg-surface-danger text-surface-danger-strong' : 'bg-paper-hover text-ink-800'}`}
      data-testid="till-message-count"
    >
      <div className="font-semibold">
        {nobody ? t('msg.countNobody') : t('msg.countLine', { n: data.recipients, atTill: data.atTillNow })}
      </div>
      {data.frozen && !nobody && <div className="mt-0.5 text-[12px] font-normal text-ink-500">{t('msg.countFrozen')}</div>}
      {data.sample.length > 0 && (
        <ul className="mt-1.5 flex flex-col gap-0.5 text-[12px] font-normal text-ink-700" data-testid="till-message-count-sample">
          {data.sample.map((person) => (
            <li key={person.id} className="flex flex-wrap items-center gap-x-2">
              <span className="font-semibold">{person.name}</span>
              <span className="text-ink-500">{person.pharmacyName}</span>
              <TillSeenBadge atTillNow={person.atTillNow} lastSeenAt={person.lastTillSeenAt} />
            </li>
          ))}
          {data.sampleMore > 0 && <li className="text-ink-500">{t('msg.andMore', { n: data.sampleMore }).trim()}</li>}
        </ul>
      )}
    </div>
  )
}
