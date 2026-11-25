// Pharmacists — реестр фармацевтов. Плотная таблица (25.11.2026) — образец для остальных таблиц
// админки: строка счётчиков вместо плиток, поиск и фильтры одной строкой, второстепенные действия
// в меню «Ещё», действия строки в меню «⋯», сортировка по любой колонке.

import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAdministrativeCapabilities } from '@/app/useAdministrativeCapabilities'
import { Button, DataTable, Empty, Menu, PageHeader, SearchInput, Select, useToast } from '@/ui'
import type { MenuItem } from '@/ui'
import { IconChevDown, IconPharmacist, IconPlus } from '@/ui/icons'
import type { PharmacistDto } from '@/lib/api-types'
import {
  useActivatePharmacist,
  useBlockPharmacist,
  useCreatePharmacist,
  usePharmacists,
  useUnblockPharmacist,
} from '@/lib/queries/pharmacists'
import { usePharmacies } from '@/lib/queries/pharmacies'
import { useTrainingDashboard, useTrainingPreferences } from '@/lib/queries/lms'
import { describeError } from '@/lib/describeError'
import { formatCount } from '@/lib/format'
import { useT } from '@/i18n'
import { useUiStore } from '@/app/store'
import { canManagePharmacistCredentials } from '@/app/accessPolicy'
import { FORMAT_LABEL } from '@/features/lms/training-ui'
import { useReportDownload } from '@/features/reports/useReportDownload'
import { ActivatePharmacistModal } from './ActivatePharmacistModal'
import { CreatePharmacistModal } from './CreatePharmacistModal'
import { StandardNSellerMappingsModal } from './StandardNSellerMappingsModal'
import CredentialExportDialog from './CredentialExportDialog'
import { PharmacistPasswordModal } from './PharmacistPasswordModal'
import { PharmacistTrainingProfileModal, TrainingPreferenceModal } from './PharmacistTrainingModals'
import { pharmacistColumns } from './pharmacist-columns'
import {
  ALL,
  chainOf,
  countRegistry,
  distinctSorted,
  filterRegistry,
  isFiltered,
  isTestRecord,
  type FormatFilter,
  type StatusFilter,
} from './registry-model'

const MAX_CREDENTIAL_PEOPLE = 25

export default function PharmacistsPage() {
  const t = useT()
  const access = useAdministrativeCapabilities()
  const toast = useToast()
  const navigate = useNavigate()
  const [status, setStatus] = useState<StatusFilter>(ALL)
  const [chain, setChain] = useState(ALL)
  const [city, setCity] = useState(ALL)
  const [format, setFormat] = useState<FormatFilter>(ALL)
  const [query, setQuery] = useState('')
  const [activating, setActivating] = useState<PharmacistDto | null>(null)
  const [createOpen, setCreateOpen] = useState(false)
  const [createError, setCreateError] = useState<string | null>(null)
  const [passwordTarget, setPasswordTarget] = useState<PharmacistDto | null>(null)
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set())
  const [preferenceTargets, setPreferenceTargets] = useState<PharmacistDto[] | null>(null)
  const [trainingProfileId, setTrainingProfileId] = useState<string | null>(null)
  const [standardNMappingsOpen, setStandardNMappingsOpen] = useState(false)
  const [personalDataOwner, setPersonalDataOwner] = useState<string | null>(null)
  const [credentialSelection, setCredentialSelection] = useState<{
    ownerId: string
    version: number | null
    people: { id: string; name: string }[]
  } | null>(null)
  const currentAccount = useUiStore((state) => state.authedUser?.id)
  const adminRole = useUiStore((state) => state.authedUser?.role)
  const canManageStandardN = access.canManageStandardNMappings
  const canManageCredentials = canManagePharmacistCredentials(adminRole)
  // Revealed IIN/phones belong to this account, role and access version only.
  const personalDataContext = JSON.stringify([currentAccount, adminRole, access.data?.version])
  const personalDataVisible =
    !!currentAccount && canManageCredentials && personalDataOwner === personalDataContext

  const { data: pharmacists = [], isLoading, isError, error, refetch } = usePharmacists()
  const { data: pharmacyList = [] } = usePharmacies()
  const preferencesQuery = useTrainingPreferences()
  const trainingDashboardQuery = useTrainingDashboard()
  const activatePharmacist = useActivatePharmacist()
  const createPharmacist = useCreatePharmacist()
  const blockPharmacist = useBlockPharmacist()
  const unblockPharmacist = useUnblockPharmacist()
  const registryExport = useReportDownload()

  const pharmacies = useMemo(() => new Map(pharmacyList.map((p) => [p.id, p])), [pharmacyList])
  const preferenceByPharmacist = useMemo(
    () => new Map((preferencesQuery.data ?? []).map((preference) => [preference.pharmacistId, preference])),
    [preferencesQuery.data],
  )
  const canManagePreferences = trainingDashboardQuery.data?.capabilities.canManagePreferences ?? false
  const canManageAssignments = trainingDashboardQuery.data?.capabilities.canManageAssignments ?? false
  const canSelectPharmacists =
    canManagePreferences || canManageAssignments || access.canExportLearnerCredentials

  const hasData = pharmacists.length > 0
  const showFullError = isError && !hasData
  const showWarningBanner = isError && hasData

  const filters = { status, chain, city, format, query }
  const counters = useMemo(() => countRegistry(pharmacists), [pharmacists])
  const chains = useMemo(() => distinctSorted(pharmacists.map((p) => chainOf(p, pharmacies))), [pharmacists, pharmacies])
  const cities = useMemo(() => distinctSorted(pharmacists.map((p) => p.city)), [pharmacists])
  const filtered = useMemo(
    () => filterRegistry(pharmacists, { status, chain, city, format, query }, pharmacies, preferenceByPharmacist),
    [pharmacists, status, chain, city, format, query, pharmacies, preferenceByPharmacist],
  )

  const allVisibleSelected = filtered.length > 0 && filtered.every((p) => selectedIds.has(p.id))
  const selectedPharmacists = pharmacists.filter((p) => selectedIds.has(p.id))
  const assignableSelected = selectedPharmacists.filter((p) => p.status === 'active')
  const toggleSelection = (id: string) =>
    setSelectedIds((current) => {
      const next = new Set(current)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  const toggleVisible = () =>
    setSelectedIds((current) => {
      const next = new Set(current)
      if (allVisibleSelected) filtered.forEach((p) => next.delete(p.id))
      else filtered.forEach((p) => next.add(p.id))
      return next
    })

  const toggleBlock = (p: PharmacistDto) => {
    if (p.status === 'blocked') {
      unblockPharmacist.mutate(p.id, {
        onSuccess: () => toast.push(t('phc.unblockedToast', { name: p.name })),
        onError: () => toast.push(t('phc.unblockErr')),
      })
      return
    }
    if (!confirm(t('phc.confirmBlock', { name: p.name }))) return
    blockPharmacist.mutate(p.id, {
      onSuccess: () => toast.push(t('phc.blockedToast', { name: p.name })),
      onError: () => toast.push(t('phc.blockErr')),
    })
  }

  const handleActivate = (pharmacyId: string) => {
    if (!activating) return
    activatePharmacist.mutate(
      { id: activating.id, request: { pharmacyId } },
      {
        onSuccess: () => {
          toast.push(t('phc.activatedToast', { name: activating.name }))
          setActivating(null)
        },
        onError: (activationError) => toast.push(`${t('phc.activateErr')}: ${describeError(activationError)}`),
      },
    )
  }

  const rowActions = (p: PharmacistDto): MenuItem[] => {
    const preference = preferenceByPharmacist.get(p.id)
    // Only this row waits for its own request; a quick second click cannot send two.
    const blockPending =
      (blockPharmacist.isPending && blockPharmacist.variables === p.id) ||
      (unblockPharmacist.isPending && unblockPharmacist.variables === p.id)
    const items: (MenuItem | false)[] = [
      { id: 'training', label: 'Обучение', onSelect: () => setTrainingProfileId(p.id) },
      canManagePreferences && {
        id: 'format',
        label: 'Формат обучения',
        hint: preference ? `Сейчас: ${FORMAT_LABEL[preference.defaultFormat]}` : 'Не задан',
        onSelect: () => setPreferenceTargets([p]),
      },
      canManageCredentials && {
        id: 'password',
        label: p.hasPassword ? 'Сменить пароль' : 'Задать пароль',
        onSelect: () => setPasswordTarget(p),
      },
      p.status === 'pending' && {
        id: 'activate',
        label: t('phc.activate'),
        disabled: activatePharmacist.isPending && activatePharmacist.variables?.id === p.id,
        onSelect: () => setActivating(p),
      },
      {
        id: 'block',
        label: p.status === 'blocked' ? 'Разблокировать' : 'Заблокировать',
        danger: p.status !== 'blocked',
        disabled: blockPending,
        onSelect: () => toggleBlock(p),
      },
    ]
    return items.filter((item): item is MenuItem => !!item)
  }

  const columns = pharmacistColumns({
    t,
    personalDataVisible,
    canSelect: canSelectPharmacists,
    selectedIds,
    allVisibleSelected,
    onToggleVisible: toggleVisible,
    onToggleRow: toggleSelection,
    pharmacies,
    rowActions,
  })

  const credentialsReady =
    !!currentAccount &&
    selectedPharmacists.length >= 1 &&
    selectedPharmacists.length <= MAX_CREDENTIAL_PEOPLE &&
    selectedPharmacists.length === selectedIds.size

  const exportRegistry = async () => {
    const result = await registryExport.download(
      '/api/admin/reports/pharmacists.xlsx',
      { status: status === ALL ? undefined : status },
      'pharmacists.xlsx',
    )
    if (result?.ok) toast.push('Excel передан браузеру для скачивания.')
    else if (result) toast.push(result.error)
  }

  const moreItems: (MenuItem | false)[] = [
    canManageCredentials && {
      id: 'personal',
      label: personalDataVisible ? 'Скрыть ИИН и телефоны' : 'Показать ИИН и телефоны',
      onSelect: () => setPersonalDataOwner(personalDataVisible ? null : personalDataContext),
    },
    access.canExportLearnerCredentials && {
      id: 'credentials',
      label: 'Учётные данные ЛК',
      disabled: !credentialsReady,
      hint: credentialsReady
        ? `Выбрано: ${selectedPharmacists.length}`
        : `Выберите в таблице до ${MAX_CREDENTIAL_PEOPLE} фармацевтов`,
      onSelect: () =>
        currentAccount &&
        setCredentialSelection({
          ownerId: currentAccount,
          version: access.data?.version ?? null,
          people: selectedPharmacists.map(({ id, name }) => ({ id, name })),
        }),
    },
    {
      id: 'excel',
      label: registryExport.pending ? 'Формируем Excel…' : 'Excel: реестр',
      disabled: !access.canExportPharmacists || registryExport.pending,
      hint: !access.canExportPharmacists
        ? 'Нет права на эту выгрузку'
        : isFiltered({ ...filters, status: ALL })
          ? 'По статусу; поиск и другие фильтры не учитываются'
          : undefined,
      onSelect: () => void exportRegistry(),
    },
    canManageStandardN && {
      id: 'standardn',
      label: 'Продавцы Standard-N',
      onSelect: () => setStandardNMappingsOpen(true),
    },
  ]

  const statusLabel = { active: 'Активные', pending: 'Ожидают', blocked: 'Заблокированы' } as const
  const counterLine = [
    `Всего ${formatCount(counters.total)}`,
    `с телефоном ${formatCount(counters.withPhone)}`,
    `с паролем ${formatCount(counters.withPassword)}`,
    `из Стандарт-Н ${formatCount(counters.fromStandardN)}`,
    ...(isFiltered(filters) ? [`показано ${formatCount(filtered.length)}`] : []),
  ].join(' · ')

  return (
    <div className="flex h-[calc(100dvh-106px)] min-h-[460px] flex-col gap-3">
      {access.canExportLearnerCredentials &&
        credentialSelection &&
        credentialSelection.ownerId === currentAccount &&
        credentialSelection.version === access.data?.version && (
          <CredentialExportDialog
            key={`${credentialSelection.ownerId}:${credentialSelection.version}`}
            people={credentialSelection.people}
            onClose={() => setCredentialSelection(null)}
          />
        )}
      <PageHeader
        title={t('page.pharmacists.title')}
        meta={
          hasData ? (
            <span className="num" data-testid="pharmacists-counters">
              {counterLine}
            </span>
          ) : undefined
        }
        actions={
          <>
            <Button
              size="sm"
              leading={<IconPlus size={14} />}
              onClick={() => {
                setCreateError(null)
                setCreateOpen(true)
              }}
            >
              Добавить фармацевта
            </Button>
            <Menu
              trigger={
                <>
                  <span>Ещё</span>
                  <IconChevDown size={14} />
                </>
              }
              items={moreItems.filter((item): item is MenuItem => !!item)}
              width={264}
              testId="pharmacists-more"
              triggerClassName="btn btn-sm btn-outline"
            />
          </>
        }
      />

      <div className="card flex min-h-0 flex-1 flex-col overflow-hidden">
        <div className="dense-controls hairline flex flex-wrap items-center gap-2 border-b px-3 py-2">
          <div className="w-full sm:w-[260px]">
            <SearchInput value={query} onChange={setQuery} placeholder={t('phc.search')} />
          </div>
          <Select
            ariaLabel="Сеть"
            value={chain}
            onChange={setChain}
            className="w-[calc(50%-4px)] sm:w-[160px]"
            options={[{ value: ALL, label: 'Все сети' }, ...chains.map((value) => ({ value, label: value }))]}
          />
          <Select
            ariaLabel="Город"
            value={city}
            onChange={setCity}
            className="w-[calc(50%-4px)] sm:w-[150px]"
            options={[{ value: ALL, label: 'Все города' }, ...cities.map((value) => ({ value, label: value }))]}
          />
          <Select
            ariaLabel="Статус"
            value={status}
            onChange={(value) => setStatus(value as StatusFilter)}
            className="w-[calc(50%-4px)] sm:w-[170px]"
            options={[
              { value: ALL, label: 'Все статусы' },
              ...(['active', 'pending', 'blocked'] as const).map((value) => ({
                value,
                label: `${statusLabel[value]} · ${formatCount(counters.byStatus[value])}`,
              })),
            ]}
          />
          <Select
            ariaLabel="Формат обучения"
            value={format}
            onChange={(value) => setFormat(value as FormatFilter)}
            className="w-[calc(50%-4px)] sm:w-[160px]"
            options={[
              { value: ALL, label: 'Все форматы' },
              ...Object.entries(FORMAT_LABEL).map(([value, label]) => ({ value, label })),
              { value: 'unset', label: 'Формат не задан' },
            ]}
          />
          {(canManagePreferences && selectedPharmacists.length > 0) ||
          (canManageAssignments && assignableSelected.length > 0) ? (
            <div className="flex flex-wrap items-center gap-2 sm:ml-auto">
              {canManagePreferences && selectedPharmacists.length > 0 && (
                <Button size="sm" onClick={() => setPreferenceTargets(selectedPharmacists)}>
                  Изменить формат ({selectedPharmacists.length})
                </Button>
              )}
              {canManageAssignments && assignableSelected.length > 0 && (
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() => {
                    const params = new URLSearchParams({
                      action: 'assign',
                      pharmacists: assignableSelected.map((pharmacist) => pharmacist.id).join(','),
                    })
                    navigate(`/lms?${params.toString()}`)
                  }}
                >
                  Назначить обучение ({assignableSelected.length})
                </Button>
              )}
            </div>
          ) : null}
        </div>

        {showWarningBanner && (
          <div className="hairline border-b bg-accent-warning/10 px-3 py-1.5 text-[12px] font-semibold text-ink-700">
            {t('common.staleWarn', { e: describeError(error) })}{' '}
            <button type="button" onClick={() => refetch()} className="font-bold text-brand-green-700 underline">
              {t('common.retry')}
            </button>
          </div>
        )}

        {isLoading && !hasData ? (
          <Empty title={t('phc.loading')} body={t('common.connecting')} icon={<IconPharmacist size={26} />} />
        ) : showFullError ? (
          <Empty
            title={t('common.loadFailed')}
            body={describeError(error)}
            icon={<IconPharmacist size={26} />}
            action={<Button onClick={() => refetch()}>{t('common.retry')}</Button>}
          />
        ) : (
          <DataTable
            rows={filtered}
            columns={columns}
            rowKey={(p) => p.id}
            rowMeta={(p) => ({
              testId: `pharmacist-row-${p.id}`,
              selected: selectedIds.has(p.id),
              muted: isTestRecord(p.name),
            })}
            defaultSort={{ columnId: 'name', direction: 'asc' }}
            label={t('page.pharmacists.title')}
            testId="pharmacists-table"
            scrollTestId="pharmacists-table-scroll"
            className="min-h-0 flex-1"
            empty={
              <Empty
                title={isFiltered(filters) ? t('common.notFound') : t('phc.empty')}
                body={isFiltered(filters) ? t('common.notFoundBody') : t('phc.emptyBody')}
                icon={<IconPharmacist size={26} />}
              />
            }
          />
        )}
      </div>

      {activating && (
        <ActivatePharmacistModal
          key={activating.id}
          pharmacist={activating}
          pharmacies={pharmacyList}
          pending={activatePharmacist.isPending}
          onClose={() => setActivating(null)}
          onActivate={handleActivate}
        />
      )}
      {createOpen && (
        <CreatePharmacistModal
          open
          pharmacies={pharmacyList}
          pending={createPharmacist.isPending}
          error={createError}
          onClose={() => setCreateOpen(false)}
          onCreate={(request) => {
            setCreateError(null)
            createPharmacist.mutate(request, {
              onSuccess: (created) => {
                setCreateOpen(false)
                toast.push(`Фармацевт ${created.name} создан`)
              },
              onError: (createRequestError) => setCreateError(describeError(createRequestError)),
            })
          }}
        />
      )}
      {canManageCredentials && passwordTarget && (
        <PharmacistPasswordModal
          key={passwordTarget.id}
          pharmacist={passwordTarget}
          onClose={() => setPasswordTarget(null)}
        />
      )}
      {preferenceTargets && (
        <TrainingPreferenceModal
          key={preferenceTargets.map((pharmacist) => pharmacist.id).join(':')}
          pharmacists={preferenceTargets}
          current={
            preferenceTargets.length === 1
              ? (preferenceByPharmacist.get(preferenceTargets[0].id) ?? null)
              : null
          }
          onClose={() => setPreferenceTargets(null)}
          onSaved={() => {
            setPreferenceTargets(null)
            setSelectedIds(new Set())
          }}
        />
      )}
      {trainingProfileId && (
        <PharmacistTrainingProfileModal
          pharmacistId={trainingProfileId}
          onClose={() => setTrainingProfileId(null)}
        />
      )}
      {standardNMappingsOpen && canManageStandardN && (
        <StandardNSellerMappingsModal pharmacists={pharmacists} onClose={() => setStandardNMappingsOpen(false)} />
      )}
    </div>
  )
}
