import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { AtTillListDto } from '@/lib/api-types'
import { AtTillPanel } from './AtTillPanel'

const atTillHooks = vi.hoisted(() => ({ useAtTill: vi.fn() }))
vi.mock('@/lib/queries/atTill', () => atTillHooks)
const pharmacyHooks = vi.hoisted(() => ({ usePharmacies: vi.fn() }))
vi.mock('@/lib/queries/pharmacies', () => pharmacyHooks)
const screenHooks = vi.hoisted(() => ({ useNarrowScreen: vi.fn() }))
vi.mock('@/lib/useNarrowScreen', () => screenHooks)

const refetch = vi.fn()

const list: AtTillListDto = {
  observedAt: '2026-11-26T12:00:00Z',
  windowMinutes: 20,
  total: 2,
  shown: 2,
  items: [
    {
      pharmacistId: 'ph_a',
      name: 'Сапарова Айгерим Серикқызы',
      phoneTail: '4567',
      homePharmacyId: 'ph_abay',
      homePharmacyName: 'Аптека со склада, Абая 103',
      pharmacyId: 'ph_abay',
      pharmacyName: 'Аптека со склада',
      city: 'Алматы',
      address: 'Абая 103',
      deviceId: 'KASSA1',
      since: '2026-11-26T10:40:00Z',
      seenAt: '2026-11-26T11:53:00Z',
    },
    {
      pharmacistId: 'ph_b',
      name: 'Омарова Дана',
      phoneTail: null,
      homePharmacyId: 'ph_tole',
      homePharmacyName: 'Аптека на Толе би',
      pharmacyId: 'ph_abay',
      pharmacyName: 'Аптека со склада',
      city: 'Алматы',
      address: 'Абая 103',
      deviceId: 'KASSA2',
      since: '2026-11-26T11:55:00Z',
      seenAt: '2026-11-26T11:59:30Z',
    },
  ],
}

function answer(data: AtTillListDto | undefined, extra: Record<string, unknown> = {}) {
  atTillHooks.useAtTill.mockReturnValue({ data, isLoading: false, isFetching: false, isError: false, error: null, refetch, ...extra })
}

beforeEach(() => {
  vi.clearAllMocks()
  screenHooks.useNarrowScreen.mockReturnValue(false)
  answer(list)
  pharmacyHooks.usePharmacies.mockReturnValue({
    data: [
      { id: 'ph_abay', name: 'Аптека со склада', city: 'Алматы', addr: 'Абая 103' },
      { id: 'ph_tole', name: 'Аптека со склада', city: 'Алматы', addr: 'Толе би 286' },
    ],
    isLoading: false,
    isError: false,
  })
})

describe('AtTillPanel', () => {
  it('показывает, сколько человек за кассами, и каждого: имя, аптеку кассы, кассу, с какого времени и когда касса проверяла', () => {
    render(<AtTillPanel />)
    expect(screen.getByTestId('at-till-total')).toHaveTextContent('2')
    expect(screen.getByText('человек')).toBeInTheDocument()
    expect(screen.getByTestId('at-till-updated')).toHaveTextContent(/^Обновлено в \d{2}:\d{2}$/)

    const first = screen.getByTestId('at-till-row-ph_a')
    expect(first).toHaveTextContent('Сапарова Айгерим Серикқызы')
    expect(first).toHaveTextContent('тел. …4567')
    expect(first).not.toHaveTextContent('из аптеки')
    expect(first).toHaveTextContent('Аптека со склада, Абая 103')
    expect(first).toHaveTextContent('Алматы')
    expect(first).toHaveTextContent('KASSA1')
    expect(first).toHaveTextContent(/с \d{2}:\d{2} \(1 ч 20 мин\)/)
    expect(first).toHaveTextContent('7 мин назад')

    const second = screen.getByTestId('at-till-row-ph_b')
    // Человек из другой аптеки: видно, откуда он.
    expect(second).toHaveTextContent('из аптеки Аптека на Толе би')
    expect(second).toHaveTextContent(/\(5 мин\)/)
    expect(second).toHaveTextContent('только что')
    expect(screen.getByText(/Человек считается «за кассой», пока касса на связи и называла его за последние 20 минут/)).toBeInTheDocument()
  })

  it('кнопка «Обновить» читает список заново и выключена, пока идёт чтение', async () => {
    const user = userEvent.setup()
    const { rerender } = render(<AtTillPanel />)
    await user.click(screen.getByTestId('at-till-refresh'))
    expect(refetch).toHaveBeenCalledTimes(1)
    answer(list, { isFetching: true })
    rerender(<AtTillPanel />)
    expect(screen.getByTestId('at-till-refresh')).toBeDisabled()
  })

  it('поиск по тексту и выбор аптеки уходят в запрос, и видно, сколько показано из скольких', async () => {
    const user = userEvent.setup()
    render(<AtTillPanel />)
    expect(atTillHooks.useAtTill).toHaveBeenLastCalledWith({ pharmacyId: undefined, q: undefined })

    answer({ ...list, shown: 1, items: [list.items[1]] })
    await user.type(screen.getByPlaceholderText('ФИО, телефон, ИИН или аптека'), 'омарова')
    await waitFor(() => expect(atTillHooks.useAtTill).toHaveBeenLastCalledWith({ pharmacyId: undefined, q: 'омарова' }))
    expect(screen.getByTestId('at-till-shown')).toHaveTextContent('Показано 1 из 2')
    expect(screen.queryByTestId('at-till-row-ph_a')).not.toBeInTheDocument()

    await user.selectOptions(screen.getByRole('combobox', { name: 'Аптека кассы' }), 'ph_tole')
    await waitFor(() => expect(atTillHooks.useAtTill).toHaveBeenLastCalledWith({ pharmacyId: 'ph_tole', q: 'омарова' }))
    const select = screen.getByRole('combobox', { name: 'Аптека кассы' })
    expect(within(select).getByRole('option', { name: 'Все аптеки' })).toBeInTheDocument()
    expect(within(select).getByRole('option', { name: 'Аптека со склада, Алматы, Толе би 286' })).toBeInTheDocument()
  })

  it('на телефоне вместо таблицы карточки, без горизонтальной прокрутки таблицы', () => {
    screenHooks.useNarrowScreen.mockReturnValue(true)
    render(<AtTillPanel />)
    expect(screen.queryByTestId('at-till-table')).not.toBeInTheDocument()
    const card = screen.getByTestId('at-till-card-ph_a')
    expect(card).toHaveTextContent('Сапарова Айгерим Серикқызы')
    expect(card).toHaveTextContent('Аптека со склада, Абая 103')
    expect(card).toHaveTextContent('касса KASSA1')
    expect(card).toHaveTextContent('7 мин назад')
    expect(screen.getByTestId('at-till-card-ph_b')).toHaveTextContent('из аптеки Аптека на Толе би')
  })

  it('никого за кассами нет: так и сказано; никого не нашли по фильтру: тоже', async () => {
    const user = userEvent.setup()
    answer({ ...list, total: 0, shown: 0, items: [] })
    render(<AtTillPanel />)
    expect(screen.getByTestId('at-till-empty')).toHaveTextContent('Сейчас за кассами никого нет')
    expect(screen.getByTestId('at-till-total')).toHaveTextContent('0')

    answer({ ...list, total: 5, shown: 0, items: [] })
    await user.type(screen.getByPlaceholderText('ФИО, телефон, ИИН или аптека'), 'нет такого')
    await waitFor(() => expect(screen.getByTestId('at-till-empty')).toHaveTextContent('Никого не нашли'))
    expect(screen.getByTestId('at-till-shown')).toHaveTextContent('Показано 0 из 5')
  })

  it('пока список грузится, число заменено на «…», а когда не загрузился, есть сообщение и «Повторить»', async () => {
    const user = userEvent.setup()
    answer(undefined, { isLoading: true })
    const { unmount } = render(<AtTillPanel />)
    expect(screen.getByTestId('at-till-total')).toHaveTextContent('…')
    expect(screen.getByText('Загружаем…')).toBeInTheDocument()
    unmount()

    answer(undefined, { isError: true, error: new Error('boom') })
    render(<AtTillPanel />)
    expect(screen.getByTestId('at-till-error')).toHaveTextContent('Список не загрузился.')
    await user.click(within(screen.getByTestId('at-till-error')).getByRole('button', { name: 'Повторить' }))
    expect(refetch).toHaveBeenCalledTimes(1)
  })

  it('когда обновление не удалось, а старый список есть, он остаётся на экране', () => {
    answer(list, { isError: true, error: new Error('boom') })
    render(<AtTillPanel />)
    expect(screen.queryByTestId('at-till-error')).not.toBeInTheDocument()
    expect(screen.getByTestId('at-till-row-ph_a')).toBeInTheDocument()
  })
})
