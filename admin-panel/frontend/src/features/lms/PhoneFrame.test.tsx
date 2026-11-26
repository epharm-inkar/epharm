import { afterEach, describe, expect, it } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { useState } from 'react'
import { PhoneFrame } from './PhoneFrame'

afterEach(() => {
  document.head.innerHTML = ''
})

/** The frame's own document, once it has loaded and the children are drawn into it. */
async function frameBody(title = 'Телефон') {
  const frame = (await screen.findByTitle(title)) as HTMLIFrameElement
  await waitFor(() =>
    expect(frame.contentDocument?.getElementById('phone-root')?.childElementCount).toBeGreaterThan(
      0,
    ),
  )
  return { frame, body: frame.contentDocument!.body }
}

function Counter() {
  const [count, setCount] = useState(0)
  return (
    <>
      <output data-testid="outside">{count}</output>
      <PhoneFrame title="Телефон">
        <button onClick={() => setCount((value) => value + 1)}>Плюс</button>
        <span>В рамке</span>
      </PhoneFrame>
    </>
  )
}

describe('PhoneFrame', () => {
  it('draws its children inside a frame of its own, not in the admin page', async () => {
    render(<Counter />)
    const { body } = await frameBody()
    expect(within(body).getByText('В рамке')).toBeInTheDocument()
    expect(within(body).getByRole('button', { name: 'Плюс' })).toBeInTheDocument()
    // Not in the admin page's own document.
    expect(screen.queryByText('В рамке')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Плюс' })).not.toBeInTheDocument()
  })

  it("keeps the admin page's React state: a click in the frame reaches the page", async () => {
    render(<Counter />)
    const { body } = await frameBody()
    expect(screen.getByTestId('outside')).toHaveTextContent('0')
    fireEvent.click(within(body).getByRole('button', { name: 'Плюс' }))
    fireEvent.click(within(body).getByRole('button', { name: 'Плюс' }))
    expect(screen.getByTestId('outside')).toHaveTextContent('2')
  })

  it('gives the frame the body class of the learner cabinet', async () => {
    render(<Counter />)
    const { body } = await frameBody()
    expect(body).toHaveClass('learner-portal')
    // And does not put it on the admin page itself, which would repaint the admin in the cabinet's teal.
    expect(document.body).not.toHaveClass('learner-portal')
  })

  it("starts the frame with the admin page's stylesheets", async () => {
    const link = document.createElement('link')
    link.rel = 'stylesheet'
    link.href = '/assets/index-abc123.css'
    document.head.append(link)
    render(<Counter />)
    const { frame } = await frameBody()
    expect(frame.getAttribute('srcdoc')).toContain('/assets/index-abc123.css')
  })

  it('names the frame and sandboxes it: our own screens, no scripts of its own', async () => {
    render(<Counter />)
    const { frame } = await frameBody()
    expect(frame).toHaveAttribute('title', 'Телефон')
    const sandbox = frame.getAttribute('sandbox') ?? ''
    expect(sandbox.split(/\s+/)).toContain('allow-same-origin')
    expect(sandbox.split(/\s+/)).not.toContain('allow-scripts')
    expect(frame).toHaveAttribute('allowfullscreen')
  })

  it('is as narrow as a phone and not wider than the room it has', () => {
    render(<Counter />)
    const outer = screen.getByTestId('phone-frame')
    expect(outer.className).toMatch(/\bmax-w-\[410px\]/)
    expect(outer.className).toMatch(/\bw-full\b/)
  })

  it('removes the frame content when it unmounts', async () => {
    const { unmount } = render(<Counter />)
    const { body } = await frameBody()
    unmount()
    expect(within(body).queryByText('В рамке')).not.toBeInTheDocument()
  })
})
