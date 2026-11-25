// Menu — выпадающее меню «Ещё ▾» и меню действий строки (⋯).
// Список рисуется поверх страницы (portal, position: fixed), поэтому его не обрезает контейнер
// таблицы с собственной прокруткой. Закрывается выбором пункта, кликом мимо, Escape, прокруткой.

import { useEffect, useId, useRef, useState, type ReactNode } from 'react'
import { createPortal } from 'react-dom'

export interface MenuItem {
  id: string
  label: ReactNode
  onSelect?: () => void
  disabled?: boolean
  /** Second line under the label: why an item is disabled or what it does not include. */
  hint?: string
  danger?: boolean
}

interface MenuProps {
  /** Trigger content (text and/or icon). */
  trigger: ReactNode
  /** Accessible name of the trigger when it shows only an icon. */
  ariaLabel?: string
  items: MenuItem[]
  triggerClassName?: string
  width?: number
  testId?: string
}

interface Position {
  top: number
  left: number
  maxHeight: number
}

const GAP = 4
const ITEM = 34
const ITEM_WITH_HINT = 50

export function Menu({ trigger, ariaLabel, items, triggerClassName = '', width = 248, testId }: MenuProps) {
  const [position, setPosition] = useState<Position | null>(null)
  const triggerRef = useRef<HTMLButtonElement>(null)
  const listRef = useRef<HTMLDivElement>(null)
  const menuId = useId()
  const open = position !== null

  const close = (restoreFocus: boolean) => {
    setPosition(null)
    if (restoreFocus) triggerRef.current?.focus({ preventScroll: true })
  }

  const openMenu = () => {
    const rect = triggerRef.current?.getBoundingClientRect()
    const viewportWidth = window.innerWidth || 1024
    const viewportHeight = window.innerHeight || 768
    const estimated = items.reduce((sum, item) => sum + (item.hint ? ITEM_WITH_HINT : ITEM), 8)
    const left = Math.max(8, Math.min((rect?.right ?? 0) - width, viewportWidth - width - 8))
    const below = (rect?.bottom ?? 0) + GAP
    const top = below + estimated > viewportHeight && (rect?.top ?? 0) > estimated + GAP
      ? (rect?.top ?? 0) - estimated - GAP
      : below
    // A list longer than the room left scrolls inside itself instead of leaving the screen.
    setPosition({ top, left, maxHeight: Math.max(120, viewportHeight - top - 8) })
  }

  useEffect(() => {
    if (!open) return
    listRef.current?.querySelector<HTMLElement>('[role="menuitem"]:not([disabled])')?.focus({ preventScroll: true })
    const onPointer = (event: MouseEvent) => {
      const target = event.target as Node
      if (triggerRef.current?.contains(target) || listRef.current?.contains(target)) return
      setPosition(null)
    }
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault()
        setPosition(null)
        triggerRef.current?.focus({ preventScroll: true })
        return
      }
      if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return
      const entries = Array.from(
        listRef.current?.querySelectorAll<HTMLElement>('[role="menuitem"]:not([disabled])') ?? [],
      )
      if (!entries.length) return
      event.preventDefault()
      const current = entries.indexOf(document.activeElement as HTMLElement)
      const step = event.key === 'ArrowDown' ? 1 : -1
      entries[(current + step + entries.length) % entries.length].focus()
    }
    // A fixed list would drift away from its trigger when anything scrolls or resizes.
    const onMove = (event: Event) => {
      if (listRef.current?.contains(event.target as Node)) return
      setPosition(null)
    }
    document.addEventListener('mousedown', onPointer)
    document.addEventListener('keydown', onKey)
    window.addEventListener('scroll', onMove, true)
    window.addEventListener('resize', onMove)
    return () => {
      document.removeEventListener('mousedown', onPointer)
      document.removeEventListener('keydown', onKey)
      window.removeEventListener('scroll', onMove, true)
      window.removeEventListener('resize', onMove)
    }
  }, [open])

  return (
    <>
      <button
        ref={triggerRef}
        type="button"
        aria-label={ariaLabel}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
        data-testid={testId}
        onClick={(event) => {
          event.stopPropagation()
          if (open) close(false)
          else openMenu()
        }}
        className={triggerClassName}
      >
        {trigger}
      </button>
      {open &&
        createPortal(
          <div
            ref={listRef}
            id={menuId}
            role="menu"
            style={{ position: 'fixed', top: position.top, left: position.left, width, maxHeight: position.maxHeight }}
            className="card z-[60] overflow-y-auto py-1 shadow-elevated"
          >
            {items.map((item) => (
              <button
                key={item.id}
                type="button"
                role="menuitem"
                disabled={item.disabled}
                title={item.disabled ? item.hint : undefined}
                onClick={() => {
                  if (item.disabled) return
                  close(true)
                  item.onSelect?.()
                }}
                className={`flex w-full flex-col items-start px-3 py-1.5 text-left text-[13px] font-semibold leading-5 outline-none hover:bg-paper-hover focus-visible:bg-paper-hover disabled:cursor-default disabled:text-ink-300 disabled:hover:bg-transparent ${
                  item.danger ? 'text-accent-danger' : 'text-ink-800'
                }`}
              >
                <span>{item.label}</span>
                {item.hint && (
                  <span className="text-[11px] font-medium leading-4 text-ink-500">{item.hint}</span>
                )}
              </button>
            ))}
          </div>,
          document.body,
        )}
    </>
  )
}
