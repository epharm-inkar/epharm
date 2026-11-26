// «Сейчас за кассой» (Григорий, 26.11.2026): какие фармацевты прямо сейчас сидят за кассой. Касса раз в 15 минут и при
// смене кассира называет серверу пользователя Стандарт-Н; человек «за кассой», пока касса на связи и называла его
// за последние 20 минут. Список можно сузить по аптеке кассы и по тексту (часть ФИО, телефон цифрами, ИИН, аптека).
// Только HQ: страница показывает, где каждый человек в эту минуту.

import { useMemo, useState } from 'react'
import { Button, SearchInput, Select } from '@/ui'
import { IconRefresh } from '@/ui/icons'
import { useT } from '@/i18n'
import { describeError } from '@/lib/describeError'
import { useAtTill } from '@/lib/queries/atTill'
import { usePharmacies } from '@/lib/queries/pharmacies'
import { useDebouncedValue } from '@/lib/useDebouncedValue'
import { useNarrowScreen } from '@/lib/useNarrowScreen'
import type { AtTillItemDto, AtTillListDto } from '@/lib/api-types'
import { agoLabel, clockLabel, placeLabel, sinceLabel } from './atTillFormat'
import type { TFn } from './tillMessages'

// Не пустая строка: пустое значение у списка показывает «Выбрать», а не «Все аптеки».
const ALL_PHARMACIES = 'all'
const SEARCH_DELAY_MS = 300

export function AtTillPanel() {
  const t = useT()
  const narrow = useNarrowScreen()
  const [search, setSearch] = useState('')
  const [pharmacyId, setPharmacyId] = useState(ALL_PHARMACIES)
  const query = useDebouncedValue(search.trim(), SEARCH_DELAY_MS)
  const list = useAtTill({ pharmacyId: pharmacyId === ALL_PHARMACIES ? undefined : pharmacyId, q: query || undefined })
  const pharmacies = usePharmacies()
  const options = useMemo(
    () => [
      { value: ALL_PHARMACIES, label: t('att.allPharmacies') },
      ...(pharmacies.data ?? []).map((pharmacy) => ({
        value: pharmacy.id,
        label: [pharmacy.name, pharmacy.city, pharmacy.addr].filter(Boolean).join(', '),
      })),
    ],
    [pharmacies.data, t],
  )
  const data = list.data
  const filtered = pharmacyId !== ALL_PHARMACIES || Boolean(query)

  return (
    <div className="flex flex-col gap-3" data-testid="at-till-panel">
      <div className="card flex flex-col gap-3 p-3 sm:p-4">
        <div className="flex flex-wrap items-end justify-between gap-3">
          <div>
            <div className="text-[12px] font-bold uppercase tracking-[0.06em] text-ink-500">{t('att.heading')}</div>
            <div className="num mt-1 text-[28px] font-extrabold leading-none text-ink-900" data-testid="at-till-total">
              {data ? data.total : '…'}
            </div>
            <div className="mt-1 text-[12px] text-ink-500">{t('att.totalSub')}</div>
          </div>
          <div className="flex flex-wrap items-center gap-2 text-[12px] text-ink-500">
            {data && (
              <span className="num" data-testid="at-till-updated">
                {t('att.updated', { time: clockLabel(data.observedAt, data.observedAt) })}
              </span>
            )}
            <Button
              variant="outline"
              size="sm"
              leading={<IconRefresh size={14} />}
              disabled={list.isFetching}
              onClick={() => list.refetch()}
              data-testid="at-till-refresh"
            >
              {t('att.refresh')}
            </Button>
          </div>
        </div>

        <div className="grid gap-2 md:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
          <div data-testid="at-till-search">
            <SearchInput value={search} onChange={setSearch} placeholder={t('att.searchPlaceholder')} />
          </div>
          <Select ariaLabel={t('att.pharmacy')} value={pharmacyId} onChange={setPharmacyId} options={options} />
        </div>

        {filtered && data && (
          <div className="text-[12px] text-ink-500" data-testid="at-till-shown">
            {t('att.shown', { shown: data.shown, total: data.total })}
          </div>
        )}

        {list.isError && !data ? (
          <div
            className="flex items-center justify-between gap-3 rounded-lg border border-accent-danger/20 bg-surface-danger px-3 py-2 text-[13px] text-surface-danger-strong"
            data-testid="at-till-error"
          >
            <span>
              {t('att.loadFailed')} {list.error ? describeError(list.error) : ''}
            </span>
            <Button variant="outline" size="sm" onClick={() => list.refetch()}>
              {t('common.retry')}
            </Button>
          </div>
        ) : !data ? (
          <div className="py-6 text-center text-[13px] text-ink-500">{t('att.loading')}</div>
        ) : data.items.length === 0 ? (
          <div className="py-6 text-center text-[13px] text-ink-500" data-testid="at-till-empty">
            {filtered ? t('att.nothingFound') : t('att.nobody')}
          </div>
        ) : narrow ? (
          <ul className="flex flex-col gap-2" data-testid="at-till-cards">
            {data.items.map((item) => (
              <AtTillCard key={item.pharmacistId} item={item} data={data} t={t} />
            ))}
          </ul>
        ) : (
          <AtTillTable items={data.items} data={data} t={t} />
        )}

        <div className="text-[12px] text-ink-400">{t('att.rule', { n: data?.windowMinutes ?? 20 })}</div>
      </div>
    </div>
  )
}

function AtTillTable({ items, data, t }: { items: AtTillItemDto[]; data: AtTillListDto; t: TFn }) {
  return (
    <div role="region" aria-label={t('att.tableLabel')} className="scrollbar-thin max-w-full overflow-x-auto">
      {/* !table: общий стиль админки делает таблицы display:block, а тогда ширины колонок не действуют. */}
      <table className="!table w-full table-fixed text-[12px]" data-testid="at-till-table">
        <colgroup>
          <col className="w-[28%]" />
          <col />
          <col className="w-[120px]" />
          <col className="w-[190px]" />
          <col className="w-[130px]" />
        </colgroup>
        <thead className="hairline border-b">
          <tr className="text-left text-[11px] font-bold uppercase tracking-[0.06em] text-ink-500">
            <th className="px-2.5 py-2">{t('att.th.person')}</th>
            <th className="px-2.5 py-2">{t('att.th.pharmacy')}</th>
            <th className="px-2.5 py-2">{t('att.th.till')}</th>
            <th className="px-2.5 py-2">{t('att.th.since')}</th>
            <th className="px-2.5 py-2">{t('att.th.seen')}</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-ink-100">
          {items.map((item) => (
            <tr key={item.pharmacistId} data-testid={`at-till-row-${item.pharmacistId}`}>
              <td className="min-w-0 px-2.5 py-1.5 align-middle">
                <div className="truncate font-bold text-ink-900" title={item.name}>
                  {item.name}
                </div>
                <PersonNote item={item} t={t} />
              </td>
              <td className="min-w-0 px-2.5 py-1.5 align-middle">
                <div className="truncate font-semibold text-ink-800" title={placeLabel(item)}>
                  {placeLabel(item)}
                </div>
                <div className="truncate text-[11px] text-ink-500">{item.city}</div>
              </td>
              <td className="num truncate px-2.5 py-1.5 align-middle font-bold text-ink-800" title={item.deviceId}>
                {item.deviceId}
              </td>
              <td className="px-2.5 py-1.5 align-middle text-ink-700">{sinceLabel(t, item, data.observedAt)}</td>
              <td className="px-2.5 py-1.5 align-middle text-ink-700">{agoLabel(t, item.seenAt, data.observedAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function AtTillCard({ item, data, t }: { item: AtTillItemDto; data: AtTillListDto; t: TFn }) {
  return (
    <li className="rounded-lg border border-ink-100 p-3 text-[12px]" data-testid={`at-till-card-${item.pharmacistId}`}>
      <div className="min-w-0">
        <div className="font-bold text-ink-900">{item.name}</div>
        <PersonNote item={item} t={t} />
      </div>
      <div className="mt-1.5 text-ink-700">
        <div className="font-semibold text-ink-800">{placeLabel(item)}</div>
        <div className="text-[11px] text-ink-500">
          {[item.city, t('att.tillName', { name: item.deviceId })].filter(Boolean).join(' · ')}
        </div>
      </div>
      <div className="mt-1.5 flex flex-wrap gap-x-3 gap-y-0.5 text-ink-600">
        <span>{sinceLabel(t, item, data.observedAt)}</span>
        <span>{agoLabel(t, item.seenAt, data.observedAt)}</span>
      </div>
    </li>
  )
}

/** Телефон последними цифрами и своя аптека человека, если он сидит за кассой другой. */
function PersonNote({ item, t }: { item: AtTillItemDto; t: TFn }) {
  const away = item.homePharmacyId !== null && item.homePharmacyId !== item.pharmacyId
  const parts = [
    item.phoneTail ? t('msg.phoneTail', { tail: item.phoneTail }) : '',
    away && item.homePharmacyName ? t('att.fromPharmacy', { name: item.homePharmacyName }) : '',
  ].filter(Boolean)
  if (parts.length === 0) return null
  return <div className="truncate text-[11px] text-ink-500">{parts.join(' · ')}</div>
}
