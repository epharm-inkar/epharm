import { useState } from 'react'
import { Button, Empty, Metric } from '@/ui'
import { IconPharmacist } from '@/ui/icons'
import { describeError } from '@/lib/describeError'
import { usePharmacistCoverage } from '@/lib/queries/pharmacists'
import { CoverageChart } from './CoverageChart'

/** The admin accent (brand terracotta) for the data line; the total is a quiet dashed reference. */
const ACCENT = '#B95336'
const REFERENCE = '#8A8A84'

const percent = (value: number) => `${value.toLocaleString('ru-RU', { maximumFractionDigits: 1 })}%`

/**
 * «Покрытие фармацевтов» (Grigoriy, 25.11.2026): how many pharmacists are in the system, how many
 * worked with ePharm in the last seven days, the share, and how both move by date.
 */
export function CoverageView() {
  const [days, setDays] = useState(30)
  const query = usePharmacistCoverage(days)
  const data = query.data

  if (query.isLoading)
    return <div className="card p-6 text-[13px] text-ink-500">Считаем покрытие…</div>
  if (query.isError || !data)
    return (
      <Empty
        title="Не удалось посчитать покрытие"
        body={describeError(query.error)}
        icon={<IconPharmacist size={28} />}
        action={<Button onClick={() => query.refetch()}>Повторить</Button>}
      />
    )

  const points = data.daily.map((day) => ({
    date: day.date,
    values: {
      total: day.total,
      active7d: day.active7d,
      sellers7d: day.sellers7d,
      coveragePct: day.coveragePct,
    },
  }))
  const first = data.daily[0]
  const growth = first ? data.active7d - first.active7d : 0

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <Metric
          label="Фармацевтов в системе"
          value={data.total.toLocaleString('ru-RU')}
          accent="ink"
          sub={`сами ${data.registered} · Стандарт-Н ${data.fromStandardN}`}
        />
        <Metric
          label="Работали с ePharm за 7 дней"
          value={data.active7d.toLocaleString('ru-RU')}
          accent="green"
          sub={`${growth >= 0 ? '+' : ''}${growth} за ${data.daily.length} дн.`}
        />
        <Metric
          label="Покрытие"
          value={percent(data.coveragePct)}
          accent="purple"
          sub="работали / в системе"
        />
        <Metric
          label="Продавали через ePharm за 7 дней"
          value={data.sellers7d.toLocaleString('ru-RU')}
          accent="amber"
          sub={`в кабинет входили ${data.cabinet7d}`}
        />
      </div>

      <div className="card p-4 sm:p-5">
        <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
          <div>
            <h2 className="text-[15px] font-bold text-ink-900">
              Фармацевты: в системе и работали с ePharm
            </h2>
            <p className="text-[12px] text-ink-500">
              «Работали» за 7 дней до каждой даты: продажа на кассе с ePharm, показанная подсказка
              или вход в кабинет.
            </p>
          </div>
          <div className="flex gap-1" role="group" aria-label="Период">
            {[30, 60, 90].map((value) => (
              <button
                key={value}
                type="button"
                aria-pressed={days === value}
                className={`rounded-md px-2.5 py-1 text-[12px] font-semibold ${days === value ? 'bg-ink-900 text-white' : 'bg-paper-hover text-ink-600 hover:bg-ink-100'}`}
                onClick={() => setDays(value)}
              >
                {value} дней
              </button>
            ))}
          </div>
        </div>
        <CoverageChart
          title="Фармацевты в системе и работавшие с ePharm за 7 дней, по дням"
          points={points}
          series={[
            { key: 'total', label: 'в системе', color: REFERENCE, reference: true },
            { key: 'active7d', label: 'работали', color: ACCENT },
          ]}
        />
      </div>

      <div className="card p-4 sm:p-5">
        <h2 className="text-[15px] font-bold text-ink-900">Покрытие, % от фармацевтов в системе</h2>
        <p className="mb-3 text-[12px] text-ink-500">
          Доля тех, кто работал с ePharm за 7 дней до даты.
        </p>
        <CoverageChart
          title="Покрытие в процентах по дням"
          points={points}
          max={100}
          series={[{ key: 'coveragePct', label: 'покрытие', color: ACCENT, format: percent }]}
        />
      </div>

      <details className="card p-4 sm:p-5">
        <summary className="cursor-pointer text-[13px] font-semibold text-ink-700">
          Таблица по дням
        </summary>
        <div className="mt-3 overflow-x-auto">
          <table className="w-full min-w-[520px] text-[12.5px]">
            <thead className="text-left text-ink-500">
              <tr>
                <th className="py-1.5 pr-3 font-semibold">Дата</th>
                <th className="py-1.5 pr-3 text-right font-semibold">В системе</th>
                <th className="py-1.5 pr-3 text-right font-semibold">Новые</th>
                <th className="py-1.5 pr-3 text-right font-semibold">Работали 7 дн.</th>
                <th className="py-1.5 pr-3 text-right font-semibold">Продавали 7 дн.</th>
                <th className="py-1.5 text-right font-semibold">Покрытие</th>
              </tr>
            </thead>
            <tbody>
              {[...data.daily].reverse().map((day) => (
                <tr key={day.date} className="border-t border-ink-100">
                  <td className="py-1.5 pr-3">{day.date.split('-').reverse().join('.')}</td>
                  <td className="num py-1.5 pr-3 text-right">{day.total}</td>
                  <td className="num py-1.5 pr-3 text-right">{day.joined || ''}</td>
                  <td className="num py-1.5 pr-3 text-right">{day.active7d}</td>
                  <td className="num py-1.5 pr-3 text-right">{day.sellers7d}</td>
                  <td className="num py-1.5 text-right">{percent(day.coveragePct)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </details>
    </div>
  )
}
