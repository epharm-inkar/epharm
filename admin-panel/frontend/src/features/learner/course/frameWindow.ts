// The learner screens normally own the whole window; the admin's course preview draws them in a
// frame of its own, where «the window» and «the document» are the frame's, not the page's.

/** Scrolls to the top the window the element is drawn in: the preview's frame, or the page itself. */
export function scrollToTopOf(element: Element | null): void {
  const shownIn = element?.ownerDocument
  const view = shownIn && shownIn !== document ? shownIn.defaultView : null
  ;(view ?? window).scrollTo({ top: 0, left: 0, behavior: 'auto' })
}

/** The document the element is drawn in (the page's own unless it sits in a frame). */
export function documentOf(element: Element | null): Document {
  return element?.ownerDocument ?? document
}
