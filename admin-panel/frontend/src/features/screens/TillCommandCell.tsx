// «Управление» в «Все кассы» (только HQ): «Диагностика» и «Перезапуск» без AnyDesk и последнее
// задание кассы. Касса берёт задание при следующем опросе, выполняет и отвечает.
// В таблице колонка узкая: кнопки-значки с подсказками и короткий статус, подробности во всплывающей
// подсказке. В карточке на телефоне места больше: кнопки и статус словами.

import { useState, type ReactNode } from 'react'
import { RotateCw, Stethoscope } from 'lucide-react'
import { Button, Modal, useToast } from '@/ui'
import { IconDownload } from '@/ui/icons'
import { useT } from '@/i18n'
import { describeError } from '@/lib/describeError'
import type { ScreenFleetRegisterDto, TillCommandAction, TillCommandDto } from '@/lib/api-types'
import { fetchTillArtifact, isActiveTillCommand, useCreateTillCommand } from '@/lib/queries/tillCommands'
import { saveReport } from '@/features/reports/ledgerReports'
import { almatyDateTime, type TFn, type Tone } from './fleetFormat'
import { commandBlocker, commandStatusView, fallbackArchiveName, formatArchiveSize } from './tillCommands'

const TONE_TEXT: Record<Tone, string> = {
  green: 'text-emerald-700',
  amber: 'text-surface-warning-strong',
  red: 'text-surface-danger-strong',
  ink: 'text-ink-500',
}
const ACTION_ICON: Record<TillCommandAction, ReactNode> = {
  diagnostics: <Stethoscope size={14} aria-hidden="true" />,
  restart: <RotateCw size={14} aria-hidden="true" />,
}
const ICON_BUTTON =
  'inline-flex h-6 w-6 items-center justify-center rounded-md text-ink-600 hover:bg-paper-hover hover:text-ink-900 disabled:cursor-not-allowed disabled:text-ink-300 disabled:hover:bg-transparent'
const DOWNLOAD_ICON_BUTTON =
  'inline-flex h-5 w-5 shrink-0 items-center justify-center rounded-md text-brand-green-700 hover:bg-paper-hover disabled:opacity-50'
// На телефоне палец: строка-кнопка высотой 32 px.
const TEXT_BUTTON =
  'inline-flex min-h-8 items-center text-[12px] font-bold text-brand-green-700 hover:underline disabled:cursor-not-allowed disabled:text-ink-300 disabled:no-underline'

export function TillCommandCell({
  row,
  command,
  variant = 'table',
}: {
  row: ScreenFleetRegisterDto
  command: TillCommandDto | undefined
  variant?: 'table' | 'card'
}) {
  const t = useT()
  const toast = useToast()
  const create = useCreateTillCommand()
  const [confirmRestart, setConfirmRestart] = useState(false)
  const blocker = commandBlocker(row)
  const inWork = create.isPending || (command !== undefined && isActiveTillCommand(command))
  const disabled = blocker !== null || inWork
  const why = blocker ? t(blocker) : inWork ? t('tillCmd.blocked.inWork') : null

  const send = (action: TillCommandAction) => {
    if (!row.pharmacyId) return
    create.mutate(
      { pharmacyId: row.pharmacyId, deviceId: row.deviceId, action },
      {
        onSuccess: () => toast.push(t(action === 'restart' ? 'tillCmd.sentRestart' : 'tillCmd.sentDiagnostics')),
        onError: (error) => toast.push(describeError(error), { kind: 'error' }),
      },
    )
  }
  const actions: Array<{ action: TillCommandAction; hint: string; run: () => void }> = [
    { action: 'diagnostics', hint: t('tillCmd.diagnosticsHint'), run: () => send('diagnostics') },
    { action: 'restart', hint: t('tillCmd.restartHint'), run: () => setConfirmRestart(true) },
  ]
  const card = variant === 'card'

  return (
    <div
      className={card ? 'flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1' : 'flex min-w-0 flex-col gap-0.5'}
      data-testid={`till-commands-${row.pharmacyId ?? 'none'}-${row.deviceId}`}
    >
      <div className={`flex items-center ${card ? 'gap-3' : 'gap-1'}`}>
        {actions.map(({ action, hint, run }) => {
          const label = t(`tillCmd.action.${action}`)
          return (
            <button
              key={action}
              type="button"
              className={card ? TEXT_BUTTON : ICON_BUTTON}
              disabled={disabled}
              aria-label={label}
              title={`${label}: ${why ?? hint}`}
              onClick={run}
            >
              {card ? label : ACTION_ICON[action]}
            </button>
          )
        })}
      </div>
      {command && <CommandStatus command={command} card={card} t={t} />}
      <Modal
        open={confirmRestart}
        onClose={() => setConfirmRestart(false)}
        title={t('tillCmd.restartTitle')}
        width={460}
        footer={
          <>
            <Button variant="outline" size="sm" onClick={() => setConfirmRestart(false)}>
              {t('common.cancel')}
            </Button>
            <Button
              size="sm"
              onClick={() => {
                setConfirmRestart(false)
                send('restart')
              }}
            >
              {t('tillCmd.restartConfirm')}
            </Button>
          </>
        }
      >
        <p className="text-[13px] text-ink-800">{t('tillCmd.restartBody')}</p>
        <p className="mt-2 text-[12px] text-ink-500">
          {row.pharmacyName ?? row.pharmacyId} · <span className="num">{row.deviceId}</span>
        </p>
      </Modal>
    </div>
  )
}

function CommandStatus({ command, card, t }: { command: TillCommandDto; card: boolean; t: TFn }) {
  const toast = useToast()
  const [downloading, setDownloading] = useState(false)
  const view = commandStatusView(command)
  const action = t(`tillCmd.action.${command.action}`)
  const status = t(view.key)
  const at =
    command.status === 'pending'
      ? t('tillCmd.until', { time: almatyDateTime(command.expiresAt) })
      : command.status === 'delivered'
        ? null
        : almatyDateTime(command.finishedAt ?? command.createdAt)
  const removed = !command.artifactAvailable && command.artifactBytes !== null
  const size = command.artifactBytes ? formatArchiveSize(t, command.artifactBytes) : null
  const title = [
    `${action}: ${status}${at ? `, ${at}` : ''}`,
    command.resultMessage,
    removed ? t('tillCmd.archiveRemoved') : null,
    t('tillCmd.requestedBy', { who: command.requestedBy }),
  ]
    .filter(Boolean)
    .join('\n')

  const download = async () => {
    setDownloading(true)
    try {
      const { blob, fileName } = await fetchTillArtifact(command.id)
      saveReport(blob, fileName ?? fallbackArchiveName(command))
    } catch (error) {
      toast.push(describeError(error), { kind: 'error' })
    } finally {
      setDownloading(false)
    }
  }
  const downloadLabel = `${t('tillCmd.download')}${size ? ` ${size}` : ''}`

  if (!card) {
    return (
      <div className="flex min-w-0 items-center gap-1 text-[11px]" title={title} data-testid="till-command-status">
        <span className="shrink-0 text-ink-400">{ACTION_ICON[command.action]}</span>
        <span className={`truncate font-semibold ${TONE_TEXT[view.tone]}`}>{status}</span>
        {command.artifactAvailable && (
          <button
            type="button"
            className={DOWNLOAD_ICON_BUTTON}
            disabled={downloading}
            aria-label={downloadLabel}
            title={downloadLabel}
            onClick={download}
          >
            <IconDownload size={14} />
          </button>
        )}
      </div>
    )
  }
  return (
    <div className="flex min-w-0 flex-wrap items-center gap-x-1.5 text-[12px]" title={title} data-testid="till-command-status">
      <span className={`font-semibold ${TONE_TEXT[view.tone]}`}>
        {action}: {status}
        {command.status === 'failed' && command.resultMessage ? `: ${command.resultMessage}` : ''}
      </span>{' '}
      {at && <span className="num text-ink-400">{at}</span>}{' '}
      {command.artifactAvailable && (
        <button
          type="button"
          className="font-bold text-brand-green-700 hover:underline disabled:opacity-50"
          disabled={downloading}
          onClick={download}
        >
          {downloadLabel}
        </button>
      )}
      {removed && <span className="text-ink-400">{t('tillCmd.archiveRemoved')}</span>}
    </div>
  )
}
