// «ИИН указали сами» (26.11.2026): что фармацевты ввели о себе на кассе или в кабинете. HQ подтверждает или
// отклоняет; пока заявка открыта, бонусы не начисляются. Данные Стандарт-Н главнее: когда справочник узнаёт
// человека, заявка уступает, а расхождение ИИН видно во вкладке «Расхождения».

import { useMemo, useState } from 'react'
import { Button, Empty, Field, Input, Modal, Tabs, useToast, type TabItem } from '@/ui'
import { IconUsers } from '@/ui/icons'
import { useT } from '@/i18n'
import { describeError } from '@/lib/describeError'
import type { IdentityClaimDto } from '@/lib/api-types'
import {
  useConfirmIdentityClaim,
  useIdentityClaims,
  useRejectIdentityClaim,
} from '@/lib/queries/identityClaims'
import { dateTime } from '@/features/lms/training-ui'
import { maskedIin } from './personal-data'
import { confirmQuestion, conflictText, splitClaims, type ClaimTab } from './identityClaimsView'

export function IdentityClaimsModal({ onClose }: { onClose: () => void }) {
  const t = useT()
  const toast = useToast()
  const claims = useIdentityClaims()
  const confirm = useConfirmIdentityClaim()
  const reject = useRejectIdentityClaim()
  const [tab, setTab] = useState<ClaimTab>('open')
  const [showIin, setShowIin] = useState(false)
  const [rejecting, setRejecting] = useState<IdentityClaimDto | null>(null)
  const [reason, setReason] = useState('')
  const [now] = useState(() => Date.now())

  const split = useMemo(() => splitClaims(claims.data ?? [], now), [claims.data, now])
  const shown = split[tab]
  const busy = confirm.isPending || reject.isPending
  const tabs: TabItem<ClaimTab>[] = [
    { value: 'open', label: t('idc.tabOpen'), count: split.open.length },
    { value: 'flags', label: t('idc.tabFlags'), count: split.flags.length },
    { value: 'done', label: t('idc.tabDone'), count: split.done.length },
  ]

  const decide = (claim: IdentityClaimDto) => {
    if (busy || !window.confirm(confirmQuestion(t, claim))) return
    confirm.mutate(
      { id: claim.id, request: { iin: claim.iin, fullName: claim.fullName } },
      {
        onSuccess: () => toast.push(t('idc.confirmedToast', { name: claim.fullName })),
        onError: (error) => toast.push(describeError(error)),
      },
    )
  }

  const closeRejecting = () => {
    setRejecting(null)
    setReason('')
  }

  const sendRejection = () => {
    if (!rejecting || busy || reason.trim().length < 3) return
    reject.mutate(
      { id: rejecting.id, reason: reason.trim() },
      {
        onSuccess: () => {
          toast.push(t('idc.rejectedToast'))
          closeRejecting()
        },
        onError: (error) => toast.push(describeError(error)),
      },
    )
  }

  return (
    <Modal
      open
      onClose={onClose}
      title={t('idc.title')}
      subtitle={t('idc.subtitle')}
      width={1040}
      footer={
        <>
          <Button
            variant="ghost"
            aria-pressed={showIin}
            onClick={() => setShowIin((value) => !value)}
          >
            {showIin ? t('idc.hideIin') : t('idc.showIin')}
          </Button>
          <Button onClick={onClose}>{t('idc.close')}</Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <Tabs<ClaimTab> items={tabs} value={tab} onChange={setTab} />
        {claims.isLoading ? (
          <div className="py-8 text-center text-[13px] text-ink-500">{t('idc.loading')}</div>
        ) : claims.isError ? (
          <div
            role="alert"
            className="rounded-lg bg-surface-danger px-4 py-3 text-[13px] font-semibold text-accent-danger"
          >
            {describeError(claims.error)}
            <Button
              className="ml-3"
              size="sm"
              variant="outline"
              onClick={() => claims.refetch()}
              disabled={claims.isFetching}
            >
              {t('common.retry')}
            </Button>
          </div>
        ) : shown.length === 0 ? (
          <Empty
            title={t(
              tab === 'open'
                ? 'idc.emptyOpen'
                : tab === 'flags'
                  ? 'idc.emptyFlags'
                  : 'idc.emptyDone',
            )}
            icon={<IconUsers size={24} />}
          />
        ) : (
          <div className="max-h-[60vh] overflow-auto rounded-lg border border-ink-100">
            <table className="w-full min-w-[820px] text-[12px]" data-testid="identity-claims-table">
              <thead className="sticky top-0 bg-white text-left text-[10px] font-bold uppercase text-ink-500">
                <tr>
                  <th className="px-3 py-2">{t('idc.thWho')}</th>
                  <th className="px-3 py-2">{t('idc.thEntered')}</th>
                  <th className="px-3 py-2">{t('idc.thPharmacy')}</th>
                  <th className="px-3 py-2">{t('idc.thChecks')}</th>
                  <th className="px-3 py-2" />
                </tr>
              </thead>
              <tbody className="divide-y divide-ink-100">
                {shown.map((claim) => (
                  <ClaimRow
                    key={claim.id}
                    claim={claim}
                    showIin={showIin}
                    busy={busy}
                    onConfirm={() => decide(claim)}
                    onReject={() => setRejecting(claim)}
                  />
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {rejecting && (
        <Modal
          open
          onClose={closeRejecting}
          title={t('idc.rejectTitle')}
          subtitle={rejecting.fullName}
          width={460}
          footer={
            <>
              <Button variant="ghost" onClick={closeRejecting}>
                {t('common.cancel')}
              </Button>
              <Button
                variant="danger"
                disabled={busy || reason.trim().length < 3}
                onClick={sendRejection}
              >
                {t('idc.reject')}
              </Button>
            </>
          }
        >
          <Field label={t('idc.rejectReason')} hint={t('idc.rejectHint')}>
            <Input
              value={reason}
              maxLength={500}
              autoFocus
              onChange={(event) => setReason(event.target.value)}
            />
          </Field>
        </Modal>
      )}
    </Modal>
  )
}

function ClaimRow({
  claim,
  showIin,
  busy,
  onConfirm,
  onReject,
}: {
  claim: IdentityClaimDto
  showIin: boolean
  busy: boolean
  onConfirm: () => void
  onReject: () => void
}) {
  const t = useT()
  const pending = claim.status === 'pending'
  return (
    <tr data-testid={`identity-claim-${claim.id}`}>
      <td className="px-3 py-2.5 align-top">
        <span className="chip chip-ink">
          {claim.kind === 'name' ? t('idc.kindName') : t('idc.kindPerson')}
        </span>
        {claim.kind === 'person' ? (
          <>
            <b className="mt-1 block text-ink-800">
              {t('idc.userId', { id: claim.standardNUserId ?? '' })}
            </b>
            {claim.standardNUserName && (
              <span className="block text-[11px] text-ink-500">
                {t('idc.userName', { name: claim.standardNUserName })}
              </span>
            )}
          </>
        ) : (
          <span className="mt-1 block text-[11px] text-ink-500">
            {t('idc.currentName', { name: claim.pharmacistName ?? '' })}
          </span>
        )}
        <span className="block text-[11px] text-ink-400">{dateTime(claim.createdAt)}</span>
      </td>
      <td className="px-3 py-2.5 align-top">
        <span className="num block font-bold text-ink-900">
          {showIin ? claim.iin : maskedIin(claim.iin)}
        </span>
        <span className="block font-semibold text-ink-800">{claim.fullName}</span>
      </td>
      <td className="px-3 py-2.5 align-top text-ink-700">
        {claim.pharmacyName ?? t('idc.noPharmacy')}
      </td>
      <td className="px-3 py-2.5 align-top">
        {claim.conflicts.length === 0 ? (
          <span className="text-ink-400">-</span>
        ) : (
          <ul className="space-y-1">
            {claim.conflicts.map((conflict, index) => (
              <li key={`${conflict.code}-${index}`} className="font-semibold text-accent-warning">
                {conflictText(t, conflict)}
              </li>
            ))}
          </ul>
        )}
      </td>
      <td className="px-3 py-2.5 text-right align-top">
        {pending ? (
          <div className="flex items-center justify-end gap-2">
            <Button size="sm" disabled={busy} onClick={onConfirm}>
              {t('idc.confirm')}
            </Button>
            <Button size="sm" variant="outline" disabled={busy} onClick={onReject}>
              {t('idc.reject')}
            </Button>
          </div>
        ) : (
          <div>
            <span
              className={`chip ${
                claim.status === 'confirmed'
                  ? 'chip-green'
                  : claim.status === 'rejected'
                    ? 'chip-red'
                    : 'chip-amber'
              }`}
            >
              {t(
                claim.status === 'confirmed'
                  ? 'idc.statusConfirmed'
                  : claim.status === 'rejected'
                    ? 'idc.statusRejected'
                    : 'idc.statusSuperseded',
              )}
            </span>
            {claim.decisionReason && claim.status === 'rejected' && (
              <span className="mt-1 block text-[11px] text-ink-500">
                {t('idc.reasonLabel', { reason: claim.decisionReason })}
              </span>
            )}
            {claim.decidedAt && (
              <span className="mt-1 block text-[11px] text-ink-400">
                {[claim.decidedByName, dateTime(claim.decidedAt)].filter(Boolean).join(', ')}
              </span>
            )}
          </div>
        )}
      </td>
    </tr>
  )
}
