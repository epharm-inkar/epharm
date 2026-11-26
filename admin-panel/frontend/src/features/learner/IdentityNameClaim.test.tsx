import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useUiStore } from '@/app/store'
import IdentityNameClaim from './IdentityNameClaim'

type State = {
  name: string
  nameSource: 'registration' | 'standardn' | 'self' | 'hq'
  iinSource: 'registration' | 'standardn' | 'self' | 'hq'
  nameIncomplete: boolean
  canPropose: boolean
  claim: {
    status: 'pending' | 'rejected'
    fullName: string
    reason: string | null
    createdAt: string
  } | null
}

const short: State = {
  name: 'Бузурбаева',
  nameSource: 'standardn',
  iinSource: 'standardn',
  nameIncomplete: true,
  canPropose: true,
  claim: null,
}

const originalLanguage = useUiStore.getState().language

function requestOf(state: State | Error, onPost?: (body: unknown) => State | Error) {
  return vi.fn(async (path: string, init?: RequestInit) => {
    const answer = init?.method === 'POST' && onPost ? onPost(JSON.parse(String(init.body))) : state
    if (answer instanceof Error) throw answer
    expect(path).toBe(
      init?.method === 'POST' ? '/api/mobile/me/identity/name' : '/api/mobile/me/identity',
    )
    return answer
  }) as unknown as <T>(path: string, init?: RequestInit) => Promise<T>
}

beforeEach(() => {
  act(() => useUiStore.setState({ language: 'ru' }))
})
afterEach(() => {
  act(() => useUiStore.setState({ language: originalLanguage }))
})

describe('cabinet: the name on the card', () => {
  it('says where a full name came from and offers nothing', async () => {
    render(
      <IdentityNameClaim
        request={requestOf({
          ...short,
          name: 'Бузурбаева Лаура Ермековна',
          nameIncomplete: false,
          canPropose: false,
        })}
      />,
    )
    expect(await screen.findByTestId('identity-name-source')).toHaveTextContent(
      'ИИН и ФИО: из Стандарт-Н',
    )
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
  })

  it('shows a name the pharmacist typed and the office confirmed as theirs', async () => {
    render(
      <IdentityNameClaim
        request={requestOf({
          ...short,
          name: 'Бузурбаева Лаура',
          nameSource: 'self',
          nameIncomplete: false,
          canPropose: false,
        })}
      />,
    )
    expect(await screen.findByTestId('identity-name-source')).toHaveTextContent(
      'ИИН: из Стандарт-Н · ФИО: указано вами, подтверждено офисом',
    )
  })

  it('offers the form to a card with a one-word name, checks two words before sending', async () => {
    const request = requestOf(short)
    render(<IdentityNameClaim request={request} />)
    const field = await screen.findByRole('textbox', { name: 'Фамилия Имя Отчество' })
    expect(screen.getByText(/В карточке указано «Бузурбаева»/)).toBeInTheDocument()
    await userEvent.type(field, 'Лаура')
    await userEvent.click(screen.getByRole('button', { name: /Отправить на проверку/ }))
    expect(screen.getByRole('alert')).toHaveTextContent(
      'Введите фамилию и имя, не меньше двух слов',
    )
    // Nothing went to the server: only the first read.
    expect(request).toHaveBeenCalledTimes(1)
  })

  it('sends the clean name and then shows that the office will check it', async () => {
    const request = requestOf(short, (body) => {
      expect(body).toEqual({ fullName: 'Бузурбаева Лаура Ермековна' })
      return {
        ...short,
        canPropose: false,
        claim: {
          status: 'pending',
          fullName: 'Бузурбаева Лаура Ермековна',
          reason: null,
          createdAt: '2026-11-26T10:00:00Z',
        },
      }
    })
    render(<IdentityNameClaim request={request} />)
    await userEvent.type(await screen.findByRole('textbox'), '  Бузурбаева   Лаура Ермековна ')
    await userEvent.click(screen.getByRole('button', { name: /Отправить на проверку/ }))
    expect(await screen.findByText('Отправлено, офис проверит')).toBeInTheDocument()
    expect(screen.getByText('Вы указали: Бузурбаева Лаура Ермековна')).toBeInTheDocument()
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
  })

  it('reloads the profile when the card took the directory name at once', async () => {
    const onNameChanged = vi.fn()
    const request = requestOf(short, () => ({
      ...short,
      name: 'Бузурбаева Лаура Ермековна',
      nameIncomplete: false,
      canPropose: false,
    }))
    render(<IdentityNameClaim request={request} onNameChanged={onNameChanged} />)
    await userEvent.type(await screen.findByRole('textbox'), 'Бузурбаева Лаура')
    await userEvent.click(screen.getByRole('button', { name: /Отправить на проверку/ }))
    await waitFor(() => expect(onNameChanged).toHaveBeenCalledOnce())
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
  })

  it('shows the reason of a rejection above the form, and the server refusal when sending fails', async () => {
    const request = requestOf(
      {
        ...short,
        claim: {
          status: 'rejected',
          fullName: 'Лаура Б',
          reason: 'Напишите имя как в документе',
          createdAt: '2026-11-26T10:00:00Z',
        },
      },
      () => new Error('Слишком много попыток. Попробуйте позже'),
    )
    render(<IdentityNameClaim request={request} />)
    expect(
      await screen.findByText(
        /Офис не подтвердил: Напишите имя как в документе\. Проверьте и отправьте снова\./,
      ),
    ).toBeInTheDocument()
    await userEvent.type(screen.getByRole('textbox'), 'Бузурбаева Лаура')
    await userEvent.click(screen.getByRole('button', { name: /Отправить на проверку/ }))
    expect(await screen.findByText('Слишком много попыток. Попробуйте позже')).toBeInTheDocument()
    expect(screen.getByRole('textbox')).toHaveValue('Бузурбаева Лаура')
  })

  it('shows nothing when the server has no such route yet', async () => {
    const { container } = render(<IdentityNameClaim request={requestOf(new Error('404'))} />)
    await waitFor(() => expect(container).toBeEmptyDOMElement())
  })

  it('speaks Kazakh when the cabinet is in Kazakh', async () => {
    act(() => useUiStore.setState({ language: 'kk' }))
    render(<IdentityNameClaim request={requestOf(short)} />)
    expect(await screen.findByText('Аты-жөніңізді толық көрсетіңіз')).toBeInTheDocument()
    expect(screen.getByTestId('identity-name-source')).toHaveTextContent(
      'ЖСН және аты-жөні: Стандарт-Нан',
    )
  })
})
