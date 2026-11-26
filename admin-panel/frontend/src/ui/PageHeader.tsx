// PageHeader — admin §14 — единый шаблон шапки секции.
// 22/700 title + 13/500 subtitle (макс ширина 720px) + правый блок actions.
// Правило: не больше 2 кнопок в actions; остальное — через «Ещё ▾» dropdown.

import type { ReactNode } from 'react'
import { HelpTip } from './HelpTip'

interface PageHeaderProps {
  title: string
  subtitle?: string
  actions?: ReactNode
  /** Plain explanation of the page; shown as a "?" next to the title. */
  help?: ReactNode
}

export function PageHeader({ title, subtitle, actions, help }: PageHeaderProps) {
  return (
    <div className="flex flex-wrap items-start justify-between gap-4">
      {/* h1 stays a direct child of this block: the "?" sits beside it, the subtitle takes its own line. */}
      <div className="flex min-w-0 flex-wrap items-center gap-x-2">
        <h1 className="min-w-0 break-words text-[22px] font-bold leading-7 tracking-[-0.01em] text-ink-900">
          {title}
        </h1>
        {help && <HelpTip topic={title}>{help}</HelpTip>}
        {subtitle && (
          <p className="mt-0.5 max-w-[720px] basis-full text-[13px] leading-5 text-ink-500">
            {subtitle}
          </p>
        )}
      </div>
      {actions && <div className="flex max-w-full flex-wrap items-center gap-2">{actions}</div>}
    </div>
  )
}
