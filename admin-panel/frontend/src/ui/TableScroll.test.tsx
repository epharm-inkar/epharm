import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import { TableScroll } from './TableScroll'

afterEach(cleanup)

describe('TableScroll', () => {
  it('is a plain scroll box around the table that never widens the page', () => {
    render(
      <TableScroll>
        <table data-testid="t">
          <tbody>
            <tr>
              <td>1</td>
            </tr>
          </tbody>
        </table>
      </TableScroll>,
    )
    const box = screen.getByTestId('t').parentElement!
    expect(box.className).toContain('overflow-x-auto')
    expect(box.className).toContain('max-w-full')
    expect(box).not.toHaveAttribute('role')
  })

  it('with a label it is a named, focusable region so the keyboard can scroll a wide table', () => {
    render(
      <TableScroll label="Таблица аптек">
        <table>
          <tbody>
            <tr>
              <td>1</td>
            </tr>
          </tbody>
        </table>
      </TableScroll>,
    )
    const region = screen.getByRole('region', { name: 'Таблица аптек' })
    expect(region).toHaveAttribute('tabindex', '0')
  })
})

describe('shared table style', () => {
  // The admin turned every table into display: block with its own scroll. The rows then sat in an
  // anonymous table that shrank to its content, so each table ended at 55-85% of its card.
  const css = readFileSync(resolve(__dirname, '../index.css'), 'utf8')

  it('keeps tables as real tables that fill their box', () => {
    const rule = css.match(/\.admin-console main table\s*\{([^}]*)\}/)
    expect(rule, 'the shared rule for tables is missing').not.toBeNull()
    const body = rule![1]
    expect(body).toMatch(/width:\s*100%/)
    expect(body).not.toMatch(/display:\s*block/)
    expect(body).not.toMatch(/overflow/)
  })
})
