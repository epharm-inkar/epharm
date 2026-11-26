// «Фармацевты в ePharm»: the first block of the Analytics page.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { useUiStore } from '@/app/store'
import type { PharmacistCoverageDayDto, PharmacistCoverageDto } from '@/lib/api-types'
import { PharmacistsUsage } from './PharmacistsUsage'

const coverageHook = vi.hoisted(() => ({ usePharmacistCoverage: vi.fn() }))
vi.mock('@/lib/queries/pharmacists', () => coverageHook)

const png = vi.hoisted(() => ({ renderChartPng: vi.fn() }))
vi.mock('./chartPng', () => png)

const saver = vi.hoisted(() => ({ saveReport: vi.fn() }))
vi.mock('@/features/reports/ledgerReports', () => saver)

function day(date: string, over: Partial<PharmacistCoverageDayDto> = {}): PharmacistCoverageDayDto {
  return {
    date,
    total: 5320,
    active7d: 1500,
    sellers7d: 800,
    coveragePct: 28.2,
    joined: 0,
    active1d: 300,
    coverage1dPct: 5.6,
    ...over,
  }
}

function coverage(over: Partial<PharmacistCoverageDto> = {}): PharmacistCoverageDto {
  return {
    date: '2026-11-26',
    total: 5320,
    fromStandardN: 5308,
    registered: 12,
    active7d: 1880,
    sellers7d: 900,
    cabinet7d: 30,
    coveragePct: 35.3,
    daily: [
      // Before anybody was in the system: dropped from the chart.
      day('2026-07-01', { total: 0, active7d: 0, active1d: 0, coveragePct: 0, coverage1dPct: 0 }),
      day('2026-07-02', { total: 0, active7d: 0, active1d: 0, coveragePct: 0, coverage1dPct: 0 }),
      day('2026-07-03', { total: 60, active7d: 40, active1d: 20, coveragePct: 66.7 }),
      day('2026-11-25'),
      day('2026-11-26', { active7d: 1880, active1d: 412, coverage1dPct: 7.7, coveragePct: 35.3 }),
    ],
    generatedAt: '2026-11-26T09:00:00Z',
    activeToday: 412,
    coverageTodayPct: 7.7,
    ...over,
  }
}

function setCoverage(data: PharmacistCoverageDto | undefined, extra: Record<string, unknown> = {}) {
  coverageHook.usePharmacistCoverage.mockReturnValue({
    data,
    isLoading: false,
    isError: false,
    isFetching: false,
    error: null,
    refetch: vi.fn(),
    ...extra,
  })
}

function renderBlock() {
  return render(
    <MemoryRouter>
      <PharmacistsUsage />
    </MemoryRouter>,
  )
}

/** Intl groups thousands with a no-break or narrow no-break space: compare with a plain one. */
const plain = (text: string | null) => (text ?? '').replace(/\s/g, ' ')

beforeEach(() => {
  vi.clearAllMocks()
  useUiStore.setState({ language: 'ru' })
  setCoverage(coverage())
})

afterEach(() => useUiStore.setState({ language: 'ru' }))

describe('PharmacistsUsage: the numbers', () => {
  it('shows who is in the system, who worked today and in the last 7 days, with the shares', () => {
    renderBlock()
    expect(
      screen.getByRole('heading', { level: 2, name: 'Фармацевты в ePharm' }),
    ).toBeInTheDocument()
    const kpis = screen.getByTestId('usage-kpis')
    const tile = (label: string) => within(kpis).getByText(label).closest('.card') as HTMLElement

    const total = tile('Фармацевтов в системе')
    expect(plain(total.textContent)).toContain('5 320')
    expect(plain(total.textContent)).toContain('сами 12 · Стандарт-Н 5 308')

    const today = tile('Работали с ePharm сегодня')
    expect(plain(today.textContent)).toContain('412')
    expect(today).toHaveTextContent('7,7%')
    expect(today).toHaveTextContent('день ещё не закончен')

    const week = tile('Работали с ePharm за 7 дней')
    expect(plain(week.textContent)).toContain('1 880')
    expect(week).toHaveTextContent('35,3%')
  })

  it('asks for the whole life of the system, about a year back', () => {
    renderBlock()
    expect(coverageHook.usePharmacistCoverage).toHaveBeenCalledWith(366)
  })

  it('explains the rule and links to the coverage tab it shares its data with', () => {
    renderBlock()
    const rule = screen.getByTestId('usage-rule')
    expect(rule).toHaveTextContent('все фармацевты, кроме заблокированных')
    expect(rule).toHaveTextContent(
      'продажа на кассе с ePharm, показанная подсказка или вход в кабинет',
    )
    expect(screen.getByRole('link', { name: 'Открыть «Покрытие»' })).toHaveAttribute(
      'href',
      '/pharmacists?view=coverage',
    )
  })

  it('shows a real zero for today, not a dash', () => {
    setCoverage(coverage({ activeToday: 0, coverageTodayPct: 0 }))
    renderBlock()
    const today = within(screen.getByTestId('usage-kpis'))
      .getByText('Работали с ePharm сегодня')
      .closest('.card') as HTMLElement
    expect(today).toHaveTextContent('0')
    expect(today).toHaveTextContent('0%')
    expect(today).not.toHaveTextContent('нет данных')
  })

  it('says «нет данных» for today when the backend does not send it yet, and charts only the 7 days', () => {
    setCoverage(
      coverage({
        activeToday: undefined,
        coverageTodayPct: undefined,
        daily: [
          day('2026-11-25', { active1d: undefined, coverage1dPct: undefined }),
          day('2026-11-26', { active1d: undefined, coverage1dPct: undefined }),
        ],
      }),
    )
    renderBlock()
    const today = within(screen.getByTestId('usage-kpis'))
      .getByText('Работали с ePharm сегодня')
      .closest('.card') as HTMLElement
    expect(today).toHaveTextContent('нет данных')
    expect(today).not.toHaveTextContent('день ещё не закончен')
    const chart = screen.getByRole('img', {
      name: 'Фармацевты, работавшие с ePharm, по дням: в этот день и за 7 дней',
    })
    expect(chart.textContent).toContain('за 7 дней')
    expect(chart.textContent).not.toContain('в этот день')
  })
})

describe('PharmacistsUsage: the charts', () => {
  it('draws one line for each day and one for the 7 days, with their last values', () => {
    renderBlock()
    const count = screen.getByRole('img', {
      name: 'Фармацевты, работавшие с ePharm, по дням: в этот день и за 7 дней',
    })
    expect(plain(count.textContent)).toContain('412 в этот день')
    expect(plain(count.textContent)).toContain('1 880 за 7 дней')
    const share = screen.getByRole('img', {
      name: 'Доля фармацевтов, работавших с ePharm, по дням',
    })
    expect(plain(share.textContent)).toContain('7,7% в этот день')
    expect(plain(share.textContent)).toContain('35,3% за 7 дней')
    expect(screen.getAllByRole('img')).toHaveLength(2)
  })

  it('starts at the first day with a pharmacist in the system, not at zero before the launch', () => {
    renderBlock()
    expect(screen.getByTestId('usage-period')).toHaveTextContent('С 03.07.2026 по 26.11.2026')
    // First chart day is 03.07 (the two empty days before it are gone), then 25.11 and 26.11.
    const count = screen.getByRole('img', {
      name: 'Фармацевты, работавшие с ePharm, по дням: в этот день и за 7 дней',
    })
    expect(count.textContent).toContain('03.07')
    expect(count.textContent).not.toContain('01.07')
  })

  it('does not draw a chart of one day, and says when it will appear', () => {
    setCoverage(coverage({ daily: [day('2026-11-26')] }))
    renderBlock()
    expect(screen.getByTestId('usage-few-days')).toHaveTextContent(
      'График появится, когда наберётся хотя бы два дня данных.',
    )
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
    expect(screen.getByTestId('usage-download')).toBeDisabled()
    // The headline figures stay.
    expect(plain(screen.getByTestId('usage-kpis').textContent)).toContain('5 320')
  })
})

describe('PharmacistsUsage: states', () => {
  it('first load: a status, no made-up zeros and no charts', () => {
    setCoverage(undefined, { isLoading: true })
    renderBlock()
    expect(screen.getByTestId('usage-state')).toHaveTextContent('Считаем фармацевтов…')
    expect(screen.getByTestId('usage-state')).toHaveAttribute('role', 'status')
    expect(screen.queryByTestId('usage-kpis')).not.toBeInTheDocument()
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
  })

  it('first load failed: the reason and a retry, no zeros', async () => {
    const refetch = vi.fn()
    setCoverage(undefined, { isError: true, error: new Error('coverage failed'), refetch })
    const user = userEvent.setup()
    renderBlock()
    const state = screen.getByTestId('usage-state')
    expect(state).toHaveAttribute('role', 'alert')
    expect(state).toHaveTextContent('Не удалось посчитать фармацевтов')
    expect(state).toHaveTextContent('coverage failed')
    expect(screen.queryByTestId('usage-kpis')).not.toBeInTheDocument()
    await user.click(within(state).getByRole('button', { name: 'Повторить' }))
    expect(refetch).toHaveBeenCalledOnce()
  })

  it('a failed refresh keeps the last numbers and says so', async () => {
    const refetch = vi.fn()
    setCoverage(coverage(), { isError: true, error: new Error('refresh failed'), refetch })
    const user = userEvent.setup()
    renderBlock()
    const alert = screen.getByRole('alert')
    expect(alert).toHaveTextContent('Не удалось обновить (refresh failed)')
    expect(plain(screen.getByTestId('usage-kpis').textContent)).toContain('5 320')
    await user.click(within(alert).getByRole('button', { name: 'Повторить' }))
    expect(refetch).toHaveBeenCalledOnce()
  })

  it('speaks Kazakh', () => {
    useUiStore.setState({ language: 'kk' })
    renderBlock()
    expect(
      screen.getByRole('heading', { level: 2, name: 'ePharm-дегі фармацевттер' }),
    ).toBeInTheDocument()
    expect(screen.getByText('Бүгін ePharm-мен жұмыс істегендер')).toBeInTheDocument()
    expect(screen.getByText('Жүйедегі фармацевттер')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Графикті жүктеп алу' })).toBeInTheDocument()
    expect(screen.getByTestId('usage-period')).toHaveTextContent(
      '03.07.2026 бастап 26.11.2026 дейін',
    )
  })
})

describe('PharmacistsUsage: «Скачать график»', () => {
  it('saves one picture with the title, the period, the figures, both charts and the rule', async () => {
    const blob = new Blob(['png'], { type: 'image/png' })
    png.renderChartPng.mockResolvedValue(blob)
    const user = userEvent.setup()
    renderBlock()
    await user.click(screen.getByRole('button', { name: 'Скачать график' }))

    await waitFor(() => expect(saver.saveReport).toHaveBeenCalledOnce())
    expect(saver.saveReport).toHaveBeenCalledWith(blob, 'pharmacists-in-epharm-2026-11-26.png')

    const spec = png.renderChartPng.mock.calls[0][0]
    expect(spec.title).toBe('Фармацевты в ePharm')
    expect(spec.lines[0]).toBe('С 03.07.2026 по 26.11.2026')
    expect(plain(spec.lines[1])).toBe(
      'В системе: 5 320. Сегодня работали: 412 (7,7%). За 7 дней: 1 880 (35,3%).',
    )
    expect(spec.lines[2]).toBe('Данные на 26.11.2026')
    expect(spec.charts.map((chart: { heading: string }) => chart.heading)).toEqual([
      'Сколько фармацевтов работали с ePharm, по дням',
      'Какая доля фармацевтов в системе работала с ePharm, %',
    ])
    // The very charts on the screen, not a second drawing of them.
    expect(spec.charts.map((chart: { svg: Element }) => chart.svg.getAttribute('role'))).toEqual([
      'img',
      'img',
    ])
    expect(spec.footnote).toContain('кроме заблокированных')
    expect(spec.footnote).not.toContain('Покрытие')
  })

  it('is busy while the picture is made and then free again', async () => {
    let finish: (blob: Blob) => void = () => undefined
    png.renderChartPng.mockReturnValue(new Promise<Blob>((resolve) => (finish = resolve)))
    const user = userEvent.setup()
    renderBlock()
    await user.click(screen.getByRole('button', { name: 'Скачать график' }))
    expect(await screen.findByRole('button', { name: 'Готовим картинку…' })).toBeDisabled()
    finish(new Blob(['png']))
    expect(await screen.findByRole('button', { name: 'Скачать график' })).toBeEnabled()
  })

  it('says so when the picture cannot be made, and saves nothing', async () => {
    png.renderChartPng.mockRejectedValue(new Error('no canvas'))
    const user = userEvent.setup()
    renderBlock()
    await user.click(screen.getByRole('button', { name: 'Скачать график' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Не удалось сохранить картинку')
    expect(saver.saveReport).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: 'Скачать график' })).toBeEnabled()
  })

  it('writes «нет данных» for today into the picture when the backend does not send it', async () => {
    png.renderChartPng.mockResolvedValue(new Blob(['png']))
    setCoverage(
      coverage({
        activeToday: undefined,
        coverageTodayPct: undefined,
        daily: [
          day('2026-11-25', { active1d: undefined, coverage1dPct: undefined }),
          day('2026-11-26', { active1d: undefined, coverage1dPct: undefined }),
        ],
      }),
    )
    const user = userEvent.setup()
    renderBlock()
    await user.click(screen.getByRole('button', { name: 'Скачать график' }))
    await waitFor(() => expect(png.renderChartPng).toHaveBeenCalledOnce())
    expect(plain(png.renderChartPng.mock.calls[0][0].lines[1])).toBe(
      'В системе: 5 320. Сегодня работали: нет данных. За 7 дней: 1 880 (35,3%).',
    )
  })
})
