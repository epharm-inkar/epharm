// Профиль кабинета: откуда взято ФИО карточки и, если в нём меньше двух слов, предложение указать ФИО полностью
// (26.11.2026). Предложение ждёт офис; данные Стандарт-Н главнее, когда справочник уже знает человека.

import { useCallback, useEffect, useState } from 'react'
import { BadgeCheck, Loader2, Send } from 'lucide-react'
import { useLearnerT } from './learner-i18n'
import { isIncompleteName, nameProblem, normalizeName, type NameProblem } from './identityName'

type Request = <T>(path: string, init?: RequestInit) => Promise<T>

type IdentitySource = 'registration' | 'standardn' | 'self' | 'hq'

interface IdentityState {
  name: string
  nameSource: IdentitySource
  iinSource: IdentitySource
  nameIncomplete: boolean
  canPropose: boolean
  claim: {
    status: 'pending' | 'rejected'
    fullName: string
    reason: string | null
    createdAt: string
  } | null
}

const SOURCE_TEXT: Record<IdentitySource, string> = {
  standardn: 'из Стандарт-Н',
  self: 'указано вами, подтверждено офисом',
  registration: 'при регистрации',
  hq: 'внесено в офисе',
}

const PROBLEM_TEXT: Record<NameProblem, string> = {
  words: 'Введите фамилию и имя, не меньше двух слов',
  letters: 'В имени только буквы, дефис и апостроф',
  long: 'Слишком длинное имя',
}

export default function IdentityNameClaim({
  request,
  onNameChanged,
}: {
  request: Request
  /** The card got a new name at once (the directory already had the full one): the profile reloads. */
  onNameChanged?: () => void
}) {
  const t = useLearnerT()
  const [state, setState] = useState<IdentityState | null>(null)
  const [failed, setFailed] = useState(false)
  const [value, setValue] = useState('')
  const [problem, setProblem] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    let alive = true
    request<IdentityState>('/api/mobile/me/identity')
      .then((next) => {
        if (alive) setState(next)
      })
      // The block is an extra: without an answer (an older server) the profile stays as it was.
      .catch(() => {
        if (alive) setFailed(true)
      })
    return () => {
      alive = false
    }
  }, [request])

  const send = useCallback(async () => {
    const trimmed = normalizeName(value)
    const found = nameProblem(trimmed)
    if (found) {
      setProblem(PROBLEM_TEXT[found])
      return
    }
    setProblem(null)
    setBusy(true)
    try {
      const next = await request<IdentityState>('/api/mobile/me/identity/name', {
        method: 'POST',
        body: JSON.stringify({ fullName: trimmed }),
      })
      setState(next)
      setValue('')
      if (state && next.name !== state.name) onNameChanged?.()
    } catch (error) {
      setProblem(
        error instanceof Error && error.message
          ? error.message
          : 'Не удалось отправить. Попробуйте ещё раз.',
      )
    } finally {
      setBusy(false)
    }
  }, [onNameChanged, request, state, value])

  if (failed || !state) return null
  // «ИИН и ФИО: из Стандарт-Н» when both came from one place, otherwise each source on its own.
  const nameSource = t(SOURCE_TEXT[state.nameSource] ?? SOURCE_TEXT.registration)
  const iinSource = t(SOURCE_TEXT[state.iinSource] ?? SOURCE_TEXT.registration)
  const sourceLine =
    state.iinSource === state.nameSource
      ? `${t('ИИН и ФИО')}: ${nameSource}`
      : `${t('ИИН')}: ${iinSource} · ${t('ФИО')}: ${nameSource}`
  const pending = state.claim?.status === 'pending'
  const rejected = state.claim?.status === 'rejected'
  const offerForm = state.canPropose && isIncompleteName(state.name)

  return (
    <section
      className="rounded-2xl bg-white p-5 shadow-card"
      aria-label={t('ФИО в карточке')}
      data-testid="identity-name-claim"
    >
      <h2 className="flex items-center gap-2 text-lg font-extrabold text-ink-900">
        <BadgeCheck size={21} /> {t('ФИО в карточке')}
      </h2>
      <p className="mt-2 text-sm font-semibold text-ink-500" data-testid="identity-name-source">
        {sourceLine}
      </p>

      {pending && state.claim && (
        <div
          role="status"
          className="mt-4 rounded-xl bg-brand-green-50 p-4 text-sm leading-6 text-brand-green-800"
        >
          <p className="font-extrabold">{t('Отправлено, офис проверит')}</p>
          <p className="mt-1">{t('Вы указали: {name}', { name: state.claim.fullName })}</p>
        </div>
      )}

      {offerForm && (
        <div className="mt-4">
          <p className="text-sm font-extrabold text-ink-900">{t('Укажите ФИО полностью')}</p>
          <p className="mt-1 text-sm leading-6 text-ink-500">
            {t(
              'В карточке указано «{name}». Напишите фамилию, имя и отчество полностью. Офис проверит и подтвердит.',
              { name: state.name },
            )}{' '}
            {t('Если данные есть в Стандарт-Н, они главнее.')}
          </p>
          {rejected && state.claim && (
            <p
              role="alert"
              className="mt-3 rounded-xl bg-surface-danger p-3 text-sm font-semibold text-accent-danger"
            >
              {state.claim.reason
                ? t('Офис не подтвердил: {reason}.', {
                    reason: state.claim.reason.trim().replace(/[.!?]+$/, ''),
                  })
                : t('Офис не подтвердил данные.')}{' '}
              {t('Проверьте и отправьте снова.')}
            </p>
          )}
          <form
            className="mt-3 flex flex-col gap-3 sm:flex-row"
            onSubmit={(event) => {
              event.preventDefault()
              void send()
            }}
          >
            <input
              className="inp h-12 w-full text-base"
              value={value}
              maxLength={120}
              autoComplete="name"
              aria-label={t('Фамилия Имя Отчество')}
              placeholder={t('Фамилия Имя Отчество')}
              onChange={(event) => setValue(event.target.value)}
            />
            <button type="submit" className="btn btn-primary btn-lg shrink-0" disabled={busy}>
              {busy ? <Loader2 className="animate-spin" size={17} /> : <Send size={17} />}{' '}
              {busy ? t('Отправляем…') : t('Отправить на проверку')}
            </button>
          </form>
          {problem && (
            <p role="alert" className="mt-2 text-sm font-semibold text-accent-danger">
              {t(problem)}
            </p>
          )}
        </div>
      )}
    </section>
  )
}
