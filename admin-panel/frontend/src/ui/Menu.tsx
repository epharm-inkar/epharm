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

/** Under the trigger (above it when there is no room below); null when the trigger has left the screen. */
function placeMenu(trigger: HTMLElement | null, height: number, width: number): Position | null {
  if (!trigger) return null
  const rect = trigger.getBoundingClientRect()
  const viewportWidth = window.innerWidth || 1024
  const viewportHeight = window.innerHeight || 768
  if (rect.bottom < 0 || rect.top > viewportHeight) return null
  const left = Math.max(8, Math.min(rect.right - width, viewportWidth - width - 8))
  const below = rect.bottom + GAP
  const top = below + height > viewportHeight && rect.top > height + GAP ? rect.top - height - GAP : below
  // A list longer than the room left scrolls inside itself instead of leaving the screen.
  return { top, left, maxHeight: Math.max(120, viewportHeight - top - 8) }
}

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
    const estimated = items.reduce((sum, item) => sum + (item.hint ? ITEM_WITH_HINT : ITEM), 8)
    setPosition(placeMenu(triggerRef.current, estimated, width))
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
    // A fixed list follows its trigger when anything scrolls or resizes. Scroll events arrive a frame
    // late, so the scroll that brought the trigger into view must not close the menu it just opened.
    const onMove = (event: Event) => {
      const list = listRef.current
      // A window resize or scroll has the window as target, which is not a Node.
      if (event.target instanceof Node && list?.contains(event.target)) return
      setPosition(placeMenu(triggerRef.current, list?.offsetHeight ?? 0, list?.offsetWidth ?? 0))
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
