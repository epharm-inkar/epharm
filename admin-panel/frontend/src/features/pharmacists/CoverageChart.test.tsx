import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { CoverageChart, type CoveragePoint } from './CoverageChart'

function days(count: number): CoveragePoint[] {
  const start = Date.UTC(2026, 10, 1)
  return Array.from({ length: count }, (_, index) => ({
    date: new Date(start + index * 86_400_000).toISOString().slice(0, 10),
    values: { n: index },
  }))
}

/** The dates written under the plot (the only text anchored at its middle). */
function axisLabels() {
  const chart = screen.getByRole('img')
  return Array.from(chart.querySelectorAll('text[text-anchor="middle"]')).map(
    (node) => node.textContent,
  )
}

describe('CoverageChart axis', () => {
  it('names the first and the last day', () => {
    render(
      <CoverageChart
        title="Ряд"
        points={days(22)}
        series={[{ key: 'n', label: 'n', color: '#000' }]}
      />,
    )
    const labels = axisLabels()
    expect(labels[0]).toBe('01.11')
    expect(labels.at(-1)).toBe('22.11')
  })

  it('does not print a regular label over the last one when they are a day or two apart', () => {
    // 22 days: a label every 4th, so the 21st day (index 20) is regular and the 22nd is the last.
    render(
      <CoverageChart
        title="Ряд"
        points={days(22)}
        series={[{ key: 'n', label: 'n', color: '#000' }]}
      />,
    )
    const labels = axisLabels()
    expect(labels).not.toContain('21.11')
    expect(labels.at(-1)).toBe('22.11')
    // Nothing else is lost: the labels before it are still there.
    expect(labels).toContain('17.11')
  })

  it('keeps every label when each day is named', () => {
    render(
      <CoverageChart
        title="Ряд"
        points={days(5)}
        series={[{ key: 'n', label: 'n', color: '#000' }]}
      />,
    )
    expect(axisLabels()).toEqual(['01.11', '02.11', '03.11', '04.11', '05.11'])
  })

  it('draws a chart of a single day without failing', () => {
    render(
      <CoverageChart
        title="Ряд"
        points={days(1)}
        series={[{ key: 'n', label: 'n', color: '#000' }]}
      />,
    )
    expect(axisLabels()).toEqual(['01.11'])
  })
})
