// Ответы /api/mobile/me/earn/* (вкладка «Бонусы», 26.11.2026). Зеркало kz.epharm.mobile.earn.EarnDtos.

export interface EarnProduct {
  productId: string
  name: string
  brand: string
  mnn: string
  volume: string
  priceKzt: number
  imageUrl: string | null
  bonusKzt: number
  kinds: string[]
  campaignTitle: string | null
  campaignEndsOn: string | null
  soldWeek: number
  inMyPharmacy: boolean
  hit: boolean
}

export interface EarnProducts {
  items: EarnProduct[]
  pharmacyName: string | null
  availabilityBasis: string
  availabilityDays: number
  generatedAt: string
}

export interface EarnProductDetail {
  product: EarnProduct
  description: string | null
  keyFacts: string[]
  qa: { q: string; a: string }[]
  rxOtc: string | null
  manufacturer: string | null
  whenAsked: string[]
  scripts: string[]
  advantages: string[]
}

export interface EarnSoldProduct {
  name: string
  quantity: number
  sumKzt: number
  receipts: number
  promo: boolean
}

export interface EarnHintSale {
  at: string
  productName: string
  bonusKzt: number
}

export interface EarnStatus {
  level: 'start' | 'confident' | 'pro' | 'star' | string
  title: string
  message: string
  monthEarnedKzt: number
  nextLevelKzt: number | null
}

export interface EarnSummary {
  period: EarnPeriod
  from: string
  earnedKzt: number
  hintSales: number
  receipts: number
  units: number
  products: EarnSoldProduct[]
  recentHintSales: EarnHintSale[]
  status: EarnStatus
  weekPlace: number | null
}

export interface EarnLeader {
  place: number
  name: string
  pharmacy: string | null
  city: string | null
  earnedKzt: number
  sales: number
  me: boolean
}

export interface EarnLeaderboard {
  period: EarnPeriod
  scope: EarnScope
  items: EarnLeader[]
  me: EarnLeader | null
  participants: number
}

export type EarnPeriod = 'today' | 'week' | 'month'
export type EarnScope = 'pharmacists' | 'pharmacies'
export type EarnSort = 'sales' | 'bonus'

/** Загрузка с личным входом фармацевта: родитель передаёт её со своими токенами. */
export type EarnFetch = <T>(path: string) => Promise<T>

export const LEVEL_NAMES: Record<string, string> = {
  start: 'Старт',
  confident: 'Уверенный',
  pro: 'Профи',
  star: 'Звезда ePharm',
}

export const LEVEL_ORDER = ['start', 'confident', 'pro', 'star']
