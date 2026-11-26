// «Фармацевты в ePharm»: the first block of the Analytics page (owner, 26.11.2026). Open the page and
// see how many pharmacists use ePharm, and a chart to send to management. The numbers and the rule
// are those of the pharmacists «Покрытие» tab (GET /api/admin/pharmacists/coverage), not a second
// count: «в системе» is everyone except blocked, «работали» is a sale, a shown tip or a cabinet sign-in.

import { useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { Button, Empty, Metric } from '@/ui'
import { IconPharmacist } from '@/ui/icons'
import { describeError } from '@/lib/describeError'
import { usePharmacistCoverage } from '@/lib/queries/pharmacists'
import { useT } from '@/i18n'
import {
  CoverageChart,
  COVERAGE_ACCENT,
  COVERAGE_SECONDARY,
} from '@/features/pharmacists/CoverageChart'
import { saveReport } from '@/features/reports/ledgerReports'
import { renderChartPng } from './chartPng'
import {
  COVERAGE_SINCE_LAUNCH_DAYS,
  countRu,
  hasDailyCounts,
  percentRu,
  ruDate,
  sinceLaunch,
  usageFileName,
  usageNumbers,
  usagePoints,
} from './pharmacistUsage'

export function PharmacistsUsage() {
  const t = useT()
  const query = usePharmacistCoverage(COVERAGE_SINCE_LAUNCH_DAYS)
  const chartsRef = useRef<HTMLDivElement | null>(null)
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)
  const data = query.data

  const heading = (
    <div>
      <h2 className="text-[16px] font-bold text-ink-900">{t('usage.title')}</h2>
      <p className="mt-0.5 max-w-[720px] text-[13px] text-ink-500">{t('usage.subtitle')}</p>
    </div>
  )

  if (!data) {
    return (
      <section className="flex flex-col gap-3" data-testid="usage-block">
        {heading}
        <div role={query.isError ? 'alert' : 'status'} data-testid="usage-state">
          <Empty
            title={query.isError ? t('usage.errTitle') : t('usage.loading')}
            body={query.isError ? describeError(query.error) : undefined}
            icon={<IconPharmacist size={26} />}
            action={
              query.isError ? (
                <Button onClick={() => query.refetch()} disabled={query.isFetching}>
                  {t('common.retry')}
                </Button>
              ) : undefined
            }
          />
        </div>
      </section>
    )
  }

  const days = sinceLaunch(data.daily)
  const numbers = usageNumbers(data)
  const daily = hasDailyCounts(days)
  const points = usagePoints(days)
  const canChart = days.length >= 2
  const from = ruDate(days[0]?.date ?? data.date)
  const to = ruDate(days[days.length - 1]?.date ?? data.date)
  const todayText =
    numbers.today == null
      ? t('usage.unknown')
      : `${countRu(numbers.today)} (${percentRu(numbers.todayPct ?? 0)})`
  const weekText = `${countRu(numbers.week)} (${percentRu(numbers.weekPct)})`

  const download = async () => {
    const svgs = chartsRef.current?.querySelectorAll<SVGSVGElement>('svg[role="img"]')
    if (!svgs || svgs.length < 2) return
    setSaving(true)
    setSaveError(null)
    try {
      const blob = await renderChartPng({
        title: t('usage.title'),
        lines: [
          t('usage.period', { from, to }),
          t('usage.pngTotals', {
            total: countRu(numbers.total),
            today: todayText,
            week: weekText,
          }),
          t('usage.pngUpdated', { date: ruDate(data.date) }),
        ],
        charts: [
          { heading: t('usage.chartCount'), svg: svgs[0] },
          { heading: t('usage.chartShare'), svg: svgs[1] },
        ],
        footnote: t('usage.rule'),
      })
      saveReport(blob, usageFileName(data.date))
    } catch {
      setSaveError(t('usage.downloadErr'))
    } finally {
      setSaving(false)
    }
  }

  return (
    <section className="flex flex-col gap-4" data-testid="usage-block">
      <div className="flex flex-wrap items-start justify-between gap-3">
        {heading}
        <Button
          variant="outline"
          disabled={!canChart || saving}
          onClick={() => void download()}
          data-testid="usage-download"
        >
          {saving ? t('usage.preparing') : t('usage.download')}
        </Button>
      </div>

      {query.isError && (
        <div
          role="alert"
          className="hairline flex items-center gap-3 rounded-md border bg-accent-warning/10 px-4 py-2 text-[12px] font-semibold text-ink-700"
        >
          <span>{t('common.staleWarn', { e: describeError(query.error) })}</span>
          <Button
            size="sm"
            variant="outline"
            onClick={() => query.refetch()}
            disabled={query.isFetching}
          >
            {t('common.retry')}
          </Button>
        </div>
      )}
      {saveError && (
        <p role="alert" className="text-[12px] text-accent-danger">
          {saveError}
        </p>
      )}

      <div className="grid grid-cols-1 gap-3 sm:grid-cols-3" data-testid="usage-kpis">
        <Metric
          label={t('phc.mTotal')}
          value={countRu(numbers.total)}
          accent="ink"
          icon={<IconPharmacist size={18} />}
          meta={t('usage.metaTotal', {
            registered: countRu(numbers.registered),
            standardN: countRu(numbers.fromStandardN),
          })}
        />
        <Metric
          label={t('usage.mToday')}
          value={numbers.today == null ? t('usage.unknown') : countRu(numbers.today)}
          sub={numbers.today == null ? undefined : percentRu(numbers.todayPct ?? 0)}
          accent="green"
          meta={numbers.today == null ? undefined : t('usage.metaToday')}
        />
        <Metric
          label={t('phc.mActive')}
          value={countRu(numbers.week)}
          sub={percentRu(numbers.weekPct)}
          accent="amber"
          meta={t('usage.metaWeek')}
        />
      </div>

      {canChart ? (
        <div ref={chartsRef} className="flex flex-col gap-4">
          <div className="card p-4 sm:p-5">
            <div className="mb-3 flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
              <h3 className="text-[15px] font-bold text-ink-900">{t('usage.chartCount')}</h3>
              <span className="text-[12px] font-semibold text-ink-500" data-testid="usage-period">
                {t('usage.period', { from, to })}
              </span>
            </div>
            <CoverageChart
              title={t('usage.chartCountAria')}
              points={points}
              series={
                daily
                  ? [
                      { key: 'active1d', label: t('usage.sDay'), color: COVERAGE_ACCENT },
                      { key: 'active7d', label: t('usage.sWeek'), color: COVERAGE_SECONDARY },
                    ]
                  : [{ key: 'active7d', label: t('usage.sWeek'), color: COVERAGE_ACCENT }]
              }
            />
          </div>
          <div className="card p-4 sm:p-5">
            <h3 className="mb-3 text-[15px] font-bold text-ink-900">{t('usage.chartShare')}</h3>
            <CoverageChart
              title={t('usage.chartShareAria')}
              points={points}
              series={
                daily
                  ? [
                      {
                        key: 'coverage1dPct',
                        label: t('usage.sDay'),
                        color: COVERAGE_ACCENT,
                        format: percentRu,
                      },
                      {
                        key: 'coveragePct',
                        label: t('usage.sWeek'),
                        color: COVERAGE_SECONDARY,
                        format: percentRu,
                      },
                    ]
                  : [
                      {
                        key: 'coveragePct',
                        label: t('usage.sWeek'),
                        color: COVERAGE_ACCENT,
                        format: percentRu,
                      },
                    ]
              }
            />
          </div>
        </div>
      ) : (
        <p className="card p-4 text-[13px] text-ink-500" data-testid="usage-few-days">
          {t('usage.fewDays')}
        </p>
      )}

      <p className="text-[12px] text-ink-500" data-testid="usage-rule">
        {t('usage.rule')} {t('usage.source')}{' '}
        <Link
          className="font-semibold text-brand-green-700 underline"
          to="/pharmacists?view=coverage"
        >
          {t('usage.more')}
        </Link>
      </p>
    </section>
  )
}
