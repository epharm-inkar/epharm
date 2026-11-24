import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import LearnerOneTimeEntryPage from './LearnerOneTimeEntryPage'
import { parseOneTimeToken } from './oneTimeToken'

const TOKEN = 'A'.repeat(43)

describe('parseOneTimeToken', () => {
  it('reads the token from the fragment', () => {
    expect(parseOneTimeToken(`#t=${TOKEN}`)).toBe(TOKEN)
  })

  it('rejects a missing or malformed token', () => {
    expect(parseOneTimeToken('')).toBeNull()
    expect(parseOneTimeToken('#t=short')).toBeNull()
    expect(parseOneTimeToken(`#x=${TOKEN}`)).toBeNull()
  })
})

describe('LearnerOneTimeEntryPage', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    window.history.replaceState(null, '', `/learn/enter#t=${TOKEN}`)
  })
  afterEach(() => vi.unstubAllGlobals())

  function renderPage() {
    return render(
      <MemoryRouter initialEntries={['/learn/enter']}>
        <Routes>
          <Route path="/learn/enter" element={<LearnerOneTimeEntryPage />} />
          <Route path="/learn" element={<h1>Кабинет открыт</h1>} />
        </Routes>
      </MemoryRouter>,
    )
  }

  it('exchanges the token once, stores the session and opens the cabinet', async () => {
    const tokens = { accessToken: 'synthetic-access', refreshToken: 'synthetic-refresh' }
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ tokens, pharmacist: { name: 'Synthetic' } }), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)
    renderPage()
    expect(await screen.findByRole('heading', { name: 'Кабинет открыт' }, { timeout: 5000 })).toBeVisible()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({ token: TOKEN })
    expect(localStorage.getItem('epharm.learner.tokens')).toBe(JSON.stringify(tokens))
    expect(window.location.hash).toBe('')
  })

  it('shows the server message and a password fallback when the link is used or expired', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ message: 'Ссылка устарела. Откройте кабинет заново кнопкой на кассе' }), { status: 401 })))
    renderPage()
    expect(await screen.findByText('Ссылка устарела. Откройте кабинет заново кнопкой на кассе', undefined, { timeout: 5000 })).toBeVisible()
    expect(screen.getByRole('link', { name: 'Войти по ИИН и паролю' })).toHaveAttribute('href', '/learn')
    await waitFor(() => expect(localStorage.getItem('epharm.learner.tokens')).toBeNull())
  })
})
