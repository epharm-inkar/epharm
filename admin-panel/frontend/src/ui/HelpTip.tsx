// HelpTip: a small "?" that explains a heading or a field in one to three plain sentences.
//
// Mouse: hover shows it, a click keeps it open. Touch: tap to open, tap anywhere else to close.
// Keyboard: Tab to the button, Enter or Space opens it, Escape or Tab away closes it.
// Screen reader: the button is named "Что это? <topic>" and the text is its description, so it is
// read without opening anything.
//
// The bubble is drawn in a fixed layer on <body> and kept inside the screen, so a card that clips
// its content, a table wrapper that scrolls and a 375 px phone do not cut it.

import { useCallback, useEffect, useId, useRef, useState, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { useT } from '@/i18n'

interface HelpTipProps {
  /** The explanation: one to three plain sentences. */
  children: ReactNode
  /** What the tip is about; goes into the button's name so the buttons of a page differ. */
  topic?: string
  className?: string
}

const MARGIN = 12
const GAP = 8
const MAX_WIDTH = 320

export function HelpTip({ children, topic, className = '' }: HelpTipProps) {
  const t = useT()
  const descId = useId()
  const [pinned, setPinned] = useState(false)
  const [hover, setHover] = useState(false)
  const open = pinned || hover
  const buttonRef = useRef<HTMLButtonElement>(null)
  const bubbleRef = useRef<HTMLSpanElement | null>(null)

  // Put the bubble next to the button, inside the screen: below it, above when there is no room.
  const place = useCallback(() => {
    const button = buttonRef.current
    const bubble = bubbleRef.current
    if (!button || !bubble) return
    const b = button.getBoundingClientRect()
    const viewW = document.documentElement.clientWidth
    const viewH = window.innerHeight
    const width = Math.min(MAX_WIDTH, viewW - MARGIN * 2)
    bubble.style.width = `${width}px`
    const height = bubble.offsetHeight
    const center = b.left + b.width / 2
    const left = Math.max(MARGIN, Math.min(center - width / 2, viewW - width - MARGIN))
    const below = b.bottom + GAP + height <= viewH - MARGIN || b.top - GAP - height < MARGIN
    bubble.style.left = `${left}px`
    bubble.style.top = `${below ? b.bottom + GAP : b.top - GAP - height}px`
    bubble.style.setProperty('--tip-arrow', `${Math.max(14, Math.min(center - left, width - 14))}px`)
    bubble.dataset.side = below ? 'below' : 'above'
  }, [])

  const setBubble = useCallback(
    (node: HTMLSpanElement | null) => {
      bubbleRef.current = node
      if (node) place()
    },
    [place],
  )

  useEffect(() => {
    if (!open) return
    const follow = () => {
      const b = buttonRef.current?.getBoundingClientRect()
      // The "?" scrolled out of sight: the bubble has nothing to point at.
      if (b && (b.bottom < 0 || b.top > window.innerHeight)) {
        setPinned(false)
        setHover(false)
        return
      }
      place()
    }
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setPinned(false)
        setHover(false)
      }
    }
    const onPointerDown = (event: PointerEvent) => {
      if (event.target instanceof Node && buttonRef.current?.contains(event.target)) return
      setPinned(false)
    }
    window.addEventListener('resize', follow)
    window.addEventListener('scroll', follow, true)
    document.addEventListener('keydown', onKey)
    document.addEventListener('pointerdown', onPointerDown)
    return () => {
      window.removeEventListener('resize', follow)
      window.removeEventListener('scroll', follow, true)
      document.removeEventListener('keydown', onKey)
      document.removeEventListener('pointerdown', onPointerDown)
    }
  }, [open, place])

  const label = topic ? `${t('help.label')} ${topic}` : t('help.label')

  return (
    <span className={`inline-flex shrink-0 align-middle ${className}`}>
      <button
        ref={buttonRef}
        type="button"
        aria-label={label}
        aria-expanded={open}
        aria-describedby={descId}
        data-testid="help-tip"
        onClick={() => setPinned((value) => !value)}
        onPointerEnter={(event) => event.pointerType === 'mouse' && setHover(true)}
        onPointerLeave={(event) => event.pointerType === 'mouse' && setHover(false)}
        onBlur={() => setPinned(false)}
        className="relative flex h-[18px] w-[18px] items-center justify-center rounded-full border border-ink-300 bg-paper-card text-[11px] font-bold leading-none text-ink-500 transition-colors before:absolute before:-inset-2 before:content-[''] hover:border-brand-green-600 hover:text-brand-green-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-green-600/40 aria-expanded:border-brand-green-600 aria-expanded:text-brand-green-700"
      >
        <span aria-hidden="true">?</span>
      </button>
      <span id={descId} className="sr-only">
        {children}
      </span>
      {open &&
        createPortal(
          <span
            ref={setBubble}
            aria-hidden="true"
            data-testid="help-tip-bubble"
            className="pointer-events-none fixed left-0 top-0 z-[85] rounded-lg bg-ink-900 px-3 py-2 text-left text-[12px] font-medium normal-case leading-[18px] tracking-normal text-white shadow-elevated before:absolute before:left-[var(--tip-arrow)] before:h-2 before:w-2 before:-translate-x-1/2 before:rotate-45 before:bg-ink-900 before:content-[''] data-[side=above]:before:-bottom-1 data-[side=below]:before:-top-1"
            style={{ width: MAX_WIDTH }}
          >
            {children}
          </span>,
          document.body,
        )}
    </span>
  )
}
