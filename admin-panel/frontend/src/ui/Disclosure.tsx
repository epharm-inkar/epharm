// Disclosure: a titled card that is folded by default and says in one line what is inside it.
// Built on the native <details>, so the keyboard (Enter, Space) and screen readers work without
// extra code, and everything inside stays mounted: a folded field keeps what was typed in it.

import type { ReactNode } from 'react'
import { IconChevDown } from './icons'

interface DisclosureProps {
  title: string
  /** One short line under the title while the block is folded: what is inside and what is set. */
  summary?: ReactNode
  open: boolean
  onOpenChange: (open: boolean) => void
  children: ReactNode
  testId?: string
}

export function Disclosure({
  title,
  summary,
  open,
  onOpenChange,
  children,
  testId,
}: DisclosureProps) {
  return (
    <details
      open={open}
      onToggle={(event) => onOpenChange(event.currentTarget.open)}
      className="card group"
      data-testid={testId}
    >
      <summary className="flex cursor-pointer list-none items-start justify-between gap-3 rounded-xl px-5 py-4 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-green-600/40 [&::-webkit-details-marker]:hidden">
        <span className="min-w-0">
          <span className="block text-[13px] font-extrabold uppercase tracking-[0.06em] text-ink-500">
            {title}
          </span>
          {summary && !open && (
            <span
              className="mt-1 block break-words text-[13px] leading-5 text-ink-600"
              data-testid={testId ? `${testId}-summary` : undefined}
            >
              {summary}
            </span>
          )}
        </span>
        <IconChevDown
          size={18}
          className="mt-0.5 flex-none text-ink-400 transition-transform group-open:rotate-180"
        />
      </summary>
      <div className="flex flex-col gap-4 px-5 pb-5">{children}</div>
    </details>
  )
}
