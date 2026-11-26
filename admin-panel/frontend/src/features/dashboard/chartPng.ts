// «Скачать график»: the charts on the screen drawn into one PNG with a title and the key figures,
// ready to be sent to management. The charts are plain SVG, so the picture is the screen, not a
// second drawing of it.

const SVG_NS = 'http://www.w3.org/2000/svg'
/** The page font first; the standalone SVG and the canvas fall back to the system one. */
const FONT = "Manrope, 'Segoe UI', Arial, sans-serif"
const SCALE = 2
const PAD = 28
const MIN_WIDTH = 720
/** The on-screen chart lets its end-of-line labels run past the box; an image would cut them off. */
const LABEL_ROOM = 56
const INK = '#1C1B19'
const MUTED = '#6B6862'

export interface ChartSvgImage {
  markup: string
  width: number
  height: number
}

/**
 * A self-contained copy of an on-screen chart: it carries its own size, namespace and font, has room
 * on the right for the labels at the ends of the lines, and leaves out the invisible rectangle that
 * only catches the mouse for the tooltip.
 */
export function chartSvgImage(svg: SVGSVGElement): ChartSvgImage {
  const shown = Number(svg.getAttribute('width')) || svg.getBoundingClientRect().width
  const width = shown + LABEL_ROOM
  const height = Number(svg.getAttribute('height')) || svg.getBoundingClientRect().height
  const copy = svg.cloneNode(true) as SVGSVGElement
  copy.setAttribute('xmlns', SVG_NS)
  copy.setAttribute('width', String(width))
  copy.setAttribute('height', String(height))
  copy.setAttribute('viewBox', `0 0 ${width} ${height}`)
  copy.setAttribute('font-family', FONT)
  copy.removeAttribute('class')
  copy.querySelectorAll('rect[fill="transparent"]').forEach((node) => node.remove())
  return { markup: new XMLSerializer().serializeToString(copy), width, height }
}

/** Greedy line breaking by measured width; a word wider than the line stays whole on its own line. */
export function wrapText(
  text: string,
  maxWidth: number,
  measure: (text: string) => number,
): string[] {
  const lines: string[] = []
  let line = ''
  for (const word of text.split(/\s+/).filter(Boolean)) {
    const next = line ? `${line} ${word}` : word
    if (line && measure(next) > maxWidth) {
      lines.push(line)
      line = word
    } else {
      line = next
    }
  }
  if (line) lines.push(line)
  return lines
}

function loadImage(markup: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const image = new Image()
    image.onload = () => resolve(image)
    image.onerror = () => reject(new Error('The chart could not be drawn as an image'))
    image.src = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(markup)}`
  })
}

export interface ChartPngSpec {
  title: string
  /** Under the title: the period, the key figures. Long lines wrap. */
  lines: string[]
  charts: Array<{ heading: string; svg: SVGSVGElement }>
  footnote: string
}

export async function renderChartPng(spec: ChartPngSpec): Promise<Blob> {
  const sizes = spec.charts.map((chart) => chartSvgImage(chart.svg))
  const images = await Promise.all(sizes.map((size) => loadImage(size.markup)))
  const contentWidth = Math.max(MIN_WIDTH, ...sizes.map((size) => size.width))
  const width = contentWidth + PAD * 2

  const probe = document.createElement('canvas').getContext('2d')
  if (!probe) throw new Error('A canvas is not available')
  const measureAt = (font: string) => (text: string) => {
    probe.font = font
    return probe.measureText(text).width
  }
  const titleFont = `700 26px ${FONT}`
  const bodyFont = `500 15px ${FONT}`
  const headingFont = `700 16px ${FONT}`
  const noteFont = `500 12px ${FONT}`
  const body = spec.lines.flatMap((line) => wrapText(line, contentWidth, measureAt(bodyFont)))
  const note = wrapText(spec.footnote, contentWidth, measureAt(noteFont))

  const chartsHeight = sizes.reduce(
    (sum, size) => sum + 30 + Math.round((size.height * contentWidth) / size.width) + 12,
    0,
  )
  const height = PAD + 34 + body.length * 22 + 10 + chartsHeight + 8 + note.length * 17 + PAD

  const canvas = document.createElement('canvas')
  canvas.width = width * SCALE
  canvas.height = height * SCALE
  const ctx = canvas.getContext('2d')
  if (!ctx) throw new Error('A canvas is not available')
  ctx.scale(SCALE, SCALE)
  ctx.fillStyle = '#FFFFFF'
  ctx.fillRect(0, 0, width, height)
  ctx.textBaseline = 'alphabetic'

  let y = PAD + 26
  ctx.fillStyle = INK
  ctx.font = titleFont
  ctx.fillText(spec.title, PAD, y)
  y += 8
  ctx.font = bodyFont
  ctx.fillStyle = MUTED
  for (const line of body) {
    y += 22
    ctx.fillText(line, PAD, y)
  }
  y += 10
  spec.charts.forEach((chart, index) => {
    y += 22
    ctx.font = headingFont
    ctx.fillStyle = INK
    ctx.fillText(chart.heading, PAD, y)
    y += 8
    const drawHeight = Math.round((sizes[index].height * contentWidth) / sizes[index].width)
    ctx.drawImage(images[index], PAD, y, contentWidth, drawHeight)
    y += drawHeight + 12
  })
  y += 8
  ctx.font = noteFont
  ctx.fillStyle = MUTED
  for (const line of note) {
    y += 17
    ctx.fillText(line, PAD, y)
  }

  return new Promise((resolve, reject) =>
    canvas.toBlob(
      (blob) => (blob ? resolve(blob) : reject(new Error('The picture could not be created'))),
      'image/png',
    ),
  )
}
