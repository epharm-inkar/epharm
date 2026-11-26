import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useUiStore } from '@/app/store'
import { HelpTip } from './HelpTip'

afterEach(() => {
  cleanup()
  useUiStore.setState({ language: 'ru' })
})

const TEXT = 'Сколько тенге бонусов кампания может выплатить.'

function renderTip() {
  return render(<HelpTip topic="Бюджет">{TEXT}</HelpTip>)
}

describe('HelpTip', () => {
  it('is a button named after its topic and describes it to a screen reader without opening', () => {
    renderTip()
    const button = screen.getByRole('button', { name: 'Что это? Бюджет' })
    expect(button).toHaveAttribute('aria-expanded', 'false')
    expect(button).toHaveAccessibleDescription(TEXT)
    expect(screen.queryByTestId('help-tip-bubble')).not.toBeInTheDocument()
  })

  it('opens on a click or tap and closes on the next one', async () => {
    const user = userEvent.setup()
    renderTip()
    const button = screen.getByRole('button', { name: /Что это\?/ })
    await user.click(button)
    expect(button).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByTestId('help-tip-bubble')).toHaveTextContent(TEXT)
    // a click sends the mouse over the button first: leave it, so only the pinned state is left
    await user.unhover(button)
    expect(screen.getByTestId('help-tip-bubble')).toBeInTheDocument()
    await user.click(button)
    await user.unhover(button)
    expect(screen.queryByTestId('help-tip-bubble')).not.toBeInTheDocument()
  })

  it('works from the keyboard: Enter opens it, Escape closes it', async () => {
    const user = userEvent.setup()
    renderTip()
    await user.tab()
    const button = screen.getByRole('button', { name: /Что это\?/ })
    expect(button).toHaveFocus()
    await user.keyboard('{Enter}')
    expect(screen.getByTestId('help-tip-bubble')).toBeInTheDocument()
    await user.keyboard('{Escape}')
    expect(screen.queryByTestId('help-tip-bubble')).not.toBeInTheDocument()
    await user.keyboard(' ')
    expect(screen.getByTestId('help-tip-bubble')).toBeInTheDocument()
  })

  it('closes on a press outside and when the focus leaves the button', async () => {
    const user = userEvent.setup()
    render(
      <div>
        <HelpTip topic="Бюджет">{TEXT}</HelpTip>
        <p>Другой текст страницы</p>
        <button type="button">Следующая кнопка</button>
      </div>,
    )
    const button = screen.getByRole('button', { name: /Что это\?/ })
    await user.click(button)
    await user.unhover(button)
    expect(screen.getByTestId('help-tip-bubble')).toBeInTheDocument()
    await user.click(screen.getByText('Другой текст страницы'))
    expect(screen.queryByTestId('help-tip-bubble')).not.toBeInTheDocument()

    await user.click(button)
    await user.unhover(button)
    expect(screen.getByTestId('help-tip-bubble')).toBeInTheDocument()
    await user.tab()
    expect(screen.getByRole('button', { name: 'Следующая кнопка' })).toHaveFocus()
    expect(screen.queryByTestId('help-tip-bubble')).not.toBeInTheDocument()
  })

  it('shows on mouse hover and hides when the mouse leaves; a touch brush does not open it', async () => {
    const user = userEvent.setup()
    renderTip()
    const button = screen.getByRole('button', { name: /Что это\?/ })
    await user.hover(button)
    expect(screen.getByTestId('help-tip-bubble')).toBeInTheDocument()
    await user.unhover(button)
    expect(screen.queryByTestId('help-tip-bubble')).not.toBeInTheDocument()
    // a touch fires pointerenter too, but only the tap (the click) may open it
    fireEvent.pointerEnter(button, { pointerType: 'touch' })
    expect(screen.queryByTestId('help-tip-bubble')).not.toBeInTheDocument()
  })

  it('keeps the bubble inside a 375 px screen even for a "?" at the right edge', async () => {
    const user = userEvent.setup()
    Object.defineProperty(document.documentElement, 'clientWidth', {
      value: 375,
      configurable: true,
    })
    renderTip()
    const button = screen.getByRole('button', { name: /Что это\?/ })
    button.getBoundingClientRect = () =>
      ({ left: 349, right: 367, top: 100, bottom: 118, width: 18, height: 18 }) as DOMRect
    await user.click(button)
    const bubble = screen.getByTestId('help-tip-bubble')
    const left = parseFloat(bubble.style.left)
    const width = parseFloat(bubble.style.width)
    expect(left).toBeGreaterThanOrEqual(12)
    expect(left + width).toBeLessThanOrEqual(375 - 12)
    expect(width).toBeLessThanOrEqual(351)
    Object.defineProperty(document.documentElement, 'clientWidth', {
      value: 0,
      configurable: true,
    })
  })

  it('names the button in Kazakh when the interface is Kazakh', () => {
    useUiStore.setState({ language: 'kk' })
    renderTip()
    expect(screen.getByRole('button', { name: 'Бұл не? Бюджет' })).toBeInTheDocument()
  })
})
