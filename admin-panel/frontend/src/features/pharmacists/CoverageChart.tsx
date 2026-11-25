import { useEffect, useRef, useState } from 'react'

export type CoveragePoint = { date: string; values: Record<string, number> }
export type CoverageSeries = {
  key: string
  label: string
  /** CSS colour of the mark; text never takes it (labels stay in ink). */
  color: string
  /** A reference line (the total) is dashed and quiet; a data series is solid. */
  reference?: boolean
  format?: (value: number) => string
}

const HEIGHT = 220
const PAD = { top: 14, right: 112, bottom: 26, left: 40 }

function useWidth<T extends HTMLElement>() {
  const ref = useRef<T | null>(null)
  const [width, setWidth] = useState(640)
  useEffect(() => {
    const element = ref.current
    if (!element || typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(([entry]) =>
      setWidth(Math.max(280, entry.contentRect.width)),
    )
    observer.observe(element)
    return () => observer.disconnect()
  }, [])
  return [ref, width] as const
}

function niceMax(value: number): number {
  if (value <= 0) return 1
  const power = 10 ** Math.floor(Math.log10(value))
  const step = [1, 2, 2.5, 5, 10].find((candidate) => candidate * power >= value / 4) ?? 10
  return Math.ceil(value / (step * power)) * step * power
}

const dayLabel = (iso: string) => {
  const [, month, day] = iso.split('-')
  return `${day}.${month}`
}

/**
 * One measure per chart (no second axis): a line per series with its name and last value at the
 * right end, a recessive grid, and a crosshair that snaps to the nearest day with every series in
 * one tooltip. The table under the charts carries the same numbers without hovering.
 */
export function CoverageChart({
  points,
  series,
  max,
  title,
}: {
  points: CoveragePoint[]
  series: CoverageSeries[]
  /** Fixed top of the scale (100 for percents); otherwise a round number above the data. */
  max?: number
  title: string
}) {
  const [ref, width] = useWidth<HTMLDivElement>()
  const [hover, setHover] = useState<number | null>(null)
  const plotWidth = width - PAD.left - PAD.right
  const plotHeight = HEIGHT - PAD.top - PAD.bottom
  const top =
    max ??
    niceMax(Math.max(1, ...points.flatMap((point) => series.map((s) => point.values[s.key] ?? 0))))
  const x = (index: number) =>
    PAD.left + (points.length <= 1 ? plotWidth / 2 : (index / (points.length - 1)) * plotWidth)
  const y = (value: number) => PAD.top + plotHeight - (value / top) * plotHeight
  const ticks = [0, 0.25, 0.5, 0.75, 1].map((share) => top * share)
  const labelEvery = Math.max(1, Math.ceil(points.length / Math.max(2, Math.floor(plotWidth / 64))))
  const fmt = (s: CoverageSeries, value: number) =>
    s.format ? s.format(value) : value.toLocaleString('ru-RU')
  const last = points.length - 1

  const onMove = (event: React.PointerEvent<SVGRectElement>) => {
    const box = event.currentTarget.getBoundingClientRect()
    const relative = (event.clientX - box.left) / Math.max(1, box.width)
    setHover(Math.round(Math.min(1, Math.max(0, relative)) * Math.max(0, points.length - 1)))
  }

  return (
    <div ref={ref} className="relative w-full">
      <svg
        width={width}
        height={HEIGHT}
        role="img"
        aria-label={title}
        className="block overflow-visible"
      >
        {ticks.map((tick) => (
          <g key={tick}>
            <line
              x1={PAD.left}
              x2={PAD.left + plotWidth}
              y1={y(tick)}
              y2={y(tick)}
              stroke="#ECECE9"
              strokeWidth={1}
            />
            <text x={PAD.left - 8} y={y(tick) + 4} textAnchor="end" fontSize={11} fill="#8A8A84">
              {series[0]?.format
                ? series[0].format(tick)
                : Math.round(tick).toLocaleString('ru-RU')}
            </text>
          </g>
        ))}
        {points.map((point, index) =>
          index % labelEvery === 0 || index === last ? (
            <text
              key={point.date}
              x={x(index)}
              y={HEIGHT - 6}
              textAnchor="middle"
              fontSize={11}
              fill="#8A8A84"
            >
              {dayLabel(point.date)}
            </text>
          ) : null,
        )}
        {series.map((s) => {
          const path = points
            .map(
              (point, index) =>
                `${index === 0 ? 'M' : 'L'}${x(index).toFixed(1)},${y(point.values[s.key] ?? 0).toFixed(1)}`,
            )
            .join(' ')
          const lastValue = points[last]?.values[s.key] ?? 0
          return (
            <g key={s.key}>
              <path
                d={path}
                fill="none"
                stroke={s.color}
                strokeWidth={2}
                strokeDasharray={s.reference ? '5 4' : undefined}
                strokeLinejoin="round"
                strokeLinecap="round"
              />
              {points.length > 0 && (
                <>
                  {!s.reference && (
                    <circle
                      cx={x(last)}
                      cy={y(lastValue)}
                      r={4}
                      fill={s.color}
                      stroke="#fff"
                      strokeWidth={2}
                    />
                  )}
                  <text
                    x={x(last) + 8}
                    y={y(lastValue) + 4}
                    fontSize={12}
                    fontWeight={700}
                    fill="#2B2B28"
                  >
                    {fmt(s, lastValue)}
                    <tspan fontWeight={500} fill="#6B6B65">{` ${s.label}`}</tspan>
                  </text>
                </>
              )}
            </g>
          )
        })}
        {hover !== null && points[hover] && (
          <line
            x1={x(hover)}
            x2={x(hover)}
            y1={PAD.top}
            y2={PAD.top + plotHeight}
            stroke="#B8B8B2"
            strokeWidth={1}
          />
        )}
        <rect
          x={PAD.left}
          y={PAD.top}
          width={Math.max(1, plotWidth)}
          height={plotHeight}
          fill="transparent"
          onPointerMove={onMove}
          onPointerLeave={() => setHover(null)}
        />
      </svg>
      {hover !== null && points[hover] && (
        <div
          className="pointer-events-none absolute z-10 min-w-[150px] rounded-lg border border-ink-100 bg-white px-3 py-2 text-[12px] shadow-lg"
          style={{ left: Math.min(width - 170, Math.max(0, x(hover) + 10)), top: 8 }}
        >
          <div className="mb-1 font-semibold text-ink-500">{dayLabel(points[hover].date)}</div>
          {series.map((s) => (
            <div key={s.key} className="flex items-center gap-2">
              <span
                className="inline-block h-0.5 w-3"
                style={{
                  background: s.color,
                  borderTop: s.reference ? `2px dashed ${s.color}` : undefined,
                }}
              />
              <strong className="text-ink-900">{fmt(s, points[hover].values[s.key] ?? 0)}</strong>
              <span className="text-ink-500">{s.label}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
