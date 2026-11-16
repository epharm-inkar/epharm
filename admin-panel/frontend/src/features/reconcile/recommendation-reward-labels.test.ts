import { expect, it } from 'vitest'
import { rewardReason } from './recommendation-reward-labels'

it('explains technical accounting overflow separately from campaign budget exhaustion in RU/KK', () => {
  expect(rewardReason('reward_budget_overflow')).toContain('технический диапазон')
  expect(rewardReason('reward_budget_overflow', 'kk')).toContain('техникалық диапазоннан')
  expect(rewardReason('reward_budget_exhausted')).toBe('Бюджет акции исчерпан')
})
