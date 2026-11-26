// Promo — управление промо-кампаниями брендов.
// Этап 3.3: данные приходят из backend через usePromos/useCreatePromo/useUpdatePromo/useArchivePromo.

import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  Button,
  Empty,
  Metric,
  PageHeader,
  ProgressBar,
  SearchInput,
  Select,
  StatusChip,
  useToast,
  type SelectOption,
} from '@/ui'
import {
  IconArchive,
  IconArrowUp,
  IconBox,
  IconFinance,
  IconGrid,
  IconLift,
  IconList,
  IconPause,
  IconPlay,
  IconPlus,
  IconPromo,
  IconRefresh,
} from '@/ui/icons'
import { formatKzt, type Promo } from '@/mocks/fixtures'
import type { PromoStatus } from '@/lib/api-types'
import { describeError } from '@/lib/describeError'
import { proxyMedia } from '@/lib/media'
import {
  useArchivePromo,
  useCreatePromo,
  usePromos,
  useRefreshPrices,
  useRestorePromo,
  useUpdatePromo,
} from '@/lib/queries/promo'
import { useStorefrontProduct } from '@/lib/queries/storefront'
import { useT } from '@/i18n'
import { CreatePromoModal } from './CreatePromoModal'

type StatusFilter = 'all' | PromoStatus

// Режим просмотра кампаний: «сетка» (карточки с фото товара 3:4) ↔ «список» (таблица).
// Выбор запоминаем в localStorage, чтобы не сбрасывался между визитами.
type ViewMode = 'grid' | 'list'
const VIEW_MODE_KEY = 'epharm.promoView'

function readViewMode(): ViewMode {
  try {
    return localStorage.getItem(VIEW_MODE_KEY) === 'list' ? 'list' : 'grid'
  } catch {
    return 'grid'
  }
}

// Эффективное фото товара для превью: ручной override → снимок из Medusa.
// Возвращаем уже прогнанный через image-прокси URL либо null.
function promoPhoto(promo: Promo): string | null {
  const raw = promo.overrideImage?.trim() || promo.productImage?.trim() || ''
  return raw ? proxyMedia(raw) : null
}

/**
 * Фото для карточки и строки списка. Если у кампании своего фото нет (ни ручного, ни снимка), берём
 * фото товара из каталога, как это делает страница кампании. Нет и его: null, и карточка рисует
 * спокойный нейтральный блок с названием товара, а не выдуманную картинку.
 */
function usePromoPhoto(promo: Promo): string | null {
  const own = promoPhoto(promo)
  const { data } = useStorefrontProduct(own ? null : promo.medusaProductId)
  if (own) return own
  const raw = data?.imageUrl?.trim() || data?.images?.[0]?.trim() || ''
  return raw ? proxyMedia(raw) : null
}

export default function PromoPage() {
  const toast = useToast()
  const navigate = useNavigate()
  const t = useT()

  const STATUS_OPTIONS: SelectOption[] = [
    { value: 'all', label: t('pm.statusAll') },
    { value: 'active', label: t('pm.statusActive') },
    { value: 'draft', label: t('pm.statusDraft') },
    { value: 'paused', label: t('pm.statusPaused') },
    { value: 'archived', label: t('pm.statusArchived') },
  ]
  const { data: promos = [], isLoading, isError, error, refetch } = usePromos()
  // Bug Q: data может быть из persist'ed cache даже при isError → показываем
  // данные + warning banner вместо полного error UI. Различаем «нет данных
  // вообще» vs «есть кэш, но refetch упал».
  const hasData = promos.length > 0
  const showFullError = isError && !hasData
  const showWarningBanner = isError && hasData
  const updatePromo = useUpdatePromo()
  const archivePromo = useArchivePromo()
  const restorePromo = useRestorePromo()
  const createPromo = useCreatePromo()
  const refreshPrices = useRefreshPrices()

  const handleRefreshPrices = () => {
    refreshPrices.mutate(undefined, {
      onSuccess: (r) =>
        toast.push(t('pm.pricesRefreshed', { p: r.promosUpdated, n: r.productsUpdated })),
      onError: (e) => toast.push(`${t('pm.refreshErr')}: ${describeError(e)}`),
    })
  }

  const [q, setQ] = useState('')
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('all')
  const [createOpen, setCreateOpen] = useState(false)
  const [viewMode, setViewMode] = useState<ViewMode>(readViewMode)

  useEffect(() => {
    try {
      localStorage.setItem(VIEW_MODE_KEY, viewMode)
    } catch {
      /* localStorage недоступен (приватный режим) — режим просто не запомнится */
    }
  }, [viewMode])

  const filtered = useMemo<Promo[]>(() => {
    return promos.filter((p) => {
      if (statusFilter !== 'all' && p.status !== statusFilter) return false
      if (!q) return true
      return `${p.title} ${p.brand}`.toLowerCase().includes(q.toLowerCase())
    })
  }, [promos, q, statusFilter])

  const metrics = useMemo(() => {
    const active = promos.filter((p) => p.status === 'active')
    const totalBudget = promos.filter((p) => p.budgetUnlimited !== true).reduce((a, p) => a + p.budget, 0)
    const unlimitedCount = promos.filter((p) => p.budgetUnlimited === true).length
    const totalSpent = promos.reduce((a, p) => a + p.spent, 0)
    return {
      activeCount: active.length,
      totalCount: promos.length,
      totalBudget,
      unlimitedCount,
      totalSpent,
      spentPct: totalBudget > 0 ? Math.round((totalSpent / totalBudget) * 100) : 0,
    }
  }, [promos])

  const handleToggle = (p: Promo) => {
    if (p.status === 'draft') {
      navigate(`/promo/${p.id}`)
      return
    }
    const next: PromoStatus = p.status === 'active' ? 'paused' : 'active'
    updatePromo.mutate(
      { id: p.id, patch: { status: next } },
      {
        onSuccess: () => toast.push(next === 'active' ? t('pm.resumed') : t('pm.pausedToast')),
        // Включение draft→active может упасть на тех же бизнес-правилах
        // (товар не привязан / занят другой кампанией) — показываем причину.
        onError: (e) => toast.push(`${t('pm.toggleErr')}: ${describeError(e)}`),
      },
    )
  }

  const handleArchive = (p: Promo) => {
    if (!confirm(t('pm.confirmArchive', { title: p.title }))) return
    archivePromo.mutate(p.id, {
      onSuccess: () => toast.push(t('pm.archivedToast')),
      onError: () => toast.push(t('pm.archiveErr')),
    })
  }

  const handleRestore = (p: Promo) => {
    restorePromo.mutate(p.id, {
      onSuccess: () => toast.push(t('pm.restoredToast')),
      onError: () => toast.push(t('pm.restoreErr')),
    })
  }

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title={t('page.promo.title')}
        subtitle={t('page.promo.subtitle')}
        help={t('help.promo.page')}
        actions={
          // Bug M fix: убрал «Экспорт» — onClick не был подключён, кнопка выглядела
          // сломанной. Вернётся в Этапе 7 (Operational polish) с реальной CSV-выгрузкой.
          <div className="flex flex-wrap items-center gap-2">
            <ViewModeToggle mode={viewMode} onChange={setViewMode} />
            <Button
              variant="ghost"
              size="md"
              leading={<IconRefresh size={14} />}
              onClick={handleRefreshPrices}
              disabled={refreshPrices.isPending}
            >
              {refreshPrices.isPending ? t('pm.refreshing') : t('pm.refreshPrices')}
            </Button>
            <Button
              variant="primary"
              size="md"
              leading={<IconPlus size={14} />}
              onClick={() => setCreateOpen(true)}
            >
              {t('pm.newCampaign')}
            </Button>
          </div>
        }
      />

      {/* 4 KPI metrics: two by two on a phone */}
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4 lg:gap-4">
        <Metric
          label={t('pm.mActive')}
          value={`${metrics.activeCount}`}
          sub={t('pm.ofN', { n: metrics.totalCount })}
          accent="green"
          icon={<IconPromo size={18} />}
        />
        <Metric
          label={t(metrics.unlimitedCount > 0 ? 'budget.finiteTotal' : 'pm.mBudget')}
          value={formatKzt(metrics.totalBudget)}
          sub={metrics.unlimitedCount > 0 ? t('budget.unlimitedCount', { n: metrics.unlimitedCount }) : undefined}
          accent="blue"
          icon={<IconFinance size={18} />}
          help={metrics.unlimitedCount > 0 ? t('help.promo.finiteTotal') : undefined}
        />
        <Metric
          label={t('pm.mSpent')}
          value={formatKzt(metrics.totalSpent)}
          sub={metrics.unlimitedCount === 0 && metrics.totalBudget > 0 ? `${metrics.spentPct}%` : undefined}
          accent="amber"
          icon={<IconArrowUp size={18} />}
          help={t('help.promo.spent')}
        />
        <Metric
          label={t('pm.mRoi')}
          value="—"
          sub={t('pm.roiHint')}
          accent="purple"
          icon={<IconLift size={18} />}
        />
      </div>

      <div className="card">
        <div className="hairline flex flex-wrap items-center justify-between gap-3 border-b px-5 py-3">
          <div className="flex flex-wrap items-center gap-2">
            <SearchInput
              value={q}
              onChange={setQ}
              placeholder={t('pm.search')}
              className="!h-9 !w-[260px] flex-none"
            />
            <Select
              value={statusFilter}
              onChange={(v) => setStatusFilter(v as StatusFilter)}
              options={STATUS_OPTIONS}
              className="w-[170px] flex-none"
            />
          </div>
        </div>

        {showWarningBanner && (
          <div className="hairline border-b bg-accent-warning/10 px-5 py-2 text-[12px] font-semibold text-ink-700">
            {t('rules.staleWarn', { e: describeError(error) })}{' '}
            <button
              type="button"
              onClick={() => refetch()}
              className="font-bold text-brand-green-700 underline"
            >
              {t('common.retry')}
            </button>
          </div>
        )}

        <div className="p-5">
          {isLoading && !hasData ? (
            <Empty
              title={t('pm.loading')}
              body={t('common.connecting')}
              icon={<IconPromo size={26} />}
            />
          ) : showFullError ? (
            <Empty
              title={t('pm.errTitle')}
              body={describeError(error)}
              icon={<IconPromo size={26} />}
              action={<Button onClick={() => refetch()}>{t('common.retry')}</Button>}
            />
          ) : filtered.length === 0 ? (
            <Empty
              title={q || statusFilter !== 'all' ? t('common.notFound') : t('pm.empty')}
              body={q || statusFilter !== 'all' ? t('common.notFoundBody') : t('pm.emptyBody')}
              icon={<IconPromo size={26} />}
              action={
                <Button leading={<IconPlus size={14} />} onClick={() => setCreateOpen(true)}>
                  {t('pm.newCampaign')}
                </Button>
              }
            />
          ) : viewMode === 'list' ? (
            <PromoListView
              promos={filtered}
              onOpen={(p) => navigate(`/promo/${p.id}`)}
              onToggle={handleToggle}
              onArchive={handleArchive}
              onRestore={handleRestore}
            />
          ) : (
            <div
              className="grid gap-4"
              style={{ gridTemplateColumns: 'repeat(auto-fill, minmax(220px, 1fr))' }}
              data-testid="promo-grid"
            >
              {filtered.map((p) => (
                <PromoCard
                  key={p.id}
                  promo={p}
                  onOpen={() => navigate(`/promo/${p.id}`)}
                  onToggle={() => handleToggle(p)}
                  onArchive={() => handleArchive(p)}
                  onRestore={() => handleRestore(p)}
                />
              ))}
            </div>
          )}
        </div>
      </div>

      <CreatePromoModal
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        onCreate={async (req) => {
          try {
            const created = await createPromo.mutateAsync(req)
            toast.push(t('pm.createdToast'))
            setCreateOpen(false)
            navigate(`/promo/${created.id}`)
          } catch (e) {
            // Раскрываем конкретную причину от backend (1:1-конфликт товара,
            // несогласованные даты и т.п.) — иначе пользователь видит лишь
            // бесполезное «Не удалось создать кампанию».
            toast.push(`${t('pm.createErr')}: ${describeError(e)}`)
          }
        }}
        pending={createPromo.isPending}
      />
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────
// PromoCard — карточка кампании
// ─────────────────────────────────────────────────────────────────────────

interface PromoCardProps {
  promo: Promo
  onOpen: () => void
  onToggle: () => void
  onArchive: () => void
  onRestore: () => void
}

function PromoCard({ promo, onOpen, onToggle, onArchive, onRestore }: PromoCardProps) {
  const t = useT()
  const photo = usePromoPhoto(promo)
  const isArchive = promo.status === 'archived'
  const unlimited = promo.budgetUnlimited === true
  // Название товара в нейтральном блоке нужно, только если оно говорит больше, чем название кампании.
  const showProductName =
    !!promo.productName && promo.productName.trim().toLowerCase() !== promo.title.trim().toLowerCase()

  // Outer = div role="button" (НЕ настоящий <button>) — иначе nested-button
  // невалиден по HTML и провоцирует баги. tabIndex+Enter/Space делают
  // его клавиатурно доступным.
  // Inner buttons останавливают propagation чтобы клик по ним не вызывал onOpen.
  const stop = (fn: () => void) => (e: React.MouseEvent) => {
    e.stopPropagation()
    fn()
  }
  const onKeyDownOuter = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault()
      onOpen()
    }
  }

  return (
    <div
      role="button"
      tabIndex={0}
      onClick={onOpen}
      onKeyDown={onKeyDownOuter}
      className="card-soft flex w-full min-w-0 cursor-pointer flex-col overflow-hidden text-left transition hover:shadow-elevated focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-green-600"
      data-testid={`promo-card-${promo.id}`}
      aria-label={`${t('pm.openDetails')} — ${promo.title}`}
    >
      {/* Превью товара в формате 3:4: фото кампании или товара из каталога (через image-прокси).
          Фото нет совсем: спокойный нейтральный блок с названием товара, без выдуманной картинки.
          Поверх — статус, бренд и название кампании. */}
      <div className="relative aspect-[3/4] w-full overflow-hidden bg-paper-input">
        {photo ? (
          <>
            <img
              src={photo}
              alt={promo.productName || promo.title}
              loading="lazy"
              className="absolute inset-0 h-full w-full object-cover"
            />
            {/* Скрим внизу — чтобы белый текст названия читался на любом фото. */}
            <div className="absolute inset-x-0 bottom-0 h-2/5 bg-gradient-to-t from-black/70 to-transparent" />
          </>
        ) : (
          <div
            className="absolute inset-0 flex flex-col items-center justify-center gap-2 px-5 pb-20 pt-12 text-center text-ink-400"
            data-testid={`promo-card-nophoto-${promo.id}`}
          >
            <IconBox size={30} />
            {showProductName && (
              <span className="line-clamp-3 break-words text-[13px] font-semibold leading-5 text-ink-500">
                {promo.productName}
              </span>
            )}
          </div>
        )}
        <div className="absolute left-3 top-3">
          <StatusChip status={promo.status} />
        </div>
        <div
          className={`absolute bottom-3 left-4 right-4 ${photo ? 'text-white' : 'text-ink-900'}`}
        >
          <div className="truncate text-[11px] font-bold uppercase tracking-[0.06em] opacity-80">
            {promo.brand}
          </div>
          <div
            className={`line-clamp-2 break-words text-[15px] font-extrabold leading-tight ${photo ? 'drop-shadow' : ''}`}
            title={promo.title}
          >
            {promo.title}
          </div>
        </div>
      </div>
      <div className="flex flex-1 flex-col gap-2.5 p-4">
        <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-0.5 text-[12px] font-semibold text-ink-500">
          <span className="whitespace-nowrap">{promo.period || '—'}</span>
          <span className="whitespace-nowrap">{t('pm.cardPharm', { n: promo.pharmacies })}</span>
        </div>
        {/* Бюджет: подпись над значением, а не в одной строке с ним. Значение переносится только
            между «расход» и «лимит», сами числа и «Без лимита бюджета» не ломаются. */}
        <div data-testid={`promo-card-budget-${promo.id}`}>
          <div className="text-[11px] font-bold uppercase tracking-[0.04em] text-ink-400">
            {t('pm.cardBudget')}
          </div>
          <div className="num mt-0.5 flex flex-wrap items-baseline gap-x-1.5 text-[13px] font-bold text-ink-900">
            <span className="whitespace-nowrap">{formatKzt(promo.spent)}</span>{' '}
            <span className="whitespace-nowrap text-ink-600">
              / {unlimited ? t('budget.unlimited') : formatKzt(promo.budget)}
            </span>
          </div>
          {!unlimited && promo.budget > 0 && (
            <div className="mt-1.5">
              <ProgressBar value={promo.spent} max={promo.budget} />
            </div>
          )}
        </div>
        {promo.kpi && (
          <p
            className="line-clamp-2 break-words text-[12px] font-semibold leading-4 text-ink-700"
            title={promo.kpi}
          >
            {promo.kpi}
          </p>
        )}
        <div className="mt-auto flex flex-wrap items-center justify-between gap-x-2 gap-y-1 pt-0.5">
          <span className="whitespace-nowrap text-[12px] font-bold text-brand-green-700">
            {t('pm.openDetails')}
          </span>
          <div className="flex flex-none items-center gap-1">
            {isArchive ? (
              <button
                type="button"
                onClick={stop(onRestore)}
                title={t('pm.restore')}
                aria-label={t('pm.restore')}
                className="flex h-7 items-center gap-1 rounded-md px-2 text-[11px] font-bold text-ink-500 hover:bg-paper-hover hover:text-brand-green-700"
              >
                <IconRefresh size={12} />
                {t('pm.restore')}
              </button>
            ) : (
              <>
                <button
                  type="button"
                  onClick={stop(onToggle)}
                  title={
                    promo.status === 'draft'
                      ? 'Проверить и запустить'
                      : promo.status === 'active'
                        ? t('pm.pause')
                        : t('pm.resume')
                  }
                  aria-label={
                    promo.status === 'draft'
                      ? 'Проверить и запустить'
                      : promo.status === 'active'
                        ? t('pm.pause')
                        : t('pm.resume')
                  }
                  className="flex h-7 w-7 items-center justify-center rounded-md text-ink-500 hover:bg-paper-hover hover:text-ink-900"
                >
                  {promo.status === 'active' ? <IconPause size={14} /> : <IconPlay size={14} />}
                </button>
                <button
                  type="button"
                  onClick={stop(onArchive)}
                  title={t('pd.archive')}
                  aria-label={t('pm.archive')}
                  className="flex h-7 w-7 items-center justify-center rounded-md text-ink-500 hover:bg-paper-hover hover:text-accent-danger"
                >
                  <IconArchive size={14} />
                </button>
              </>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────
// ViewModeToggle — сегментированный переключатель «сетка ↔ список»
// ─────────────────────────────────────────────────────────────────────────

interface ViewModeToggleProps {
  mode: ViewMode
  onChange: (mode: ViewMode) => void
}

function ViewModeToggle({ mode, onChange }: ViewModeToggleProps) {
  const t = useT()
  const items: { value: ViewMode; label: string; icon: React.ReactNode }[] = [
    { value: 'grid', label: t('pm.viewGrid'), icon: <IconGrid size={16} /> },
    { value: 'list', label: t('pm.viewList'), icon: <IconList size={16} /> },
  ]
  return (
    <div
      role="group"
      aria-label={t('pm.viewMode')}
      className="hairline inline-flex items-center gap-0.5 rounded-lg border bg-paper-input p-0.5"
    >
      {items.map((it) => {
        const active = mode === it.value
        return (
          <button
            key={it.value}
            type="button"
            onClick={() => onChange(it.value)}
            aria-pressed={active}
            title={it.label}
            data-testid={`promo-view-${it.value}`}
            className={
              active
                ? 'flex h-8 items-center gap-1.5 rounded-md bg-white px-2.5 text-[12px] font-bold text-ink-900 shadow-sm'
                : 'flex h-8 items-center gap-1.5 rounded-md px-2.5 text-[12px] font-bold text-ink-500 hover:text-ink-700'
            }
          >
            {it.icon}
            <span className="hidden sm:inline">{it.label}</span>
          </button>
        )
      })}
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────
// PromoListView — табличный режим (как реестр контрактов)
// ─────────────────────────────────────────────────────────────────────────

interface PromoListViewProps {
  promos: Promo[]
  onOpen: (p: Promo) => void
  onToggle: (p: Promo) => void
  onArchive: (p: Promo) => void
  onRestore: (p: Promo) => void
}

function PromoListView({ promos, onOpen, onToggle, onArchive, onRestore }: PromoListViewProps) {
  const t = useT()
  return (
    <div className="hairline overflow-x-auto rounded-lg border" data-testid="promo-list">
      <table className="w-full min-w-[760px] border-collapse text-left">
        <thead>
          <tr className="hairline border-b bg-paper-input text-[11px] font-bold uppercase tracking-[0.04em] text-ink-500">
            <th className="px-4 py-2.5 font-bold">{t('pm.colCampaign')}</th>
            <th className="px-4 py-2.5 font-bold">{t('pm.colStatus')}</th>
            <th className="px-4 py-2.5 font-bold">{t('pm.colPeriod')}</th>
            <th className="px-4 py-2.5 text-right font-bold">{t('pm.colPharm')}</th>
            <th className="px-4 py-2.5 font-bold">{t('pm.colBudget')}</th>
            <th className="px-4 py-2.5 text-right font-bold">{t('pm.colActions')}</th>
          </tr>
        </thead>
        <tbody>
          {promos.map((p) => (
            <PromoRow
              key={p.id}
              promo={p}
              onOpen={() => onOpen(p)}
              onToggle={() => onToggle(p)}
              onArchive={() => onArchive(p)}
              onRestore={() => onRestore(p)}
            />
          ))}
        </tbody>
      </table>
    </div>
  )
}

interface PromoRowProps {
  promo: Promo
  onOpen: () => void
  onToggle: () => void
  onArchive: () => void
  onRestore: () => void
}

function PromoRow({ promo, onOpen, onToggle, onArchive, onRestore }: PromoRowProps) {
  const t = useT()
  const photo = usePromoPhoto(promo)
  const isArchive = promo.status === 'archived'
  const stop = (fn: () => void) => (e: React.MouseEvent) => {
    e.stopPropagation()
    fn()
  }
  const onKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault()
      onOpen()
    }
  }
  return (
    <tr
      role="button"
      tabIndex={0}
      onClick={onOpen}
      onKeyDown={onKeyDown}
      className="hairline cursor-pointer border-b transition last:border-0 hover:bg-paper-hover focus:outline-none focus-visible:bg-paper-hover"
      data-testid={`promo-row-${promo.id}`}
      aria-label={`${t('pm.openDetails')} — ${promo.title}`}
    >
      {/* Кампания: мини-превью товара 3:4 + название + бренд */}
      <td className="px-4 py-3">
        <div className="flex items-center gap-3">
          <div className="hairline flex h-12 w-9 flex-none items-center justify-center overflow-hidden rounded-md border bg-paper-input text-ink-300">
            {photo ? (
              <img
                src={photo}
                alt={promo.productName || promo.title}
                loading="lazy"
                className="h-full w-full object-cover"
              />
            ) : (
              <IconBox size={16} />
            )}
          </div>
          <div className="min-w-0">
            <div className="truncate text-[13px] font-bold text-ink-900">{promo.title}</div>
            <div className="truncate text-[11px] font-semibold uppercase tracking-[0.04em] text-ink-500">
              {promo.brand || '—'}
            </div>
          </div>
        </div>
      </td>
      <td className="px-4 py-3">
        <StatusChip status={promo.status} />
      </td>
      <td className="px-4 py-3 text-[12px] font-semibold text-ink-700">{promo.period || '—'}</td>
      <td className="num px-4 py-3 text-right text-[12px] font-bold text-ink-700">
        {promo.pharmacies}
      </td>
      <td className="px-4 py-3">
        <div className="min-w-[150px]">
          <div className="mb-1 flex items-center justify-between gap-2 text-[12px] font-bold">
            <span className="num text-ink-900">
              {formatKzt(promo.spent)} / {promo.budgetUnlimited === true ? t('budget.unlimited') : formatKzt(promo.budget)}
            </span>
          </div>
          {promo.budgetUnlimited !== true && promo.budget > 0 && <ProgressBar value={promo.spent} max={promo.budget} />}
        </div>
      </td>
      <td className="px-4 py-3">
        <div className="flex items-center justify-end gap-1">
          {isArchive ? (
            <button
              type="button"
              onClick={stop(onRestore)}
              title={t('pm.restore')}
              aria-label={t('pm.restore')}
              className="flex h-7 items-center gap-1 rounded-md px-2 text-[11px] font-bold text-ink-500 hover:bg-paper-hover hover:text-brand-green-700"
            >
              <IconRefresh size={12} />
              {t('pm.restore')}
            </button>
          ) : (
            <>
              <button
                type="button"
                onClick={stop(onToggle)}
                title={
                  promo.status === 'draft'
                    ? 'Проверить и запустить'
                    : promo.status === 'active'
                      ? t('pm.pause')
                      : t('pm.resume')
                }
                aria-label={
                  promo.status === 'draft'
                    ? 'Проверить и запустить'
                    : promo.status === 'active'
                      ? t('pm.pause')
                      : t('pm.resume')
                }
                className="flex h-7 w-7 items-center justify-center rounded-md text-ink-500 hover:bg-paper-hover hover:text-ink-900"
              >
                {promo.status === 'active' ? <IconPause size={14} /> : <IconPlay size={14} />}
              </button>
              <button
                type="button"
                onClick={stop(onArchive)}
                title={t('pd.archive')}
                aria-label={t('pm.archive')}
                className="flex h-7 w-7 items-center justify-center rounded-md text-ink-500 hover:bg-paper-hover hover:text-accent-danger"
              >
                <IconArchive size={14} />
              </button>
            </>
          )}
        </div>
      </td>
    </tr>
  )
}
