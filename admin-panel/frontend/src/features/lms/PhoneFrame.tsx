import { useMemo, useState, type ReactNode, type SyntheticEvent } from 'react'
import { createPortal } from 'react-dom'
import { PHONE_ROOT_ID, phoneDocument } from './phoneDocument'

/**
 * A phone: a bezel around a frame as wide as a phone screen. The children are drawn inside the frame
 * (a portal into its document), so they keep the admin page's React state and context, while the
 * layout answers to the phone's width, not to the admin page's.
 */
export function PhoneFrame({ title, children }: { title: string; children: ReactNode }) {
  const [root, setRoot] = useState<HTMLElement | null>(null)
  // Read once: the frame's page is fixed for its life.
  const srcDoc = useMemo(() => phoneDocument(document), [])

  const attach = (event: SyntheticEvent<HTMLIFrameElement>) => {
    const doc = event.currentTarget.contentDocument
    if (!doc?.body) return
    let mount = doc.getElementById(PHONE_ROOT_ID)
    if (!mount) {
      mount = doc.createElement('div')
      mount.id = PHONE_ROOT_ID
      doc.body.append(mount)
    }
    doc.body.classList.add('learner-portal')
    setRoot(mount)
  }

  return (
    <div className="mx-auto w-full max-w-[410px]" data-testid="phone-frame">
      <div className="relative rounded-[38px] bg-ink-900 p-2.5 shadow-elevated">
        <span
          className="absolute left-1/2 top-[7px] h-1 w-14 -translate-x-1/2 rounded-full bg-ink-700"
          aria-hidden="true"
        />
        <div
          className="overflow-hidden rounded-[28px] bg-white"
          // A phone screen that fits under the drawer's header and selector, never shorter than a
          // small phone and never taller than a tall one.
          style={{ height: 'clamp(520px, calc(100vh - 270px), 780px)' }}
        >
          <iframe
            title={title}
            srcDoc={srcDoc}
            // The frame shows our own screens and runs no scripts of its own: links may open a new
            // tab and files may be saved, nothing else.
            sandbox="allow-same-origin allow-popups allow-popups-to-escape-sandbox allow-downloads"
            allowFullScreen
            onLoad={attach}
            className="block h-full w-full border-0"
          />
        </div>
      </div>
      {root && createPortal(children, root)}
    </div>
  )
}
