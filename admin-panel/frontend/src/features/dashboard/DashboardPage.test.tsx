// Тесты DashboardPage — KPI из real API, топ-списки, loading/error.

import { describe, expect, it, beforeEach, afterEach, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { ToastHost } from '@/ui'
import { useUiStore } from '@/app/store'
import { formatKzt } from '@/mocks/fixtures'
import type { DashboardSummaryDto, RecommendationAnalyticsDto } from '@/lib/api-types'
import DashboardPage from './DashboardPage'

const dashHooks = vi.hoisted(() => ({
  useDashboardSummary: vi.fn(),
  useRecommendationAnalytics: vi.fn(),
}))

vi.mock('@/lib/queries/dashboard', () => dashHooks)

function mkSummary(over: Partial<DashboardSummaryDto> = {}): DashboardSummaryDto {
  return {
    impressions: 1500,
    accepts: 500,
    convRate: 33.3,
    activeRules: 4,
    totalRules: 6,
    paidOut: 800_000,
    pendingPayout: 300_000,
    pilotPharmacies: 8,
    avgLiftPct: 12.4,
    topRules: [
      { id: 'r_1', label: 'Аквамарис', convRate: 40, accepts: 400 },
      { id: 'r_2', label: 'Линекс', convRate: 20, accepts: 100 },
    ],
    topPharmacies: [{ id: 'ph_1', name: 'Аптека Лидер', city: 'Алматы', rulesAccepted: 300 }],
    topProducts: [{ id: 'p_1', name: 'Аквамарис', revenue: 500_000 }],
    ...over,
  }
}

function setSummary(data: DashboardSummaryDto | undefined, extra: Record<string, unknown> = {}) {
  dashHooks.useDashboardSummary.mockReturnValue({
    data,
    isLoading: false,
    isError: false,
    error: null,
    refetch: vi.fn(),
    ...extra,
  })
}

function mkAnalytics(over: Partial<RecommendationAnalyticsDto> = {}): RecommendationAnalyticsDto {
  return {
    windowDays: 90,
    shown: 2,
    converted: 1,
    convRate: 50,
    convertedUnder2m: 1,
    avgSecondsToSale: 65,
    medianSecondsToSale: 65,
    attributedRevenue: 4500,
    buckets: [],
    page: 0,
    pageSize: 50,
    totalElements: 3,
    totalPages: 1,
    log: [
      {
        id: 'rec_1',
        type: 'show',
        at: '2026-06-26T10:00:00Z',
        title: 'SelfieLab Zen',
        triggerName: 'Супрастин',
        pharmacyId: 'ph_1',
        pharmacyName: 'Аптека Центр',
        pharmacistId: 'u_1',
        pharmacistName: 'Иван',
        amount: 4500,
        converted: true,
        secondsToSale: 65,
        units: null,
        saleId: null,
      },
      // Один чек (sale_1) = две позиции = две строки журнала, связанные saleId.
      {
        id: 'sale_1#0',
        type: 'sale',
        at: '2026-06-26T10:01:05Z',
        title: 'Аквамарис',
        triggerName: null,
        pharmacyId: 'ph_1',
        pharmacyName: 'Аптека Центр',
        pharmacistId: 'u_1',
        pharmacistName: 'Иван',
        pharmacistSource: 'standardn_name_match',
        amount: 2100,
        converted: false,
        secondsToSale: 65, // позиция рекомендованного товара — чип «через … после показа»
        units: 2,
        saleId: 'sale_1',
      },
      {
        id: 'sale_1#1',
        type: 'sale',
        at: '2026-06-26T10:01:05Z',
        title: 'Цитрамон-П табл №10',
        triggerName: null,
        pharmacyId: 'ph_1',
        pharmacyName: 'Аптека Центр',
        pharmacistId: 'u_1',
        pharmacistName: 'Иван',
        amount: 1100,
        converted: false,
        secondsToSale: null,
        units: 1,
        saleId: 'sale_1',
      },
    ],
    ...over,
  }
}

function setAnalytics(
  data: RecommendationAnalyticsDto | undefined,
  extra: Record<string, unknown> = {},
) {
  dashHooks.useRecommendationAnalytics.mockReturnValue({
    data,
    isLoading: false,
    isError: false,
    error: null,
    refetch: vi.fn(),
    ...extra,
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  useUiStore.setState({ language: 'ru' })
  setSummary(mkSummary())
  setAnalytics(mkAnalytics())
})

afterEach(() => useUiStore.setState({ language: 'ru' }))

it.each([
  ['ru', 'Последние 30 дней', 'Сумма цен проданных рекомендаций', 'Одобрено к выплате', 'не фактическая выручка', 'Рекомендации с продажей'],
  ['kk', 'Соңғы 30 күн', 'Сатылған ұсыныстар бағасының сомасы', 'Төлеуге мақұлданды', 'нақты түсім де', 'Сатумен аяқталған ұсыныстар'],
] as const)('explains amount sources and rolling period in %s', (language, period, metric, payout, note, converted) => {
  useUiStore.setState({ language })
  setAnalytics(mkAnalytics({ windowDays: 30 }))
  renderPage()

  expect(screen.getByTestId('recan-period')).toHaveTextContent(period)
  expect(screen.getByText(metric)).toBeInTheDocument()
  expect(within(screen.getByTestId('recan-kpis')).getByText(converted)).toBeInTheDocument()
  expect(screen.getByTestId('recan-amount-note')).toHaveTextContent(note)
  expect(screen.getByText(payout)).toBeInTheDocument()
  expect(screen.getByTestId('dash-payout-note')).not.toBeEmptyDOMElement()
  expect(screen.getByTestId('dash-amount-note')).not.toBeEmptyDOMElement()
  // Accepted-offer prices, converted-offer prices and receipt item totals differ.
  const moneyText = (amount: number) => formatKzt(amount).replace(/\s/g, ' ')
  expect(screen.getByTestId('dash-top-products-p_1')).toHaveTextContent(moneyText(500_000))
  expect(screen.getByTestId('recan-kpis')).toHaveTextContent(moneyText(4500))
  expect(screen.getByTestId('recan-row-rec_1')).toHaveTextContent(moneyText(4500))
  expect(screen.getByTestId('recan-row-sale_1#0')).toHaveTextContent(moneyText(2100))
  expect(screen.getByTestId('recan-row-sale_1#0')).not.toHaveTextContent(moneyText(4500))
})

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <ToastHost>
          <DashboardPage />
        </ToastHost>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('DashboardPage — рендер', () => {
  it('показывает автоматическое сопоставление через вход ePharm отдельно от правила HQ', () => {
    const analytics = mkAnalytics()
    analytics.log[1].pharmacistSource = 'standardn_rps_binding'
    setAnalytics(analytics)
    renderPage()
    const badge = screen.getByText('Сопоставлен через ePharm')
    expect(badge).toHaveAttribute(
      'title',
      'ИИН или полное имя совпали с пользователем Стандарт-Н на этой кассе',
    )
    const row = badge.closest('tr')!
    expect(within(row).getByText('Иван')).toBeInTheDocument()
    expect(within(row).queryByText('Standard-N: правило')).not.toBeInTheDocument()
  })

  it('H1 + 4 KPI из API', () => {
    renderPage()
    expect(
      screen.getByRole('heading', { level: 1, name: /Дашборд аналитики/i }),
    ).toBeInTheDocument()
    expect(screen.getByText('Показано рекомендаций')).toBeInTheDocument()
    expect(screen.getByText('33.3% конверсия')).toBeInTheDocument()
    // «Принято» is a sale in the receipt now (the till has no accept key): the tile says so.
    expect(screen.getByText('товар попал в чек')).toBeInTheDocument()
    expect(screen.getByText('из 6')).toBeInTheDocument()
  })

  it('топ-3 списка показывают реальные строки', () => {
    renderPage()
    expect(screen.getByTestId('dash-top-rules-r_1')).toBeInTheDocument()
    expect(screen.getByTestId('dash-top-pharmacies-ph_1')).toBeInTheDocument()
    expect(screen.getByTestId('dash-top-products-p_1')).toBeInTheDocument()
    expect(screen.getByText('Аптека Лидер')).toBeInTheDocument()
  })

  it('time-series блоки — Empty (данных нет)', () => {
    renderPage()
    expect(screen.getByText(/Данные для графика пока недоступны/i)).toBeInTheDocument()
    expect(screen.getByText(/Карта активности пока недоступна/i)).toBeInTheDocument()
    expect(screen.getByText(/Это не означает отсутствия продаж/i)).toBeInTheDocument()
  })
})

describe('DashboardPage — пустые топ-списки', () => {
  it('Empty в каждом топе когда массив пуст', () => {
    setSummary(mkSummary({ topRules: [], topPharmacies: [], topProducts: [] }))
    renderPage()
    expect(screen.getAllByText(/Нет данных/i).length).toBeGreaterThanOrEqual(3)
  })
})

describe('DashboardPage — Журнал показов и продаж (V032)', () => {
  it.each([
    ['ru', 'Возврат', 'Продажа'],
    ['kk', 'Қайтару', 'Сату'],
  ] as const)('отличает возврат от продажи по статусу API в %s', (language, refund, sale) => {
    useUiStore.setState({ language })
    const analytics = mkAnalytics()
    // Status is authoritative, not a Russian prefix in the product title.
    analytics.log[1].receiptStatus = 'refund'
    setAnalytics(analytics)
    renderPage()

    const row = screen.getByTestId('recan-row-sale_1#0')
    const badge = within(row).getByText(refund)
    expect(badge).toHaveClass('chip-amber')
    expect(badge).not.toHaveClass('chip-green')
    expect(within(row).queryByText(sale)).not.toBeInTheDocument()
    expect(row).toHaveTextContent(formatKzt(2100).replace(/\s/g, ' '))
    expect(row).not.toHaveTextContent('-2')
    expect(row).not.toHaveTextContent('после показа')
    expect(row).not.toHaveTextContent('кейін')
  })

  it.each([undefined, null])('сохраняет продажу для старого API со статусом %s и не угадывает статус по названию', (receiptStatus) => {
    const analytics = mkAnalytics()
    analytics.log[1].receiptStatus = receiptStatus
    analytics.log[1].title = 'Возврат: название из старого источника'
    setAnalytics(analytics)
    renderPage()

    const row = screen.getByTestId('recan-row-sale_1#0')
    expect(within(row).getByText('Продажа')).toHaveClass('chip-green')
    expect(within(row).queryByText('Возврат')).not.toBeInTheDocument()
  })

  it('первая загрузка журнала не подменяет данные нулевыми KPI или пустым периодом', () => {
    setAnalytics(undefined, { isLoading: true, isFetching: true })
    renderPage()

    expect(screen.getByRole('status')).toHaveTextContent('Загружаем журнал…')
    expect(screen.queryByTestId('recan-kpis')).not.toBeInTheDocument()
    expect(screen.queryByText('Пока нет событий за период')).not.toBeInTheDocument()
    expect(screen.queryByTestId('recan-table')).not.toBeInTheDocument()
  })

  it('ошибка первой загрузки журнала явно показана без ложных нулей, повтор доступен', async () => {
    const refetch = vi.fn()
    setAnalytics(undefined, { isError: true, error: new Error('journal failed'), refetch })
    const user = userEvent.setup()
    renderPage()

    const alert = screen.getByRole('alert')
    expect(alert).toHaveTextContent('Не удалось загрузить журнал')
    expect(alert).toHaveTextContent('journal failed')
    expect(screen.queryByTestId('recan-kpis')).not.toBeInTheDocument()
    expect(screen.queryByText('Пока нет событий за период')).not.toBeInTheDocument()
    await user.click(within(alert).getByRole('button', { name: 'Повторить' }))
    expect(refetch).toHaveBeenCalledOnce()
  })

  it('ошибка обновления сохраняет последние KPI и строки, но предупреждает об их актуальности', async () => {
    const refetch = vi.fn()
    setAnalytics(mkAnalytics(), { isError: true, error: new Error('refresh failed'), refetch })
    const user = userEvent.setup()
    renderPage()

    const alert = screen.getByRole('alert')
    expect(alert).toHaveTextContent('Не удалось обновить (refresh failed)')
    expect(alert).toHaveTextContent('Показаны последние сохранённые')
    expect(screen.getByTestId('recan-kpis')).toHaveTextContent('1 · 50%')
    expect(screen.getByTestId('recan-row-rec_1')).toHaveTextContent('SelfieLab Zen')
    expect(screen.getByTestId('recan-row-sale_1#0')).toHaveTextContent('Аквамарис')
    expect(screen.queryByText('Пока нет событий за период')).not.toBeInTheDocument()
    await user.click(within(alert).getByRole('button', { name: 'Повторить' }))
    expect(refetch).toHaveBeenCalledOnce()
  })

  it('повтор загрузки журнала недоступен, пока запрос уже выполняется', () => {
    setAnalytics(undefined, { isError: true, isFetching: true, error: new Error('retrying') })
    renderPage()

    expect(
      within(screen.getByRole('alert')).getByRole('button', { name: 'Повторить' }),
    ).toBeDisabled()
  })

  it('KPI + единый лог: строка показа и строка продажи', () => {
    renderPage()
    expect(screen.getByTestId('recan-kpis')).toBeInTheDocument()
    // показ
    expect(screen.getByTestId('recan-row-rec_1')).toBeInTheDocument()
    expect(screen.getByText('SelfieLab Zen')).toBeInTheDocument() // что рекомендовали
    expect(screen.getByText(/выбрали: Супрастин/)).toBeInTheDocument() // что выбрал покупатель
    // продажа: каждая позиция чека — отдельной строкой со своим кол-вом и суммой
    const pos0 = screen.getByTestId('recan-row-sale_1#0')
    const pos1 = screen.getByTestId('recan-row-sale_1#1')
    expect(screen.getByText('Фармацевт / продавец')).toBeInTheDocument()
    expect(within(pos0).getByText('Аквамарис')).toBeInTheDocument()
    expect(within(pos0).getByText('Иван')).toBeInTheDocument()
    expect(within(pos0).getByText('Сопоставлено по ФИО Standard-N')).toBeInTheDocument()
    expect(within(pos1).getByText('Цитрамон-П табл №10')).toBeInTheDocument()
    expect(screen.getByTestId('recan-units-sale_1#0')).toHaveTextContent('2 шт')
    expect(screen.getByTestId('recan-units-sale_1#1')).toHaveTextContent('1 шт')
    // конвертированный показ → chip «Продано · 1 мин 5 с» (65 сек)
    expect(screen.getByText(/Продано · 1 мин 5 с/)).toBeInTheDocument()
    // чип «через 1 мин 5 с после показа» — ровно на ОДНОЙ позиции (рекомендованный товар)
    expect(screen.getAllByText(/через 1 мин 5 с после показа/)).toHaveLength(1)
  })

  it('пустой период → Empty', () => {
    setAnalytics(mkAnalytics({ log: [], shown: 0, converted: 0, totalElements: 0, totalPages: 0 }))
    renderPage()
    expect(screen.getByText(/Пока нет событий за период/i)).toBeInTheDocument()
  })

  it.each([null, -30])('не выдумывает нулевой или отрицательный интервал продажи при времени %s', (seconds) => {
    const analytics = mkAnalytics()
    analytics.log[0].secondsToSale = seconds
    analytics.log[1].secondsToSale = seconds
    setAnalytics(analytics)
    renderPage()

    const show = screen.getByTestId('recan-row-rec_1')
    expect(within(show).getByText('Продажа')).toBeInTheDocument()
    expect(show).not.toHaveTextContent('Продано ·')
    expect(screen.getByTestId('recan-row-sale_1#0')).not.toHaveTextContent('после показа')
  })

  it('сохраняет достоверный нулевой интервал продажи', () => {
    const analytics = mkAnalytics()
    analytics.log[0].secondsToSale = 0
    analytics.log[1].secondsToSale = 0
    setAnalytics(analytics)
    renderPage()

    expect(screen.getByTestId('recan-row-rec_1')).toHaveTextContent('Продано · 0 с')
    expect(screen.getByTestId('recan-row-sale_1#0')).toHaveTextContent('через 0 с после показа')
  })

  it('переключает страницы и показывает полный размер журнала', async () => {
    setAnalytics(mkAnalytics({ totalElements: 123, totalPages: 3 }))
    const user = userEvent.setup()
    renderPage()

    expect(screen.getByTestId('recan-pagination')).toHaveTextContent('Показано 1–50 из 123')
    await user.click(screen.getByRole('button', { name: 'Перейти на страницу 2' }))
    expect(dashHooks.useRecommendationAnalytics).toHaveBeenLastCalledWith(1, 50)
  })
})

describe('DashboardPage — loading/error', () => {
  it('isLoading без data — загрузка без ложных нулевых метрик и пустых топов', () => {
    setSummary(undefined, { isLoading: true })
    renderPage()
    expect(screen.getByTestId('dash-summary-state')).toHaveTextContent('загрузка…')
    expect(screen.queryByTestId('dash-summary-kpis')).not.toBeInTheDocument()
    expect(screen.queryByText('Нет активности')).not.toBeInTheDocument()
    expect(screen.queryByText('Нет данных')).not.toBeInTheDocument()
    expect(screen.getByTestId('recan-table')).toBeInTheDocument()
  })

  it('isError без data — баннер + Повторить', async () => {
    const refetch = vi.fn()
    setSummary(undefined, { isError: true, error: new Error('boom'), refetch })
    const user = userEvent.setup()
    renderPage()
    expect(screen.getByText(/Не удалось загрузить сводку/i)).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('boom')
    expect(screen.queryByTestId('dash-summary-kpis')).not.toBeInTheDocument()
    expect(screen.queryByText('Нет данных')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /Повторить/ }))
    expect(refetch).toHaveBeenCalled()
  })

  it('ошибка обновления сводки сохраняет числа и топы с явным предупреждением', async () => {
    const refetch = vi.fn()
    setSummary(mkSummary(), { isError: true, error: new Error('summary refresh failed'), refetch })
    const user = userEvent.setup()
    renderPage()

    const alert = screen.getByRole('alert')
    expect(alert).toHaveTextContent('summary refresh failed')
    expect(alert).toHaveTextContent('Показаны последние сохранённые')
    expect(screen.getByTestId('dash-summary-kpis')).toHaveTextContent('33.3% конверсия')
    expect(screen.getByTestId('dash-top-rules-r_1')).toBeInTheDocument()
    await user.click(within(alert).getByRole('button', { name: 'Повторить' }))
    expect(refetch).toHaveBeenCalledOnce()
  })

  it('повтор сводки недоступен, пока запрос уже выполняется', () => {
    setSummary(undefined, { isError: true, isFetching: true, error: new Error('retrying') })
    renderPage()
    expect(within(screen.getByRole('alert')).getByRole('button', { name: 'Повторить' })).toBeDisabled()
  })
})
