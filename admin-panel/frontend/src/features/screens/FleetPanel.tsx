// «Все кассы»: все кассы сети одним снимком (GET /api/admin/screens/fleet).
// Сверху плитки-фильтры, ниже плотная таблица на компьютере и карточки на телефоне,
// в конце эфиры, которые сейчас назначены на экраны касс. HQ видит ещё «Управление»:
// диагностика и перезапуск кассы без AnyDesk (GET/POST /api/admin/tills/commands).

import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { Button, SearchInput } from '@/ui'
import { IconRefresh } from '@/ui/icons'
import { useScreenFleet } from '@/lib/queries/screens'
import { useTillCommands } from '@/lib/queries/tillCommands'
import { useUiStore } from '@/app/store'
import { canManageTills } from '@/app/accessPolicy'
import { describeError } from '@/lib/describeError'
import type {
  PosmCoverageGapDto,
  ScreenFleetDto,
  ScreenFleetPlaylistDto,
  ScreenFleetRegisterDto,
  TillCommandDto,
} from '@/lib/api-types'
import { useT } from '@/i18n'
import { staffSourceStatusLabel } from './staffSourceStatus'
import {
  almatyDateTime,
  matchesFilter,
  matchesSearch,
  relativeAgo,
  sortRows,
  standardNShort,
  type FleetFilter,
  type FleetSort,
  type TFn,
  type Tone,
} from './fleetFormat'
import { TillCommandCell } from './TillCommandCell'
import { latestCommandByTill, tillKey } from './tillCommands'

const PAGE_SIZE = 200
// Фирменный «green» в этой теме коралловый и читается как тревога, поэтому «всё в порядке»
// красим обычным зелёным, а предупреждения и ошибки общими поверхностями админки.
const TONE_TEXT: Record<Tone, string> = {
  green: 'text-emerald-700',
  amber: 'text-surface-warning-strong',
  red: 'text-surface-danger-strong',
  ink: 'text-ink-900',
}
const TONE_CHIP: Record<Tone, string> = {
  green: 'bg-emerald-50 text-emerald-700',
  amber: 'chip-amber',
  red: 'chip-red',
  ink: 'chip-ink',
}
const PLACEHOLDERS = new Set(['', '-', '—'])

export function FleetPanel() {
  const t = useT()
  const fleet = useScreenFleet()
  const narrow = useNarrowScreen()
  const manage = canManageTills(useUiStore((s) => s.authedUser?.role))
  const tillCommands = useTillCommands(manage)
  // Последнее задание каждой кассы; null: «Управление» не показываем (не HQ).
  const commands = useMemo(
    () => (manage ? latestCommandByTill(tillCommands.data) : null),
    [manage, tillCommands.data],
  )
  const [filter, setFilter] = useState<FleetFilter>('all')
  const [query, setQuery] = useState('')
  const [sort, setSort] = useState<FleetSort>('pharmacy')
  const [descending, setDescending] = useState(false)
  const [limit, setLimit] = useState(PAGE_SIZE)
  const data = fleet.data

  const rows = useMemo(() => {
    if (!data) return []
    const visible = data.registers.filter((row) => matchesFilter(row, filter) && matchesSearch(row, query))
    return sortRows(visible, sort, descending)
  }, [data, filter, query, sort, descending])

  const gaps = useMemo(() => {
    const needle = query.trim().toLowerCase()
    return (data?.pharmaciesWithoutRegisters ?? []).filter(
      (gap) => !needle || [gap.pharmacyName, gap.city, gap.address].some((v) => v.toLowerCase().includes(needle)),
    )
  }, [data, query])

  if (fleet.isLoading) {
    return <div className="card p-5 text-[13px] text-ink-500">{t('fleet.loading')}</div>
  }
  if (!data) {
    return (
      <div className="card flex items-center justify-between gap-3 p-5 text-[13px]" data-testid="fleet-error">
        <span className="text-surface-danger-strong">
          {t('fleet.loadFailed')} {fleet.error ? describeError(fleet.error) : ''}
        </span>
        <Button variant="outline" size="sm" onClick={() => fleet.refetch()}>
          {t('common.retry')}
        </Button>
      </div>
    )
  }

  const chooseFilter = (next: FleetFilter) => {
    setFilter((current) => (current === next ? 'all' : next))
    setLimit(PAGE_SIZE)
  }
  const chooseSort = (next: FleetSort) => {
    if (next === sort) setDescending((value) => !value)
    else {
      setSort(next)
      setDescending(false)
    }
  }
  const shownRows = rows.slice(0, limit)
  const total = filter === 'gaps' ? data.pharmaciesWithoutRegisters.length : data.registers.length
  const shown = filter === 'gaps' ? gaps.length : rows.length

  return (
    <div className="flex flex-col gap-3" data-testid="fleet-panel">
      <FleetTiles data={data} filter={filter} onFilter={chooseFilter} />

      <div className="card flex flex-col gap-3 p-3 sm:p-4">
        <div className="flex flex-col gap-2 md:flex-row md:items-center md:justify-between">
          <div className="w-full md:max-w-[420px]" data-testid="fleet-search">
            <SearchInput
              value={query}
              onChange={(value) => {
                setQuery(value)
                setLimit(PAGE_SIZE)
              }}
              placeholder={t('fleet.searchPlaceholder')}
            />
          </div>
          <div className="flex flex-wrap items-center gap-2 text-[12px] text-ink-500">
            <span data-testid="fleet-count">{t('fleet.shown', { shown, total })}</span>
            {filter !== 'all' && (
              <button
                type="button"
                className="chip chip-blue"
                onClick={() => chooseFilter(filter)}
                data-testid="fleet-filter-reset"
              >
                {t(`fleet.filter.${filter}`)} ×
              </button>
            )}
            {!data.salesAvailable && <span className="chip chip-amber">{t('fleet.salesUnavailable')}</span>}
            <span className="num">{t('fleet.updated', { time: almatyDateTime(data.observedAt) })}</span>
            <Button
              variant="outline"
              size="sm"
              leading={<IconRefresh size={14} />}
              disabled={fleet.isFetching}
              onClick={() => fleet.refetch()}
            >
              {t('fleet.refresh')}
            </Button>
          </div>
        </div>

        {filter === 'gaps' ? (
          <GapList gaps={gaps} />
        ) : rows.length === 0 ? (
          <div className="py-6 text-center text-[13px] text-ink-500">{t('fleet.nothingFound')}</div>
        ) : narrow ? (
          <ul className="flex flex-col gap-2" data-testid="fleet-cards">
            {shownRows.map((row) => (
              <FleetCard key={rowKey(row)} row={row} data={data} t={t} commands={commands} />
            ))}
          </ul>
        ) : (
          <FleetTable
            rows={shownRows}
            data={data}
            sort={sort}
            descending={descending}
            onSort={chooseSort}
            commands={commands}
          />
        )}

        {filter !== 'gaps' && rows.length > limit && (
          <Button variant="outline" size="sm" onClick={() => setLimit((value) => value + PAGE_SIZE)}>
            {t('fleet.showMore', { n: Math.min(PAGE_SIZE, rows.length - limit) })}
          </Button>
        )}
      </div>

      <PlaylistsInUse data={data} />
    </div>
  )
}

function FleetTiles({
  data,
  filter,
  onFilter,
}: {
  data: ScreenFleetDto
  filter: FleetFilter
  onFilter: (filter: FleetFilter) => void
}) {
  const t = useT()
  const s = data.summary
  const days = data.staleAfterDays
  const tiles: Array<{ id: FleetFilter; label: string; value: ReactNode; sub: string; tone: Tone }> = [
    { id: 'online', label: t('fleet.kpi.online'), value: s.online, sub: t('fleet.kpi.onlineSub', { n: s.registers }), tone: 'green' },
    { id: 'offline', label: t('fleet.kpi.offline'), value: s.offline, sub: t('fleet.kpi.offlineSub', { days }), tone: s.offline > 0 ? 'amber' : 'ink' },
    { id: 'stale', label: t('fleet.kpi.stale'), value: s.stale, sub: t('fleet.kpi.staleSub', { days }), tone: 'ink' },
    { id: 'clientScreen', label: t('fleet.kpi.clientScreen'), value: s.clientScreens, sub: t('fleet.kpi.clientScreenSub'), tone: 'green' },
    { id: 'standardN', label: t('fleet.kpi.standardN'), value: s.standardNUnavailable, sub: t('fleet.kpi.standardNSub'), tone: s.standardNUnavailable > 0 ? 'red' : 'ink' },
    {
      id: 'noSalesToday',
      label: t('fleet.kpi.noSales'),
      value: data.salesAvailable ? s.onlinePharmaciesWithoutSalesToday : '—',
      sub: t('fleet.kpi.noSalesSub'),
      tone: data.salesAvailable && s.onlinePharmaciesWithoutSalesToday > 0 ? 'amber' : 'ink',
    },
    {
      id: 'gaps',
      label: t('fleet.kpi.gaps'),
      value: data.pharmaciesWithoutRegisters.length,
      sub: t('fleet.kpi.gapsSub', { n: s.activePharmacies }),
      tone: data.pharmaciesWithoutRegisters.length > 0 ? 'amber' : 'ink',
    },
  ]
  return (
    <div className="grid grid-cols-3 gap-1.5 sm:grid-cols-4 sm:gap-2 xl:grid-cols-7" data-testid="fleet-tiles">
      {tiles.map((tile) => (
        <button
          key={tile.id}
          type="button"
          aria-pressed={filter === tile.id}
          onClick={() => onFilter(tile.id)}
          data-testid={`fleet-kpi-${tile.id}`}
          className={`card flex min-w-0 flex-col items-start gap-0.5 px-2.5 py-2 text-left transition-colors sm:gap-1 sm:px-3 sm:py-2.5 ${
            filter === tile.id ? 'ring-2 ring-inset ring-brand-green-400' : 'hover:bg-paper-hover'
          }`}
        >
          <span className="text-[11px] font-semibold leading-4 text-ink-500">{tile.label}</span>
          <span className={`num text-[20px] font-bold leading-none sm:text-[22px] ${TONE_TEXT[tile.tone]}`}>{tile.value}</span>
          <span className="hidden text-[11px] leading-4 text-ink-400 sm:block">{tile.sub}</span>
        </button>
      ))}
    </div>
  )
}

function FleetTable({
  rows,
  data,
  sort,
  descending,
  onSort,
  commands,
}: {
  rows: ScreenFleetRegisterDto[]
  data: ScreenFleetDto
  sort: FleetSort
  descending: boolean
  onSort: (sort: FleetSort) => void
  commands: Map<string, TillCommandDto> | null
}) {
  const t = useT()
  const header = (id: FleetSort | null, label: string, hint?: string) =>
    id ? (
      <th className="px-2.5 py-2 align-middle" aria-sort={sort === id ? (descending ? 'descending' : 'ascending') : 'none'}>
        <button type="button" title={hint} className="inline-flex items-center gap-1 uppercase hover:text-ink-900" onClick={() => onSort(id)}>
          {label}
          <span aria-hidden="true" className="text-ink-300">{sort === id ? (descending ? '▼' : '▲') : '↕'}</span>
        </button>
      </th>
    ) : (
      <th className="px-2.5 py-2 align-middle">{label}</th>
    )
  return (
    <div
      role="region"
      aria-label={t('fleet.tableLabel')}
      tabIndex={0}
      className="scrollbar-thin max-w-full overflow-x-auto focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-brand-green-400"
    >
      {/* !table: общий стиль админки делает таблицы display:block, а тогда ширины колонок
          не действуют и узкая колонка растягивает строку в несколько этажей. */}
      {/* «Управление» (только HQ) узкая: связь и эфир отдают ей 30 px, чтобы на ноутбуке 1440 px
          таблица по-прежнему помещалась без прокрутки. */}
      <table className="!table w-full min-w-[1100px] table-fixed text-[12px]" data-testid="fleet-table">
        <colgroup>
          <col />
          <col className="w-[160px]" />
          <col className={commands ? 'w-[100px]' : 'w-[110px]'} />
          <col className="w-[140px]" />
          <col className="w-[140px]" />
          <col className={commands ? 'w-[150px]' : 'w-[170px]'} />
          <col className="w-[150px]" />
          {commands && <col className="w-[110px]" />}
        </colgroup>
        <thead className="hairline border-b">
          <tr className="text-left text-[11px] font-bold uppercase tracking-[0.06em] text-ink-500">
            {header('pharmacy', t('fleet.th.pharmacy'))}
            {header('version', t('fleet.th.register'), t('fleet.sortByVersion'))}
            {header('state', t('fleet.th.state'))}
            {header(null, t('fleet.th.screen'))}
            {header(null, t('fleet.th.standardN'))}
            {header(null, t('fleet.th.playlist'))}
            {header('sales', t('fleet.th.sales'))}
            {commands && header(null, t('tillCmd.th'))}
          </tr>
        </thead>
        <tbody className="divide-y divide-ink-100">
          {rows.map((row) => (
            <tr key={rowKey(row)} data-testid={`fleet-row-${rowKey(row)}`}>
              <td className="min-w-0 px-2.5 py-1.5 align-middle">
                <div className="truncate font-bold text-ink-900" title={row.pharmacyName ?? undefined}>
                  {row.pharmacyName ?? row.pharmacyId ?? t('fleet.noPharmacy')}
                </div>
                <div className="truncate text-[11px] text-ink-500">{placeLine(row)}</div>
              </td>
              <td className="min-w-0 px-2.5 py-1.5 align-middle">
                <div className="num truncate font-bold text-ink-800" title={row.deviceId}>{row.deviceId}</div>
                <div className="flex min-w-0 items-center gap-1 text-[11px] text-ink-500">
                  <span className="num truncate">{row.appVersion ?? ''}</span>
                  <RegisterFlags row={row} />
                </div>
              </td>
              <td className="px-2.5 py-1.5 align-middle"><StateChip row={row} data={data} /></td>
              <td className="px-2.5 py-1.5 align-middle"><ScreenChip row={row} /></td>
              <td className="px-2.5 py-1.5 align-middle"><StandardNChip row={row} /></td>
              <td className="min-w-0 px-2.5 py-1.5 align-middle"><PlaylistCell row={row} data={data} /></td>
              <td className="px-2.5 py-1.5 align-middle"><SalesCell row={row} data={data} /></td>
              {commands && (
                <td className="min-w-0 px-2.5 py-1.5 align-middle">
                  <TillCommandCell row={row} command={commands.get(tillKey(row.pharmacyId, row.deviceId))} />
                </td>
              )}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function FleetCard({
  row,
  data,
  t,
  commands,
}: {
  row: ScreenFleetRegisterDto
  data: ScreenFleetDto
  t: TFn
  commands: Map<string, TillCommandDto> | null
}) {
  return (
    <li className="rounded-lg border border-ink-100 p-3 text-[12px]" data-testid={`fleet-card-${rowKey(row)}`}>
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <div className="truncate font-bold text-ink-900">{row.pharmacyName ?? row.pharmacyId ?? t('fleet.noPharmacy')}</div>
          <div className="truncate text-[11px] text-ink-500">{placeLine(row)}</div>
        </div>
        <StateChip row={row} data={data} />
      </div>
      <div className="mt-2 flex flex-wrap items-center gap-1.5">
        <span className="num font-bold text-ink-800">{row.deviceId}</span>
        {row.appVersion && <span className="num text-ink-500">v{row.appVersion}</span>}
        {row.state === 'online' && <ScreenChip row={row} />}
        {row.state === 'online' && <StandardNChip row={row} />}
        <RegisterFlags row={row} />
      </div>
      <div className="mt-1.5 flex flex-col gap-0.5 text-ink-600">
        <PlaylistCell row={row} data={data} />
        <SalesCell row={row} data={data} inline />
      </div>
      {commands && (
        <div className="hairline mt-2 border-t pt-2">
          <TillCommandCell
            row={row}
            command={commands.get(tillKey(row.pharmacyId, row.deviceId))}
            variant="card"
          />
        </div>
      )}
    </li>
  )
}

function RegisterFlags({ row }: { row: ScreenFleetRegisterDto }) {
  const t = useT()
  return (
    <>
      {!row.registered && <span className="chip chip-amber px-1.5 py-0" title={t('fleet.unregisteredHint')}>{t('fleet.unregistered')}</span>}
      {row.pharmacyActive === false && <span className="chip chip-red px-1.5 py-0">{t('fleet.pharmacyClosed')}</span>}
    </>
  )
}

function StateChip({ row, data }: { row: ScreenFleetRegisterDto; data: ScreenFleetDto }) {
  const t = useT()
  const title = row.lastSeen ? t('fleet.lastSeenAt', { time: almatyDateTime(row.lastSeen) }) : undefined
  if (row.state === 'online') return <span className={`chip ${TONE_CHIP.green} py-0.5`} title={title}>{t('fleet.state.online')}</span>
  const tone = row.state === 'offline' ? 'chip-amber' : 'chip-ink'
  return <span className={`chip ${tone} py-0.5`} title={title}>{relativeAgo(t, row.lastSeen, data.observedAt)}</span>
}

function ScreenChip({ row }: { row: ScreenFleetRegisterDto }) {
  const t = useT()
  if (row.state !== 'online') return <span className="text-ink-400">—</span>
  if (row.hasClientScreen === true) return <span className={`chip ${TONE_CHIP.green} py-0.5`}>{t('fleet.screen.client')}</span>
  if (row.hasClientScreen === false) return <span className="chip chip-ink py-0.5" title={t('fleet.screen.singleHint')}>{t('fleet.screen.single')}</span>
  return <span className="text-ink-400" title={t('fleet.screen.unknownHint')}>{t('fleet.screen.unknown')}</span>
}

function StandardNChip({ row }: { row: ScreenFleetRegisterDto }) {
  const t = useT()
  if (row.state !== 'online') return <span className="text-ink-400">—</span>
  const short = standardNShort(row.staffSourceStatus)
  return (
    <span className={`chip ${TONE_CHIP[short.tone]} py-0.5`} title={staffSourceStatusLabel(row.staffSourceStatus)}>
      {t(short.key)}
    </span>
  )
}

function PlaylistCell({ row, data }: { row: ScreenFleetRegisterDto; data: ScreenFleetDto }) {
  const t = useT()
  const playlist = data.playlists.find((p) => p.id === row.playlistId)
  if (!playlist) return <span className="text-ink-400">{t('fleet.source.none')}</span>
  const dim = row.state === 'online' && row.hasClientScreen === false
  return (
    <div
      className={`min-w-0 ${dim ? 'text-ink-400' : 'text-ink-700'}`}
      title={[playlist.name, ...playlist.slides.map((s) => `${s.position + 1}. ${s.title}`)].join('\n')}
    >
      <div className="truncate font-semibold">{playlist.name}</div>
      <div className="truncate text-[11px] text-ink-500">
        {t(`fleet.source.${row.playlistSource}`)} · {t('fleet.slides', { n: playlist.slides.length })}
        {dim ? ` · ${t('fleet.notShown')}` : ''}
      </div>
    </div>
  )
}

function SalesCell({ row, data, inline = false }: { row: ScreenFleetRegisterDto; data: ScreenFleetDto; inline?: boolean }) {
  const t = useT()
  if (!data.salesAvailable || !row.pharmacyId) return <span className="text-ink-400">—</span>
  // Чеки самой кассы; если сегодня их нет, подсказка: когда был последний или продаёт ли аптека через другую кассу.
  let note: string | null = null
  if (row.salesToday === 0) {
    if (row.lastSaleAt) note = t('fleet.lastSale', { time: almatyDateTime(row.lastSaleAt) })
    else if (row.pharmacySalesToday > 0) note = t('fleet.pharmacyToday', { n: row.pharmacySalesToday })
    else if (row.pharmacyLastSaleAt) note = t('fleet.pharmacyLastSale', { time: almatyDateTime(row.pharmacyLastSaleAt) })
    else note = t('fleet.noSalesEver')
  }
  const silent = row.state === 'online' && row.salesToday === 0 && row.pharmacySalesToday === 0
  const tone = row.salesToday > 0 ? 'text-ink-900' : silent ? 'text-surface-warning-strong' : 'text-ink-500'
  return (
    <div className={`min-w-0 ${inline ? 'flex flex-wrap gap-x-2' : ''}`}>
      <div className="num truncate">
        <span className={`font-bold ${tone}`}>{t('fleet.salesToday', { n: row.salesToday })}</span>
        <span className="text-ink-500"> · {t('fleet.salesWeek', { n: row.sales7d })}</span>
      </div>
      {note && <div className="num truncate text-[11px] text-ink-500">{note}</div>}
    </div>
  )
}

function GapList({ gaps }: { gaps: PosmCoverageGapDto[] }) {
  const t = useT()
  if (gaps.length === 0) return <div className="py-6 text-center text-[13px] text-ink-500">{t('fleet.noGaps')}</div>
  return (
    <ul className="hairline flex flex-col divide-y divide-ink-100 border-t text-[12px]" data-testid="fleet-gaps">
      {gaps.map((gap) => (
        <li key={gap.pharmacyId} className="flex flex-col py-1.5 md:flex-row md:items-center md:gap-3">
          <span className="font-bold text-ink-900 md:w-[34%] md:truncate">{gap.pharmacyName}</span>
          <span className="text-ink-500 md:flex-1 md:truncate">{[gap.city, gap.address].filter(Boolean).join(', ')}</span>
          <span className="num text-[11px] text-ink-400">{gap.pharmacyId}</span>
        </li>
      ))}
    </ul>
  )
}

function PlaylistsInUse({ data }: { data: ScreenFleetDto }) {
  const t = useT()
  const counts = useMemo(() => {
    const result = new Map<string, number>()
    data.registers.forEach((row) => {
      if (row.state === 'online' && row.playlistId) result.set(row.playlistId, (result.get(row.playlistId) ?? 0) + 1)
    })
    return result
  }, [data])
  if (data.playlists.length === 0) return null
  return (
    <div className="card flex flex-col gap-2 p-3 sm:p-4" data-testid="fleet-playlists">
      <div className="text-[12px] font-bold uppercase tracking-[0.06em] text-ink-500">{t('fleet.playlistsTitle')}</div>
      <ul className="flex flex-col divide-y divide-ink-100 text-[12px]">
        {data.playlists.map((playlist: ScreenFleetPlaylistDto) => (
          <li key={playlist.id} className="flex flex-col gap-1 py-2">
            <div className="flex flex-wrap items-baseline justify-between gap-2">
              <span className="font-bold text-ink-900">{playlist.name}</span>
              <span className="num text-ink-500">
                {t('fleet.playlistMeta', {
                  registers: counts.get(playlist.id) ?? 0,
                  slides: playlist.slides.length,
                  minutes: Math.round(playlist.durationSec / 60),
                })}
              </span>
            </div>
            <div className="flex flex-wrap gap-1">
              {playlist.slides.map((slide) => (
                <span
                  key={`${playlist.id}:${slide.position}`}
                  className={`chip py-0.5 ${slide.inherited ? 'chip-ink' : 'chip-blue'}`}
                  title={slide.inherited ? t('fleet.inheritedHint') : undefined}
                >
                  {slide.position + 1}. {slide.title}
                </span>
              ))}
            </div>
          </li>
        ))}
      </ul>
    </div>
  )
}

/** «Город, адрес» без заглушек «—» и без повтора названия, которое в справочнике бывает адресом. */
function placeLine(row: ScreenFleetRegisterDto): string {
  const city = row.city?.trim() ?? ''
  const address = row.address?.trim() ?? ''
  return [city, address === row.pharmacyName?.trim() ? '' : address]
    .filter((part) => !PLACEHOLDERS.has(part))
    .join(', ')
}

function rowKey(row: ScreenFleetRegisterDto): string {
  return `${row.pharmacyId ?? 'none'}-${row.deviceId}`
}

function useNarrowScreen() {
  const query = '(max-width: 767px)'
  const [matches, setMatches] = useState(
    () => typeof window !== 'undefined' && Boolean(window.matchMedia?.(query).matches),
  )
  useEffect(() => {
    if (typeof window === 'undefined' || !window.matchMedia) return
    const media = window.matchMedia(query)
    const onChange = (event: MediaQueryListEvent) => setMatches(event.matches)
    media.addEventListener('change', onChange)
    return () => media.removeEventListener('change', onChange)
  }, [])
  return matches
}
