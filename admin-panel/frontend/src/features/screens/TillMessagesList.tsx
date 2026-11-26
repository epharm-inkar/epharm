// Отправленные уведомления: кому, до какого числа на кассах, сколько человек увидели и закрыли.

import { Button, SectionCard } from '@/ui'
import { useT } from '@/i18n'
import type { PosmMessageDto } from '@/lib/api-types'
import { audienceLabel, localDateTime, messageState, share } from './tillMessages'

export function TillMessagesList({
  messages,
  loading,
  failed,
  onWithdraw,
}: {
  messages: PosmMessageDto[]
  loading: boolean
  failed: boolean
  onWithdraw: (message: PosmMessageDto) => void
}) {
  const t = useT()
  return (
    <SectionCard title={t('msg.listTitle')} subtitle={t('msg.listSubtitle')}>
      {failed ? (
        <div className="rounded-lg border border-accent-danger/20 bg-surface-danger px-3 py-2 text-[12px] font-semibold text-surface-danger-strong">
          {t('msg.listError')}
        </div>
      ) : messages.length === 0 ? (
        <div className="py-8 text-center text-[13px] text-ink-400">{loading ? t('msg.loading') : t('msg.empty')}</div>
      ) : (
        <ul className="flex flex-col gap-3" data-testid="till-messages-list">
          {messages.map((message) => (
            <MessageRow key={message.id} message={message} onWithdraw={onWithdraw} />
          ))}
        </ul>
      )}
    </SectionCard>
  )
}

function MessageRow({ message, onWithdraw }: { message: PosmMessageDto; onWithdraw: (message: PosmMessageDto) => void }) {
  const t = useT()
  const state = messageState(message)
  const chip =
    state === 'active'
      ? { text: t('msg.stateActive', { date: localDateTime(message.expiresAt) }), cls: 'bg-brand-green-100 text-brand-green-700' }
      : state === 'withdrawn'
        ? { text: t('msg.stateWithdrawn', { date: localDateTime(message.withdrawnAt ?? message.expiresAt) }), cls: 'bg-ink-100 text-ink-600' }
        : { text: t('msg.stateExpired'), cls: 'bg-ink-100 text-ink-500' }
  const total = message.recipientsTotal
  return (
    <li className="hairline rounded-xl border bg-white p-4" data-testid={`till-message-${message.id}`}>
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0 flex-1">
          <div className="text-[14px] font-extrabold text-ink-900">
            {message.title}
            {message.titleKk && (
              <span className="ml-2 rounded-full bg-paper-hover px-2 py-0.5 align-middle text-[11px] font-bold text-ink-600" title={message.titleKk}>
                {t('msg.kkBadge')}
              </span>
            )}
          </div>
          <div className="mt-0.5 text-[12px] text-ink-500">
            {localDateTime(message.createdAt)}, {message.createdBy}
          </div>
        </div>
        <span className={`rounded-full px-2.5 py-1 text-[11px] font-bold ${chip.cls}`}>{chip.text}</span>
      </div>
      <p className="mt-2 line-clamp-3 whitespace-pre-line text-[13px] text-ink-700">{message.text}</p>
      <div className="mt-2 text-[12px] font-semibold text-ink-600">{audienceLabel(t, message)}</div>
      <div className="mt-3 grid grid-cols-2 gap-3">
        <Counter label={t('msg.shown')} value={message.shownCount} total={total} testId={`till-message-${message.id}-shown`} />
        <Counter label={t('msg.closed')} value={message.closedCount} total={total} testId={`till-message-${message.id}-closed`} />
      </div>
      <div className="mt-2 flex flex-wrap items-center justify-between gap-2">
        <span className="text-[11px] text-ink-400">{t('msg.recipientsHint', { n: total })}</span>
        {state === 'active' && (
          <Button variant="outline" size="sm" onClick={() => onWithdraw(message)}>
            {t('msg.withdraw')}
          </Button>
        )}
      </div>
    </li>
  )
}

function Counter({ label, value, total, testId }: { label: string; value: number; total: number; testId: string }) {
  const t = useT()
  const percent = share(value, total)
  return (
    <div className="rounded-lg bg-paper-hover px-3 py-2" data-testid={testId}>
      <div className="flex items-baseline justify-between gap-2">
        <span className="text-[11px] font-bold uppercase tracking-[0.05em] text-ink-500">{label}</span>
        <span className="num text-[13px] font-extrabold text-ink-900">
          {total > 0 ? t('msg.countOf', { n: value, total }) : value}
        </span>
      </div>
      {total > 0 && (
        <div className="mt-1.5 h-1.5 overflow-hidden rounded-full bg-ink-100">
          <div className="h-full rounded-full bg-brand-green-600" style={{ width: `${percent}%` }} />
        </div>
      )}
    </div>
  )
}
