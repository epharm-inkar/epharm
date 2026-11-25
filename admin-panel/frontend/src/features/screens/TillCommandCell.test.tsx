import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ScreenFleetRegisterDto, TillCommandDto } from '@/lib/api-types'
import { TillCommandCell } from './TillCommandCell'

const tillHooks = vi.hoisted(() => ({
  useCreateTillCommand: vi.fn(),
  fetchTillArtifact: vi.fn(),
}))
vi.mock('@/lib/queries/tillCommands', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/queries/tillCommands')>()),
  ...tillHooks,
}))
const reports = vi.hoisted(() => ({ saveReport: vi.fn() }))
vi.mock('@/features/reports/ledgerReports', () => reports)

const mutate = vi.fn()

function row(overrides: Partial<ScreenFleetRegisterDto> = {}): ScreenFleetRegisterDto {
  return {
    deviceId: 'KASSA1',
    pharmacyId: 'ph_a',
    pharmacyName: 'Аптека со склада №1',
    chainName: null,
    city: 'Алматы',
    address: 'Абая 1',
    pharmacyActive: true,
    registered: true,
    registeredAt: null,
    state: 'online',
    lastSeen: '2026-11-25T08:59:30Z',
    appVersion: '1.0.94',
    monitorCount: 2,
    hasClientScreen: true,
    staffAuthStatus: null,
    staffSourceStatus: 'resolved',
    playlistId: null,
    playlistSource: 'none',
    salesToday: 0,
    sales7d: 0,
    lastSaleAt: null,
    pharmacyLastSaleAt: null,
    pharmacySalesToday: 0,
    pharmacySales7d: 0,
    ...overrides,
  }
}

function command(overrides: Partial<TillCommandDto>): TillCommandDto {
  return {
    id: 'cmd-1',
    pharmacyId: 'ph_a',
    deviceId: 'KASSA1',
    action: 'diagnostics',
    status: 'pending',
    requestedBy: 'hq@example.test',
    createdAt: '2026-11-25T09:00:00Z',
    expiresAt: '2026-11-25T09:30:00Z',
    deliveredAt: null,
    finishedAt: null,
    resultMessage: null,
    artifactAvailable: false,
    artifactBytes: null,
    ...overrides,
  }
}

const buttons = () => {
  const cell = screen.getByTestId('till-commands-ph_a-KASSA1')
  return {
    diagnostics: within(cell).getByRole('button', { name: 'Диагностика' }),
    restart: within(cell).getByRole('button', { name: 'Перезапуск' }),
  }
}

describe('TillCommandCell', () => {
  beforeEach(() => {
    mutate.mockReset()
    reports.saveReport.mockReset()
    tillHooks.fetchTillArtifact.mockReset()
    tillHooks.useCreateTillCommand.mockReturnValue({ mutate, isPending: false })
  })

  it('диагностика уходит сразу, без вопроса', async () => {
    const user = userEvent.setup()
    render(<TillCommandCell row={row()} command={undefined} />)
    await user.click(buttons().diagnostics)
    expect(mutate).toHaveBeenCalledWith(
      { pharmacyId: 'ph_a', deviceId: 'KASSA1', action: 'diagnostics' },
      expect.anything(),
    )
  })

  it('перезапуск спрашивает подтверждение; отмена ничего не отправляет', async () => {
    const user = userEvent.setup()
    render(<TillCommandCell row={row()} command={undefined} />)
    await user.click(buttons().restart)
    const dialog = screen.getByRole('dialog')
    expect(dialog).toHaveTextContent('Программа ePharm на этой кассе перезапустится, касса Стандарт-Н продолжит работать')
    expect(dialog).toHaveTextContent('Аптека со склада №1 · KASSA1')
    await user.click(within(dialog).getByRole('button', { name: 'Отмена' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(mutate).not.toHaveBeenCalled()

    await user.click(buttons().restart)
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Перезапустить' }))
    expect(mutate).toHaveBeenCalledWith({ pharmacyId: 'ph_a', deviceId: 'KASSA1', action: 'restart' }, expect.anything())
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('пока задание ждёт кассу, новые не даются и видно, до какого времени оно живёт', () => {
    render(<TillCommandCell row={row()} command={command({ status: 'pending' })} />)
    expect(buttons().diagnostics).toBeDisabled()
    expect(buttons().restart).toBeDisabled()
    expect(buttons().restart).toHaveAttribute('title', 'Перезапуск: У кассы уже есть задание в работе')
    const status = screen.getByTestId('till-command-status')
    expect(status).toHaveTextContent('ждёт кассу')
    expect(status).toHaveAttribute('title', expect.stringContaining('Диагностика: ждёт кассу, до 25.11, 14:30'))
  })

  it('касса взяла задание: «выполняется»', () => {
    render(
      <TillCommandCell
        row={row()}
        command={command({ status: 'delivered', action: 'restart', deliveredAt: '2026-11-25T09:01:00Z' })}
      />,
    )
    const status = screen.getByTestId('till-command-status')
    expect(status).toHaveTextContent('выполняется')
    expect(status).toHaveAttribute('title', expect.stringContaining('Перезапуск: выполняется'))
    expect(buttons().diagnostics).toBeDisabled()
  })

  it('в карточке на телефоне кнопки и статус словами', async () => {
    const user = userEvent.setup()
    render(<TillCommandCell row={row()} command={command({ status: 'pending' })} variant="card" />)
    expect(buttons().diagnostics).toHaveTextContent('Диагностика')
    expect(screen.getByTestId('till-command-status')).toHaveTextContent('Диагностика: ждёт кассу до 25.11, 14:30')
    await user.click(buttons().restart)
    expect(mutate).not.toHaveBeenCalled()
  })

  it('готовая диагностика скачивается архивом', async () => {
    const user = userEvent.setup()
    const blob = new Blob(['PK'])
    tillHooks.fetchTillArtifact.mockResolvedValue({ blob, fileName: 'epharm-diagnostics-ph_a-KASSA1-20261125-1405.zip' })
    render(
      <TillCommandCell
        row={row()}
        command={command({
          status: 'done',
          deliveredAt: '2026-11-25T09:01:00Z',
          finishedAt: '2026-11-25T09:05:00Z',
          resultMessage: 'Журналы за 3 дня',
          artifactAvailable: true,
          artifactBytes: 1_310_720,
        })}
      />,
    )
    const status = screen.getByTestId('till-command-status')
    expect(status).toHaveTextContent('собрана')
    expect(status).toHaveAttribute('title', expect.stringContaining('Диагностика: собрана, 25.11, 14:05'))
    expect(status).toHaveAttribute('title', expect.stringContaining('Журналы за 3 дня'))
    expect(status).toHaveAttribute('title', expect.stringContaining('Дал: hq@example.test'))
    expect(buttons().diagnostics).toBeEnabled()
    await user.click(within(status).getByRole('button', { name: 'Скачать 1,3 МБ' }))
    await waitFor(() =>
      expect(reports.saveReport).toHaveBeenCalledWith(blob, 'epharm-diagnostics-ph_a-KASSA1-20261125-1405.zip'),
    )
    expect(tillHooks.fetchTillArtifact).toHaveBeenCalledWith('cmd-1')
  })

  it('если браузер не видит имя файла, подставляет своё', async () => {
    const user = userEvent.setup()
    tillHooks.fetchTillArtifact.mockResolvedValue({ blob: new Blob(['PK']), fileName: null })
    render(
      <TillCommandCell
        row={row()}
        command={command({ status: 'done', finishedAt: '2026-11-25T09:05:00Z', artifactAvailable: true, artifactBytes: 2048 })}
      />,
    )
    await user.click(screen.getByRole('button', { name: 'Скачать 2 КБ' }))
    await waitFor(() =>
      expect(reports.saveReport).toHaveBeenCalledWith(expect.any(Blob), 'epharm-diagnostics-ph_a-KASSA1.zip'),
    )
  })

  it('ошибка кассы показывается с её сообщением, истёкшее задание и удалённый архив подписаны', () => {
    const failed = command({ status: 'failed', finishedAt: '2026-11-25T09:05:00Z', resultMessage: 'Стандарт-Н не отвечает' })
    const { rerender } = render(<TillCommandCell row={row()} command={failed} variant="card" />)
    expect(screen.getByTestId('till-command-status')).toHaveTextContent('Диагностика: ошибка: Стандарт-Н не отвечает')
    rerender(<TillCommandCell row={row()} command={failed} />)
    expect(screen.getByTestId('till-command-status')).toHaveTextContent('ошибка')
    expect(screen.getByTestId('till-command-status')).toHaveAttribute(
      'title',
      expect.stringContaining('Стандарт-Н не отвечает'),
    )

    const expired = command({ status: 'expired', finishedAt: '2026-11-25T09:30:00Z' })
    rerender(<TillCommandCell row={row()} command={expired} variant="card" />)
    expect(screen.getByTestId('till-command-status')).toHaveTextContent('Диагностика: нет ответа')
    expect(buttons().diagnostics).toBeEnabled()

    const removed = command({ status: 'done', finishedAt: '2026-11-10T09:05:00Z', artifactAvailable: false, artifactBytes: 4096 })
    rerender(<TillCommandCell row={row()} command={removed} variant="card" />)
    expect(screen.getByTestId('till-command-status')).toHaveTextContent('архив удалён через 14 дней')
    expect(screen.queryByRole('button', { name: /Скачать/ })).not.toBeInTheDocument()
    rerender(<TillCommandCell row={row()} command={removed} />)
    expect(screen.getByTestId('till-command-status')).toHaveAttribute(
      'title',
      expect.stringContaining('архив удалён через 14 дней'),
    )
  })

  it('касса без своего ключа и давно молчащая касса заданий не получают', () => {
    const { rerender } = render(<TillCommandCell row={row({ registered: false })} command={undefined} />)
    expect(buttons().diagnostics).toBeDisabled()
    expect(buttons().diagnostics).toHaveAttribute('title', expect.stringContaining('нет своего ключа'))
    rerender(<TillCommandCell row={row({ state: 'stale' })} command={undefined} />)
    expect(buttons().restart).toBeDisabled()
  })

  it('пока запрос уходит, кнопки не нажимаются второй раз', () => {
    tillHooks.useCreateTillCommand.mockReturnValue({ mutate, isPending: true })
    render(<TillCommandCell row={row()} command={undefined} />)
    expect(buttons().diagnostics).toBeDisabled()
    expect(buttons().restart).toBeDisabled()
  })
})
