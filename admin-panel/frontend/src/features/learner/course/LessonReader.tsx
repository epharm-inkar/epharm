import { useEffect, useMemo, useRef, useState } from 'react'
import {
  AlertTriangle,
  ArrowLeft,
  Check,
  ChevronRight,
  Lightbulb,
  MessageSquareQuote,
} from 'lucide-react'
import { useLearnerT } from '../learner-i18n'
import { lessonSections, readingSeconds, type ContentBlock } from './lessonContent'

/**
 * The lesson text one part at a time, like a story: a strip of segments on top, one part on the
 * screen, «Дальше» below. On the first reading «Дальше» opens after a short reading time that only
 * runs while the page is visible (Grigoriy, 25.11.2026: «чтобы люди реально читали»). A finished
 * lesson reads freely.
 */
export function LessonReader({
  content,
  gated,
  onFinished,
  finishLabel,
}: {
  content: string
  /** True on the first reading: each new part waits for its reading time. */
  gated: boolean
  onFinished: () => void
  /** Text of the button on the last part, e.g. «К самопроверке». */
  finishLabel: string
}) {
  const t = useLearnerT()
  const sections = useMemo(() => lessonSections(content), [content])
  const [part, setPart] = useState(0)
  const [readParts, setReadParts] = useState<Set<number>>(() => new Set())
  const [elapsed, setElapsed] = useState(0)
  const top = useRef<HTMLDivElement | null>(null)
  const current = sections[Math.min(part, sections.length - 1)]
  const needed = current ? readingSeconds(current) : 0
  // A part counts as read once its reading time has passed; going back to it later does not wait again.
  const currentRead = readParts.has(part) || elapsed >= needed
  const waiting = gated && !currentRead
  const remaining = waiting ? Math.max(0, needed - elapsed) : 0

  useEffect(() => {
    if (!waiting) return
    const timer = window.setInterval(() => {
      // Time counts only while the page is on screen.
      if (typeof document !== 'undefined' && document.visibilityState === 'hidden') return
      setElapsed((value) => value + 1)
    }, 1000)
    return () => window.clearInterval(timer)
  }, [waiting, part])

  if (!current) return null
  const last = part === sections.length - 1

  const go = (next: number) => {
    if (currentRead) setReadParts((done) => new Set(done).add(part))
    setPart(next)
    setElapsed(0)
    top.current?.scrollIntoView?.({ behavior: 'smooth', block: 'start' })
  }

  return (
    <section ref={top} className="lesson-reader scroll-mt-24" aria-label={t('Материал урока')}>
      {sections.length > 1 && (
        <div className="mb-4">
          <div className="flex gap-1.5" aria-hidden="true">
            {sections.map((section, index) => {
              const fill =
                index < part || readParts.has(index) || (!gated && index <= part)
                  ? 100
                  : index === part
                    ? currentRead
                      ? 100
                      : Math.min(100, Math.round((elapsed / Math.max(1, needed)) * 100))
                    : 0
              return (
                <span
                  key={`${section.title ?? 'intro'}-${index}`}
                  className="h-1.5 flex-1 overflow-hidden rounded-full bg-ink-100"
                >
                  <span
                    className="block h-full rounded-full bg-brand-green-600 transition-[width] duration-1000 ease-linear"
                    style={{ width: `${fill}%` }}
                  />
                </span>
              )
            })}
          </div>
          <div className="mt-2 text-xs font-bold text-ink-400">
            {t('Часть {current} из {total}', { current: part + 1, total: sections.length })}
          </div>
        </div>
      )}

      <div key={part} className="lesson-reader-part">
        {current.title && (
          <h3 className="mb-4 text-xl font-extrabold leading-7 text-ink-900 sm:text-2xl sm:leading-8">
            {current.title}
          </h3>
        )}
        <div className="space-y-4">
          {current.blocks.map((block, index) => (
            <LessonBlock key={`${block.kind}-${index}`} block={block} />
          ))}
        </div>
      </div>

      <div className="mt-7 flex items-center justify-between gap-3 border-t border-ink-100 pt-5">
        <button
          type="button"
          className="btn btn-outline btn-md"
          disabled={part === 0}
          onClick={() => go(Math.max(0, part - 1))}
        >
          <ArrowLeft size={16} /> {t('Назад')}
        </button>
        <button
          type="button"
          className="btn btn-primary btn-md min-w-[9.5rem]"
          disabled={remaining > 0}
          aria-live="polite"
          onClick={() => (last ? onFinished() : go(part + 1))}
        >
          {remaining > 0 ? (
            t('Читайте ещё {seconds} с', { seconds: remaining })
          ) : last ? (
            <>
              <Check size={17} /> {finishLabel}
            </>
          ) : (
            <>
              {t('Дальше')} <ChevronRight size={17} />
            </>
          )}
        </button>
      </div>
    </section>
  )
}

export function LessonBlock({ block }: { block: ContentBlock }) {
  switch (block.kind) {
    case 'paragraph':
      return <p className="lesson-text">{block.text}</p>
    case 'quote':
      return (
        <blockquote className="flex gap-3 rounded-2xl bg-brand-green-50 px-4 py-3 text-[15px] font-semibold leading-6 text-brand-green-800 sm:text-base">
          <MessageSquareQuote className="mt-0.5 shrink-0" size={18} aria-hidden="true" />
          <span>{block.text}</span>
        </blockquote>
      )
    case 'list': {
      const List = block.ordered ? 'ol' : 'ul'
      return (
        <div>
          {block.lead && (
            <p className="lesson-text mb-2 font-semibold text-ink-800">{block.lead}</p>
          )}
          <List className="grid gap-2">
            {block.items.map((item, index) => (
              <li key={`${item}-${index}`} className="flex items-start gap-3">
                <span
                  className="mt-[0.45rem] flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-brand-green-100 text-[11px] font-extrabold text-brand-green-700"
                  aria-hidden="true"
                >
                  {block.ordered ? index + 1 : <Check size={12} strokeWidth={3} />}
                </span>
                <span className="lesson-text min-w-0">{item.replace(/[;,]$/, '')}</span>
              </li>
            ))}
          </List>
        </div>
      )
    }
    case 'definitions':
      return (
        <div className="grid gap-2.5 sm:grid-cols-2">
          {block.items.map((item) => (
            <div key={item.term} className="rounded-2xl border border-ink-100 bg-white p-4">
              <div className="text-[15px] font-extrabold leading-6 text-ink-900">{item.term}</div>
              <div className="mt-1 text-[15px] leading-6 text-ink-600">{item.text}</div>
            </div>
          ))}
        </div>
      )
    case 'steps':
      return (
        <ol className="relative grid gap-4 pl-1">
          {block.items.map((item, index) => (
            <li key={item.title} className="relative flex gap-4">
              {index < block.items.length - 1 && (
                <span
                  className="absolute left-[15px] top-9 h-[calc(100%-1.25rem)] w-0.5 bg-brand-green-100"
                  aria-hidden="true"
                />
              )}
              <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-brand-green-600 text-sm font-extrabold text-white">
                {index + 1}
              </span>
              <span className="min-w-0 pt-1">
                <span className="block text-base font-extrabold leading-6 text-ink-900">
                  {item.title}
                </span>
                {item.text && <span className="lesson-text mt-1 block">{item.text}</span>}
              </span>
            </li>
          ))}
        </ol>
      )
    case 'callout': {
      const tone = {
        warning: { box: 'border-red-200 bg-red-50 text-red-900', Icon: AlertTriangle },
        example: { box: 'border-amber-200 bg-amber-50 text-amber-950', Icon: MessageSquareQuote },
        tip: {
          box: 'border-brand-green-200 bg-brand-green-50 text-brand-green-900',
          Icon: Lightbulb,
        },
      }[block.tone]
      return (
        <aside className={`rounded-2xl border p-4 ${tone.box}`}>
          <div className="flex items-center gap-2 text-sm font-extrabold uppercase tracking-[0.06em]">
            <tone.Icon size={17} aria-hidden="true" /> {block.title}
          </div>
          <p className="mt-2 text-[15px] leading-6 sm:text-base sm:leading-7">{block.text}</p>
        </aside>
      )
    }
  }
}
