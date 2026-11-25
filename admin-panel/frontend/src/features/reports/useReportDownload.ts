import { useEffect, useRef, useState } from 'react'
import { describeError } from '@/lib/describeError'
import { downloadReport, saveReport } from './ledgerReports'

export type ReportDownloadResult = { ok: true } | { ok: false; error: string }

/**
 * An Excel download started from a menu item: the request belongs to the page, not to the menu that
 * closes on click. One at a time; leaving the page cancels it. The caller reports the result (toast).
 */
export function useReportDownload() {
  const request = useRef<AbortController | null>(null)
  const [pending, setPending] = useState(false)
  useEffect(() => () => request.current?.abort(), [])

  const download = async (
    path: string,
    params: Record<string, string | undefined>,
    filename: string,
  ): Promise<ReportDownloadResult | null> => {
    if (request.current) return null
    const controller = new AbortController()
    request.current = controller
    setPending(true)
    try {
      const blob = await downloadReport(path, params, controller.signal)
      if (controller.signal.aborted) return null
      saveReport(blob, filename)
      return { ok: true }
    } catch (error) {
      if (controller.signal.aborted) return null
      return { ok: false, error: describeError(error) }
    } finally {
      if (request.current === controller) request.current = null
      if (!controller.signal.aborted) setPending(false)
    }
  }

  return { pending, download }
}
