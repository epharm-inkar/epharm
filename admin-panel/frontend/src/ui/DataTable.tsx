// DataTable — плотная таблица админки (шаблон 25.11.2026, первая страница «Фармацевты»).
// Строка 30 px без аватаров, шапка прилипает при прокрутке, сортировка по клику на любую колонку
// с данными (по возрастанию / убыванию, стрелка у заголовка), длинный текст обрезается многоточием
// с полным текстом во всплывающей подсказке, числа выровнены вправо. Таблица прокручивается в своём
// контейнере: на телефоне вбок и вниз, страница целиком вбок не уезжает.

import { useMemo, useState, type CSSProperties, type ReactNode } from 'react'
import { IconArrowDown, IconArrowUp, IconSort } from './icons'
import { sortRows, type SortDirection, type SortValue } from './sortRows'

export type { SortDirection, SortValue }

export interface SortState {
  columnId: string
  direction: SortDirection
}

export interface DataTableColumn<T> {
  id: string
  header: ReactNode
  /** Full header text for the tooltip when the visible header is short. */
  headerTitle?: string
  cell: (row: T) => ReactNode
  /** Value to sort by; a column without it (selection, row actions) is not sortable. */
  sortValue?: (row: T) => SortValue
  /** Direction of the first click on this column; numbers and dates usually start with the largest. */
  firstDirection?: SortDirection
  /** Tooltip of the cell; by default the text of a string or number cell. */
  tooltip?: (row: T) => string | undefined
  align?: 'left' | 'right' | 'center'
  /** Fixed width in px. Columns without it share the rest of the table width. */
  width?: number
  /** Least width of a column without a fixed width; counts toward the table's own minimum width. */
  minWidth?: number
  /** Stays in view while the table scrolls sideways; only leading columns, all with a fixed width but the last. */
  sticky?: boolean
  /** Cut long text with an ellipsis (default). Off for checkboxes and menus. */
  truncate?: boolean
  className?: string
}

export interface DataTableProps<T> {
  rows: readonly T[]
  columns: readonly DataTableColumn<T>[]
  rowKey: (row: T) => string
  /** Test id and selection mark of a row (selected rows get the coral inset). */
  rowMeta?: (row: T) => { testId?: string; selected?: boolean; muted?: boolean }
  defaultSort?: SortState | null
  onSortChange?: (sort: SortState) => void
  /** Shown in the body when there are no rows, under the header. */
  empty?: ReactNode
  label: string
  testId?: string
  scrollTestId?: string
  /** Classes of the scroll container, e.g. a height that fills the page. */
  className?: string
}

const ALIGN = { left: 'text-left', right: 'text-right', center: 'text-center' } as const

function textOf(node: ReactNode): string | undefined {
  if (typeof node === 'string') return node.trim() || undefined
  if (typeof node === 'number') return String(node)
  return undefined
}

export function DataTable<T>({
  rows,
  columns,
  rowKey,
  rowMeta,
  defaultSort = null,
  onSortChange,
  empty,
  label,
  testId,
  scrollTestId,
  className = '',
}: DataTableProps<T>) {
  const [sort, setSort] = useState<SortState | null>(defaultSort)
  const sortColumn = sort ? columns.find((column) => column.id === sort.columnId) : undefined
  const sorted = useMemo(
    () =>
      sortColumn?.sortValue && sort
        ? sortRows(rows, sortColumn.sortValue, sort.direction)
        : [...rows],
    [rows, sort, sortColumn],
  )

  // Leading sticky columns: each one starts where the previous fixed-width sticky column ends.
  const stickyLeft = new Map<string, number>()
  let offset = 0
  for (const column of columns) {
    if (!column.sticky) break
    stickyLeft.set(column.id, offset)
    offset += column.width ?? column.minWidth ?? 0
  }
  const minWidth = columns.reduce((sum, column) => sum + (column.width ?? column.minWidth ?? 120), 0)

  const toggle = (column: DataTableColumn<T>) => {
    const next: SortState =
      sort?.columnId === column.id
        ? { columnId: column.id, direction: sort.direction === 'asc' ? 'desc' : 'asc' }
        : { columnId: column.id, direction: column.firstDirection ?? 'asc' }
    setSort(next)
    onSortChange?.(next)
  }

  const stickyStyle = (column: DataTableColumn<T>): CSSProperties | undefined =>
    stickyLeft.has(column.id) ? { left: stickyLeft.get(column.id) } : undefined

  return (
    <div className={`dt-scroll scrollbar-thin overflow-auto ${className}`} data-testid={scrollTestId}>
      <table className="dt-table" style={{ minWidth }} aria-label={label} data-testid={testId}>
        <colgroup>
          {columns.map((column) => (
            <col key={column.id} style={column.width ? { width: column.width } : undefined} />
          ))}
        </colgroup>
        <thead>
          <tr>
            {columns.map((column) => {
              const active = sort?.columnId === column.id
              const sortable = !!column.sortValue
              return (
                <th
                  key={column.id}
                  scope="col"
                  title={column.headerTitle}
                  aria-sort={
                    active ? (sort.direction === 'asc' ? 'ascending' : 'descending') : undefined
                  }
                  style={stickyStyle(column)}
                  className={`${ALIGN[column.align ?? 'left']} ${stickyLeft.has(column.id) ? 'dt-sticky' : ''}`}
                >
                  {sortable ? (
                    <button
                      type="button"
                      onClick={() => toggle(column)}
                      className={`dt-sort group ${column.align === 'right' ? 'flex-row-reverse' : ''} ${
                        active ? 'text-ink-900' : ''
                      }`}
                    >
                      <span className="truncate">{column.header}</span>
                      {/* The idle hint takes no room, so short headers are not cut by an invisible icon. */}
                      <span
                        aria-hidden="true"
                        className={active ? 'flex flex-none' : 'hidden flex-none opacity-60 group-hover:flex'}
                      >
                        {active ? (
                          sort.direction === 'asc' ? (
                            <IconArrowUp size={12} />
                          ) : (
                            <IconArrowDown size={12} />
                          )
                        ) : (
                          <IconSort size={12} />
                        )}
                      </span>
                    </button>
                  ) : (
                    column.header
                  )}
                </th>
              )
            })}
          </tr>
        </thead>
        <tbody>
          {sorted.length === 0 ? (
            <tr>
              <td colSpan={columns.length} className="dt-empty">
                {empty}
              </td>
            </tr>
          ) : (
            sorted.map((row) => {
              const meta = rowMeta?.(row)
              return (
                <tr
                  key={rowKey(row)}
                  data-testid={meta?.testId}
                  data-selected={meta?.selected ? 'true' : undefined}
                  className={meta?.muted ? 'dt-muted' : undefined}
                >
                  {columns.map((column) => {
                    const content = column.cell(row)
                    const truncate = column.truncate !== false
                    return (
                      <td
                        key={column.id}
                        data-col={column.id}
                        title={column.tooltip ? column.tooltip(row) : truncate ? textOf(content) : undefined}
                        style={stickyStyle(column)}
                        className={`${ALIGN[column.align ?? 'left']} ${
                          column.align === 'right' ? 'num' : ''
                        } ${stickyLeft.has(column.id) ? 'dt-sticky' : ''} ${column.className ?? ''}`}
                      >
                        {truncate ? <div className="truncate">{content}</div> : content}
                      </td>
                    )
                  })}
                </tr>
              )
            })
          )}
        </tbody>
      </table>
    </div>
  )
}
