import { describe, expect, it } from 'vitest'
import { chartSvgImage, wrapText } from './chartPng'

const SVG_NS = 'http://www.w3.org/2000/svg'

function chart(width = 500, height = 220) {
  const svg = document.createElementNS(SVG_NS, 'svg')
  svg.setAttribute('width', String(width))
  svg.setAttribute('height', String(height))
  svg.setAttribute('role', 'img')
  svg.setAttribute('class', 'block overflow-visible')
  const line = document.createElementNS(SVG_NS, 'path')
  line.setAttribute('d', 'M0,0 L10,10')
  line.setAttribute('stroke', '#B95336')
  svg.append(line)
  // The invisible rectangle that only catches the mouse for the tooltip.
  const hit = document.createElementNS(SVG_NS, 'rect')
  hit.setAttribute('fill', 'transparent')
  svg.append(hit)
  return svg
}

describe('chartSvgImage', () => {
  it('makes a standalone SVG: namespace, own size and font, room for the end-of-line labels', () => {
    const image = chartSvgImage(chart(500, 220))
    expect(image.width).toBe(556)
    expect(image.height).toBe(220)
    expect(image.markup).toContain('xmlns="http://www.w3.org/2000/svg"')
    expect(image.markup).toContain('width="556"')
    expect(image.markup).toContain('viewBox="0 0 556 220"')
    expect(image.markup).toContain('font-family=')
    expect(image.markup).toContain('stroke="#B95336"')
  })

  it('leaves out the hover rectangle and the page classes, and does not touch the chart on screen', () => {
    const svg = chart()
    const image = chartSvgImage(svg)
    expect(image.markup).not.toContain('transparent')
    expect(image.markup).not.toContain('class=')
    expect(svg.querySelector('rect[fill="transparent"]')).not.toBeNull()
    expect(svg.getAttribute('class')).toBe('block overflow-visible')
    expect(svg.getAttribute('width')).toBe('500')
  })
})

describe('wrapText', () => {
  // One unit of width per character.
  const measure = (text: string) => text.length

  it('breaks between words at the width', () => {
    expect(wrapText('one two three four', 9, measure)).toEqual(['one two', 'three', 'four'])
  })

  it('keeps a short text on one line and collapses runs of spaces', () => {
    expect(wrapText('  a   b ', 20, measure)).toEqual(['a b'])
  })

  it('never splits a word wider than the line', () => {
    expect(wrapText('extraordinarily short', 5, measure)).toEqual(['extraordinarily', 'short'])
  })

  it('gives no lines for an empty text', () => {
    expect(wrapText('', 10, measure)).toEqual([])
  })
})
