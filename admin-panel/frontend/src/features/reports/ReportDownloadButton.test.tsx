import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { api } from '@/lib/api'
import ReportDownloadButton from './ReportDownloadButton'
afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})
describe('separate XLSX action', () => {
  it('does not issue a request without the independent export permission', async () => {
    const get = vi.spyOn(api, 'get')
    render(
      <ReportDownloadButton
        allowed={false}
        path="/api/admin/reports/pharmacists.xlsx"
        params={{}}
        filename="pharmacists.xlsx"
        label="Excel: реестр"
      />,
    )
    await userEvent.click(screen.getByRole('button'))
    expect(get).not.toHaveBeenCalled()
  })
  it('downloads the exact report and cannot submit a surrounding PDF form', async () => {
    const get = vi.spyOn(api, 'get').mockResolvedValue({
      data: new Blob(['synthetic-xlsx']),
      headers: {
        'content-type': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      },
    })
    const submit = vi.fn((event) => event.preventDefault())
    const NativeUrl = URL
    vi.stubGlobal(
      'URL',
      class extends NativeUrl {
        static createObjectURL() {
          return 'blob:synthetic-only'
        }
        static revokeObjectURL() {}
      },
    )
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    render(
      <form onSubmit={submit}>
        <ReportDownloadButton
          allowed
          path="/api/admin/reports/campaigns/demo/xlsx"
          params={{ from: '2026-10-01', to: '2026-10-29' }}
          filename="campaign.xlsx"
          label="Excel: кампания"
        />
      </form>,
    )
    await userEvent.click(screen.getByRole('button'))
    await waitFor(() =>
      expect(get).toHaveBeenCalledWith(
        '/api/admin/reports/campaigns/demo/xlsx',
        expect.objectContaining({
          params: { from: '2026-10-01', to: '2026-10-29' },
          responseType: 'blob',
          signal: expect.any(AbortSignal),
        }),
      ),
    )
    expect(submit).not.toHaveBeenCalled()
    expect(await screen.findByRole('status')).toHaveTextContent('Excel передан браузеру')
  })
  it('cancels an in-flight download when the independent permission is removed', async () => {
    let finish!: (value: unknown) => void
    const get = vi.spyOn(api, 'get').mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    const props = {
      path: '/api/admin/reports/ledger.xlsx',
      params: {},
      filename: 'ledger.xlsx',
      label: 'Excel',
    }
    const { rerender } = render(<ReportDownloadButton {...props} allowed />)
    await userEvent.click(screen.getByRole('button'))
    const signal = get.mock.calls[0][1]?.signal
    rerender(<ReportDownloadButton {...props} allowed={false} />)
    expect(signal?.aborted).toBe(true)
    await act(async () => {
      finish({
        data: new Blob(['synthetic-only']),
        headers: {
          'content-type': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        },
      })
    })
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
  })

  it.each(['filters', 'permission', 'invalid'] as const)(
    'can retry after restoring %s without reviving the cancelled request',
    async (change) => {
      const completions: Array<(value: unknown) => void> = []
      const get = vi.spyOn(api, 'get').mockImplementation(
        () => new Promise((resolve) => { completions.push(resolve) }),
      )
      const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
      const NativeUrl = URL
      vi.stubGlobal('URL', class extends NativeUrl {
        static createObjectURL() { return 'blob:synthetic-only' }
        static revokeObjectURL() {}
      })
      const props = {
        path: '/api/admin/reports/campaigns/example/xlsx',
        params: { from: '2026-10-01', to: '2026-10-31' },
        filename: 'campaign.xlsx', label: 'Excel', allowed: true,
      }
      const { rerender } = render(<ReportDownloadButton {...props} />)
      await userEvent.click(screen.getByRole('button'))
      expect(screen.getByRole('button')).toBeDisabled()
      const originalSignal = get.mock.calls[0][1]?.signal

      rerender(<ReportDownloadButton {...props}
        allowed={change === 'permission' ? false : true}
        params={change === 'filters' ? { ...props.params, to: '2026-10-30' } : props.params}
        invalid={change === 'invalid' ? 'Apply filters first' : undefined}
      />)
      expect(originalSignal?.aborted).toBe(true)
      rerender(<ReportDownloadButton {...props} />)
      expect(screen.getByRole('button', { name: 'Excel' })).toBeEnabled()
      await userEvent.click(screen.getByRole('button'))
      expect(get).toHaveBeenCalledTimes(2)
      expect(get.mock.calls[1][1]?.signal?.aborted).toBe(false)

      const response = {
        data: new Blob(['synthetic-xlsx']),
        headers: { 'content-type': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' },
      }
      await act(async () => { completions[0](response) })
      expect(click).not.toHaveBeenCalled()
      expect(screen.getByRole('button')).toBeDisabled()
      expect(screen.queryByRole('status')).not.toBeInTheDocument()

      await act(async () => { completions[1](response) })
      expect(click).toHaveBeenCalledOnce()
      expect(screen.getByRole('status')).toHaveTextContent('Excel передан браузеру')
      expect(screen.getByRole('button')).toBeEnabled()
    },
  )
})
