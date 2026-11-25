import { useMemo, useState } from 'react'
import {
  ArrowLeft,
  Award,
  CheckCircle2,
  ChevronRight,
  ListChecks,
  Loader2,
  RefreshCw,
  Target,
} from 'lucide-react'
import { useLearnerT } from '../learner-i18n'
import { seededOptions } from './shuffle'

export type TestQuestion = { id: string; prompt: string; options: string[] }

export type TestResult = {
  score: number
  passed: boolean
  correctAnswers: number
  totalQuestions: number
  attempt: number
  weakLessonIds?: string[]
}

/**
 * The graded test: a start card with the rules, one question per screen, a check before sending,
 * and a result that says plainly what next. After a failure it names the lessons to repeat; the
 * next attempt draws other questions (answers are never shown, by the server's rule).
 */
export function FinalTest({
  lessonId,
  questions,
  passingScore,
  attemptsUsed,
  bestScore,
  completed,
  lessonTitles,
  onSubmit,
  onOpenLesson,
}: {
  lessonId: string
  questions: TestQuestion[]
  passingScore: number
  attemptsUsed: number
  bestScore: number | null
  completed: boolean
  lessonTitles: Record<string, string>
  onSubmit: (answers: Record<string, number>) => Promise<TestResult | null>
  onOpenLesson: (lessonId: string) => void
}) {
  const t = useLearnerT()
  const [stage, setStage] = useState<'intro' | 'question' | 'review' | 'result'>('intro')
  const [index, setIndex] = useState(0)
  const [answers, setAnswers] = useState<Record<string, number>>({})
  const [result, setResult] = useState<TestResult | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [attemptSeed, setAttemptSeed] = useState(attemptsUsed)
  const needed = Math.ceil((questions.length * passingScore) / 100)
  const seed = `${lessonId}:${attemptSeed}`
  const shuffled = useMemo(
    () =>
      new Map(
        questions.map((question) => [
          question.id,
          seededOptions(question.options, `${seed}:${question.id}`),
        ]),
      ),
    [questions, seed],
  )
  const answered = questions.filter((question) => answers[question.id] != null).length

  if (completed && !result) {
    return (
      <section className="rounded-3xl border border-brand-green-200 bg-brand-green-50 p-5 text-brand-green-900 sm:p-6">
        <div className="flex items-center gap-3">
          <Award size={28} />
          <div>
            <div className="text-lg font-extrabold">{t('Тест пройден')}</div>
            {bestScore != null && (
              <div className="text-sm font-semibold">
                {t('Результат: {score}%', { score: bestScore })}
              </div>
            )}
          </div>
        </div>
      </section>
    )
  }

  if (stage === 'result' && result) {
    const weak = (result.weakLessonIds ?? []).filter((id) => lessonTitles[id])
    return (
      <section
        aria-live="polite"
        className={`rounded-3xl border p-5 sm:p-7 ${result.passed ? 'border-brand-green-200 bg-brand-green-50 text-brand-green-900' : 'border-amber-200 bg-amber-50 text-amber-950'}`}
      >
        <div className="text-center">
          {result.passed ? (
            <Award className="mx-auto text-brand-green-600" size={48} />
          ) : (
            <Target className="mx-auto text-amber-600" size={44} />
          )}
          <div className="mt-2 text-2xl font-extrabold">
            {result.passed ? t('Тест пройден!') : t('Пока не получилось')}
          </div>
          <div className="mt-1 text-base font-semibold">
            {t('Верно {correct} из {total}', {
              correct: result.correctAnswers,
              total: result.totalQuestions,
            })}
            {!result.passed && <> · {t('нужно {needed}', { needed })}</>}
          </div>
        </div>
        {!result.passed && (
          <div className="mt-5 rounded-2xl bg-white/70 p-4">
            {weak.length > 0 ? (
              <>
                <div className="text-sm font-extrabold">{t('Повторите эти уроки:')}</div>
                <div className="mt-2 grid gap-2">
                  {weak.map((id) => (
                    <button
                      key={id}
                      type="button"
                      className="flex items-center justify-between gap-3 rounded-xl border border-amber-200 bg-white px-3 py-2.5 text-left text-sm font-bold text-ink-800 hover:bg-amber-50"
                      onClick={() => onOpenLesson(id)}
                    >
                      <span className="min-w-0">{lessonTitles[id]}</span>
                      <ChevronRight className="shrink-0" size={17} />
                    </button>
                  ))}
                </div>
              </>
            ) : (
              <div className="text-sm font-semibold">{t('Повторите уроки курса.')}</div>
            )}
            <p className="mt-3 text-sm leading-6">
              {t('Следующая попытка с другими вопросами. Попыток сколько угодно.')}
            </p>
          </div>
        )}
        {!result.passed && (
          <button
            type="button"
            className="btn btn-primary btn-md mt-5 w-full"
            onClick={() => {
              setAnswers({})
              setIndex(0)
              setResult(null)
              setAttemptSeed((value) => value + 1)
              setStage('intro')
            }}
          >
            <RefreshCw size={17} /> {t('Пройти тест снова')}
          </button>
        )}
      </section>
    )
  }

  if (stage === 'intro') {
    return (
      <section className="rounded-3xl border border-ink-100 bg-white p-5 shadow-card sm:p-7">
        <div className="flex items-center gap-3">
          <span className="flex h-11 w-11 items-center justify-center rounded-2xl bg-brand-blue-100 text-brand-blue-600">
            <ListChecks size={22} />
          </span>
          <div>
            <div className="text-lg font-extrabold text-ink-900">{t('Итоговый тест')}</div>
            <div className="text-sm font-semibold text-ink-500">
              {attemptsUsed > 0
                ? t('Попытка {attempt}', { attempt: attemptsUsed + 1 })
                : t('Первая попытка')}
              {bestScore != null && attemptsUsed > 0 && (
                <> · {t('лучший результат {score}%', { score: bestScore })}</>
              )}
            </div>
          </div>
        </div>
        <ul className="mt-5 grid gap-2.5 text-[15px] leading-6 text-ink-700">
          <li className="flex gap-2.5">
            <CheckCircle2 className="mt-0.5 shrink-0 text-brand-green-600" size={18} />
            {t('{count} вопросов, по одному на экране', { count: questions.length })}
          </li>
          <li className="flex gap-2.5">
            <CheckCircle2 className="mt-0.5 shrink-0 text-brand-green-600" size={18} />
            {t('Чтобы пройти, ответьте верно на {needed} из {total}', {
              needed,
              total: questions.length,
            })}
          </li>
          <li className="flex gap-2.5">
            <CheckCircle2 className="mt-0.5 shrink-0 text-brand-green-600" size={18} />
            {t('До отправки можно вернуться к любому вопросу')}
          </li>
          <li className="flex gap-2.5">
            <CheckCircle2 className="mt-0.5 shrink-0 text-brand-green-600" size={18} />
            {t('Не получилось: покажем, что повторить, и дадим новые вопросы')}
          </li>
        </ul>
        <button
          type="button"
          className="btn btn-primary btn-md mt-6 w-full"
          onClick={() => setStage('question')}
        >
          {t('Начать тест')} <ChevronRight size={17} />
        </button>
      </section>
    )
  }

  if (stage === 'review') {
    return (
      <section className="rounded-3xl border border-ink-100 bg-white p-5 shadow-card sm:p-7">
        <div className="text-lg font-extrabold text-ink-900">{t('Проверьте ответы')}</div>
        <p className="mt-1 text-sm text-ink-500">
          {t('Отвечено {answered} из {total}. Нажмите на вопрос, чтобы изменить ответ.', {
            answered,
            total: questions.length,
          })}
        </p>
        <div className="mt-4 grid grid-cols-5 gap-2 sm:grid-cols-10">
          {questions.map((question, questionIndex) => (
            <button
              key={question.id}
              type="button"
              aria-label={t('Вопрос {number}', { number: questionIndex + 1 })}
              className={`flex h-11 items-center justify-center rounded-xl border-2 text-sm font-extrabold ${answers[question.id] != null ? 'border-brand-green-300 bg-brand-green-50 text-brand-green-800' : 'border-amber-300 bg-amber-50 text-amber-800'}`}
              onClick={() => {
                setIndex(questionIndex)
                setStage('question')
              }}
            >
              {questionIndex + 1}
            </button>
          ))}
        </div>
        <button
          type="button"
          className="btn btn-primary btn-md mt-6 w-full"
          disabled={submitting || answered < questions.length}
          onClick={async () => {
            setSubmitting(true)
            const next = await onSubmit(answers)
            setSubmitting(false)
            if (next) {
              setResult(next)
              setStage('result')
            }
          }}
        >
          {submitting ? <Loader2 className="animate-spin" size={17} /> : <CheckCircle2 size={17} />}
          {submitting
            ? t('Отправляем ответы…')
            : answered < questions.length
              ? t('Ответьте на все вопросы')
              : t('Отправить ответы')}
        </button>
      </section>
    )
  }

  const question = questions[index]
  if (!question) return null
  const options = shuffled.get(question.id) ?? []
  const last = index === questions.length - 1
  return (
    <section className="overflow-hidden rounded-3xl border border-ink-100 bg-white shadow-card">
      <div className="border-b border-ink-100 p-4 sm:p-5">
        <div className="flex items-center justify-between text-xs font-extrabold text-ink-500">
          <span>
            {t('Вопрос {number} из {total}', { number: index + 1, total: questions.length })}
          </span>
          <span>{t('Отвечено {answered}', { answered })}</span>
        </div>
        <div className="mt-2 flex gap-1" aria-hidden="true">
          {questions.map((item, itemIndex) => (
            <span
              key={item.id}
              className={`h-1.5 flex-1 rounded-full ${itemIndex === index ? 'bg-brand-green-600' : answers[item.id] != null ? 'bg-brand-green-300' : 'bg-ink-100'}`}
            />
          ))}
        </div>
      </div>
      <div className="p-4 sm:p-6">
        <h4 className="text-lg font-extrabold leading-7 text-ink-900">{question.prompt}</h4>
        <div className="mt-4 grid gap-2.5" role="radiogroup" aria-label={question.prompt}>
          {options.map((option, optionIndex) => {
            const selected = answers[question.id] === option.original
            return (
              <button
                key={`${question.id}-${option.original}`}
                type="button"
                role="radio"
                aria-checked={selected}
                onClick={() =>
                  setAnswers((current) => ({ ...current, [question.id]: option.original }))
                }
                className={`flex min-h-[52px] w-full items-center gap-3 rounded-2xl border-2 px-4 py-3 text-left text-[15px] font-semibold leading-6 text-ink-800 transition ${selected ? 'border-brand-green-500 bg-brand-green-50 ring-2 ring-brand-green-200' : 'border-ink-200 bg-white hover:border-brand-green-300'}`}
              >
                <span
                  aria-hidden="true"
                  className="flex h-7 w-7 shrink-0 items-center justify-center rounded-lg border border-ink-200 bg-white text-xs font-extrabold text-ink-500"
                >
                  {String.fromCharCode(1040 + optionIndex)}
                </span>
                <span className="min-w-0">{option.text}</span>
              </button>
            )
          })}
        </div>
      </div>
      <div className="flex items-center justify-between gap-3 border-t border-ink-100 bg-paper-hover p-4 sm:p-5">
        <button
          type="button"
          className="btn btn-outline btn-md"
          disabled={index === 0}
          onClick={() => setIndex((value) => Math.max(0, value - 1))}
        >
          <ArrowLeft size={16} /> {t('Назад')}
        </button>
        <button
          type="button"
          className="btn btn-primary btn-md"
          disabled={answers[question.id] == null}
          onClick={() => (last ? setStage('review') : setIndex((value) => value + 1))}
        >
          {last ? t('К отправке') : t('Дальше')} <ChevronRight size={17} />
        </button>
      </div>
    </section>
  )
}
