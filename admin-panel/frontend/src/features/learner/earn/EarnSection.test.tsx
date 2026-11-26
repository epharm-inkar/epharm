import { afterEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useUiStore } from '@/app/store'
import { EarnSection } from './EarnSection'
import type { EarnLeaderboard, EarnProductDetail, EarnProducts, EarnSummary } from './earnTypes'

const products: EarnProducts = {
  items: [
    {
      productId: 'p-tantum',
      name: 'Тантум Верде спрей 0,15%',
      brand: 'Angelini',
      mnn: 'Бензидамин',
      volume: '30 мл',
      priceKzt: 2890,
      imageUrl: null,
      bonusKzt: 200,
      kinds: ['substitution'],
      campaignTitle: 'Тантум',
      campaignEndsOn: '2026-12-31',
      soldWeek: 42,
      inMyPharmacy: true,
      hit: true,
    },
    {
      productId: 'p-sept',
      name: 'Септаназал спрей',
      brand: 'KRKA',
      mnn: '',
      volume: '10 мл',
      priceKzt: 2350,
      imageUrl: null,
      bonusKzt: 120,
      kinds: ['crosssell'],
      campaignTitle: null,
      campaignEndsOn: null,
      soldWeek: 3,
      inMyPharmacy: true,
      hit: false,
    },
  ],
  pharmacyName: 'Аптека со склада, Абая 1',
  availabilityBasis: 'sales',
  availabilityDays: 30,
  generatedAt: '2026-11-26T10:00:00Z',
}

const summary = (earned: number): EarnSummary => ({
  period: 'today',
  from: '2026-11-25T19:00:00Z',
  earnedKzt: earned,
  hintSales: earned ? 2 : 0,
  receipts: 31,
  units: 7,
  products: [
    { name: 'Тантум Верде спрей 0,15%', quantity: 2, sumKzt: 5780, receipts: 2, promo: true },
    { name: 'Парацетамол', quantity: 5, sumKzt: 900, receipts: 5, promo: false },
  ],
  recentHintSales: earned
    ? [{ at: '2026-11-26T09:00:00Z', productName: 'Тантум Верде спрей 0,15%', bonusKzt: 200 }]
    : [],
  status: { level: 'start', title: '', message: '', monthEarnedKzt: earned, nextLevelKzt: 1000 },
  weekPlace: earned ? 3 : null,
})

const detail: EarnProductDetail = {
  product: products.items[0],
  description: 'Спрей для горла с бензидамином: снимает боль и воспаление.',
  keyFacts: ['Взрослым: 4-8 впрыскиваний 2-6 раз в день'],
  qa: [{ q: 'Отпускается по рецепту?', a: 'Нет.' }],
  rxOtc: 'otc',
  manufacturer: 'Angelini',
  whenAsked: ['Гервитин спрей'],
  scripts: ['Тот же бензидамин, оригинальный препарат.'],
  advantages: [],
}

const board: EarnLeaderboard = {
  period: 'week',
  scope: 'pharmacists',
  items: [
    {
      place: 1,
      name: 'Сапарова А.',
      pharmacy: 'Аптека 2',
      city: 'Астана',
      earnedKzt: 900,
      sales: 5,
      me: false,
    },
    {
      place: 2,
      name: 'Нуртаева Ж.',
      pharmacy: 'Аптека 7',
      city: 'Шымкент',
      earnedKzt: 600,
      sales: 3,
      me: false,
    },
  ],
  me: {
    place: 26,
    name: 'Бузурбаева Л.',
    pharmacy: 'Аптека со склада, Абая 1',
    city: 'Алматы',
    earnedKzt: 10,
    sales: 1,
    me: true,
  },
  participants: 26,
}

function server(earned = 400) {
  const calls: string[] = []
  const fetchJson = vi.fn(async (path: string) => {
    calls.push(path)
    if (path.startsWith('/api/mobile/me/earn/summary')) return summary(earned)
    if (path.startsWith('/api/mobile/me/earn/products/')) return detail
    if (path.startsWith('/api/mobile/me/earn/products')) {
      const mine = path.includes('mine=true')
      return { ...products, items: mine ? products.items : [...products.items] }
    }
    if (path.startsWith('/api/mobile/me/earn/leaderboard')) return board
    throw new Error(`unexpected ${path}`)
  }) as unknown as <T>(path: string) => Promise<T>
  return { fetchJson, calls }
}

afterEach(() => useUiStore.setState({ language: 'ru' }))

describe('«Бонусы» tab', () => {
  it('opens on bonus products of my pharmacy, the network best sellers first, with today in the header', async () => {
    const { fetchJson, calls } = server()
    render(<EarnSection fetchJson={fetchJson} />)
    expect(await screen.findByTestId('earn-product-p-tantum')).toBeInTheDocument()
    expect(calls).toContain('/api/mobile/me/earn/products?sort=sales&mine=true')
    expect(screen.getByRole('switch', { name: /Есть в моей аптеке/ })).toBeChecked()
    expect(screen.getByText('По продажам вашей аптеки за 30 дней')).toBeInTheDocument()
    expect(await screen.findByTestId('earn-today')).toHaveTextContent('400 ₸')
    expect(screen.getByText('Хорошо идёте!')).toBeInTheDocument()
    expect(screen.getByText('3-е место за неделю')).toBeInTheDocument()
    const card = screen.getByTestId('earn-product-p-tantum')
    expect(within(card).getByText('+200 ₸')).toBeInTheDocument()
    expect(within(card).getByText('42 уп. за неделю')).toBeInTheDocument()
    expect(within(card).getByText('Хит')).toBeInTheDocument()
  })

  it('switches the filter off and sorts by the bonus', async () => {
    const { fetchJson, calls } = server()
    const user = userEvent.setup()
    render(<EarnSection fetchJson={fetchJson} />)
    await screen.findByTestId('earn-product-p-tantum')
    await user.click(screen.getByRole('switch', { name: /Есть в моей аптеке/ }))
    await waitFor(() =>
      expect(calls).toContain('/api/mobile/me/earn/products?sort=sales&mine=false'),
    )
    await user.click(screen.getByRole('button', { name: /Больше бонус/ }))
    await waitFor(() =>
      expect(calls).toContain('/api/mobile/me/earn/products?sort=bonus&mine=false'),
    )
  })

  it('opens a product card with when to offer it, what to say and what the drug is', async () => {
    const { fetchJson } = server()
    const user = userEvent.setup()
    render(<EarnSection fetchJson={fetchJson} />)
    await user.click(await screen.findByTestId('earn-product-p-tantum'))
    const dialog = await screen.findByRole('dialog', { name: 'Тантум Верде спрей 0,15%' })
    expect(await within(dialog).findByText('Гервитин спрей')).toBeInTheDocument()
    expect(
      within(dialog).getByText('«Тот же бензидамин, оригинальный препарат.»'),
    ).toBeInTheDocument()
    expect(within(dialog).getByText(/Спрей для горла с бензидамином/)).toBeInTheDocument()
    expect(within(dialog).getByText('Без рецепта')).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Закрыть' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('shows my sales of the day with the bonus products marked', async () => {
    const { fetchJson, calls } = server()
    const user = userEvent.setup()
    render(<EarnSection fetchJson={fetchJson} />)
    await user.click(screen.getByRole('tab', { name: /Продажи/ }))
    expect(await screen.findByText('Что вы продали')).toBeInTheDocument()
    expect(screen.getByText('Парацетамол')).toBeInTheDocument()
    expect(screen.getByText('с бонусом')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Месяц' }))
    await waitFor(() => expect(calls).toContain('/api/mobile/me/earn/summary?period=month'))
  })

  it('shows the leaderboard with medals and my own place below the top', async () => {
    const { fetchJson, calls } = server()
    const user = userEvent.setup()
    render(<EarnSection fetchJson={fetchJson} />)
    await user.click(screen.getByRole('tab', { name: /Рейтинг/ }))
    expect(await screen.findByText('Сапарова А.')).toBeInTheDocument()
    expect(screen.getByTestId('earn-leader-me')).toHaveTextContent('Бузурбаева Л.')
    expect(screen.getByTestId('earn-leader-me')).toHaveTextContent('26')
    await user.click(screen.getByRole('button', { name: /Аптеки/ }))
    await waitFor(() =>
      expect(calls).toContain('/api/mobile/me/earn/leaderboard?period=week&scope=pharmacies'),
    )
  })

  it('invites to the first sale when there is no bonus yet, in Kazakh too', async () => {
    useUiStore.setState({ language: 'kk' })
    const { fetchJson } = server(0)
    render(<EarnSection fetchJson={fetchJson} />)
    expect(await screen.findByText('Алғашқы сатылымнан бастаңыз')).toBeInTheDocument()
    expect(screen.getByRole('switch', { name: /Менің дәріханамда бар/ })).toBeChecked()
  })

  it('offers a retry when loading fails', async () => {
    const fetchJson = vi.fn(async () => {
      throw new Error('offline')
    }) as unknown as <T>(path: string) => Promise<T>
    render(<EarnSection fetchJson={fetchJson} />)
    expect(await screen.findByText('Не удалось загрузить. Проверьте интернет.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Повторить' })).toBeInTheDocument()
  })
})
