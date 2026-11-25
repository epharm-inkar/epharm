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

  it('follows its trigger on scroll and closes only when the trigger leaves the screen', async () => {
    const user = userEvent.setup()
    renderMenu()
    const trigger = screen.getByRole('button', { name: 'Ещё' })
    await user.click(trigger)
    // The late scroll event of bringing the trigger into view must not close the menu just opened.
    fireEvent.scroll(window)
    expect(screen.getByRole('menu')).toBeInTheDocument()
    fireEvent(window, new Event('resize'))
    expect(screen.getByRole('menu')).toBeInTheDocument()
    vi.spyOn(trigger, 'getBoundingClientRect').mockReturnValue({
      top: 2000, bottom: 2030, left: 10, right: 60, width: 50, height: 30, x: 10, y: 2000, toJSON: () => ({}),
    } as DOMRect)
    fireEvent.scroll(window)
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
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
