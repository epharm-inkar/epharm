import { afterEach, describe, expect, it } from 'vitest'
import { useState } from 'react'
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Disclosure } from './Disclosure'

afterEach(cleanup)

function Harness({ initial = false }: { initial?: boolean }) {
  const [open, setOpen] = useState(initial)
  return (
    <Disclosure
      title="Дополнительно"
      summary="штрихкод не задан · фото из каталога"
      open={open}
      onOpenChange={setOpen}
      testId="more"
    >
      <label>
        Штрихкод
        <input defaultValue="4601164003157" />
      </label>
    </Disclosure>
  )
}

describe('Disclosure', () => {
  it('is folded by default and says in one line what is inside', () => {
    render(<Harness />)
    const block = screen.getByTestId('more') as HTMLDetailsElement
    expect(block.open).toBe(false)
    expect(screen.getByTestId('more-summary')).toHaveTextContent(
      'штрихкод не задан · фото из каталога',
    )
  })

  it('opens and closes from the title, and the summary line shows only while it is folded', async () => {
    const user = userEvent.setup()
    render(<Harness />)
    const block = screen.getByTestId('more') as HTMLDetailsElement
    await user.click(screen.getByText('Дополнительно'))
    expect(block.open).toBe(true)
    expect(screen.queryByTestId('more-summary')).not.toBeInTheDocument()
    await user.click(screen.getByText('Дополнительно'))
    expect(block.open).toBe(false)
    expect(screen.getByTestId('more-summary')).toBeInTheDocument()
  })

  it('keeps what was typed in a folded field', async () => {
    const user = userEvent.setup()
    render(<Harness initial />)
    const input = screen.getByLabelText('Штрихкод')
    await user.clear(input)
    await user.type(input, '1234')
    await user.click(screen.getByText('Дополнительно'))
    await user.click(screen.getByText('Дополнительно'))
    expect(screen.getByLabelText('Штрихкод')).toHaveValue('1234')
  })
})
