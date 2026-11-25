import { useEffect, useRef, useState } from 'react'
import { IconChevDown } from '@/ui/icons'
import {
  PRIMARY_TRAINING_TABS,
  TRAINING_NAVIGATION,
  type TrainingTab,
} from '@/app/trainingNavigation'

/**
 * The one navigation of «Обучение» (Grigoriy, 25.11.2026: two navigations, the sidebar submenu and the
 * page tabs, were one too many). The everyday tabs stay in sight; the rarely used ones sit under
 * «Ещё», so the bar fits without a scrollbar. The open tab always shows, even from «Ещё».
 */

export function TrainingTabs({
  value,
  onChange,
  aiExam,
}: {
  value: TrainingTab
  onChange: (tab: TrainingTab) => void
  /** Link to «AI-экзаменация» for roles that may open it. */
  aiExam?: { label: string; onOpen: () => void } | null
}) {
  const [open, setOpen] = useState(false)
  const menu = useRef<HTMLDivElement | null>(null)
  const primary = TRAINING_NAVIGATION.filter((item) => PRIMARY_TRAINING_TABS.includes(item.value))
  const secondary = TRAINING_NAVIGATION.filter(
    (item) => !PRIMARY_TRAINING_TABS.includes(item.value),
  )
  const currentSecondary = secondary.find((item) => item.value === value)

  useEffect(() => {
    if (!open) return
    const close = (event: MouseEvent | KeyboardEvent) => {
      if (
        event instanceof KeyboardEvent
          ? event.key === 'Escape'
          : !menu.current?.contains(event.target as Node)
      )
        setOpen(false)
    }
    document.addEventListener('mousedown', close)
    document.addEventListener('keydown', close)
    return () => {
      document.removeEventListener('mousedown', close)
      document.removeEventListener('keydown', close)
    }
  }, [open])

  return (
    <nav aria-label="Разделы обучения" className="hairline flex items-end gap-4 border-b">
      <div className="no-scrollbar flex min-w-0 flex-1 items-center gap-5 overflow-x-auto">
        {primary.map((item) => (
          <button
            key={item.value}
            type="button"
            data-training-tab={item.value}
            aria-current={value === item.value ? 'page' : undefined}
            className={`tab flex-none whitespace-nowrap ${value === item.value ? 'active' : ''}`}
            onClick={() => onChange(item.value)}
          >
            {item.label}
          </button>
        ))}
        {currentSecondary && (
          <button
            type="button"
            aria-current="page"
            className="tab active flex-none whitespace-nowrap"
          >
            {currentSecondary.label}
          </button>
        )}
      </div>
      <div ref={menu} className="relative flex-none">
        <button
          type="button"
          className="tab whitespace-nowrap"
          aria-haspopup="menu"
          aria-expanded={open}
          onClick={() => setOpen((current) => !current)}
        >
          Ещё <IconChevDown size={14} className={open ? 'rotate-180' : ''} />
        </button>
        {open && (
          <div
            role="menu"
            className="absolute right-0 top-full z-30 mt-1 w-60 overflow-hidden rounded-lg border border-ink-100 bg-white py-1 shadow-lg"
          >
            {secondary.map((item) => (
              <button
                key={item.value}
                type="button"
                role="menuitem"
                className={`flex w-full items-center gap-2 px-3 py-2 text-left text-[13px] font-semibold hover:bg-paper-hover ${value === item.value ? 'text-brand-green-700' : 'text-ink-700'}`}
                onClick={() => {
                  setOpen(false)
                  onChange(item.value)
                }}
              >
                <item.Icon size={15} className="text-ink-400" />
                {item.sidebarLabel}
              </button>
            ))}
            {aiExam && (
              <button
                type="button"
                role="menuitem"
                className="flex w-full items-center gap-2 border-t border-ink-100 px-3 py-2 text-left text-[13px] font-semibold text-ink-700 hover:bg-paper-hover"
                onClick={() => {
                  setOpen(false)
                  aiExam.onOpen()
                }}
              >
                {aiExam.label}
              </button>
            )}
          </div>
        )}
      </div>
    </nav>
  )
}
