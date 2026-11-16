import { afterEach, expect, it } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import { useUiStore } from '@/app/store'
import { CampaignBudgetMode } from './CampaignBudgetMode'

afterEach(() => { cleanup(); useUiStore.setState({ language: 'ru' }) })

it.each([
  ['ru', 'Без лимита бюджета', 'Остальные условия начисления сохраняются'],
  ['kk', 'Бюджет лимитінсіз', 'Есептеудің басқа шарттары сақталады'],
] as const)('explains unlimited financial mode in %s without a toggle for read-only users', (language, label, hint) => {
  useUiStore.setState({ language })
  render(<CampaignBudgetMode unlimited canConfigure={false} onChange={() => {}} />)
  expect(screen.getByText(label)).toBeInTheDocument()
  expect(screen.getByText(new RegExp(hint))).toBeInTheDocument()
  expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
})
