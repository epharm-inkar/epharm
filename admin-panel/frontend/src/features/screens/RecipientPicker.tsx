// Выбор адресатов уведомления: поиск и список с галочками (аптеки или сотрудники).
// Как выбор аптек для эфира, только компактнее: выбранные можно показать отдельно.

import { useMemo, useState } from 'react'
import { Button, SearchInput, Tabs } from '@/ui'
import { useT } from '@/i18n'

export interface PickerItem {
  id: string
  title: string
  subtitle: string
}

type PickerView = 'all' | 'selected'

export function RecipientPicker({
  items,
  selected,
  onChange,
  loading,
  failed,
  searchPlaceholder,
  testId,
}: {
  items: PickerItem[]
  selected: string[]
  onChange: (ids: string[]) => void
  loading: boolean
  failed: boolean
  searchPlaceholder: string
  testId: string
}) {
  const t = useT()
  const [search, setSearch] = useState('')
  const [view, setView] = useState<PickerView>('all')
  const chosen = useMemo(() => new Set(selected), [selected])

  const filtered = useMemo(() => {
    const needle = search.trim().toLocaleLowerCase('ru-RU')
    const rows = items.filter((item) => view === 'all' || chosen.has(item.id))
    if (!needle) return rows
    return rows.filter((item) =>
      `${item.title} ${item.subtitle} ${item.id}`.toLocaleLowerCase('ru-RU').includes(needle),
    )
  }, [items, search, view, chosen])

  const toggle = (id: string) => {
    if (chosen.has(id)) onChange(selected.filter((value) => value !== id))
    else onChange([...selected, id])
  }

  return (
    <div className="flex flex-col gap-2" data-testid={testId}>
      <Tabs<PickerView>
        items={[
          { value: 'all', label: t('msg.pickerAll'), count: items.length },
          { value: 'selected', label: t('msg.pickerSelected'), count: selected.length },
        ]}
        value={view}
        onChange={setView}
      />
      <SearchInput value={search} onChange={setSearch} placeholder={searchPlaceholder} />
      <div className="flex items-center justify-between text-[12px] text-ink-500">
        <span>{t('msg.selected', { n: selected.length })}</span>
        {selected.length > 0 && (
          <Button variant="ghost" size="sm" onClick={() => onChange([])}>
            {t('msg.clearSelection')}
          </Button>
        )}
      </div>
      {failed ? (
        <div className="rounded-lg border border-accent-danger/20 bg-surface-danger px-3 py-2 text-[12px] font-semibold text-surface-danger-strong">
          {t('msg.loadError')}
        </div>
      ) : (
        <div className="hairline max-h-[300px] divide-y divide-ink-100 overflow-auto rounded-lg border">
          {filtered.map((item) => (
            <label
              key={item.id}
              className="flex cursor-pointer items-start gap-3 bg-white px-3 py-2.5 hover:bg-paper-hover"
            >
              <input
                type="checkbox"
                checked={chosen.has(item.id)}
                onChange={() => toggle(item.id)}
                className="mt-0.5 h-4 w-4 accent-brand-green-700"
                data-testid={`${testId}-${item.id}`}
              />
              <span className="min-w-0 flex-1">
                <span className="block truncate text-[13px] font-bold text-ink-800">{item.title}</span>
                {item.subtitle && (
                  <span className="block truncate text-[12px] text-ink-500">{item.subtitle}</span>
                )}
              </span>
            </label>
          ))}
          {!loading && filtered.length === 0 && (
            <div className="px-3 py-6 text-center text-[12px] text-ink-400">{t('msg.nothingFound')}</div>
          )}
          {loading && <div className="px-3 py-6 text-center text-[12px] text-ink-400">{t('msg.loading')}</div>}
        </div>
      )}
    </div>
  )
}
