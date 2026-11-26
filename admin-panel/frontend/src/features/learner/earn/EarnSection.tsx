// Вкладка «Бонусы» кабинета фармацевта (Григорий, 26.11.2026): товары с бонусом, мои продажи, рейтинг.
// «Есть в моей аптеке» включён по умолчанию: читать о том, чего в аптеке нет, смысла нет.
import { useEffect, useState, type ReactNode } from 'react'
import {
  ChevronRight,
  Coins,
  Crown,
  Flame,
  Info,
  Loader2,
  Pill,
  Receipt,
  ShoppingBag,
  Sparkles,
  Store,
  Target,
  TrendingUp,
  Trophy,
  Users,
  X,
} from 'lucide-react'
import { proxyMedia } from '../../../lib/media'
import { useLearnerT } from '../learner-i18n'
import {
  LEVEL_NAMES,
  LEVEL_ORDER,
  type EarnFetch,
  type EarnLeaderboard,
  type EarnPeriod,
  type EarnProduct,
  type EarnProductDetail,
  type EarnProducts,
  type EarnScope,
  type EarnSort,
  type EarnSummary,
} from './earnTypes'

type Tab = 'products' | 'sales' | 'rating'

/** Cards shown at once: a phone scrolls through a dozen, not a hundred. */
const PAGE = 12

const money = (value: number) => `${Math.round(value).toLocaleString('ru-RU')} ₸`
const qty = (value: number) =>
  Number.isInteger(value)
    ? String(value)
    : value.toLocaleString('ru-RU', { maximumFractionDigits: 2 })

function useLoad<T>(fetchJson: EarnFetch, path: string) {
  const [state, setState] = useState<{ path: string; data: T | null; error: boolean }>({
    path: '',
    data: null,
    error: false,
  })
  const [attempt, setAttempt] = useState(0)
  useEffect(() => {
    let alive = true
    fetchJson<T>(path)
      .then((data) => alive && setState({ path, data, error: false }))
      .catch(() => alive && setState({ path, data: null, error: true }))
    return () => {
      alive = false
    }
  }, [fetchJson, path, attempt])
  const fresh = state.path === path
  return {
    data: fresh ? state.data : null,
    loading: !fresh,
    error: fresh && state.error,
    retry: () => setAttempt((n) => n + 1),
  }
}

export function EarnSection({ fetchJson }: { fetchJson: EarnFetch }) {
  const t = useLearnerT()
  const [tab, setTab] = useState<Tab>('products')
  const today = useLoad<EarnSummary>(fetchJson, '/api/mobile/me/earn/summary?period=today')
  return (
    <section className="space-y-4" aria-label={t('Бонусы')}>
      <StatusCard summary={today.data} loading={today.loading} />
      <div className="flex gap-1 rounded-2xl bg-white p-1 shadow-card" role="tablist">
        {(
          [
            ['products', t('Товары'), Pill],
            ['sales', t('Продажи'), Receipt],
            ['rating', t('Рейтинг'), Trophy],
          ] as const
        ).map(([id, label, Icon]) => (
          <button
            key={id}
            type="button"
            role="tab"
            aria-selected={tab === id}
            className={`flex min-w-0 flex-1 items-center justify-center gap-1.5 rounded-xl px-2 py-2.5 text-xs font-extrabold transition sm:text-sm ${tab === id ? 'bg-brand-green-600 text-white shadow-sm' : 'text-ink-600 hover:bg-paper-hover'}`}
            onClick={() => setTab(id)}
          >
            <Icon size={16} className="shrink-0" />
            <span className="truncate">{label}</span>
          </button>
        ))}
      </div>
      {tab === 'products' ? (
        <ProductsTab fetchJson={fetchJson} />
      ) : tab === 'sales' ? (
        <SalesTab fetchJson={fetchJson} />
      ) : (
        <RatingTab fetchJson={fetchJson} />
      )}
    </section>
  )
}

// ── шапка: сегодня, уровень, место ──────────────────────────────────────────

function StatusCard({ summary, loading }: { summary: EarnSummary | null; loading: boolean }) {
  const t = useLearnerT()
  const status = summary?.status
  const levelIndex = Math.max(0, LEVEL_ORDER.indexOf(status?.level ?? 'start'))
  const nextName = LEVEL_NAMES[LEVEL_ORDER[levelIndex + 1] ?? ''] ?? null
  const progress =
    status && status.nextLevelKzt
      ? Math.min(100, Math.round((status.monthEarnedKzt / status.nextLevelKzt) * 100))
      : 100
  return (
    <div className="relative overflow-hidden rounded-2xl bg-ink-900 p-5 text-white shadow-card sm:p-6">
      <div className="pointer-events-none absolute -right-16 -top-16 h-48 w-48 rounded-full bg-brand-green-500 opacity-25 blur-2xl" />
      <div className="relative flex flex-wrap items-start justify-between gap-4">
        <div className="min-w-0">
          <div className="text-[11px] font-extrabold uppercase tracking-[0.14em] text-white/55">
            {t('Бонус сегодня')}
          </div>
          <div className="mt-1 text-4xl font-black tracking-tight" data-testid="earn-today">
            {loading ? '…' : money(summary?.earnedKzt ?? 0)}
          </div>
          <div className="mt-2 text-sm font-bold text-white/90">
            {summary ? statusText(t, summary).title : ''}
          </div>
          <p className="mt-1 max-w-xl text-xs leading-5 text-white/65">
            {summary ? statusText(t, summary).message : ''}
          </p>
        </div>
        <div className="flex flex-wrap gap-2 sm:flex-col sm:items-end">
          <span className="inline-flex items-center gap-1.5 rounded-full bg-white/10 px-3 py-1.5 text-xs font-extrabold ring-1 ring-white/15">
            <Crown size={14} className="text-accent-amber" />
            {t(LEVEL_NAMES[status?.level ?? 'start'] ?? 'Старт')}
          </span>
          {summary?.weekPlace ? (
            <span className="inline-flex items-center gap-1.5 rounded-full bg-accent-amber/20 px-3 py-1.5 text-xs font-extrabold text-accent-amber">
              <Trophy size={14} />
              {t('{place}-е место за неделю', { place: summary.weekPlace })}
            </span>
          ) : null}
        </div>
      </div>
      <div className="relative mt-5">
        <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-0.5 text-[11px] font-bold text-white/60">
          <span>{t('Бонус за месяц: {sum}', { sum: money(status?.monthEarnedKzt ?? 0) })}</span>
          {nextName && status?.nextLevelKzt ? (
            <span>
              {t('До уровня «{level}»: {sum}', {
                level: t(nextName),
                sum: money(Math.max(0, status.nextLevelKzt - status.monthEarnedKzt)),
              })}
            </span>
          ) : (
            <span>{t('Высший уровень')}</span>
          )}
        </div>
        <div className="mt-1.5 h-2 overflow-hidden rounded-full bg-white/10">
          <div
            className="h-full rounded-full transition-all"
            style={{
              width: `${progress}%`,
              background: 'linear-gradient(90deg, var(--learner-accent-400, #46baaf), #F4B73A)',
            }}
          />
        </div>
      </div>
    </div>
  )
}

type Translate = ReturnType<typeof useLearnerT>

/** The status line is composed here, in the cabinet's language, from the numbers the server gives. */
function statusText(t: Translate, s: EarnSummary) {
  if (s.earnedKzt > 0)
    return {
      title: t('Хорошо идёте!'),
      message: t('Сегодня продаж по подсказкам: {count}, бонус {sum}.', {
        count: s.hintSales,
        sum: money(s.earnedKzt),
      }),
    }
  if (s.status.monthEarnedKzt > 0)
    return {
      title: t('Вы уже в игре'),
      message: t('В этом месяце бонус {sum}. Предложите товар с бонусом следующему покупателю.', {
        sum: money(s.status.monthEarnedKzt),
      }),
    }
  return {
    title: t('Начните с первой продажи'),
    message: t('Посмотрите товары с бонусом и предложите один из них, когда касса подскажет.'),
  }
}

// ── товары с бонусом ────────────────────────────────────────────────────────

function ProductsTab({ fetchJson }: { fetchJson: EarnFetch }) {
  const t = useLearnerT()
  const [mine, setMine] = useState(true)
  const [sort, setSort] = useState<EarnSort>('sales')
  const [open, setOpen] = useState<EarnProduct | null>(null)
  const [shown, setShown] = useState(PAGE)
  const list = useLoad<EarnProducts>(
    fetchJson,
    `/api/mobile/me/earn/products?sort=${sort}&mine=${mine}`,
  )
  const items = list.data?.items ?? []
  const maxSold = Math.max(1, ...(list.data?.items ?? []).map((item) => item.soldWeek))
  return (
    <div className="rounded-2xl bg-white p-4 shadow-card sm:p-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <label className="flex cursor-pointer items-start gap-3">
          <input
            type="checkbox"
            role="switch"
            className="peer sr-only"
            checked={mine}
            onChange={(event) => {
              setMine(event.target.checked)
              setShown(PAGE)
            }}
          />
          <span
            aria-hidden
            className={`relative mt-0.5 h-6 w-11 shrink-0 rounded-full transition ${mine ? 'bg-brand-green-600' : 'bg-ink-200'} peer-focus-visible:ring-2 peer-focus-visible:ring-ink-400`}
          >
            <span
              className={`absolute top-0.5 h-5 w-5 rounded-full bg-white shadow transition-all ${mine ? 'left-[22px]' : 'left-0.5'}`}
            />
          </span>
          <span>
            <span className="flex items-center gap-1.5 text-sm font-extrabold text-ink-900">
              <Store size={15} /> {t('Есть в моей аптеке')}
            </span>
            <span className="mt-0.5 block text-[11px] leading-4 text-ink-500">
              {t('По продажам вашей аптеки за {days} дней', {
                days: list.data?.availabilityDays ?? 30,
              })}
            </span>
          </span>
        </label>
        <div className="flex gap-1 rounded-xl bg-paper-hover p-1" aria-label={t('Сортировка')}>
          {(
            [
              ['sales', t('Чаще продают'), TrendingUp],
              ['bonus', t('Больше бонус'), Coins],
            ] as const
          ).map(([id, label, Icon]) => (
            <button
              key={id}
              type="button"
              aria-pressed={sort === id}
              className={`flex items-center gap-1 rounded-lg px-2.5 py-1.5 text-xs font-extrabold transition ${sort === id ? 'bg-white text-ink-900 shadow-sm' : 'text-ink-500 hover:text-ink-800'}`}
              onClick={() => {
                setSort(id)
                setShown(PAGE)
              }}
            >
              <Icon size={14} /> {label}
            </button>
          ))}
        </div>
      </div>

      {list.loading ? (
        <Loading text={t('Загружаем товары…')} />
      ) : list.error ? (
        <Failure onRetry={list.retry} />
      ) : items.length ? (
        <>
          <ul className="mt-4 grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-3">
            {items.slice(0, shown).map((item) => (
              <li key={item.productId}>
                <ProductCard item={item} maxSold={maxSold} onOpen={() => setOpen(item)} />
              </li>
            ))}
          </ul>
          {items.length > shown ? (
            <button
              type="button"
              className="mx-auto mt-4 flex items-center gap-1.5 rounded-xl border border-ink-200 px-4 py-2 text-xs font-extrabold text-ink-700 transition hover:bg-paper-hover"
              onClick={() => setShown((n) => n + PAGE)}
            >
              {t('Показать ещё ({count} из {total})', {
                count: Math.min(PAGE, items.length - shown),
                total: items.length,
              })}
            </button>
          ) : null}
        </>
      ) : (
        <Empty
          icon={<Store size={28} />}
          text={
            mine
              ? t(
                  'В вашей аптеке за 30 дней не было продаж товаров с бонусом. Выключите фильтр, чтобы увидеть все.',
                )
              : t('Сейчас нет товаров с бонусом.')
          }
        />
      )}
      <p className="mt-4 flex items-start gap-2 text-[11px] leading-4 text-ink-400">
        <Info size={14} className="mt-px shrink-0" />
        {t(
          'Бонус засчитывается за упаковку, проданную по подсказке кассы в том же чеке. Справочная информация не заменяет инструкцию и решение врача.',
        )}
      </p>
      {open ? (
        <ProductSheet fetchJson={fetchJson} product={open} onClose={() => setOpen(null)} />
      ) : null}
    </div>
  )
}

function ProductCard({
  item,
  maxSold,
  onOpen,
}: {
  item: EarnProduct
  maxSold: number
  onOpen: () => void
}) {
  const t = useLearnerT()
  return (
    <button
      type="button"
      onClick={onOpen}
      className="group flex h-full w-full gap-3 rounded-2xl border border-ink-100 bg-white p-3 text-left transition hover:-translate-y-0.5 hover:border-brand-green-200 hover:shadow-card"
      data-testid={`earn-product-${item.productId}`}
    >
      <div className="relative flex h-20 w-20 shrink-0 items-center justify-center overflow-hidden rounded-xl bg-paper-hover">
        <ProductImage url={item.imageUrl} size={26} className="p-1.5" />
        {item.hit ? (
          <span className="absolute left-1 top-1 inline-flex items-center gap-0.5 rounded-full bg-accent-danger px-1.5 py-0.5 text-[9px] font-black uppercase text-white">
            <Flame size={10} /> {t('Хит')}
          </span>
        ) : null}
      </div>
      <div className="min-w-0 flex-1">
        <div className="flex items-start justify-between gap-2">
          <div className="line-clamp-2 text-sm font-extrabold leading-5 text-ink-900">
            {item.name}
          </div>
          <span className="shrink-0 rounded-lg bg-recommendation-green px-2 py-1 text-xs font-black text-white">
            +{money(item.bonusKzt)}
          </span>
        </div>
        <div className="mt-0.5 truncate text-[11px] text-ink-500">
          {[item.brand, item.volume].filter(Boolean).join(' · ')}
        </div>
        <div className="mt-2 flex items-center gap-2">
          <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-ink-100">
            <div
              className="h-full rounded-full bg-brand-green-500"
              style={{
                width: `${Math.max(item.soldWeek ? 6 : 0, Math.round((item.soldWeek / maxSold) * 100))}%`,
              }}
            />
          </div>
          <span className="shrink-0 text-[11px] font-bold text-ink-500">
            {t('{count} уп. за неделю', { count: item.soldWeek })}
          </span>
        </div>
        <div className="mt-2 flex items-center justify-between">
          <span className="flex gap-1">
            {item.kinds.map((kind) => (
              <span key={kind} className="chip chip-green text-[10px]">
                {kind === 'crosssell' ? t('Допродажа') : t('Замена')}
              </span>
            ))}
          </span>
          <ChevronRight size={16} className="text-ink-300 transition group-hover:translate-x-0.5" />
        </div>
      </div>
    </button>
  )
}

function ProductSheet({
  fetchJson,
  product,
  onClose,
}: {
  fetchJson: EarnFetch
  product: EarnProduct
  onClose: () => void
}) {
  const t = useLearnerT()
  const detail = useLoad<EarnProductDetail>(
    fetchJson,
    `/api/mobile/me/earn/products/${encodeURIComponent(product.productId)}`,
  )
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => event.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])
  const d = detail.data
  const image = d?.product.imageUrl ?? product.imageUrl
  return (
    <div
      className="fixed inset-0 z-50 flex items-end justify-center bg-ink-900/50 p-0 sm:items-center sm:p-6"
      role="dialog"
      aria-modal="true"
      aria-label={product.name}
      onClick={onClose}
    >
      <div
        className="max-h-[92vh] w-full max-w-2xl overflow-y-auto rounded-t-3xl bg-white p-5 shadow-2xl sm:rounded-3xl sm:p-6"
        onClick={(event) => event.stopPropagation()}
      >
        <div className="flex items-start gap-4">
          <div className="flex h-24 w-24 shrink-0 items-center justify-center overflow-hidden rounded-2xl bg-paper-hover">
            <ProductImage url={image} size={30} className="p-2" />
          </div>
          <div className="min-w-0 flex-1">
            <h3 className="text-lg font-black leading-6 text-ink-900">{product.name}</h3>
            <div className="mt-1 text-xs text-ink-500">
              {[product.brand, product.mnn, product.volume].filter(Boolean).join(' · ')}
            </div>
            <div className="mt-2 flex flex-wrap items-center gap-1.5">
              <span className="rounded-lg bg-recommendation-green px-2 py-1 text-xs font-black text-white">
                {t('Бонус +{sum} за упаковку', { sum: money(product.bonusKzt) })}
              </span>
              {d?.rxOtc ? (
                <span className="chip text-[10px]">
                  {d.rxOtc === 'rx' ? t('По рецепту') : t('Без рецепта')}
                </span>
              ) : null}
            </div>
          </div>
          <button
            type="button"
            className="rounded-full p-2 text-ink-400 transition hover:bg-paper-hover hover:text-ink-800"
            onClick={onClose}
            aria-label={t('Закрыть')}
          >
            <X size={20} />
          </button>
        </div>

        {detail.loading ? (
          <Loading text={t('Загружаем карточку…')} />
        ) : detail.error ? (
          <Failure onRetry={detail.retry} />
        ) : d ? (
          <div className="mt-5 space-y-5 text-sm leading-6 text-ink-700">
            {d.whenAsked.length ? (
              <Block icon={<Target size={16} />} title={t('Когда предлагать')}>
                <p>{t('Когда покупатель просит:')}</p>
                <ul className="mt-1 flex flex-wrap gap-1.5">
                  {d.whenAsked.map((name) => (
                    <li
                      key={name}
                      className="rounded-lg bg-paper-hover px-2 py-1 text-xs font-bold text-ink-700"
                    >
                      {name}
                    </li>
                  ))}
                </ul>
              </Block>
            ) : null}
            {d.scripts.length ? (
              <Block icon={<Sparkles size={16} />} title={t('Что сказать покупателю')}>
                {d.scripts.map((script) => (
                  <p
                    key={script}
                    className="rounded-xl bg-brand-green-50 px-3 py-2 font-semibold text-ink-800"
                  >
                    «{script}»
                  </p>
                ))}
              </Block>
            ) : null}
            {d.description ? (
              <Block icon={<Pill size={16} />} title={t('Что это за препарат')}>
                <p className="whitespace-pre-line">{d.description}</p>
              </Block>
            ) : null}
            {d.keyFacts.length ? (
              <Block icon={<Info size={16} />} title={t('Главное о товаре')}>
                <ul className="list-disc space-y-1 pl-5">
                  {d.keyFacts.map((fact) => (
                    <li key={fact}>{fact}</li>
                  ))}
                </ul>
              </Block>
            ) : null}
            {d.advantages.length ? (
              <Block icon={<TrendingUp size={16} />} title={t('Преимущества')}>
                <ul className="list-disc space-y-1 pl-5">
                  {d.advantages.map((item) => (
                    <li key={item}>{item}</li>
                  ))}
                </ul>
              </Block>
            ) : null}
            {d.qa.length ? (
              <Block icon={<Info size={16} />} title={t('Вопросы и ответы')}>
                <dl className="space-y-2">
                  {d.qa.map((item) => (
                    <div key={item.q}>
                      <dt className="font-extrabold text-ink-900">{item.q}</dt>
                      <dd>{item.a}</dd>
                    </div>
                  ))}
                </dl>
              </Block>
            ) : null}
            {product.campaignEndsOn ? (
              <p className="text-xs text-ink-400">
                {t('Акция до {date}', {
                  date: new Date(product.campaignEndsOn).toLocaleDateString('ru-RU'),
                })}
              </p>
            ) : null}
            <p className="text-[11px] leading-4 text-ink-400">
              {t('Справочная информация. Перед применением прочитайте инструкцию.')}
            </p>
          </div>
        ) : null}
      </div>
    </div>
  )
}

/** A product photo; a missing or dead link shows a pill instead of a broken image. */
function ProductImage({
  url,
  size,
  className,
}: {
  url: string | null
  size: number
  className: string
}) {
  const [failed, setFailed] = useState(false)
  const src = url && !failed ? proxyMedia(url) : ''
  return src ? (
    <img
      src={src}
      alt=""
      loading="lazy"
      className={`h-full w-full object-contain ${className}`}
      onError={() => setFailed(true)}
    />
  ) : (
    <Pill size={size} className="text-ink-300" />
  )
}

function Block({ icon, title, children }: { icon: ReactNode; title: string; children: ReactNode }) {
  return (
    <div>
      <h4 className="mb-1.5 flex items-center gap-1.5 text-sm font-black text-ink-900">
        <span className="text-brand-green-600">{icon}</span> {title}
      </h4>
      {children}
    </div>
  )
}

// ── мои продажи ─────────────────────────────────────────────────────────────

function PeriodSwitch({
  value,
  onChange,
}: {
  value: EarnPeriod
  onChange: (p: EarnPeriod) => void
}) {
  const t = useLearnerT()
  return (
    <div className="flex gap-1 rounded-xl bg-paper-hover p-1">
      {(
        [
          ['today', t('Сегодня')],
          ['week', t('Неделя')],
          ['month', t('Месяц')],
        ] as const
      ).map(([id, label]) => (
        <button
          key={id}
          type="button"
          aria-pressed={value === id}
          className={`rounded-lg px-3 py-1.5 text-xs font-extrabold transition ${value === id ? 'bg-white text-ink-900 shadow-sm' : 'text-ink-500 hover:text-ink-800'}`}
          onClick={() => onChange(id)}
        >
          {label}
        </button>
      ))}
    </div>
  )
}

function SalesTab({ fetchJson }: { fetchJson: EarnFetch }) {
  const t = useLearnerT()
  const [period, setPeriod] = useState<EarnPeriod>('today')
  const summary = useLoad<EarnSummary>(fetchJson, `/api/mobile/me/earn/summary?period=${period}`)
  const s = summary.data
  return (
    <div className="rounded-2xl bg-white p-4 shadow-card sm:p-5">
      <PeriodSwitch value={period} onChange={setPeriod} />
      {summary.loading ? (
        <Loading text={t('Загружаем продажи…')} />
      ) : summary.error ? (
        <Failure onRetry={summary.retry} />
      ) : s ? (
        <>
          <div className="mt-4 grid grid-cols-2 gap-2 sm:grid-cols-4">
            <Tile label={t('Бонус по подсказкам')} value={money(s.earnedKzt)} accent />
            <Tile label={t('Продаж по подсказкам')} value={String(s.hintSales)} />
            <Tile label={t('Чеков')} value={String(s.receipts)} />
            <Tile label={t('Упаковок')} value={qty(s.units)} />
          </div>
          <h3 className="mt-6 flex items-center gap-1.5 text-sm font-black text-ink-900">
            <Coins size={16} className="text-brand-green-600" /> {t('Продажи по подсказкам')}
          </h3>
          {s.recentHintSales.length ? (
            <ul className="mt-2 divide-y divide-ink-100">
              {s.recentHintSales.map((sale, index) => (
                <li
                  key={`${sale.at}-${index}`}
                  className="flex items-center justify-between gap-3 py-2 text-sm"
                >
                  <span className="min-w-0">
                    <span className="block truncate font-bold text-ink-800">
                      {sale.productName}
                    </span>
                    <span className="text-[11px] text-ink-400">
                      {new Date(sale.at).toLocaleString('ru-RU', {
                        day: '2-digit',
                        month: '2-digit',
                        hour: '2-digit',
                        minute: '2-digit',
                      })}
                    </span>
                  </span>
                  <span className="shrink-0 font-black text-recommendation-green">
                    +{money(sale.bonusKzt)}
                  </span>
                </li>
              ))}
            </ul>
          ) : (
            <p className="mt-2 rounded-xl bg-paper-hover px-3 py-3 text-xs text-ink-500">
              {t('Пока нет. Когда касса подскажет товар с бонусом, предложите его покупателю.')}
            </p>
          )}
          <h3 className="mt-6 flex items-center gap-1.5 text-sm font-black text-ink-900">
            <ShoppingBag size={16} className="text-brand-green-600" /> {t('Что вы продали')}
          </h3>
          {s.products.length ? (
            <ul className="mt-2 divide-y divide-ink-100">
              {s.products.map((item) => (
                <li
                  key={item.name}
                  className="flex items-center justify-between gap-3 py-2 text-sm"
                >
                  <span className="flex min-w-0 items-center gap-2">
                    <span className="truncate text-ink-800">{item.name}</span>
                    {item.promo ? (
                      <span className="chip chip-green shrink-0 text-[10px]">{t('с бонусом')}</span>
                    ) : null}
                  </span>
                  <span className="shrink-0 text-right">
                    <span className="block font-extrabold text-ink-900">
                      {t('{count} шт.', { count: qty(item.quantity) })}
                    </span>
                    <span className="text-[11px] text-ink-400">{money(item.sumKzt)}</span>
                  </span>
                </li>
              ))}
            </ul>
          ) : (
            <p className="mt-2 rounded-xl bg-paper-hover px-3 py-3 text-xs text-ink-500">
              {t('За этот период ваших чеков нет.')}
            </p>
          )}
        </>
      ) : null}
    </div>
  )
}

function Tile({
  label,
  value,
  accent = false,
}: {
  label: string
  value: string
  accent?: boolean
}) {
  return (
    <div className={`rounded-xl px-3 py-2.5 ${accent ? 'bg-brand-green-50' : 'bg-paper-hover'}`}>
      <div className="text-[11px] font-bold text-ink-500">{label}</div>
      <div
        className={`mt-0.5 text-lg font-black ${accent ? 'text-brand-green-700' : 'text-ink-900'}`}
      >
        {value}
      </div>
    </div>
  )
}

// ── рейтинг ─────────────────────────────────────────────────────────────────

const MEDALS = ['#F4B73A', '#B8BCC6', '#CD8A4E']

function RatingTab({ fetchJson }: { fetchJson: EarnFetch }) {
  const t = useLearnerT()
  const [scope, setScope] = useState<EarnScope>('pharmacists')
  const [period, setPeriod] = useState<EarnPeriod>('week')
  const board = useLoad<EarnLeaderboard>(
    fetchJson,
    `/api/mobile/me/earn/leaderboard?period=${period}&scope=${scope}`,
  )
  const rows = board.data ? [...board.data.items, ...(board.data.me ? [board.data.me] : [])] : []
  return (
    <div className="rounded-2xl bg-white p-4 shadow-card sm:p-5">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex gap-1 rounded-xl bg-paper-hover p-1">
          {(
            [
              ['pharmacists', t('Фармацевты'), Users],
              ['pharmacies', t('Аптеки'), Store],
            ] as const
          ).map(([id, label, Icon]) => (
            <button
              key={id}
              type="button"
              aria-pressed={scope === id}
              className={`flex items-center gap-1 rounded-lg px-3 py-1.5 text-xs font-extrabold transition ${scope === id ? 'bg-white text-ink-900 shadow-sm' : 'text-ink-500 hover:text-ink-800'}`}
              onClick={() => setScope(id)}
            >
              <Icon size={14} /> {label}
            </button>
          ))}
        </div>
        <PeriodSwitch value={period} onChange={setPeriod} />
      </div>
      <p className="mt-3 text-[11px] text-ink-500">
        {t('По бонусу за продажи по подсказкам кассы, по всему Казахстану')}
      </p>
      {board.loading ? (
        <Loading text={t('Загружаем рейтинг…')} />
      ) : board.error ? (
        <Failure onRetry={board.retry} />
      ) : rows.length ? (
        <ol className="mt-3 space-y-1.5">
          {rows.map((row, index) => (
            <li key={`${row.place}-${row.name}`}>
              {board.data?.me && index === board.data.items.length ? (
                <div className="py-1 text-center text-xs font-black text-ink-300">···</div>
              ) : null}
              <div
                className={`flex items-center gap-3 rounded-xl px-3 py-2.5 ${row.me ? 'bg-brand-green-50 ring-1 ring-brand-green-200' : 'bg-paper-hover'}`}
                data-testid={row.me ? 'earn-leader-me' : undefined}
              >
                <span
                  className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full text-xs font-black"
                  style={
                    row.place <= 3
                      ? { background: MEDALS[row.place - 1], color: '#fff' }
                      : { background: '#fff', color: '#4D4A45' }
                  }
                >
                  {row.place}
                </span>
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-sm font-extrabold text-ink-900">
                    {row.name}
                    {row.me ? (
                      <span className="ml-1.5 text-xs font-black text-brand-green-700">
                        {t('Вы')}
                      </span>
                    ) : null}
                  </span>
                  <span className="block truncate text-[11px] text-ink-500">
                    {[row.pharmacy, row.city].filter(Boolean).join(' · ')}
                  </span>
                </span>
                <span className="shrink-0 text-right">
                  <span className="block text-sm font-black text-ink-900">
                    {money(row.earnedKzt)}
                  </span>
                  <span className="text-[11px] text-ink-400">
                    {t('{count} продаж', { count: row.sales })}
                  </span>
                </span>
              </div>
            </li>
          ))}
        </ol>
      ) : (
        <Empty
          icon={<Trophy size={28} />}
          text={t('За этот период ещё никто не продал по подсказке. Станьте первым!')}
        />
      )}
    </div>
  )
}

// ── общие ───────────────────────────────────────────────────────────────────

function Loading({ text }: { text: string }) {
  return (
    <div
      className="flex min-h-32 items-center justify-center gap-2 text-sm font-bold text-ink-500"
      role="status"
    >
      <Loader2 className="animate-spin" size={18} /> {text}
    </div>
  )
}

function Failure({ onRetry }: { onRetry: () => void }) {
  const t = useLearnerT()
  return (
    <div className="py-8 text-center" role="status">
      <p className="text-sm font-bold text-accent-danger">
        {t('Не удалось загрузить. Проверьте интернет.')}
      </p>
      <button
        type="button"
        className="mt-3 rounded-lg border border-ink-200 px-3 py-1.5 text-xs font-extrabold text-ink-700 hover:bg-paper-hover"
        onClick={onRetry}
      >
        {t('Повторить')}
      </button>
    </div>
  )
}

function Empty({ icon, text }: { icon: ReactNode; text: string }) {
  return (
    <div className="mt-4 flex min-h-32 flex-col items-center justify-center rounded-xl bg-paper-hover px-5 py-8 text-center">
      <span className="text-ink-300">{icon}</span>
      <p className="mt-3 max-w-md text-sm font-bold text-ink-600">{text}</p>
    </div>
  )
}
