// Metric — admin §10.1 — KPI tile (4-up grid на дашборде)
import type { ReactNode } from 'react'
import { HelpTip } from './HelpTip'
import { IconArrowUp, IconArrowDown } from './icons'

type Accent = 'green' | 'blue' | 'amber' | 'purple' | 'ink'

interface MetricProps {
  label: string
  value: ReactNode
  sub?: ReactNode
  delta?: number
  icon?: ReactNode
  accent?: Accent
  meta?: ReactNode
  /** Plain explanation of the figure; shown as a "?" next to the label. */
  help?: ReactNode
}

const TINT: Record<Accent, string> = {
  green: 'border-brand-green-100 bg-brand-green-50 text-brand-green-700',
  blue: 'border-brand-blue-100 bg-brand-green-50 text-brand-blue-700',
  amber: 'border-amber-200 bg-amber-50 text-amber-700',
  purple: 'border-purple-200 bg-purple-50 text-purple-700',
  ink: 'border-ink-200 bg-ink-50 text-ink-700',
}

export function Metric({
  label,
  value,
  sub,
  delta,
  icon,
  accent = 'green',
  meta,
  help,
}: MetricProps) {
  return (
    <div className="card flex flex-col gap-2.5 p-4">
      <div className="flex items-start justify-between gap-2">
        <span className="flex min-w-0 items-start gap-1.5 text-[12px] font-semibold leading-5 text-ink-500">
          <span className="min-w-0 break-words">{label}</span>
          {help && (
            <span className="mt-0.5">
              <HelpTip topic={label}>{help}</HelpTip>
            </span>
          )}
        </span>
        {icon && (
          <span className={`flex h-8 w-8 items-center justify-center rounded-md border ${TINT[accent]}`}>
            {icon}
          </span>
        )}
      </div>
      {/* The figure never breaks in two ("1 110 000" / "₸"); a long note drops under it instead. */}
      <div className="flex flex-wrap items-baseline gap-x-2 gap-y-1">
        <span className="num whitespace-nowrap text-[26px] font-bold leading-none tracking-[-0.015em] text-ink-900">
          {value}
        </span>
        {sub && <span className="text-sm font-semibold text-ink-500">{sub}</span>}
      </div>
      <div className="flex items-center justify-between">
        {meta && <span className="text-[12px] font-semibold text-ink-500">{meta}</span>}
        {delta != null && (
          <span className={`chip ml-auto ${delta >= 0 ? 'chip-green' : 'chip-red'}`}>
            {delta >= 0 ? <IconArrowUp size={11} /> : <IconArrowDown size={11} />}
            {Math.abs(delta).toFixed(1)}%
          </span>
        )}
      </div>
    </div>
  )
}
