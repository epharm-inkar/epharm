// Карточка обучения фармацевта и смена формата обучения по умолчанию (перенесены из PharmacistsPage без изменений).

import { useState } from 'react'
import { Button, Field, Input, Modal, Select, useToast } from '@/ui'
import type { PharmacistDto, TrainingFormat, TrainingPreferenceDto } from '@/lib/api-types'
import {
  useChangeTrainingPreference,
  useMassChangeTrainingPreferences,
  usePharmacistTrainingProfile,
  useTrainingPreferenceHistory,
} from '@/lib/queries/lms'
import { describeError } from '@/lib/describeError'
import { formatKzt, formatNum } from '@/mocks/fixtures'
import { ASSIGNMENT_STATUS_LABEL, FORMAT_LABEL, dateTime } from '@/features/lms/training-ui'

export function PharmacistTrainingProfileModal({
  pharmacistId,
  onClose,
}: {
  pharmacistId: string
  onClose: () => void
}) {
  const profile = usePharmacistTrainingProfile(pharmacistId)
  const data = profile.data
  return (
    <Modal
      open
      onClose={onClose}
      title={data?.pharmacistName ?? 'Карточка обучения'}
      subtitle={
        data
          ? `${data.pharmacyName || 'Аптека не назначена'} · ${data.city}`
          : 'Загружаем историю обучения…'
      }
      width={960}
      footer={<Button onClick={onClose}>Закрыть</Button>}
    >
      {profile.isLoading ? (
        <div className="py-12 text-center text-[13px] text-ink-500">Загружаем карточку…</div>
      ) : profile.isError || !data ? (
        <div className="rounded-lg bg-surface-danger px-4 py-3 text-[13px] font-semibold text-accent-danger">
          {describeError(profile.error)}
        </div>
      ) : (
        <div className="flex flex-col gap-5">
          <div className="grid grid-cols-4 gap-3">
            <div className="rounded-lg bg-paper-hover px-4 py-3">
              <div className="text-[10px] font-bold uppercase text-ink-500">Назначено</div>
              <div className="num mt-1 text-xl font-extrabold">
                {formatNum(data.totalAssignments)}
              </div>
            </div>
            <div className="rounded-lg bg-paper-hover px-4 py-3">
              <div className="text-[10px] font-bold uppercase text-ink-500">Завершено</div>
              <div className="num mt-1 text-xl font-extrabold text-brand-green-700">
                {formatNum(data.completedAssignments)}
              </div>
            </div>
            <div className="rounded-lg bg-paper-hover px-4 py-3">
              <div className="text-[10px] font-bold uppercase text-ink-500">В работе</div>
              <div className="num mt-1 text-xl font-extrabold">
                {formatNum(data.inProgressAssignments)}
              </div>
            </div>
            <div className="rounded-lg bg-paper-hover px-4 py-3">
              <div className="text-[10px] font-bold uppercase text-ink-500">Бонусы за обучение</div>
              <div className="num mt-1 text-xl font-extrabold text-brand-green-700">
                {data.totalRewards == null ? 'Нет доступа' : formatKzt(data.totalRewards)}
              </div>
            </div>
          </div>

          <div className="flex items-center justify-between rounded-lg border border-ink-100 px-4 py-3 text-[13px]">
            <span className="font-semibold text-ink-600">Формат по умолчанию</span>
            <span className="chip chip-ink">
              {data.defaultFormat ? FORMAT_LABEL[data.defaultFormat] : 'Не задан'}
            </span>
          </div>

          <section>
            <h3 className="mb-2 text-[11px] font-bold uppercase text-ink-500">
              Назначения и результаты
            </h3>
            {data.assignments.length === 0 ? (
              <div className="rounded-lg border border-ink-100 px-4 py-5 text-center text-[12px] text-ink-500">
                Обучение ещё не назначалось
              </div>
            ) : (
              <div className="max-h-64 overflow-auto rounded-lg border border-ink-100">
                <table className="w-full text-[12px]">
                  <thead className="sticky top-0 bg-white text-left text-[10px] font-bold uppercase text-ink-500">
                    <tr>
                      <th className="px-3 py-2">Программа</th>
                      <th className="px-3 py-2">Формат</th>
                      <th className="px-3 py-2">Статус</th>
                      <th className="px-3 py-2">Прогресс</th>
                      <th className="px-3 py-2">Результат</th>
                      <th className="px-3 py-2">Завершено</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-ink-100">
                    {data.assignments.map((assignment) => (
                      <tr key={assignment.id}>
                        <td className="px-3 py-2.5 font-bold text-ink-800">
                          {assignment.programName}
                        </td>
                        <td className="px-3 py-2.5">{FORMAT_LABEL[assignment.format]}</td>
                        <td className="px-3 py-2.5">
                          {ASSIGNMENT_STATUS_LABEL[assignment.status]}
                        </td>
                        <td className="num px-3 py-2.5">{assignment.progressPct}%</td>
                        <td className="num px-3 py-2.5">
                          {assignment.score == null ? '—' : `${assignment.score}%`}
                        </td>
                        <td className="num whitespace-nowrap px-3 py-2.5 text-ink-500">
                          {assignment.completedAt ? dateTime(assignment.completedAt) : '—'}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>

          <div className="grid grid-cols-2 gap-4">
            <section>
              <h3 className="mb-2 text-[11px] font-bold uppercase text-ink-500">Сертификаты</h3>
              <div className="max-h-44 divide-y divide-ink-100 overflow-auto rounded-lg border border-ink-100">
                {data.certificates.length ? (
                  data.certificates.map((certificate) => (
                    <a
                      key={certificate.id}
                      href={certificate.pdfUrl ?? undefined}
                      target="_blank"
                      rel="noreferrer"
                      className="flex items-center justify-between gap-3 px-3 py-2.5 hover:bg-paper-hover"
                    >
                      <span>
                        <b className="block text-[12px] text-ink-800">{certificate.programName}</b>
                        <span className="num text-[11px] text-ink-500">{certificate.number}</span>
                      </span>
                      <span className="num text-[11px] font-bold text-brand-green-700">
                        {certificate.score == null ? 'PDF' : `${certificate.score}% · PDF`}
                      </span>
                    </a>
                  ))
                ) : (
                  <div className="px-3 py-5 text-center text-[12px] text-ink-500">Пока нет</div>
                )}
              </div>
            </section>
            <section>
              <h3 className="mb-2 text-[11px] font-bold uppercase text-ink-500">История формата</h3>
              <div className="max-h-44 divide-y divide-ink-100 overflow-auto rounded-lg border border-ink-100">
                {data.preferenceHistory.length ? (
                  data.preferenceHistory.map((preference) => (
                    <div key={preference.id} className="px-3 py-2.5 text-[12px]">
                      <div className="font-bold text-ink-800">
                        {FORMAT_LABEL[preference.defaultFormat]}
                      </div>
                      <div className="text-[11px] text-ink-500">
                        {dateTime(preference.validFrom)} · {preference.changedByName}
                      </div>
                      {preference.reason && (
                        <div className="mt-0.5 text-ink-600">{preference.reason}</div>
                      )}
                    </div>
                  ))
                ) : (
                  <div className="px-3 py-5 text-center text-[12px] text-ink-500">
                    Формат не менялся
                  </div>
                )}
              </div>
            </section>
          </div>
        </div>
      )}
    </Modal>
  )
}

export function TrainingPreferenceModal({
  pharmacists,
  current,
  onClose,
  onSaved,
}: {
  pharmacists: PharmacistDto[] | null
  current: TrainingPreferenceDto | null
  onClose: () => void
  onSaved: () => void
}) {
  const toast = useToast()
  const single = pharmacists?.length === 1 ? pharmacists[0] : null
  const history = useTrainingPreferenceHistory(single?.id ?? null)
  const change = useChangeTrainingPreference()
  const massChange = useMassChangeTrainingPreferences()
  const [format, setFormat] = useState<TrainingFormat>('online')
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)

  const submit = () => {
    if (!pharmacists?.length) return
    if (!reason.trim()) return setError('Укажите причину изменения формата')
    const callbacks = {
      onSuccess: () => {
        toast.push(
          pharmacists.length === 1
            ? 'Формат обучения по умолчанию изменён'
            : `Формат изменён у ${pharmacists.length} фармацевтов`,
        )
        onSaved()
      },
      onError: (requestError: unknown) => setError(describeError(requestError)),
    }
    if (single) {
      change.mutate(
        {
          pharmacistId: single.id,
          request: { defaultFormat: format, reason: reason.trim() },
        },
        callbacks,
      )
    } else {
      massChange.mutate(
        {
          pharmacistIds: pharmacists.map((pharmacist) => pharmacist.id),
          defaultFormat: format,
          reason: reason.trim(),
        },
        callbacks,
      )
    }
  }

  return (
    <Modal
      open={!!pharmacists?.length}
      onClose={onClose}
      title="Формат обучения по умолчанию"
      subtitle={
        single
          ? single.name
          : pharmacists?.length
            ? `Выбрано фармацевтов: ${pharmacists.length}`
            : undefined
      }
      width={620}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Отмена
          </Button>
          <Button disabled={change.isPending || massChange.isPending} onClick={submit}>
            Сохранить
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        {error && (
          <div className="rounded-lg bg-surface-danger px-3 py-2 text-[12px] font-semibold text-accent-danger">
            {error}
          </div>
        )}
        <Field label="Формат">
          <Select
            value={format}
            onChange={(value) => setFormat(value as TrainingFormat)}
            options={Object.entries(FORMAT_LABEL).map(([value, label]) => ({ value, label }))}
          />
        </Field>
        <Field label="Причина изменения">
          <Input
            value={reason}
            onChange={(event) => setReason(event.target.value)}
            placeholder="Например: новый график обучения"
          />
        </Field>
        {current && (
          <div className="rounded-lg bg-paper-hover px-3 py-2 text-[12px] text-ink-600">
            Сейчас: <b>{FORMAT_LABEL[current.defaultFormat]}</b> · {current.changedByName} ·{' '}
            {dateTime(current.validFrom)}
            {current.reason && <div className="mt-1">{current.reason}</div>}
          </div>
        )}
        {single && (
          <div>
            <div className="mb-2 text-[11px] font-bold uppercase text-ink-500">
              История изменений
            </div>
            {history.isLoading ? (
              <div className="text-[12px] text-ink-500">Загружаем историю…</div>
            ) : history.data?.length ? (
              <div className="max-h-44 divide-y divide-ink-100 overflow-auto rounded-lg border border-ink-100">
                {history.data.map((row) => (
                  <div key={row.id} className="px-3 py-2 text-[12px]">
                    <div className="font-bold text-ink-800">{FORMAT_LABEL[row.defaultFormat]}</div>
                    <div className="text-ink-500">
                      {dateTime(row.validFrom)} · {row.changedByName}
                    </div>
                    {row.reason && <div className="mt-0.5 text-ink-600">{row.reason}</div>}
                  </div>
                ))}
              </div>
            ) : (
              <div className="text-[12px] text-ink-500">Изменений ещё не было</div>
            )}
          </div>
        )}
      </div>
    </Modal>
  )
}
