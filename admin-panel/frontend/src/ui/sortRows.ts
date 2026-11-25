// Row sorting of DataTable: Russian collation with numbers inside text («№9» before «№10»),
// stable, empty values always at the bottom (like a spreadsheet) whatever the direction.

export type SortDirection = 'asc' | 'desc'
export type SortValue = string | number | boolean | null | undefined

const collator = new Intl.Collator('ru', { numeric: true, sensitivity: 'base' })

function isEmpty(value: SortValue): boolean {
  return value === null || value === undefined || value === '' || Number.isNaN(value)
}

function compareValues(a: NonNullable<SortValue>, b: NonNullable<SortValue>): number {
  if (typeof a === 'number' && typeof b === 'number') return a - b
  if (typeof a === 'boolean' && typeof b === 'boolean') return Number(a) - Number(b)
  return collator.compare(String(a), String(b))
}

export function sortRows<T>(
  rows: readonly T[],
  value: (row: T) => SortValue,
  direction: SortDirection,
): T[] {
  const factor = direction === 'asc' ? 1 : -1
  return rows
    .map((row, index) => ({ row, index, key: value(row) }))
    .sort((a, b) => {
      const aEmpty = isEmpty(a.key)
      const bEmpty = isEmpty(b.key)
      if (aEmpty !== bEmpty) return aEmpty ? 1 : -1
      if (aEmpty) return a.index - b.index
      const order = compareValues(a.key as NonNullable<SortValue>, b.key as NonNullable<SortValue>)
      return order === 0 ? a.index - b.index : order * factor
    })
    .map((entry) => entry.row)
}
