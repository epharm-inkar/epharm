import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ScreenFleetDto, ScreenFleetRegisterDto } from '@/lib/api-types'
import { FleetPanel } from './FleetPanel'

const screenHooks = vi.hoisted(() => ({ useScreenFleet: vi.fn() }))
vi.mock('@/lib/queries/screens', () => screenHooks)

const OBSERVED = '2026-11-24T09:00:00Z'

function register(overrides: Partial<ScreenFleetRegisterDto>): ScreenFleetRegisterDto {
  return {
    deviceId: 'KASSA1',
    pharmacyId: 'ph_a',
    pharmacyName: 'Аптека со склада №1',
    chainName: 'Аптека со склада',
    city: 'Алматы',
    address: 'Абая 1',
    pharmacyActive: true,
    registered: true,
    registeredAt: '2026-10-01T09:00:00Z',
    state: 'online',
    lastSeen: '2026-11-24T08:59:30Z',
    appVersion: '1.0.90',
    monitorCount: 2,
    hasClientScreen: true,
    staffAuthStatus: null,
    staffSourceStatus: 'resolved',
    playlistId: 'pl_broadcast',
    playlistSource: 'broadcast',
    salesToday: 7,
    sales7d: 40,
    lastSaleAt: '2026-11-24T08:40:00Z',
    pharmacyLastSaleAt: '2026-11-24T08:40:00Z',
    pharmacySalesToday: 12,
    pharmacySales7d: 85,
    ...overrides,
  }
}

function fleet(overrides: Partial<ScreenFleetDto> = {}): ScreenFleetDto {
  return {
    observedAt: OBSERVED,
    staleAfterDays: 7,
    salesAvailable: true,
    summary: {
      activePharmacies: 3,
      pharmaciesWithRegisters: 2,
      registers: 3,
      online: 2,
      offline: 1,
      stale: 0,
      clientScreens: 1,
      standardNUnavailable: 1,
      onlinePharmaciesWithoutSalesToday: 1,
    },
    registers: [
      register({}),
      register({
        deviceId: 'KASSA2',
        pharmacyId: 'ph_b',
        pharmacyName: 'Аптека со склада №2',
        address: 'Достык 248а',
        monitorCount: 1,
        hasClientScreen: false,
        staffSourceStatus: 'database_unavailable',
        salesToday: 0,
        sales7d: 0,
        lastSaleAt: null,
        pharmacySalesToday: 0,
        pharmacySales7d: 0,
        pharmacyLastSaleAt: '2026-11-15T10:00:00Z',
      }),
      register({
        deviceId: 'KASSA3',
        state: 'offline',
        lastSeen: '2026-11-24T04:00:00Z',
        appVersion: null,
        monitorCount: null,
        hasClientScreen: null,
        staffSourceStatus: null,
      }),
    ],
    playlists: [
      {
        id: 'pl_broadcast',
        name: 'Эфир касс',
        durationSec: 120,
        slides: [
          { id: 's1', url: 'https://x/1.mp4', kind: 'video', durationSec: 60, title: 'Скидочный четверг', position: 0 },
          { id: 's2', url: 'https://x/2.mp4', kind: 'video', durationSec: 60, title: 'SNRG', position: 1 },
        ],
      },
    ],
    pharmaciesWithoutRegisters: [
      { pharmacyId: 'ph_gap', pharmacyName: 'Аптека без кассы', city: 'Каскелен', address: 'Абылай хана 92' },
    ],
    ...overrides,
  }
}

function mockFleet(data: ScreenFleetDto | undefined, extra: Record<string, unknown> = {}) {
  screenHooks.useScreenFleet.mockReturnValue({
    data,
    isLoading: false,
    isFetching: false,
    error: null,
    refetch: vi.fn(),
    ...extra,
  })
}

describe('FleetPanel', () => {
  beforeEach(() => mockFleet(fleet()))
  afterEach(() => vi.unstubAllGlobals())

  it('показывает плитки и все кассы таблицей', () => {
    render(<FleetPanel />)
    expect(screen.getByTestId('fleet-kpi-online')).toHaveTextContent('2')
    expect(screen.getByTestId('fleet-kpi-online')).toHaveTextContent('из 3 касс')
    expect(screen.getByTestId('fleet-kpi-standardN')).toHaveTextContent('1')
    expect(screen.getByTestId('fleet-kpi-gaps')).toHaveTextContent('1')
    const table = screen.getByTestId('fleet-table')
    expect(within(table).getAllByRole('row')).toHaveLength(4)
    expect(screen.getByTestId('fleet-count')).toHaveTextContent('Показано 3 из 3')
    expect(screen.getByTestId('fleet-row-ph_b-KASSA2')).toHaveTextContent('нет доступа к базе')
    expect(screen.getByTestId('fleet-row-ph_b-KASSA2')).toHaveTextContent('не показывается')
    expect(screen.getByTestId('fleet-row-ph_a-KASSA3')).toHaveTextContent('5 ч назад')
    expect(screen.getByTestId('fleet-row-ph_a-KASSA1')).toHaveTextContent('сегодня 7 · неделя 40')
    // Касса молчит неделю: показываем, когда был последний чек аптеки.
    expect(screen.getByTestId('fleet-row-ph_b-KASSA2')).toHaveTextContent('аптека: 15.11, 15:00')
  })

  it('плитка фильтрует кассы, повторное нажатие снимает фильтр', async () => {
    const user = userEvent.setup()
    render(<FleetPanel />)
    await user.click(screen.getByTestId('fleet-kpi-standardN'))
    expect(screen.getByTestId('fleet-kpi-standardN')).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByTestId('fleet-count')).toHaveTextContent('Показано 1 из 3')
    expect(screen.getByTestId('fleet-row-ph_b-KASSA2')).toBeInTheDocument()
    expect(screen.queryByTestId('fleet-row-ph_a-KASSA1')).not.toBeInTheDocument()
    await user.click(screen.getByTestId('fleet-kpi-standardN'))
    expect(screen.getByTestId('fleet-count')).toHaveTextContent('Показано 3 из 3')
  })

  it('поиск находит кассу по адресу', async () => {
    const user = userEvent.setup()
    render(<FleetPanel />)
    await user.type(within(screen.getByTestId('fleet-search')).getByRole('textbox'), 'достык')
    expect(screen.getByTestId('fleet-count')).toHaveTextContent('Показано 1 из 3')
    expect(screen.getByTestId('fleet-row-ph_b-KASSA2')).toBeInTheDocument()
  })

  it('плитка «Аптеки без касс» показывает список аптек', async () => {
    const user = userEvent.setup()
    render(<FleetPanel />)
    await user.click(screen.getByTestId('fleet-kpi-gaps'))
    expect(screen.getByTestId('fleet-gaps')).toHaveTextContent('Аптека без кассы')
    expect(screen.getByTestId('fleet-gaps')).toHaveTextContent('Каскелен, Абылай хана 92')
    expect(screen.queryByTestId('fleet-table')).not.toBeInTheDocument()
  })

  it('без продаж не рисует нули, а помечает продажи недоступными', () => {
    mockFleet(fleet({ salesAvailable: false }))
    render(<FleetPanel />)
    expect(screen.getByTestId('fleet-kpi-noSalesToday')).toHaveTextContent('—')
    expect(screen.getByText('продажи временно недоступны')).toBeInTheDocument()
    expect(screen.getByTestId('fleet-row-ph_a-KASSA1')).not.toHaveTextContent('сегодня 7')
  })

  it('при ошибке загрузки даёт повторить', async () => {
    const user = userEvent.setup()
    const refetch = vi.fn()
    mockFleet(undefined, { error: new Error('boom'), refetch })
    render(<FleetPanel />)
    await user.click(within(screen.getByTestId('fleet-error')).getByRole('button'))
    expect(refetch).toHaveBeenCalled()
  })

  it('на телефоне показывает карточки вместо таблицы', () => {
    vi.stubGlobal('matchMedia', (query: string) => ({
      matches: query.includes('max-width'),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    }))
    render(<FleetPanel />)
    expect(screen.queryByTestId('fleet-table')).not.toBeInTheDocument()
    expect(screen.getByTestId('fleet-card-ph_b-KASSA2')).toHaveTextContent('нет доступа к базе')
  })

  it('показывает, что назначено на экраны, и сколько касс это крутят', () => {
    render(<FleetPanel />)
    const block = screen.getByTestId('fleet-playlists')
    expect(block).toHaveTextContent('Эфир касс')
    expect(block).toHaveTextContent('касс на связи: 2')
    expect(block).toHaveTextContent('1. Скидочный четверг')
  })
})
