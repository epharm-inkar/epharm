import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { DataTable, type DataTableColumn } from './DataTable'
import { sortRows } from './sortRows'

interface Row {
  id: string
  name: string
  receipts: number | null
  city: string
}

const rows: Row[] = [
  { id: 'b', name: 'Бекова Жанар', receipts: 12, city: 'Алматы' },
  { id: 'a', name: 'Ахметова Зарина Сериковна из очень длинной строки', receipts: null, city: 'Астана' },
  { id: 'c', name: 'Аптека №10', receipts: 1_742, city: '' },
  { id: 'd', name: 'Аптека №9', receipts: 0, city: 'Шымкент' },
]

const columns: DataTableColumn<Row>[] = [
  { id: 'name', header: 'ФИО', sortValue: (row) => row.name, cell: (row) => row.name, sticky: true, width: 160 },
  {
    id: 'receipts',
    header: 'Чеки',
    align: 'right',
    firstDirection: 'desc',
    sortValue: (row) => row.receipts,
    cell: (row) => (row.receipts == null ? '—' : String(row.receipts)),
  },
  {
    id: 'city',
    header: 'Город',
    sortValue: (row) => row.city,
    tooltip: (row) => (row.city ? `Город: ${row.city}` : undefined),
    cell: (row) => row.city || '—',
  },
  { id: 'actions', header: <span className="sr-only">Действия</span>, truncate: false, cell: () => <button>⋯</button> },
]

function names() {
  return screen.getAllByTestId(/^row-/).map((row) => row.getAttribute('data-testid'))
}

function renderTable(extra: Partial<Parameters<typeof DataTable<Row>>[0]> = {}) {
  return render(
    <DataTable
      rows={rows}
      columns={columns}
      rowKey={(row) => row.id}
      rowMeta={(row) => ({ testId: `row-${row.id}`, selected: row.id === 'b', muted: row.id === 'd' })}
      label="Фармацевты"
      testId="table"
      {...extra}
    />,
  )
}

describe('sortRows', () => {
  it('keeps empty values at the bottom in both directions and compares numbers inside text', () => {
    const value = (row: Row) => row.receipts
    expect(sortRows(rows, value, 'asc').map((row) => row.id)).toEqual(['d', 'b', 'c', 'a'])
    expect(sortRows(rows, value, 'desc').map((row) => row.id)).toEqual(['c', 'b', 'd', 'a'])
    expect(sortRows(rows, (row) => row.name, 'asc').map((row) => row.id)).toEqual(['d', 'c', 'a', 'b'])
    expect(sortRows(rows, (row) => row.city, 'desc').map((row) => row.id)).toEqual(['d', 'a', 'b', 'c'])
  })

  it('is stable for equal keys and sorts booleans', () => {
    const flags = [
      { id: 1, on: true },
      { id: 2, on: false },
      { id: 3, on: true },
    ]
    expect(sortRows(flags, (row) => row.on, 'desc').map((row) => row.id)).toEqual([1, 3, 2])
    expect(sortRows(flags, (row) => row.on, 'asc').map((row) => row.id)).toEqual([2, 1, 3])
  })
})

describe('DataTable', () => {
  it('sorts by any data column on click, toggles direction and shows the state on the header', async () => {
    const user = userEvent.setup()
    const onSortChange = vi.fn()
    renderTable({ defaultSort: { columnId: 'name', direction: 'asc' }, onSortChange })
    const table = screen.getByTestId('table')
    const nameHeader = within(table).getByRole('columnheader', { name: 'ФИО' })
    expect(nameHeader).toHaveAttribute('aria-sort', 'ascending')
    expect(nameHeader).toHaveAttribute('scope', 'col')
    expect(names()).toEqual(['row-d', 'row-c', 'row-a', 'row-b'])

    await user.click(within(table).getByRole('button', { name: 'Чеки' }))
    const receiptsHeader = within(table).getByRole('columnheader', { name: 'Чеки' })
    expect(receiptsHeader).toHaveAttribute('aria-sort', 'descending')
    expect(nameHeader).not.toHaveAttribute('aria-sort')
    expect(names()).toEqual(['row-c', 'row-b', 'row-d', 'row-a'])
    expect(onSortChange).toHaveBeenLastCalledWith({ columnId: 'receipts', direction: 'desc' })

    await user.click(within(table).getByRole('button', { name: 'Чеки' }))
    expect(receiptsHeader).toHaveAttribute('aria-sort', 'ascending')
    expect(names()).toEqual(['row-d', 'row-b', 'row-c', 'row-a'])
  })

  it('does not make selection or action columns sortable', () => {
    renderTable()
    const table = screen.getByTestId('table')
    expect(within(table).getAllByRole('columnheader')).toHaveLength(4)
    expect(within(table).queryByRole('button', { name: 'Действия' })).not.toBeInTheDocument()
    expect(within(table).getAllByRole('button', { name: /ФИО|Чеки|Город/ })).toHaveLength(3)
  })

  it('truncates text with the full value in the tooltip and keeps custom tooltips', () => {
    renderTable()
    const longRow = screen.getByTestId('row-a')
    const nameCell = within(longRow).getByRole('cell', { name: rows[1].name })
    expect(nameCell).toHaveAttribute('title', rows[1].name)
    expect(nameCell.firstElementChild).toHaveClass('truncate')
    const cityCell = longRow.querySelector('[data-col="city"]')!
    expect(cityCell).toHaveAttribute('title', 'Город: Астана')
    expect(screen.getByTestId('row-c').querySelector('[data-col="city"]')).not.toHaveAttribute('title')
    const actions = longRow.querySelector('[data-col="actions"]')!
    expect(actions.querySelector('.truncate')).toBeNull()
    expect(actions).not.toHaveAttribute('title')
  })

  it('right-aligns numbers with tabular figures and marks selected, muted and sticky cells', () => {
    renderTable()
    const receipts = screen.getByTestId('row-c').querySelector('[data-col="receipts"]')!
    expect(receipts).toHaveClass('text-right', 'num')
    expect(screen.getByTestId('row-b')).toHaveAttribute('data-selected', 'true')
    expect(screen.getByTestId('row-c')).not.toHaveAttribute('data-selected')
    expect(screen.getByTestId('row-d')).toHaveClass('dt-muted')
    const sticky = screen.getByTestId('row-b').querySelector('[data-col="name"]') as HTMLElement
    expect(sticky).toHaveClass('dt-sticky')
    expect(['0', '0px']).toContain(sticky.style.left)
    expect(screen.getByTestId('table').style.minWidth).toBe(`${160 + 120 * 3}px`)
  })

  it('shows the empty state under the header instead of rows', () => {
    renderTable({ rows: [], empty: <p>Фармацевтов пока нет</p> })
    const table = screen.getByTestId('table')
    expect(within(table).getAllByRole('columnheader')).toHaveLength(4)
    expect(within(table).getByText('Фармацевтов пока нет')).toBeInTheDocument()
    expect(within(table).getByRole('cell')).toHaveAttribute('colspan', '4')
  })
})
