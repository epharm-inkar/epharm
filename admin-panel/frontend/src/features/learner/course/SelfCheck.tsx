import { useEffect, useMemo, useRef, useState } from 'react'
import { CheckCircle2, RotateCcw, XCircle } from 'lucide-react'
import { useLearnerT } from '../learner-i18n'
import { seededOptions, seededOrder } from './shuffle'

export type SelfCheckQuestion = {
  id: string
  prompt: string
  options: string[]
  correctOption: number
  explanation?: string
}

/**
 * Questions after the lesson text, one at a time, in the Duolingo manner: choose, «Проверить»,
 * see right away whether it is right and why. A wrong answer comes back at the end of the round,
 * so the lesson ends only when every question has been answered correctly.
 */
export function SelfCheck({
  questions,
  seed,
  passed,
  onPassed,
}: {
  questions: SelfCheckQuestion[]
  seed: string
  passed: boolean
  onPassed: () => void
}) {
  const t = useLearnerT()
  const ordered = useMemo(() => seededOrder(questions, `${seed}:order`), [questions, seed])
  const [queue, setQueue] = useState<string[]>(() => ordered.map((question) => question.id))
  const [selected, setSelected] = useState<number | null>(null)
  const [checked, setChecked] = useState(false)
  const [solved, setSolved] = useState<Set<string>>(() => new Set())
  const [practice, setPractice] = useState(false)
  const reported = useRef(false)
  const byId = useMemo(
    () => new Map(questions.map((question) => [question.id, question])),
    [questions],
  )
  const done = queue.length === 0

  useEffect(() => {
    if (done && !reported.current) {
      reported.current = true
      onPassed()
    }
  }, [done, onPassed])

  if (passed && !practice) {
    return (
      <section className="rounded-2xl border border-brand-green-200 bg-brand-green-50 p-4 sm:p-5">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-2 font-extrabold text-brand-green-800">
            <CheckCircle2 size={20} /> {t('Самопроверка пройдена')}
          </div>
          <button
            type="button"
            className="btn btn-outline btn-sm"
            onClick={() => {
              setPractice(true)
              setQueue(ordered.map((question) => question.id))
              setSolved(new Set())
              setSelected(null)
              setChecked(false)
              reported.current = true
            }}
          >
            <RotateCcw size={15} /> {t('Повторить')}
          </button>
        </div>
      </section>
    )
  }

  if (done) {
    return (
      <section
        className="rounded-2xl border border-brand-green-200 bg-brand-green-50 p-5 text-center"
        aria-live="polite"
      >
        <CheckCircle2 className="mx-auto text-brand-green-600" size={40} />
        <div className="mt-2 text-lg font-extrabold text-brand-green-900">
          {t('Все ответы верные')}
        </div>
        <p className="mt-1 text-sm text-brand-green-800">{t('Урок можно завершить.')}</p>
      </section>
    )
  }

  const question = byId.get(queue[0])
  if (!question) return null
  const options = seededOptions(question.options, `${seed}:${question.id}`)
  const correct = selected !== null && options[selected]?.original === question.correctOption
  const progress = Math.round((solved.size / questions.length) * 100)

  const check = () => {
    if (selected === null) return
    setChecked(true)
    if (correct) setSolved((current) => new Set(current).add(question.id))
  }
  const next = () => {
    setQueue((current) => (correct ? current.slice(1) : [...current.slice(1), current[0]]))
    setSelected(null)
    setChecked(false)
  }

  return (
    <section
      className="overflow-hidden rounded-3xl border border-ink-100 bg-white shadow-card"
      aria-label={t('Проверьте себя')}
    >
      <div className="border-b border-ink-100 p-4 sm:p-5">
        <div className="flex items-center justify-between gap-3">
          <div>
            <div className="text-xs font-extrabold uppercase tracking-[0.14em] text-brand-green-700">
              {t('Проверьте себя')}
            </div>
            <div className="mt-0.5 text-xs font-semibold text-ink-400">
              {t('Верно {done} из {total}', { done: solved.size, total: questions.length })}
            </div>
          </div>
        </div>
        <div className="mt-3 h-2 overflow-hidden rounded-full bg-ink-100" aria-hidden="true">
          <div
            className="h-full rounded-full bg-brand-green-500 transition-[width] duration-500"
            style={{ width: `${progress}%` }}
          />
        </div>
      </div>

      <div className="p-4 sm:p-6">
        <h4 className="text-lg font-extrabold leading-7 text-ink-900">{question.prompt}</h4>
        <div className="mt-4 grid gap-2.5" role="radiogroup" aria-label={question.prompt}>
          {options.map((option, index) => {
            const isSelected = selected === index
            const isCorrect = option.original === question.correctOption
            const state = !checked
              ? isSelected
                ? 'border-brand-green-500 bg-brand-green-50 ring-2 ring-brand-green-200'
                : 'border-ink-200 bg-white hover:border-brand-green-300'
              : isCorrect
                ? 'border-brand-green-500 bg-brand-green-50'
                : isSelected
                  ? 'border-red-400 bg-red-50'
                  : 'border-ink-100 bg-white opacity-60'
            return (
              <button
                key={`${question.id}-${option.original}`}
                type="button"
                role="radio"
                aria-checked={isSelected}
                disabled={checked}
                onClick={() => setSelected(index)}
                className={`flex min-h-[52px] w-full items-center gap-3 rounded-2xl border-2 px-4 py-3 text-left text-[15px] font-semibold leading-6 text-ink-800 transition ${state}`}
              >
                <span
                  aria-hidden="true"
                  className="flex h-7 w-7 shrink-0 items-center justify-center rounded-lg border border-ink-200 bg-white text-xs font-extrabold text-ink-500"
                >
                  {checked && isCorrect ? (
                    <CheckCircle2 className="text-brand-green-600" size={18} />
                  ) : checked && isSelected ? (
                    <XCircle className="text-red-500" size={18} />
                  ) : (
                    String.fromCharCode(1040 + index)
                  )}
                </span>
                <span className="min-w-0">{option.text}</span>
              </button>
            )
          })}
        </div>
      </div>

      <div
        className={`border-t p-4 sm:p-5 ${checked ? (correct ? 'border-brand-green-200 bg-brand-green-50' : 'border-red-200 bg-red-50') : 'border-ink-100 bg-paper-hover'}`}
        aria-live="polite"
      >
        {checked && (
          <div className={`mb-3 ${correct ? 'text-brand-green-900' : 'text-red-900'}`}>
            <div className="flex items-center gap-2 text-base font-extrabold">
              {correct ? <CheckCircle2 size={20} /> : <XCircle size={20} />}
              {correct ? t('Верно!') : t('Не совсем')}
            </div>
            {question.explanation && (
              <p className="mt-1 text-[15px] leading-6">{question.explanation}</p>
            )}
            {!correct && (
              <p className="mt-1 text-sm font-semibold">{t('Этот вопрос вернётся в конце.')}</p>
            )}
          </div>
        )}
        <button
          type="button"
          className={`btn btn-md w-full ${checked && !correct ? 'bg-red-600 text-white hover:bg-red-700' : 'btn-primary'}`}
          disabled={selected === null}
          onClick={checked ? next : check}
        >
          {checked ? (correct ? t('Дальше') : t('Понятно')) : t('Проверить')}
        </button>
      </div>
    </section>
  )
}
