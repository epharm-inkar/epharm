import { useState } from 'react'
import { useAdministrativeCapabilities } from '@/app/useAdministrativeCapabilities'
import { Button, Drawer, Empty, Field, Input, Select, useToast } from '@/ui'
import { describeError } from '@/lib/describeError'
import { formatKzt } from '@/mocks/fixtures'
import ReportDownloadButton from '@/features/reports/ReportDownloadButton'
import { reportPeriodError } from '@/features/reports/campaignReports'
import { rewardReason, rewardStates } from './recommendation-reward-labels'
import {
  useRecommendationReward,
  useRecommendationRewards,
  useRewardDecision,
  type RewardDecision,
  type RewardDetail,
  type RewardFilter,
  type RewardState,
} from './recommendation-rewards-api'

const date = (value: string) => {
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime())
    ? 'Нет данных'
    : parsed.toLocaleString('ru-RU', { timeZone: 'Asia/Almaty' })
}
const actualCredit = (item: RewardDetail) =>
  item.state === 'credited'
    ? formatKzt(item.totalBonus)
    : item.state === 'refund_review'
      ? 'Ранее начисленное требует сверки'
      : 'Не начислено'

export default function RecommendationRewardsQueue() {
  const access = useAdministrativeCapabilities()
  if (access.isPending || (access.isFetching && !access.isFetchedAfterMount))
    return <p role="status">Проверяем доступ…</p>
  if (!access.canViewRecommendationRewards)
    return (
      <Empty
        title="Нет доступа к продажам по рекомендациям"
        body="Нужно право на просмотр сверки начислений в вашей области."
        action={
          access.isError ? (
            <Button onClick={() => access.refetch()}>Повторить проверку доступа</Button>
          ) : undefined
        }
      />
    )
  return (
    <RewardsWorkspace
      canReview={access.canReviewRecommendationRewards}
      canExport={access.canExportRecommendationRewards}
    />
  )
}

function RewardsWorkspace({ canReview, canExport }: { canReview: boolean; canExport: boolean }) {
  const [filter, setFilter] = useState<RewardFilter>({ state: 'review_required' })
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [offset, setOffset] = useState(0)
  const [selected, setSelected] = useState<string | null>(null)
  const list = useRecommendationRewards(filter, offset)
  const invalid = from || to ? reportPeriodError(from, to) : undefined
  const changed = from !== (filter.from ?? '') || to !== (filter.to ?? '')
  const replace = (next: RewardFilter) => {
    setFilter(next)
    setOffset(0)
  }
  return (
    <div className="flex min-w-0 flex-col gap-4" data-testid="recommendation-rewards-queue">
      <p className="text-sm text-ink-500">
        Бонус за полные упаковки рекомендованного товара. Показы, расчёт и фактическое начисление
        различаются. Неполные доказательства требуют проверки.
      </p>
      <div className="card flex flex-wrap items-end gap-3 p-4">
        <Field label="Статус начисления">
          <Select
            ariaLabel="Статус начисления"
            value={filter.state ?? 'all'}
            onChange={(state) =>
              replace({ ...filter, state: state === 'all' ? undefined : (state as RewardState) })
            }
            options={[
              { value: 'all', label: 'Все статусы' },
              ...Object.entries(rewardStates).map(([value, label]) => ({ value, label })),
            ]}
          />
        </Field>
        <Field label="С даты">
          <Input
            type="date"
            aria-label="Начало периода продаж"
            value={from}
            onChange={(e) => setFrom(e.target.value)}
          />
        </Field>
        <Field label="По дату включительно">
          <Input
            type="date"
            aria-label="Окончание периода продаж"
            value={to}
            onChange={(e) => setTo(e.target.value)}
          />
        </Field>
        <Button
          variant="outline"
          disabled={!!invalid || list.isFetching}
          onClick={() =>
            changed
              ? replace({ ...filter, from: from || undefined, to: to || undefined })
              : void list.refetch()
          }
        >
          Показать
        </Button>
        <ReportDownloadButton
          allowed={canExport}
          path="/api/admin/reconcile/recommendation-rewards/export.xlsx"
          params={{ state: filter.state, from: filter.from, to: filter.to }}
          filename="epharm-recommendation-rewards.xlsx"
          label="Excel: продажи по рекомендациям"
          invalid={changed ? 'Сначала примените выбранные даты' : invalid}
        />
      </div>
      {invalid && (
        <p role="alert" className="text-sm text-accent-danger">
          {invalid}
        </p>
      )}
      {changed && !invalid && (
        <p className="text-sm text-ink-500">
          Даты изменены. Нажмите «Показать», чтобы обновить очередь и Excel.
        </p>
      )}
      {list.isLoading ? (
        <Empty title="Загружаем продажи по рекомендациям" />
      ) : list.isError ? (
        <Empty
          title="Не удалось загрузить начисления"
          body={describeError(list.error)}
          action={<Button onClick={() => list.refetch()}>Повторить загрузку</Button>}
        />
      ) : !list.data ? (
        <Empty title="Данные о начислениях не получены" />
      ) : (
        <>
          <div className="card overflow-x-auto">
            <table className="w-full text-left text-[13px]">
              <thead className="hairline border-b text-ink-500">
                <tr>
                  {[
                    'Продажа / аптека',
                    'Фармацевт / товар',
                    'Состояние и причина',
                    'Бонус',
                    '',
                  ].map((label, i) => (
                    <th key={i} className="px-4 py-3">
                      {label}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-ink-100">
                {list.data.items.map((item) => (
                  <tr key={item.id}>
                    <td className="px-4 py-3">
                      <div>{item.sourceSaleId}</div>
                      <div className="text-ink-500">{item.pharmacyName || item.pharmacyId}</div>
                      <div className="text-xs text-ink-400">{date(item.createdAt)}</div>
                    </td>
                    <td className="px-4 py-3">
                      <div>
                        {item.pharmacistName || item.pharmacistId || 'Получатель не подтверждён'}
                      </div>
                      <div className="text-ink-500">{item.productName || item.productId}</div>
                    </td>
                    <td className="min-w-56 px-4 py-3">
                      <span
                        className={`chip ${item.state === 'credited' ? 'chip-green' : 'chip-amber'}`}
                      >
                        {rewardStates[item.state] ?? item.state}
                      </span>
                      <p className="mt-1 text-xs text-ink-500">{rewardReason(item.reasonCode)}</p>
                    </td>
                    <td className="px-4 py-3">
                      {actualCredit(item)}
                      <div className="text-xs text-ink-500">
                        {item.unitBonus > 0
                          ? `${formatKzt(item.unitBonus)} за упаковку`
                          : 'Бонус не подтверждён'}
                      </div>
                    </td>
                    <td className="px-4 py-3">
                      <Button size="sm" variant="outline" onClick={() => setSelected(item.id)}>
                        Открыть проверку
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {list.data.items.length === 0 && <Empty title="По выбранным фильтрам записей нет" />}
          </div>
          <div className="flex flex-wrap items-center justify-between gap-3 text-sm">
            <p role="status">
              Всего: {list.data.total}
              {list.isFetching ? ' · обновляем…' : ''}
            </p>
            <div className="flex gap-2">
              <Button
                variant="outline"
                disabled={offset === 0 || list.isFetching}
                onClick={() => setOffset(Math.max(0, offset - 50))}
              >
                Назад
              </Button>
              <Button
                variant="outline"
                disabled={offset + list.data.limit >= list.data.total || list.isFetching}
                onClick={() => setOffset(offset + 50)}
              >
                Далее
              </Button>
            </div>
          </div>
        </>
      )}
      <RewardDrawer id={selected} onClose={() => setSelected(null)} canReview={canReview} />
    </div>
  )
}

function RewardDrawer({
  id,
  onClose,
  canReview,
}: {
  id: string | null
  onClose: () => void
  canReview: boolean
}) {
  const detail = useRecommendationReward(id)
  return (
    <Drawer open={!!id} onClose={onClose} title="Проверка продажи по рекомендации" width={680}>
      {detail.isLoading ? (
        <p role="status">Загружаем доказательства…</p>
      ) : detail.isError ? (
        <Empty
          title="Не удалось загрузить доказательства"
          body={describeError(detail.error)}
          action={
            <Button onClick={() => detail.refetch()}>Повторить загрузку доказательств</Button>
          }
        />
      ) : (
        detail.data && (
          <div className="flex flex-col gap-4">
            <dl className="grid grid-cols-1 gap-3 text-sm sm:grid-cols-2">
              <Datum label="Статус" value={rewardStates[detail.data.state] ?? detail.data.state} />
              <Datum label="Причина" value={rewardReason(detail.data.reasonCode)} />
              <Datum label="Аптека" value={detail.data.pharmacyName || detail.data.pharmacyId} />
              <Datum
                label="Получатель"
                value={detail.data.pharmacistName || detail.data.pharmacistId || 'Не подтверждён'}
              />
              <Datum label="Товар" value={detail.data.productName || detail.data.productId} />
              <Datum label="Устройство" value={detail.data.deviceId} />
              <Datum
                label="Бонус за полную упаковку"
                value={
                  detail.data.unitBonus > 0 ? formatKzt(detail.data.unitBonus) : 'Не подтверждён'
                }
              />
              <Datum
                label="Подтверждённое количество упаковок"
                value={
                  detail.data.eligiblePackages > 0
                    ? detail.data.eligiblePackages
                    : 'Не подтверждено'
                }
              />
              <Datum label="Фактически начислено" value={actualCredit(detail.data)} />
              <Datum label="Резерв бюджета" value={detail.data.budgetReserved ? 'Да' : 'Нет'} />
              <Datum label="Продажа" value={detail.data.sourceSaleId} />
              <Datum label="Физический чек" value={detail.data.canonicalReceiptId} />
            </dl>
            <Evidence detail={detail.data} />
            {canReview && detail.isFetchedAfterMount && !detail.isFetching && (
              <DecisionForm
                key={`${detail.data.id}:${detail.data.updatedAt}`}
                detail={detail.data}
                onSuccess={onClose}
              />
            )}
            {!canReview && (
              <p className="text-sm text-ink-500">
                Доступен только просмотр. Для решения нужно отдельное право на проверку начислений.
              </p>
            )}
          </div>
        )
      )}
    </Drawer>
  )
}

function Datum({ label, value }: { label: string; value: unknown }) {
  return (
    <div className="min-w-0">
      <dt className="text-xs text-ink-500">{label}</dt>
      <dd className="mt-1 break-words font-medium">
        {typeof value === 'string' || typeof value === 'number' ? String(value) : 'Нет данных'}
      </dd>
    </div>
  )
}
const record = (value: unknown): Record<string, unknown> | null =>
  value && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null
function Evidence({ detail }: { detail: RewardDetail }) {
  const sale = record(detail.sale)
  const evidence = record(detail.rewardEvidence) ?? record(sale?.rewardEvidence)
  const completion = record(evidence?.completion)
  const identity = record(completion?.identity)
  const changes = Array.isArray(evidence?.changes)
    ? evidence.changes.map(record).filter((v): v is Record<string, unknown> => !!v)
    : []
  return (
    <section className="card-soft space-y-3 p-4">
      <h3 className="font-bold">Доказательства кассы</h3>
      {!evidence && (
        <p className="text-sm text-ink-500">
          Полные доказательства не получены. Факт оплаты и полные упаковки нельзя считать
          подтверждёнными.
        </p>
      )}
      <dl className="grid grid-cols-1 gap-3 text-sm sm:grid-cols-2">
        <Datum
          label="Состояние оплаты"
          value={completion?.status === 'paid' ? 'Оплачен' : 'Не подтверждена'}
        />
        <Datum label="Источник оплаты" value={completion?.source} />
        <Datum
          label="Документ Стандарт-Н"
          value={completion?.sourceDocumentId ?? sale?.sourceDocumentId}
        />
        <Datum label="Статус документа" value={completion?.documentStatus} />
        <Datum label="Время оплаты" value={completion?.completedAt} />
        <Datum label="Знаковый итог чека" value={completion?.signedTotal} />
        <Datum
          label="Фискальный номер / смена"
          value={
            completion
              ? `${completion.fiscalNumber ?? '—'} / ${completion.fiscalShift ?? '—'}`
              : null
          }
        />
        <Datum
          label="Финальные строки прочитаны"
          value={
            completion?.finalDetailsRead === true
              ? 'Да'
              : completion?.finalDetailsRead === false
                ? 'Нет'
                : null
          }
        />
        <Datum label="USER Стандарт-Н" value={identity?.rawUserId} />
        <Datum label="Кассовая сессия" value={identity?.nativeSessionId} />
        <Datum label="Рабочее место" value={identity?.workstationId} />
        <Datum
          label="Сверка сотрудника"
          value={
            identity?.identityStatus === 'match'
              ? 'Совпадает'
              : identity?.identityStatus === 'mismatch'
                ? 'Не совпадает'
                : 'Не выполнена'
          }
        />
      </dl>
      <details>
        <summary className="cursor-pointer text-sm font-semibold">
          История изменения строк: {changes.length || 'нет данных'}
        </summary>
        {changes.length > 0 && (
          <div className="mt-3 max-h-64 overflow-auto">
            <table className="w-full text-left text-xs">
              <thead>
                <tr>
                  <th>Время</th>
                  <th>Строка / товар</th>
                  <th>Было → стало</th>
                  <th>Единицы</th>
                </tr>
              </thead>
              <tbody>
                {changes.map((change, index) => (
                  <tr key={index} className="border-t border-ink-100">
                    <td className="p-2">{String(change.observedAt ?? '—')}</td>
                    <td className="p-2">
                      {String(change.sourceLineId ?? '—')} /{' '}
                      {String(change.sku ?? change.barcode ?? '—')}
                    </td>
                    <td className="p-2">
                      {String(change.beforeQty ?? '—')} → {String(change.afterQty ?? '—')}
                    </td>
                    <td className="p-2">
                      {change.unitSemantics === 'full_package'
                        ? 'Полные упаковки'
                        : 'Не подтверждены'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </details>
    </section>
  )
}

function DecisionForm({ detail, onSuccess }: { detail: RewardDetail; onSuccess: () => void }) {
  const decision = useRewardDecision()
  const toast = useToast()
  const actions: { value: RewardDecision['action']; label: string }[] =
    detail.state === 'review_required'
      ? [
          { value: 'approve', label: 'Подтвердить и начислить' },
          { value: 'reject', label: 'Отклонить' },
        ]
      : detail.state === 'waiting_evidence'
        ? [{ value: 'reject', label: 'Отклонить' }]
        : detail.state === 'refund_review'
          ? [{ value: 'acknowledge_refund', label: 'Зафиксировать проверку возврата' }]
          : []
  const [action, setAction] = useState<RewardDecision['action']>(actions[0]?.value ?? 'reject')
  const [reason, setReason] = useState('')
  const [pharmacist, setPharmacist] = useState(
    detail.pharmacistId &&
      (!detail.eligiblePharmacists ||
        detail.eligiblePharmacists.some((person) => person.id === detail.pharmacistId))
      ? detail.pharmacistId
      : '',
  )
  const [packages, setPackages] = useState(String(detail.eligiblePackages || ''))
  const [payment, setPayment] = useState(false)
  const [units, setUnits] = useState(false)
  const qty = Number(packages)
  const recipientEligible =
    pharmacist.trim().length > 0 &&
    (!detail.eligiblePharmacists ||
      detail.eligiblePharmacists.some((person) => person.id === pharmacist))
  const total = qty * detail.unitBonus
  const validAmount = Number.isSafeInteger(total) && total > 0
  const valid =
    reason.trim().length >= 3 &&
    reason.trim().length <= 2000 &&
    (action !== 'approve' ||
      (recipientEligible &&
        /^[0-9]+$/.test(packages) &&
        Number.isSafeInteger(qty) &&
        qty > 0 &&
        qty <= 1_000_000 &&
        validAmount &&
        payment &&
        units))
  if (actions.length === 0)
    return (
      <p className="text-sm text-ink-500">
        Для этого состояния ручное начисление недоступно. Повторное начисление не выполняется.
      </p>
    )
  const submit = () => {
    if (!valid || decision.isPending) return
    const body: RewardDecision = {
      action,
      reason: reason.trim(),
      ...(action === 'approve'
        ? {
            pharmacistId: pharmacist.trim(),
            eligiblePackages: qty,
            confirmedPayment: payment,
            confirmedPackages: units,
          }
        : {}),
    }
    decision.mutate(
      { id: detail.id, decision: body },
      {
        onSuccess: () => {
          toast.push('Решение сохранено')
          onSuccess()
        },
      },
    )
  }
  return (
    <section className="space-y-3 border-t border-ink-100 pt-4">
      <h3 className="font-bold">Решение проверяющего</h3>
      <Field label="Действие">
        <Select
          ariaLabel="Решение по начислению"
          value={action}
          onChange={(value) => setAction(value as RewardDecision['action'])}
          options={actions}
          disabled={decision.isPending}
        />
      </Field>
      {action === 'approve' && (
        <>
          <Field
            label="Получатель бонуса"
            hint="Сервер проверит, что фармацевт активен и назначен в эту аптеку."
          >
            {detail.eligiblePharmacists ? (
              <Select
                ariaLabel="Получатель бонуса"
                value={pharmacist}
                onChange={setPharmacist}
                disabled={decision.isPending}
                options={detail.eligiblePharmacists.map((person) => ({
                  value: person.id,
                  label: `${person.name} (${person.id})`,
                }))}
              />
            ) : (
              <Input
                aria-label="ID получателя бонуса"
                value={pharmacist}
                onChange={(e) => setPharmacist(e.target.value)}
                disabled={decision.isPending}
              />
            )}
          </Field>
          <Field label="Подтверждённые полные упаковки">
            <Input
              aria-label="Подтверждённые полные упаковки"
              type="number"
              min="1"
              max="1000000"
              step="1"
              value={packages}
              onChange={(e) => setPackages(e.target.value)}
              disabled={decision.isPending}
            />
          </Field>
          <label className="flex items-start gap-2 text-sm">
            <input
              type="checkbox"
              checked={payment}
              onChange={(e) => setPayment(e.target.checked)}
              disabled={decision.isPending}
            />
            Оплату этого чека проверил(а)
          </label>
          <label className="flex items-start gap-2 text-sm">
            <input
              type="checkbox"
              checked={units}
              onChange={(e) => setUnits(e.target.checked)}
              disabled={decision.isPending}
            />
            Количество именно полных упаковок проверил(а)
          </label>
          <p className="text-sm text-ink-500">
            По введённому количеству: {validAmount ? formatKzt(total) : 'сумма не подтверждена'}.
            Окончательные условия и бюджет проверяет сервер.
          </p>
        </>
      )}
      {action === 'acknowledge_refund' && (
        <p className="text-sm text-ink-500">
          Только запись результата проверки. Деньги автоматически не начисляются и не списываются.
        </p>
      )}
      <Field label="Основание решения — обязательно" hint="От 3 до 2000 символов.">
        <textarea
          className="inp min-h-24"
          aria-label="Основание решения"
          value={reason}
          minLength={3}
          maxLength={2000}
          onChange={(e) => setReason(e.target.value)}
          disabled={decision.isPending}
        />
      </Field>
      {decision.isError && (
        <p role="alert" className="text-sm text-accent-danger">
          {describeError(decision.error)} Перед повторной попыткой обновите запись и проверьте её
          состояние.
        </p>
      )}
      {decision.isError && (
        <Button variant="outline" onClick={onSuccess}>
          Закрыть и проверить запись заново
        </Button>
      )}
      <Button disabled={!valid || decision.isPending || decision.isError} onClick={submit}>
        {decision.isPending ? 'Сохраняем…' : 'Сохранить решение'}
      </Button>
    </section>
  )
}
