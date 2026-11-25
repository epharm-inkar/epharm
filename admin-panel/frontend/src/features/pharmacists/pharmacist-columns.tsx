// Колонки плотного реестра фармацевтов (DataTable). Только то, что различается между людьми:
// тир (у всех Silver), баланс (у всех 0) и формат обучения убраны из колонок; формат остаётся
// фильтром и действием строки.

import type { DataTableColumn, MenuItem } from '@/ui'
import { Menu } from '@/ui'
import { IconDots } from '@/ui/icons'
import type { PharmacistDto, PharmacyDto } from '@/lib/api-types'
import { formatCount, formatDay, formatDayTime, formatTenge } from '@/lib/format'
import { maskedIin } from './personal-data'
import { chainOf, hasPhone, isTestRecord, sourceLabel } from './registry-model'
import { IinDiagnostic, Present, Tag } from './RegistryCells'

export interface PharmacistColumnContext {
  t: (key: string) => string
  personalDataVisible: boolean
  canSelect: boolean
  selectedIds: ReadonlySet<string>
  allVisibleSelected: boolean
  onToggleVisible: () => void
  onToggleRow: (id: string) => void
  pharmacies: ReadonlyMap<string, PharmacyDto>
  rowActions: (pharmacist: PharmacistDto) => MenuItem[]
}

const DASH = '—'
const TEST_MARK = /^\s*\[[^\]]*\]\s*/

function shownIin(pharmacist: PharmacistDto, revealed: boolean): string {
  return revealed ? pharmacist.iin?.trim() || DASH : maskedIin(pharmacist.iin)
}

const STATUS_TAG = { pending: 'ожидает', blocked: 'заблокирован' } as const

function nameTooltip(pharmacist: PharmacistDto): string {
  const created = formatDay(pharmacist.createdAt)
  return [
    pharmacist.name,
    isTestRecord(pharmacist.name) ? 'Тестовая запись' : null,
    pharmacist.status !== 'active' ? STATUS_TAG[pharmacist.status] : null,
    created ? `Карточка с ${created}` : null,
  ]
    .filter(Boolean)
    .join('\n')
}

function otherPharmacies(pharmacist: PharmacistDto) {
  return (pharmacist.standardNPharmacies ?? []).filter((pharmacy) => pharmacy.id !== pharmacist.pharmacyId)
}

function pharmacyTooltip(
  pharmacist: PharmacistDto,
  pharmacies: ReadonlyMap<string, PharmacyDto>,
  unassigned: string,
): string {
  const chain = chainOf(pharmacist, pharmacies)
  const main = pharmacist.pharmacyName
    ? `Основная: ${[pharmacist.pharmacyName, pharmacist.city].filter(Boolean).join(', ')}${chain ? ` (${chain})` : ''}`
    : unassigned
  const listed = pharmacist.standardNPharmacies ?? []
  if (!listed.length) return main
  return `${main}\nВ Стандарт-Н: ${listed.map((pharmacy) => [pharmacy.name, pharmacy.city].filter(Boolean).join(', ')).join('; ')}`
}

function lastSaleTooltip(pharmacist: PharmacistDto): string {
  const at = formatDayTime(pharmacist.lastSaleAt)
  if (at) return `Последний чек: ${at}`
  if (pharmacist.lastSaleAt === undefined) return 'Нет данных'
  return pharmacist.receipts30d == null ? 'Нет доступа к продажам' : 'Продаж не было'
}

export function pharmacistColumns(ctx: PharmacistColumnContext): DataTableColumn<PharmacistDto>[] {
  const { t, personalDataVisible: revealed } = ctx
  const select: DataTableColumn<PharmacistDto>[] = ctx.canSelect
    ? [
        {
          id: 'select',
          width: 32,
          sticky: true,
          truncate: false,
          align: 'center',
          header: (
            <input
              type="checkbox"
              aria-label="Выбрать всех видимых фармацевтов"
              checked={ctx.allVisibleSelected}
              onChange={ctx.onToggleVisible}
              className="h-3.5 w-3.5 align-middle accent-brand-green-700"
            />
          ),
          cell: (p) => (
            <input
              type="checkbox"
              aria-label={`Выбрать ${p.name}`}
              checked={ctx.selectedIds.has(p.id)}
              onChange={() => ctx.onToggleRow(p.id)}
              className="h-3.5 w-3.5 align-middle accent-brand-green-700"
            />
          ),
        },
      ]
    : []

  return [
    ...select,
    {
      id: 'name',
      header: 'ФИО',
      minWidth: 180,
      sticky: true,
      truncate: false,
      // «[ДЕМО] …» sorts by the name after the mark, so test cards do not head the list.
      sortValue: (p) => p.name.replace(TEST_MARK, ''),
      tooltip: nameTooltip,
      cell: (p) => (
        <span className="flex min-w-0 items-center gap-1.5">
          <span className={`truncate font-semibold ${isTestRecord(p.name) ? 'text-ink-500' : 'text-ink-900'}`}>
            {p.name}
          </span>
          {isTestRecord(p.name) && <Tag tone="ink">тест</Tag>}
          {p.status === 'pending' && <Tag tone="amber">{STATUS_TAG.pending}</Tag>}
          {p.status === 'blocked' && <Tag tone="red">{STATUS_TAG.blocked}</Tag>}
        </span>
      ),
    },
    {
      id: 'iin',
      header: t('phc.thIin'),
      width: 120,
      truncate: false,
      sortValue: (p) => shownIin(p, revealed),
      cell: (p) => (
        <span className="flex min-w-0 items-center gap-1 overflow-hidden">
          <span className="num truncate">{shownIin(p, revealed)}</span>
          <IinDiagnostic pharmacist={p} t={t} />
        </span>
      ),
    },
    {
      id: 'pharmacy',
      header: t('phc.thPharmacy'),
      width: 164,
      truncate: false,
      sortValue: (p) => p.pharmacyName,
      tooltip: (p) => pharmacyTooltip(p, ctx.pharmacies, t('phc.unassigned')),
      cell: (p) => {
        const others = otherPharmacies(p)
        return (
          <span className="flex min-w-0 items-center gap-1.5">
            <span className={`truncate ${p.pharmacyName ? '' : 'font-semibold text-accent-warning'}`}>
              {p.pharmacyName || t('phc.unassigned')}
              {p.pharmacyName && p.city && <span className="text-ink-400"> · {p.city}</span>}
            </span>
            {others.length > 0 && <Tag tone="ink">+{others.length}</Tag>}
          </span>
        )
      },
    },
    {
      id: 'source',
      header: 'Источник',
      headerTitle: 'Откуда карточка: Стандарт-Н (создана сервером по справочнику) или регистрация',
      width: 100,
      sortValue: (p) => sourceLabel(p),
      cell: (p) => sourceLabel(p) ?? DASH,
    },
    {
      id: 'phone',
      header: 'Телефон',
      width: revealed ? 148 : 68,
      align: revealed ? 'left' : 'center',
      firstDirection: 'desc',
      sortValue: (p) => (revealed ? p.phone?.trim() || null : hasPhone(p)),
      tooltip: (p) => (revealed ? p.phone?.trim() || 'Телефона нет' : hasPhone(p) ? 'Телефон указан' : 'Телефона нет'),
      cell: (p) =>
        revealed ? p.phone?.trim() || DASH : hasPhone(p) ? <Present label="Телефон указан" /> : DASH,
    },
    {
      id: 'password',
      header: 'Пароль',
      headerTitle: 'Задан ли пароль кабинета (сам пароль не показывается)',
      width: 62,
      align: 'center',
      firstDirection: 'desc',
      sortValue: (p) => p.hasPassword,
      tooltip: (p) => (p.hasPassword ? 'Пароль задан' : 'Пароля нет'),
      cell: (p) => (p.hasPassword ? <Present label="Пароль задан" /> : DASH),
    },
    {
      id: 'lastSale',
      header: 'Посл. продажа',
      headerTitle: 'Последний чек человека по ИИН в любой аптеке, без возвратов',
      width: 112,
      align: 'right',
      firstDirection: 'desc',
      sortValue: (p) => (p.lastSaleAt ? Date.parse(p.lastSaleAt) : null),
      tooltip: lastSaleTooltip,
      cell: (p) => formatDay(p.lastSaleAt) ?? DASH,
    },
    {
      id: 'receipts30d',
      header: 'Чеки 30 дн',
      headerTitle: 'Чеков за 30 дней в аптеке карточки, без возвратов',
      width: 96,
      align: 'right',
      firstDirection: 'desc',
      sortValue: (p) => p.receipts30d,
      cell: (p) => (p.receipts30d == null ? DASH : formatCount(p.receipts30d)),
    },
    {
      id: 'earned',
      header: 'Заработано',
      headerTitle: 'Заработано за текущий месяц: начисления с 1-го числа, как в окне ePharm на кассе',
      width: 104,
      align: 'right',
      firstDirection: 'desc',
      sortValue: (p) => p.earnedMonthKzt,
      cell: (p) => (p.earnedMonthKzt == null ? DASH : formatTenge(p.earnedMonthKzt)),
    },
    {
      id: 'tasks',
      header: 'Задания',
      headerTitle: 'Открытые задания по обучению: не завершены и не отменены',
      width: 72,
      align: 'right',
      firstDirection: 'desc',
      sortValue: (p) => p.openAssignments,
      cell: (p) => (p.openAssignments == null ? DASH : formatCount(p.openAssignments)),
    },
    {
      id: 'actions',
      header: <span className="sr-only">Действия</span>,
      width: 36,
      align: 'center',
      truncate: false,
      cell: (p) => (
        <Menu
          trigger={<IconDots size={16} />}
          ariaLabel={`Действия: ${p.name}`}
          items={ctx.rowActions(p)}
          width={220}
          triggerClassName="inline-flex h-6 w-6 items-center justify-center rounded text-ink-400 hover:bg-ink-100 hover:text-ink-900"
        />
      ),
    },
  ]
}
