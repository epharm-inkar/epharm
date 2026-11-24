import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { parseOneTimeToken } from './oneTimeToken'

/** Same storage the cabinet uses after a password login (LearnerTrainingPage). */
const TOKEN_KEY = 'epharm.learner.tokens'

type Tokens = { accessToken: string; refreshToken: string }

function storeTokens(tokens: Tokens) {
  const serialized = JSON.stringify(tokens)
  try {
    localStorage.setItem(TOKEN_KEY, serialized)
    sessionStorage.removeItem(TOKEN_KEY)
  } catch {
    sessionStorage.setItem(TOKEN_KEY, serialized)
  }
}

/**
 * Opened from the till: «Мой кабинет» button or the QR in the ePharm window. The link carries a one-time token
 * issued for the person signed in to Standard-N at that till. No password, no SMS.
 */
export default function LearnerOneTimeEntryPage() {
  const navigate = useNavigate()
  const [token] = useState(() => parseOneTimeToken(window.location.hash))
  const [failed, setFailed] = useState<string | null>(() =>
    token ? null : 'Ссылка неполная. Откройте кабинет заново кнопкой на кассе.',
  )
  const started = useRef(false)

  useEffect(() => {
    // The token must not stay in the address bar or the browser history.
    window.history.replaceState(null, '', window.location.pathname)
    if (started.current || !token) return
    started.current = true
    fetch('/api/mobile/auth/one-time', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ token }),
    })
      .then(async (response) => {
        if (!response.ok) {
          const body = (await response.json().catch(() => null)) as { message?: string } | null
          throw new Error(body?.message || 'Ссылка устарела. Откройте кабинет заново кнопкой на кассе.')
        }
        return (await response.json()) as { tokens: Tokens }
      })
      .then((result) => {
        storeTokens(result.tokens)
        navigate('/learn', { replace: true })
      })
      .catch((error: unknown) => {
        setFailed(error instanceof Error ? error.message : 'Не удалось войти. Попробуйте ещё раз с кассы.')
      })
  }, [navigate, token])

  return (
    <main
      style={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        padding: 16,
        background: '#F4F6F7',
        fontFamily: 'system-ui, -apple-system, "Segoe UI", Roboto, sans-serif',
      }}
    >
      <section
        style={{
          maxWidth: 420,
          width: '100%',
          background: '#fff',
          borderRadius: 16,
          padding: '28px 24px',
          boxShadow: '0 8px 28px rgba(17,43,51,0.12)',
          textAlign: 'center',
        }}
      >
        <div style={{ color: '#112B33', fontWeight: 700, fontSize: 14, opacity: 0.7 }}>ePharm</div>
        {failed ? (
          <>
            <h1 style={{ fontSize: 22, margin: '10px 0 8px', color: '#171A2F' }}>Не получилось войти</h1>
            <p style={{ color: '#6B7280', margin: '0 0 18px', lineHeight: 1.45 }}>{failed}</p>
            <a
              href="/learn"
              style={{
                display: 'inline-block',
                background: '#1F8A70',
                color: '#fff',
                borderRadius: 10,
                padding: '12px 18px',
                textDecoration: 'none',
                fontWeight: 600,
              }}
            >
              Войти по ИИН и паролю
            </a>
          </>
        ) : (
          <>
            <h1 style={{ fontSize: 22, margin: '10px 0 8px', color: '#171A2F' }}>Открываем ваш кабинет…</h1>
            <p style={{ color: '#6B7280', margin: 0 }}>Вход выполняется по данным кассы, пароль не нужен.</p>
          </>
        )}
      </section>
    </main>
  )
}
