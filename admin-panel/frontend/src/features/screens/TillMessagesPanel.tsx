// «Уведомления на кассах» (Григорий, 25.11.2026): HQ пишет уведомление, выбирает адресатов и отправляет. Касса
// ePharm 1.0.99+ показывает его фармацевту в углу экрана, пока тот не закроет крестиком; справа видно, сколько
// человек увидели и закрыли. Отправка и снятие только у HQ.
//
// 26.11.2026: кому можно отправить «не прошли курс» (и одной кнопкой «Напомнить о курсе»), конкретным людям (поиск по
// фамилии, имени, телефону, ИИН, аптеке) и любым адресатам «только тем, кто сейчас за кассой». До отправки сервер
// считает, сколько человек получат и сколько из них за кассой сейчас.

import { useMemo, useState } from 'react'
import { Button, Field, Input, Modal, SectionCard, Select, Toggle, useToast } from '@/ui'
import { useT } from '@/i18n'
import { describeError } from '@/lib/describeError'
import { usePharmacies } from '@/lib/queries/pharmacies'
import {
  POSM_PREVIEW_DEBOUNCE_MS,
  usePosmCourses,
  usePosmMessagePreview,
  usePosmMessages,
  useSendPosmMessage,
  useWithdrawPosmMessage,
} from '@/lib/queries/posmMessages'
import type { PosmAudienceRequest, PosmMessageAudience, PosmMessageDto, PosmPersonDto } from '@/lib/api-types'
import { useDebouncedValue } from '@/lib/useDebouncedValue'
import { AudiencePreview } from './AudiencePreview'
import { PharmacistSearchPicker } from './PharmacistSearchPicker'
import { RecipientPicker, type PickerItem } from './RecipientPicker'
import { TillMessagesList } from './TillMessagesList'
import {
  DAY_OPTIONS,
  EMPTY_DRAFT,
  TEXT_MAX,
  TITLE_MAX,
  audienceLabel,
  buildAudienceRequest,
  buildRequest,
  courseReminder,
  draftError,
  type TillMessageDraft,
} from './tillMessages'

const AUDIENCES: PosmMessageAudience[] = ['all', 'pharmacies', 'pharmacists', 'course']

export function TillMessagesPanel() {
  const t = useT()
  const toast = useToast()
  const [draft, setDraft] = useState<TillMessageDraft>(EMPTY_DRAFT)
  const [people, setPeople] = useState<PosmPersonDto[]>([])
  // Заголовок и текст, которые подставила кнопка «Напомнить о курсе»: пока их не правили, смена курса их обновляет.
  const [autoFilled, setAutoFilled] = useState<{ title: string; text: string } | null>(null)
  const [tried, setTried] = useState(false)
  const [confirming, setConfirming] = useState(false)
  const [withdrawing, setWithdrawing] = useState<PosmMessageDto | null>(null)
  const messages = usePosmMessages()
  const send = useSendPosmMessage()
  const withdraw = useWithdrawPosmMessage()
  const pharmacies = usePharmacies({}, draft.audience === 'pharmacies')
  const courses = usePosmCourses()
  const courseList = courses.data ?? []

  const audienceRequest = buildAudienceRequest(draft)
  const wantedKey = JSON.stringify(audienceRequest)
  // Подсчёт просим, когда выбор перестали менять: каждый щелчок по галочке не должен давать свой запрос.
  const requestKey = useDebouncedValue(wantedKey, POSM_PREVIEW_DEBOUNCE_MS)
  const preview = usePosmMessagePreview(requestKey === 'null' ? null : (JSON.parse(requestKey) as PosmAudienceRequest))
  // Число показываем, только когда оно про нынешний выбор: пока считают, старое число было бы про другой.
  const previewData = requestKey === wantedKey && !preview.isPlaceholderData ? preview.data : undefined

  const pharmacyItems = useMemo<PickerItem[]>(
    () =>
      (pharmacies.data ?? []).map((pharmacy) => ({
        id: pharmacy.id,
        title: pharmacy.name,
        subtitle: [pharmacy.city, pharmacy.addr].filter(Boolean).join(' · '),
      })),
    [pharmacies.data],
  )

  const error = draftError(draft)
  const nobody = previewData?.frozen === true && previewData.recipients === 0
  const update = (patch: Partial<TillMessageDraft>) => setDraft((current) => ({ ...current, ...patch }))
  const daysLabel = t(`msg.day${draft.days}`)
  const course = courseList.find((item) => item.id === draft.programId)
  // Аптеки сети часто называются одинаково: в подтверждении рядом с названием город и адрес.
  const chosenNames =
    draft.audience === 'pharmacies'
      ? pharmacyItems
          .filter((item) => draft.pharmacyIds.includes(item.id))
          .map((item) => [item.title, item.subtitle].filter(Boolean).join(', '))
      : people.map((person) => person.name)
  const chosenCount = draft.audience === 'pharmacies' ? draft.pharmacyIds.length : draft.pharmacistIds.length
  const summary = audienceLabel(t, {
    audience: draft.audience,
    targetCount: chosenCount,
    targetNames: chosenNames.slice(0, 5),
    onlyAtTill: draft.onlyAtTill,
    programName: course?.name,
  })

  const choosePeople = (next: PosmPersonDto[]) => {
    setPeople(next)
    update({ pharmacistIds: next.map((person) => person.id) })
  }

  // Одна кнопка: курс, который больше всего людей ещё не прошли, только тем, кто сейчас за кассой, и готовый текст.
  const remind = () => {
    const picked = courseList.find((item) => item.id === draft.programId) ?? courseList[0]
    if (!picked) return
    const filled = courseReminder(picked)
    setAutoFilled(filled)
    update({ audience: 'course', programId: picked.id, onlyAtTill: true, title: filled.title, text: filled.text })
  }

  const chooseCourse = (programId: string) => {
    const picked = courseList.find((item) => item.id === programId)
    const untouched = autoFilled !== null && draft.title === autoFilled.title && draft.text === autoFilled.text
    if (picked && untouched) {
      const filled = courseReminder(picked)
      setAutoFilled(filled)
      update({ programId, title: filled.title, text: filled.text })
    } else {
      update({ programId })
    }
  }

  const askToSend = () => {
    setTried(true)
    if (!error && !nobody) setConfirming(true)
  }

  const confirmSend = () => {
    send.mutate(buildRequest(draft), {
      onSuccess: () => {
        toast.push(t('msg.sent'))
        setConfirming(false)
        setTried(false)
        setDraft(EMPTY_DRAFT)
        setPeople([])
        setAutoFilled(null)
      },
      onError: (e) => {
        setConfirming(false)
        toast.push(describeError(e))
      },
    })
  }

  const confirmWithdraw = () => {
    if (!withdrawing) return
    withdraw.mutate(withdrawing.id, {
      onSuccess: () => {
        toast.push(t('msg.withdrawn'))
        setWithdrawing(null)
      },
      onError: (e) => {
        setWithdrawing(null)
        toast.push(describeError(e))
      },
    })
  }

  return (
    <div className="grid gap-4 xl:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]" data-testid="till-messages">
      <SectionCard title={t('msg.composeTitle')} subtitle={t('msg.composeSubtitle')}>
        <div className="flex flex-col gap-4">
          <div className="rounded-xl border border-ink-200 bg-paper-hover p-3" data-testid="till-message-remind">
            <div className="text-[13px] font-bold text-ink-900">{t('msg.remindTitle')}</div>
            <div className="mt-0.5 text-[12px] text-ink-500">{t('msg.remindHint')}</div>
            <div className="mt-2 flex flex-wrap items-center gap-2">
              <Button
                variant="outline"
                onClick={remind}
                disabled={courseList.length === 0}
                data-testid="till-message-remind-button"
              >
                {t('msg.remindButton')}
              </Button>
              {courses.isError && <span className="text-[12px] text-accent-danger">{t('msg.courseLoadError')}</span>}
              {!courses.isError && courses.isLoading && <span className="text-[12px] text-ink-400">{t('msg.remindLoading')}</span>}
              {!courses.isError && !courses.isLoading && courseList.length === 0 && (
                <span className="text-[12px] text-ink-500" data-testid="till-message-remind-none">
                  {t('msg.remindNone')}
                </span>
              )}
            </div>
          </div>

          <Field label={t('msg.title')} hint={t('msg.counter', { n: [...draft.title].length, max: TITLE_MAX })}>
            <Input
              value={draft.title}
              maxLength={TITLE_MAX}
              placeholder={t('msg.titlePlaceholder')}
              onChange={(e) => update({ title: e.target.value })}
              data-testid="till-message-title"
            />
          </Field>
          <Field label={t('msg.text')} hint={t('msg.counter', { n: [...draft.text].length, max: TEXT_MAX })}>
            <textarea
              className="inp min-h-32"
              value={draft.text}
              maxLength={TEXT_MAX}
              placeholder={t('msg.textPlaceholder')}
              onChange={(e) => update({ text: e.target.value })}
              data-testid="till-message-text"
            />
          </Field>

          <div>
            <div className="mb-1.5 text-[12px] font-semibold text-ink-700">{t('msg.audience')}</div>
            <div className="flex flex-wrap gap-2" role="radiogroup" aria-label={t('msg.audience')}>
              {AUDIENCES.map((audience) => (
                <button
                  key={audience}
                  type="button"
                  role="radio"
                  aria-checked={draft.audience === audience}
                  onClick={() => update({ audience })}
                  className={`rounded-lg border px-3 py-2 text-[13px] font-semibold ${
                    draft.audience === audience
                      ? 'border-brand-green-600 bg-brand-green-50 text-brand-green-700'
                      : 'border-ink-200 bg-white text-ink-700 hover:bg-paper-hover'
                  }`}
                  data-testid={`till-message-audience-${audience}`}
                >
                  {t(`msg.audience.${audience}`)}
                </button>
              ))}
            </div>
            <div className="mt-1.5 text-[12px] text-ink-500">{t(`msg.audienceHint.${draft.audience}`)}</div>
          </div>

          {draft.audience === 'pharmacies' && (
            <RecipientPicker
              items={pharmacyItems}
              selected={draft.pharmacyIds}
              onChange={(pharmacyIds) => update({ pharmacyIds })}
              loading={pharmacies.isLoading}
              failed={pharmacies.isError}
              searchPlaceholder={t('msg.searchPharmacies')}
              testId="till-message-pharmacies"
            />
          )}
          {draft.audience === 'pharmacists' && (
            <PharmacistSearchPicker selected={people} onChange={choosePeople} testId="till-message-pharmacists" />
          )}
          {draft.audience === 'course' && (
            <Field label={t('msg.course')}>
              <Select
                ariaLabel={t('msg.course')}
                value={draft.programId}
                placeholder={courses.isLoading ? t('msg.loading') : courseList.length === 0 ? t('msg.courseNone') : t('msg.coursePlaceholder')}
                onChange={chooseCourse}
                options={courseList.map((item) => ({ value: item.id, label: t('msg.courseOption', { name: item.name, n: item.openCount }) }))}
              />
            </Field>
          )}

          <div className="flex flex-col gap-1" data-testid="till-message-only-at-till">
            <Toggle on={draft.onlyAtTill} onChange={(onlyAtTill) => update({ onlyAtTill })} label={t('msg.onlyAtTill')} />
            <div className="text-[12px] text-ink-500">{t('msg.onlyAtTillHint', { n: previewData?.windowMinutes ?? 20 })}</div>
          </div>

          <AudiencePreview
            enabled={audienceRequest !== null}
            data={previewData}
            loading={preview.isLoading || preview.isFetching || requestKey !== wantedKey}
            failed={preview.isError}
          />

          <Field label={t('msg.days')}>
            <Select
              ariaLabel={t('msg.days')}
              value={String(draft.days)}
              onChange={(value) => update({ days: Number(value) })}
              options={DAY_OPTIONS.map((days) => ({ value: String(days), label: t(`msg.day${days}`) }))}
            />
          </Field>

          <Preview title={draft.title} text={draft.text} />

          {tried && (error || nobody) && (
            <div className="text-[12px] font-semibold text-accent-danger" role="alert" data-testid="till-message-error">
              {t(error ?? 'msg.errNobody')}
            </div>
          )}
          <div className="flex flex-wrap items-center justify-between gap-3">
            <span className="text-[12px] text-ink-500">{t('msg.whenHint')}</span>
            <Button variant="primary" onClick={askToSend} disabled={send.isPending} data-testid="till-message-send">
              {send.isPending ? t('msg.sending') : t('msg.send')}
            </Button>
          </div>
        </div>
      </SectionCard>

      <TillMessagesList
        messages={messages.data ?? []}
        loading={messages.isLoading}
        failed={messages.isError}
        onWithdraw={setWithdrawing}
      />

      <Modal
        open={confirming}
        onClose={() => setConfirming(false)}
        title={t('msg.confirmTitle')}
        footer={
          <>
            <Button variant="ghost" onClick={() => setConfirming(false)} disabled={send.isPending}>
              {t('msg.cancel')}
            </Button>
            <Button variant="primary" onClick={confirmSend} disabled={send.isPending} data-testid="till-message-confirm">
              {send.isPending ? t('msg.sending') : t('msg.send')}
            </Button>
          </>
        }
      >
        <div className="flex flex-col gap-2 text-[13px] text-ink-700">
          <div className="font-bold text-ink-900">{draft.title.trim()}</div>
          <div>{t('msg.confirmBody', { audience: summary, days: daysLabel })}</div>
          {previewData && (
            <div className="font-semibold text-ink-900" data-testid="till-message-confirm-count">
              {t('msg.countLine', { n: previewData.recipients, atTill: previewData.atTillNow })}
            </div>
          )}
          {draft.onlyAtTill && <div>{t('msg.confirmOnlyAtTill')}</div>}
          {draft.audience === 'course' && <div>{t('msg.confirmCourse')}</div>}
          {draft.audience === 'all' && !draft.onlyAtTill && <div className="font-semibold text-ink-900">{t('msg.confirmAll')}</div>}
        </div>
      </Modal>

      <Modal
        open={withdrawing !== null}
        onClose={() => setWithdrawing(null)}
        title={t('msg.withdrawTitle')}
        footer={
          <>
            <Button variant="ghost" onClick={() => setWithdrawing(null)} disabled={withdraw.isPending}>
              {t('msg.cancel')}
            </Button>
            <Button variant="danger" onClick={confirmWithdraw} disabled={withdraw.isPending} data-testid="till-message-withdraw-confirm">
              {t('msg.withdraw')}
            </Button>
          </>
        }
      >
        <div className="flex flex-col gap-2 text-[13px] text-ink-700">
          <div className="font-bold text-ink-900">{withdrawing?.title}</div>
          <div>{t('msg.withdrawBody')}</div>
        </div>
      </Modal>
    </div>
  )
}

/** Как уведомление выглядит на кассе: тёмная шапка, заголовок, текст и кнопка (касса показывает их по-русски). */
function Preview({ title, text }: { title: string; text: string }) {
  const t = useT()
  return (
    <div>
      <div className="mb-1.5 text-[12px] font-semibold text-ink-700">{t('msg.preview')}</div>
      <div className="max-w-[420px] overflow-hidden rounded-2xl border border-ink-200 bg-white shadow-sm" data-testid="till-message-preview">
        <div className="bg-recommendation-ink px-4 py-2.5 text-[13px] font-semibold text-white">{t('msg.previewHeader')}</div>
        <div className="px-4 py-3">
          <div className="text-[15px] font-extrabold text-ink-900">{title.trim() || t('msg.previewTitle')}</div>
          <div className="mt-1 text-[11px] text-ink-500">{t('msg.previewSent')}</div>
          <p className="mt-2 whitespace-pre-line text-[13px] text-ink-700">{text.trim() || t('msg.previewText')}</p>
          <div className="mt-3 rounded-lg bg-recommendation-green py-2 text-center text-[13px] font-semibold text-white">
            {t('msg.previewButton')}
          </div>
        </div>
      </div>
    </div>
  )
}
