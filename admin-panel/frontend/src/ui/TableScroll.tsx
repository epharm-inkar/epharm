// TableScroll: the one wrapper for a table in the HQ admin.
// The table fills the width of its box (`.admin-console main table` in index.css). A table that is
// wider than the box (min-w-[...] on the table) scrolls inside this wrapper, never the whole page.
// Pass `label` for a wide table: the scroll area then is a focusable region, so the keyboard can
// scroll it too.

import type { ReactNode } from 'react'

interface TableScrollProps {
  children: ReactNode
  /** Accessible name of the scroll area. Set it for wide tables. */
  label?: string
  className?: string
}

export function TableScroll({ children, label, className = '' }: TableScrollProps) {
  const region = label ? { role: 'region', 'aria-label': label, tabIndex: 0 } : {}
  return (
    <div
      {...region}
      className={`scrollbar-thin w-full max-w-full overflow-x-auto focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-brand-green-400 ${className}`}
    >
      {children}
    </div>
  )
}
