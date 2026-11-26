// SectionCard — admin §14 — белая карточка с заголовком + action + body
import type { ReactNode } from 'react'
import { HelpTip } from './HelpTip'

interface SectionCardProps {
  title?: string
  subtitle?: string
  action?: ReactNode
  /** Plain explanation of the block; shown as a "?" next to the title. */
  help?: ReactNode
  children: ReactNode
  padded?: boolean
  className?: string
}

export function SectionCard({
  title,
  subtitle,
  action,
  help,
  children,
  padded = true,
  className = '',
}: SectionCardProps) {
  return (
    <div className={`card ${className}`}>
      {(title || action) && (
        <div className="hairline flex flex-wrap items-center justify-between gap-x-4 gap-y-2 border-b px-5 py-3.5">
          <div className="min-w-0">
            {title && (
              <div className="flex items-center gap-2 text-sm font-bold text-ink-900">
                <span className="min-w-0 break-words">{title}</span>
                {help && <HelpTip topic={title}>{help}</HelpTip>}
              </div>
            )}
            {subtitle && <div className="mt-0.5 text-[13px] text-ink-500">{subtitle}</div>}
          </div>
          {action}
        </div>
      )}
      <div className={padded ? 'p-5' : ''}>{children}</div>
    </div>
  )
}
