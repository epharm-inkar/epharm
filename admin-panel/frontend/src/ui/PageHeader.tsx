// PageHeader — admin §14 — единый шаблон шапки секции.
// 22/700 title + 13/500 subtitle (макс ширина 720px) + правый блок actions.
// Правило: не больше 2 кнопок в actions; остальное — через «Ещё ▾» dropdown.
// meta: короткая строка счётчиков в одну строку с заголовком (плотные таблицы вместо плиток KPI).

import type { ReactNode } from 'react'

interface PageHeaderProps {
  title: string
  subtitle?: string
  meta?: ReactNode
  actions?: ReactNode
}

export function PageHeader({ title, subtitle, meta, actions }: PageHeaderProps) {
  return (
    <div className={`flex flex-wrap justify-between gap-4 ${meta ? 'items-center gap-y-2' : 'items-start'}`}>
      <div className="min-w-0">
        <div className="flex min-w-0 flex-wrap items-baseline gap-x-3 gap-y-0.5">
          <h1 className="text-[22px] font-bold leading-7 tracking-[-0.01em] text-ink-900">{title}</h1>
          {meta && <div className="min-w-0 text-[12px] leading-5 text-ink-500">{meta}</div>}
        </div>
        {subtitle && (
          <p className="mt-0.5 max-w-[720px] text-[13px] leading-5 text-ink-500">{subtitle}</p>
        )}
      </div>
      {actions && <div className="flex max-w-full flex-wrap items-center gap-2">{actions}</div>}
    </div>
  )
}
