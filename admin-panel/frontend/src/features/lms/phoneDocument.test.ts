import { afterEach, describe, expect, it } from 'vitest'
import { escapeAttribute, PHONE_ROOT_ID, phoneDocument } from './phoneDocument'

afterEach(() => {
  document.head.innerHTML = ''
  document.documentElement.lang = ''
})

describe('escapeAttribute', () => {
  it('keeps a URL inside the attribute it is written into', () => {
    expect(escapeAttribute('/a.css?x=1&y="2"<b>')).toBe('/a.css?x=1&amp;y=&quot;2&quot;&lt;b&gt;')
  })

  it('leaves an ordinary URL as it is', () => {
    expect(escapeAttribute('https://epharm.inkar.kz/assets/index-abc123.css')).toBe(
      'https://epharm.inkar.kz/assets/index-abc123.css',
    )
  })
})

describe('phoneDocument', () => {
  it("starts a page with the cabinet's body class and the mount point", () => {
    const html = phoneDocument(document)
    expect(html).toMatch(/^<!doctype html>/i)
    expect(html).toContain('<body class="learner-portal">')
    expect(html).toContain(`<div id="${PHONE_ROOT_ID}"></div>`)
    expect(html).toContain('name="viewport" content="width=device-width, initial-scale=1"')
  })

  it("carries the admin page's stylesheets, in their order, so the frame is drawn by the same CSS", () => {
    const link = document.createElement('link')
    link.rel = 'stylesheet'
    link.href = '/assets/index-abc123.css'
    const style = document.createElement('style')
    style.textContent = '.x { color: red }'
    const other = document.createElement('link')
    other.rel = 'stylesheet'
    other.href = '/assets/vendor.css'
    document.head.append(link, style, other)

    const html = phoneDocument(document)
    const first = html.indexOf('index-abc123.css')
    const second = html.indexOf('.x { color: red }')
    const third = html.indexOf('vendor.css')
    expect(first).toBeGreaterThan(-1)
    expect(second).toBeGreaterThan(first)
    expect(third).toBeGreaterThan(second)
    // A stylesheet is a link to the file, not a copy of it, and it is absolute for a frame without a base.
    expect(html).toMatch(
      /<link rel="stylesheet" href="https?:\/\/[^"]+\/assets\/index-abc123\.css">/,
    )
  })

  it('ignores links that are not stylesheets', () => {
    const icon = document.createElement('link')
    icon.rel = 'icon'
    icon.href = '/favicon.svg'
    const preload = document.createElement('link')
    preload.rel = 'preload'
    preload.href = '/font.woff2'
    document.head.append(icon, preload)
    const html = phoneDocument(document)
    expect(html).not.toContain('favicon.svg')
    expect(html).not.toContain('font.woff2')
  })

  it('keeps the page language, and falls back to Russian', () => {
    expect(phoneDocument(document)).toContain('<html lang="ru">')
    document.documentElement.lang = 'kk'
    expect(phoneDocument(document)).toContain('<html lang="kk">')
  })

  it('does not let a hostile language value out of its attribute', () => {
    document.documentElement.lang = 'ru"><script>alert(1)</script>'
    expect(phoneDocument(document)).not.toContain('<script>')
  })
})
