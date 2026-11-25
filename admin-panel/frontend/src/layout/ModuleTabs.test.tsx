import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { adminNavigationForRole } from '@/app/adminNavigation'
import { ModuleTabs } from './ModuleTabs'

const hqModules = adminNavigationForRole('HQ_HEAD', {
  inventory: true,
  placements: true,
  tasks: true,
  ledger: true,
})

describe('ModuleTabs: sections of the open module as tabs on the page', () => {
  it('shows the sections of the module the page belongs to and opens another one', async () => {
    const onNavigate = vi.fn()
    render(
      <ModuleTabs
        modules={hqModules}
        active="reconcile"
        activeTrainingTab="overview"
        pathname="/reconcile"
        onNavigate={onNavigate}
      />,
    )
    const current = screen.getByRole('button', { name: /Сверка чеков/ })
    expect(current).toHaveAttribute('aria-current', 'page')
    const tabs = screen.getAllByRole('button')
    expect(tabs.length).toBeGreaterThan(2)
    await userEvent.click(tabs.find((tab) => tab !== current)!)
    expect(onNavigate).toHaveBeenCalledTimes(1)
  })

  it('stays out of the training page, which has its own tabs, and of single-section modules', () => {
    const { container, rerender } = render(
      <ModuleTabs
        modules={hqModules}
        active="lms"
        activeTrainingTab="courses"
        pathname="/lms"
        onNavigate={vi.fn()}
      />,
    )
    expect(container).toBeEmptyDOMElement()
    rerender(
      <ModuleTabs
        modules={hqModules}
        active="pharmacists"
        activeTrainingTab="overview"
        pathname="/pharmacists"
        onNavigate={vi.fn()}
      />,
    )
    expect(container).toBeEmptyDOMElement()
  })

  it('from AI exam offers the way back to training', async () => {
    const onNavigate = vi.fn()
    render(
      <ModuleTabs
        modules={hqModules}
        active="ai_exam"
        activeTrainingTab="overview"
        pathname="/ai-exam"
        onNavigate={onNavigate}
      />,
    )
    await userEvent.click(screen.getByRole('button', { name: 'Обучение' }))
    expect(onNavigate).toHaveBeenCalledWith('/lms')
  })
})
