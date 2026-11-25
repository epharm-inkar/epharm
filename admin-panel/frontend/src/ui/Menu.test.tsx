import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Menu } from './Menu'

function renderMenu(onExport = vi.fn(), onHidden = vi.fn()) {
  render(
    <div>
      <button type="button">Снаружи</button>
      <Menu
        trigger="Ещё"
        items={[
          { id: 'export', label: 'Excel: реестр', hint: 'По статусу', onSelect: onExport },
          { id: 'credentials', label: 'Учётные данные ЛК', disabled: true, hint: 'Выберите людей', onSelect: onHidden },
        ]}
      />
    </div>,
  )
  return { onExport, onHidden }
}

describe('Menu', () => {
  it('opens on click, runs the chosen item once and closes', async () => {
    const user = userEvent.setup()
    const { onExport } = renderMenu()
    const trigger = screen.getByRole('button', { name: 'Ещё' })
    expect(trigger).toHaveAttribute('aria-haspopup', 'menu')
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
    await user.click(trigger)
    expect(trigger).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByRole('menuitem', { name: /Excel: реестр/ })).toHaveFocus()
    expect(screen.getByText('По статусу')).toBeInTheDocument()
    await user.click(screen.getByRole('menuitem', { name: /Excel: реестр/ }))
    expect(onExport).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
    expect(trigger).toHaveFocus()
  })

  it('keeps disabled items visible with their reason and never runs them', async () => {
    const user = userEvent.setup()
    const { onHidden } = renderMenu()
    await user.click(screen.getByRole('button', { name: 'Ещё' }))
    const item = screen.getByRole('menuitem', { name: /Учётные данные ЛК/ })
    expect(item).toBeDisabled()
    expect(item).toHaveAttribute('title', 'Выберите людей')
    fireEvent.click(item)
    expect(onHidden).not.toHaveBeenCalled()
  })

  it('closes on Escape, on a click outside and on arrow navigation keeps focus inside', async () => {
    const user = userEvent.setup()
    renderMenu()
    const trigger = screen.getByRole('button', { name: 'Ещё' })
    await user.click(trigger)
    await user.keyboard('{ArrowDown}')
    expect(screen.getByRole('menuitem', { name: /Excel: реестр/ })).toHaveFocus()
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
    expect(trigger).toHaveFocus()
    await user.click(trigger)
    await user.click(screen.getByRole('button', { name: 'Снаружи' }))
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
  })
})
