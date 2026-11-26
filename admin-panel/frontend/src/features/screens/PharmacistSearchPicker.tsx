// Выбор людей для уведомления (26.11.2026): поиск на сервере по фамилии, имени, отчеству (любая часть), телефону
// (цифрами), ИИН и аптеке; можно выбрать нескольких или одного. Выбранные остаются на виду, даже когда поиск сменился.
// Рядом с каждым человеком видно, за кассой ли он сейчас.

import { useState } from 'react'
import { Button, SearchInput } from '@/ui'
import { useT } from '@/i18n'
import type { PosmPersonDto } from '@/lib/api-types'
import { usePosmPharmacistSearch } from '@/lib/queries/posmMessages'
import { useDebouncedValue } from '@/lib/useDebouncedValue'
import { TillSeenBadge } from '@/features/pharmacists/TillSeenBadge'
import type { TFn } from './tillMessages'

const SEARCH_DELAY_MS = 300

/** «Аптека со склада, Абая 103 · Алматы · тел. …4567». */
function personLine(t: TFn, person: PosmPersonDto): string {
  const place = [person.pharmacyName, person.address].filter(Boolean).join(', ')
  return [place, person.city, person.phoneTail ? t('msg.phoneTail', { tail: person.phoneTail }) : ''].filter(Boolean).join(' · ')
}

export function PharmacistSearchPicker({
  selected,
  onChange,
  testId,
}: {
  selected: PosmPersonDto[]
  onChange: (people: PosmPersonDto[]) => void
  testId: string
}) {
  const t = useT()
  const [search, setSearch] = useState('')
  const query = useDebouncedValue(search.trim(), SEARCH_DELAY_MS)
  const found = usePosmPharmacistSearch(query)
  const chosen = new Set(selected.map((person) => person.id))
  const items = found.data?.items ?? []
  const total = found.data?.total ?? 0

  const toggle = (person: PosmPersonDto) => {
    if (chosen.has(person.id)) onChange(selected.filter((value) => value.id !== person.id))
    else onChange([...selected, person])
  }

  return (
    <div className="flex flex-col gap-2" data-testid={testId}>
      <SearchInput value={search} onChange={setSearch} placeholder={t('msg.searchPeople')} />
      <div className="flex flex-wrap items-center justify-between gap-2 text-[12px] text-ink-500">
        <span data-testid={`${testId}-found`}>
          {found.isError
            ? t('msg.loadError')
            : found.isLoading
              ? t('msg.loading')
              : total > items.length
                ? t('msg.foundMore', { shown: items.length, n: total })
                : t('msg.found', { n: total })}
        </span>
        {selected.length > 0 && (
          <span className="flex items-center gap-2">
            <span>{t('msg.selected', { n: selected.length })}</span>
            <Button variant="ghost" size="sm" onClick={() => onChange([])}>
              {t('msg.clearSelection')}
            </Button>
          </span>
        )}
      </div>

      {selected.length > 0 && (
        <ul className="flex flex-wrap gap-1.5" data-testid={`${testId}-chosen`}>
          {selected.map((person) => (
            <li key={person.id}>
              <button
                type="button"
                onClick={() => toggle(person)}
                title={t('msg.removePerson')}
                aria-label={`${t('msg.removePerson')}: ${person.name}`}
                className="inline-flex max-w-full items-center gap-1.5 rounded-full border border-brand-green-600 bg-brand-green-50 px-2.5 py-1 text-[12px] font-semibold text-brand-green-700"
              >
                <span className="truncate">{person.name}</span>
                <span aria-hidden="true">×</span>
              </button>
            </li>
          ))}
        </ul>
      )}

      <div className="hairline max-h-[300px] divide-y divide-ink-100 overflow-auto rounded-lg border">
        {items.map((person) => (
          <label key={person.id} className="flex cursor-pointer items-start gap-3 bg-white px-3 py-2.5 hover:bg-paper-hover">
            <input
              type="checkbox"
              checked={chosen.has(person.id)}
              onChange={() => toggle(person)}
              className="mt-0.5 h-4 w-4 shrink-0 accent-brand-green-700"
              data-testid={`${testId}-${person.id}`}
            />
            <span className="min-w-0 flex-1">
              <span className="block truncate text-[13px] font-bold text-ink-800">{person.name}</span>
              <span className="block truncate text-[12px] text-ink-500">{personLine(t, person)}</span>
              <span className="mt-0.5 block">
                <TillSeenBadge atTillNow={person.atTillNow} lastSeenAt={person.lastTillSeenAt} />
              </span>
            </span>
          </label>
        ))}
        {!found.isLoading && !found.isError && items.length === 0 && (
          <div className="px-3 py-6 text-center text-[12px] text-ink-400">{t('msg.nothingFound')}</div>
        )}
        {found.isLoading && <div className="px-3 py-6 text-center text-[12px] text-ink-400">{t('msg.loading')}</div>}
      </div>
    </div>
  )
}
