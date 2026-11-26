// The document a phone frame starts with. Tailwind's breakpoints follow the width of the window,
// so a 390 px box inside a wide admin page would still lay the learner screens out as a desktop.
// A frame is a window of its own: its width is the phone's, and `sm:` and `lg:` behave as they do
// on the phone.

export const PHONE_ROOT_ID = 'phone-root'

/** Quotes and ampersands in a URL must not end the attribute they are written into. */
export function escapeAttribute(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/"/g, '&quot;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
}

/**
 * The frame's page: the admin page's own stylesheets (the learner screens are drawn by the same
 * CSS as in the cabinet, one `<link>` in production, `<style>` tags under the dev server) and a body
 * with the class the cabinet gives its own. The stylesheets are part of the page itself, so the
 * frame's `load` waits for them and the first picture is not an unstyled flash.
 */
export function phoneDocument(parent: Document): string {
  const styles = Array.from(parent.querySelectorAll<HTMLElement>('link[rel~="stylesheet"], style'))
    .map((node) =>
      node.tagName === 'LINK'
        ? `<link rel="stylesheet" href="${escapeAttribute((node as HTMLLinkElement).href)}">`
        : `<style>${node.textContent ?? ''}</style>`,
    )
    .join('')
  const lang = escapeAttribute(parent.documentElement.lang || 'ru')
  return (
    `<!doctype html><html lang="${lang}"><head><meta charset="utf-8">` +
    '<meta name="viewport" content="width=device-width, initial-scale=1">' +
    `${styles}</head><body class="learner-portal"><div id="${PHONE_ROOT_ID}"></div></body></html>`
  )
}
