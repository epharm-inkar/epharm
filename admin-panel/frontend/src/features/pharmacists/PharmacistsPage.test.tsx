// Тесты PharmacistsPage: плотная таблица, меню «Ещё» и меню строки, маскирование, права, состояния.

import { describe, expect, it, beforeEach, afterEach, vi } from 'vitest'
import { act, fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { ToastHost } from '@/ui'
import type { PharmacistDto } from '@/lib/api-types'
import { useUiStore } from '@/app/store'
import PharmacistsPage from './PharmacistsPage'

const pharmacistHooks = vi.hoisted(() => ({
  usePharmacists: vi.fn(),
  usePharmacist: vi.fn(),
  useCreatePharmacist: vi.fn(),
  useUpdatePharmacist: vi.fn(),
  useActivatePharmacist: vi.fn(),
  useBlockPharmacist: vi.fn(),
  useUnblockPharmacist: vi.fn(),
  useStandardNPharmacistMappings: vi.fn(),
  useUnmappedStandardNSellers: vi.fn(),
  useUpsertStandardNPharmacistMapping: vi.fn(),
  useRevokeStandardNPharmacistMapping: vi.fn(),
}))

vi.mock('@/lib/queries/pharmacists', () => pharmacistHooks)

const pharmacyHooks = vi.hoisted(() => ({
  usePharmacies: vi.fn(),
}))

vi.mock('@/lib/queries/pharmacies', () => pharmacyHooks)

const lmsHooks = vi.hoisted(() => ({
  useTrainingPreferences: vi.fn(),
  useTrainingPreferenceHistory: vi.fn(),
  useChangeTrainingPreference: vi.fn(),
  useMassChangeTrainingPreferences: vi.fn(),
  useTrainingDashboard: vi.fn(),
  usePharmacistTrainingProfile: vi.fn(),
}))

vi.mock('@/lib/queries/lms', () => lmsHooks)
const accessHooks = vi.hoisted(() => ({ useAdministrativeCapabilities: vi.fn() }))
vi.mock('@/app/useAdministrativeCapabilities', () => accessHooks)
const reportHooks = vi.hoisted(() => ({ downloadReport: vi.fn(), saveReport: vi.fn() }))
vi.mock('@/features/reports/ledgerReports', () => reportHooks)
const originalUser = useUiStore.getState().authedUser
const originalLanguage = useUiStore.getState().language
afterEach(() => {
  act(() => useUiStore.setState({ authedUser: originalUser, language: originalLanguage }))
})

function mkPharmacist(over: Partial<PharmacistDto> = {}): PharmacistDto {
  return {
    id: 'u_1',
    name: 'Айгерим Касенова',
    iin: '950101000001',
    phone: '+7 (701) 100-10-20',
    pharmacyId: 'ph_1',
    pharmacyName: 'Europharma №100',
    city: 'Алматы',
    tier: 'Gold',
    receipts30d: 50,
    rulesAccepted30d: 15,
    earned30d: 80_000,
    balance: 50_000,
    coursesDone: 3,
    coursesTotal: 9,
    status: 'active',
    hasPassword: true,
    joinedAt: '2026-01-15',
    createdAt: '2026-01-15T00:00:00Z',
    updatedAt: '2026-01-15T00:00:00Z',
    accountSource: 'standardn',
    hasPhone: true,
    lastSaleAt: '2026-11-24T10:00:00Z',
    earnedMonthKzt: 50_000,
    openAssignments: 2,
    standardNPharmacies: [],
    ...over,
  }
}

function setPharmacists(list: PharmacistDto[] = []) {
  pharmacistHooks.usePharmacists.mockReturnValue({
    data: list,
    isLoading: false,
    isError: false,
    refetch: vi.fn(),
  })
}

function pharmacy(id: string, name: string, chainName: string, city: string) {
  return {
    id,
    name,
    chainId: chainName,
    chainName,
    city,
    district: '',
    addr: '',
    group: 'pilot',
    pharmacists: 0,
    receipts30d: 0,
    gmv30d: 0,
    liftPct: 0,
    rulesAccepted: 0,
    active: true,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  }
}

beforeEach(() => {
  vi.clearAllMocks()
  accessHooks.useAdministrativeCapabilities.mockReturnValue({
    canExportLearnerCredentials: false,
    canExportPharmacists: false,
    data: { version: 1 },
  })
  useUiStore.setState({
    language: 'ru',
    authedUser: {
      id: 'hq-test',
      email: 'hq@test.invalid',
      name: 'HQ',
      company: 'Test',
      role: 'HQ_HEAD',
    },
  })
  setPharmacists([])
  pharmacyHooks.usePharmacies.mockReturnValue({
    data: [
      pharmacy('ph_1', 'Ауэзова 134', 'Inkar', 'Алматы'),
      pharmacy('ph_2', 'Абая 10', 'Лимон Фарм', 'Астана'),
    ],
  })
  pharmacistHooks.useActivatePharmacist.mockReturnValue({ mutate: vi.fn(), isPending: false })
  pharmacistHooks.useCreatePharmacist.mockReturnValue({ mutate: vi.fn(), isPending: false })
  pharmacistHooks.useUpdatePharmacist.mockReturnValue({ mutate: vi.fn(), isPending: false })
  pharmacistHooks.useBlockPharmacist.mockReturnValue({ mutate: vi.fn(), isPending: false })
  pharmacistHooks.useUnblockPharmacist.mockReturnValue({ mutate: vi.fn(), isPending: false })
  lmsHooks.useTrainingPreferences.mockReturnValue({
    data: [],
    isLoading: false,
    isError: false,
    isSuccess: true,
  })
  lmsHooks.useTrainingPreferenceHistory.mockReturnValue({ data: [], isLoading: false })
  lmsHooks.useChangeTrainingPreference.mockReturnValue({ mutate: vi.fn(), isPending: false })
  lmsHooks.useMassChangeTrainingPreferences.mockReturnValue({ mutate: vi.fn(), isPending: false })
  lmsHooks.useTrainingDashboard.mockReturnValue({
    data: {
      capabilities: {
        canManagePrograms: true,
        canManageAssignments: true,
        canManageEvents: true,
        canMarkAttendance: true,
        canAdjustRewards: false,
        canRecordResults: true,
        canManagePreferences: true,
        canExport: true,
        regionalScope: [],
      },
    },
    isLoading: false,
    isError: false,
  })
  lmsHooks.usePharmacistTrainingProfile.mockReturnValue({
    data: {
      pharmacistId: 'u_1',
      pharmacistName: 'Айгерим Касенова',
      pharmacyName: 'Europharma №100',
      city: 'Алматы',
      defaultFormat: 'hybrid',
      totalAssignments: 1,
      completedAssignments: 1,
      inProgressAssignments: 0,
      totalRewards: 5_000,
      assignments: [],
      certificates: [
        {
          id: 'certificate-1',
          number: 'EPH-2026-001',
          assignmentId: 'assignment-1',
          pharmacistId: 'u_1',
          pharmacistName: 'Айгерим Касенова',
          programName: 'Работа с продуктом',
          format: 'hybrid',
          issuedAt: '2026-08-01T10:00:00Z',
          expiresAt: null,
          score: 92,
          signerName: 'Руководитель обучения',
          status: 'valid',
          verificationToken: 'verify-token',
          pdfUrl: '/api/public/training/certificates/verify-token/pdf',
        },
      ],
      rewards: [],
      preferenceHistory: [],
    },
    isLoading: false,
    isError: false,
  })
})

function LocationProbe() {
  const location = useLocation()
  return (
    <output data-testid="location-probe">
      {location.pathname}
      {location.search}
    </output>
  )
}

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <ToastHost>
          <PharmacistsPage />
          <LocationProbe />
        </ToastHost>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

type User = ReturnType<typeof userEvent.setup>

async function openMore(user: User) {
  await user.click(screen.getByRole('button', { name: 'Ещё' }))
  return screen.getByRole('menu')
}

async function openRowMenu(user: User, name: string) {
  await user.click(screen.getByRole('button', { name: `Действия: ${name}` }))
  return screen.getByRole('menu')
}

function cell(rowId: string, column: string) {
  return screen.getByTestId(`pharmacist-row-${rowId}`).querySelector(`[data-col="${column}"]`) as HTMLElement
}

describe('PharmacistsPage — рендер', () => {
  it('hides Standard-N mapping actions when server access is unavailable', async () => {
    accessHooks.useAdministrativeCapabilities.mockReturnValue({ canManageStandardNMappings: false })
    const user = userEvent.setup()
    renderPage()
    const menu = await openMore(user)
    expect(within(menu).queryByRole('menuitem', { name: 'Продавцы Standard-N' })).not.toBeInTheDocument()
  })

  it('shows Standard-N mapping actions for authorized legacy HQ', async () => {
    accessHooks.useAdministrativeCapabilities.mockReturnValue({ canManageStandardNMappings: true })
    const user = userEvent.setup()
    renderPage()
    const menu = await openMore(user)
    expect(within(menu).getByRole('menuitem', { name: 'Продавцы Standard-N' })).toBeInTheDocument()
  })

  it('заголовок со строкой счётчиков вместо плиток KPI', () => {
    setPharmacists([
      mkPharmacist({ id: 'a', accountSource: 'standardn', hasPhone: true, hasPassword: true }),
      mkPharmacist({ id: 'b', accountSource: 'standardn', hasPhone: false, hasPassword: false, phone: null }),
      mkPharmacist({ id: 'c', accountSource: 'registration', hasPhone: true, hasPassword: false }),
    ])
    renderPage()
    expect(screen.getByRole('heading', { level: 1, name: /Фармацевты/i })).toBeInTheDocument()
    expect(screen.getByTestId('pharmacists-counters')).toHaveTextContent(
      /^Всего 3 · с телефоном 2 · с паролем 1 · из Стандарт-Н 2$/,
    )
    for (const tile of ['Всего фармацевтов', 'Текущие балансы', 'Pending']) {
      expect(screen.queryByText(tile)).not.toBeInTheDocument()
    }
  })

  it('Empty state на пустом списке', () => {
    renderPage()
    expect(screen.getByText(/Фармацевтов пока нет/i)).toBeInTheDocument()
    expect(screen.queryByTestId('pharmacists-counters')).not.toBeInTheDocument()
  })

  it('форма создания открывается без чужих персональных данных', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(screen.getByRole('button', { name: 'Добавить фармацевта' }))

    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: 'Телефон' })).toHaveValue('')
    expect(screen.getByRole('textbox', { name: 'ФИО' })).toHaveValue('')
    expect(screen.getByLabelText('Пароль для входа')).toBeInTheDocument()
  })
})

describe('PharmacistsPage — loading/error', () => {
  it('isLoading', () => {
    pharmacistHooks.usePharmacists.mockReturnValue({
      data: undefined,
      isLoading: true,
      isError: false,
      refetch: vi.fn(),
    })
    renderPage()
    expect(screen.getByText(/Загружаем фармацевтов/i)).toBeInTheDocument()
  })

  it('isError', async () => {
    const refetch = vi.fn()
    pharmacistHooks.usePharmacists.mockReturnValue({
      data: undefined,
      isLoading: false,
      isError: true,
      refetch,
    })
    const user = userEvent.setup()
    renderPage()
    expect(screen.getByText(/Не удалось загрузить/i)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /Повторить/ }))
    expect(refetch).toHaveBeenCalled()
  })

  it('ошибка обновления оставляет прежние строки с предупреждением', async () => {
    const refetch = vi.fn()
    pharmacistHooks.usePharmacists.mockReturnValue({
      data: [mkPharmacist()],
      isLoading: false,
      isError: true,
      error: new Error('сеть'),
      refetch,
    })
    const user = userEvent.setup()
    renderPage()
    expect(screen.getByTestId('pharmacist-row-u_1')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /Повторить/ }))
    expect(refetch).toHaveBeenCalled()
  })
})

describe('PharmacistsPage — плотная таблица', () => {
  it('показывает нужные колонки и убирает одинаковые у всех', () => {
    setPharmacists([mkPharmacist()])
    renderPage()
    const table = screen.getByTestId('pharmacists-table')
    const headers = within(table)
      .getAllByRole('columnheader')
      .map((header) => header.textContent?.trim())
    expect(headers).toEqual([
      '',
      'ФИО',
      'ИИН',
      'Аптека',
      'Источник',
      'Телефон',
      'Пароль',
      'Посл. продажа',
      'Чеки 30 дн',
      'Заработано',
      'Задания',
      'Действия',
    ])
    for (const dropped of ['Тир', 'Баланс', 'Формат обучения', 'Статус']) {
      expect(within(table).queryByRole('columnheader', { name: dropped })).not.toBeInTheDocument()
    }
    expect(within(table).getByRole('checkbox', { name: 'Выбрать всех видимых фармацевтов' })).toBeInTheDocument()
  })

  it('заполняет строку фактами: источник, последняя продажа, чеки, заработок, задания, аптеки', () => {
    setPharmacists([
      mkPharmacist({
        receipts30d: 1_234,
        earnedMonthKzt: 12_400,
        openAssignments: 3,
        lastSaleAt: '2026-11-24T10:00:00Z',
        standardNPharmacies: [
          { id: 'ph_1', name: 'Europharma №100', city: 'Алматы' },
          { id: 'ph_9', name: 'Europharma №109', city: 'Астана' },
        ],
      }),
    ])
    renderPage()
    expect(cell('u_1', 'source')).toHaveTextContent('Стандарт-Н')
    expect(cell('u_1', 'lastSale')).toHaveTextContent(/^\d{2}\.\d{2}\.2026$/)
    expect(cell('u_1', 'lastSale').title).toMatch(/^Последний чек: /)
    expect(cell('u_1', 'receipts30d')).toHaveTextContent(/^1\s234$/)
    expect(cell('u_1', 'receipts30d')).toHaveClass('text-right', 'num')
    expect(cell('u_1', 'earned')).toHaveTextContent(/^12\s400\s₸$/)
    expect(cell('u_1', 'tasks')).toHaveTextContent('3')
    const pharmacyCell = cell('u_1', 'pharmacy')
    expect(pharmacyCell).toHaveTextContent('Europharma №100 · Алматы+1')
    expect(pharmacyCell.title).toBe(
      'Основная: Europharma №100, Алматы (Inkar)\nВ Стандарт-Н: Europharma №100, Алматы; Europharma №109, Астана',
    )
    expect(within(cell('u_1', 'phone')).getByRole('img', { name: 'Телефон указан' })).toBeInTheDocument()
    expect(within(cell('u_1', 'password')).getByRole('img', { name: 'Пароль задан' })).toBeInTheDocument()
  })

  it('помечает тестовые записи и не прячет их', () => {
    setPharmacists([
      mkPharmacist({ id: 'demo', name: '[ДЕМО] Фармацевт ePharm' }),
      mkPharmacist({ id: 'test', name: '[ТЕСТ] Фармацевт LMS' }),
      mkPharmacist({ id: 'real', name: 'Айгерим Касенова' }),
    ])
    renderPage()
    for (const id of ['demo', 'test']) {
      const row = screen.getByTestId(`pharmacist-row-${id}`)
      expect(within(row).getByText('тест')).toBeInTheDocument()
      expect(row).toHaveClass('dt-muted')
      expect(cell(id, 'name').title).toContain('Тестовая запись')
    }
    const real = screen.getByTestId('pharmacist-row-real')
    expect(within(real).queryByText('тест')).not.toBeInTheDocument()
    expect(real).not.toHaveClass('dt-muted')
    // The bracket mark does not put test cards at the head of the default order.
    expect(screen.getAllByTestId(/^pharmacist-row-/).map((row) => row.dataset.testid)).toEqual([
      'pharmacist-row-real',
      'pharmacist-row-demo',
      'pharmacist-row-test',
    ])
  })

  it('показывает статус меткой только у неактивных', () => {
    setPharmacists([
      mkPharmacist({ id: 'active' }),
      mkPharmacist({ id: 'pending', status: 'pending', pharmacyId: '', pharmacyName: '', city: '' }),
      mkPharmacist({ id: 'blocked', status: 'blocked' }),
    ])
    renderPage()
    expect(within(screen.getByTestId('pharmacist-row-pending')).getByText('ожидает')).toBeInTheDocument()
    expect(cell('pending', 'pharmacy')).toHaveTextContent('Аптека не назначена')
    expect(within(screen.getByTestId('pharmacist-row-blocked')).getByText('заблокирован')).toBeInTheDocument()
    expect(screen.getByTestId('pharmacist-row-active')).not.toHaveTextContent(/ожидает|заблокирован/)
  })

  it('сортирует по клику на заголовок, пустые значения внизу', async () => {
    setPharmacists([
      mkPharmacist({ id: 'few', name: 'Бекова', receipts30d: 3 }),
      mkPharmacist({ id: 'hidden', name: 'Ахметова', receipts30d: null }),
      mkPharmacist({ id: 'many', name: 'Ким', receipts30d: 900 }),
    ])
    const user = userEvent.setup()
    renderPage()
    const order = () =>
      screen.getAllByTestId(/^pharmacist-row-/).map((row) => row.getAttribute('data-testid')!.slice(15))
    expect(order()).toEqual(['hidden', 'few', 'many'])
    const header = screen.getByRole('columnheader', { name: 'Чеки 30 дн' })
    await user.click(within(header).getByRole('button'))
    expect(header).toHaveAttribute('aria-sort', 'descending')
    expect(order()).toEqual(['many', 'few', 'hidden'])
    await user.click(within(header).getByRole('button'))
    expect(order()).toEqual(['few', 'many', 'hidden'])
  })

  it('фильтрует по сети и городу из справочника аптек', async () => {
    setPharmacists([
      mkPharmacist({ id: 'inkar', name: 'Касенова', pharmacyId: 'ph_1', city: 'Алматы' }),
      mkPharmacist({ id: 'limon', name: 'Ким', pharmacyId: 'ph_2', pharmacyName: 'Абая 10', city: 'Астана' }),
    ])
    const user = userEvent.setup()
    renderPage()
    await user.selectOptions(screen.getByRole('combobox', { name: 'Сеть' }), 'Лимон Фарм')
    expect(screen.queryByTestId('pharmacist-row-inkar')).not.toBeInTheDocument()
    expect(screen.getByTestId('pharmacist-row-limon')).toBeInTheDocument()
    expect(screen.getByTestId('pharmacists-counters')).toHaveTextContent('показано 1')
    await user.selectOptions(screen.getByRole('combobox', { name: 'Сеть' }), 'all')
    await user.selectOptions(screen.getByRole('combobox', { name: 'Город' }), 'Алматы')
    expect(screen.getByTestId('pharmacist-row-inkar')).toBeInTheDocument()
    expect(screen.queryByTestId('pharmacist-row-limon')).not.toBeInTheDocument()
  })

  it('держит второстепенные действия в меню «Ещё», в шапке только две кнопки', async () => {
    accessHooks.useAdministrativeCapabilities.mockReturnValue({
      canExportLearnerCredentials: true,
      canExportPharmacists: true,
      canManageStandardNMappings: true,
      data: { version: 1 },
    })
    setPharmacists([mkPharmacist()])
    const user = userEvent.setup()
    renderPage()
    for (const label of ['Показать ИИН и телефоны', 'Учётные данные ЛК', 'Excel: реестр', 'Продавцы Standard-N']) {
      expect(screen.queryByRole('button', { name: label })).not.toBeInTheDocument()
    }
    const menu = await openMore(user)
    expect(within(menu).getAllByRole('menuitem').map((item) => item.firstChild?.textContent)).toEqual([
      'Показать ИИН и телефоны',
      'Учётные данные ЛК',
      'Excel: реестр',
      'Продавцы Standard-N',
    ])
  })

  it('выгружает Excel реестра из меню с фильтром статуса и сообщает результат', async () => {
    accessHooks.useAdministrativeCapabilities.mockReturnValue({
      canExportPharmacists: true,
      data: { version: 1 },
    })
    const blob = new Blob(['xlsx'])
    reportHooks.downloadReport.mockResolvedValue(blob)
    setPharmacists([mkPharmacist(), mkPharmacist({ id: 'b', status: 'blocked' })])
    const user = userEvent.setup()
    renderPage()
    await user.selectOptions(screen.getByRole('combobox', { name: 'Статус' }), 'blocked')
    const menu = await openMore(user)
    await user.click(within(menu).getByRole('menuitem', { name: 'Excel: реестр' }))
    expect(reportHooks.downloadReport).toHaveBeenCalledWith(
      '/api/admin/reports/pharmacists.xlsx',
      { status: 'blocked' },
      expect.any(AbortSignal),
    )
    expect(reportHooks.saveReport).toHaveBeenCalledWith(blob, 'pharmacists.xlsx')
    expect(await screen.findByText('Excel передан браузеру для скачивания.')).toBeInTheDocument()
  })

  it('без права на выгрузку пункт Excel виден, но недоступен', async () => {
    setPharmacists([mkPharmacist()])
    const user = userEvent.setup()
    renderPage()
    const menu = await openMore(user)
    expect(within(menu).getByRole('menuitem', { name: /Excel: реестр/ })).toBeDisabled()
    expect(reportHooks.downloadReport).not.toHaveBeenCalled()
  })
})

describe('PharmacistsPage — список и actions', () => {
  it.each([
    'FINANCE_REVIEWER',
    'CATEGORY_LEAD',
    'BRAND_MANAGER',
    'TRAINING_MANAGER',
    'REGIONAL_MANAGER',
    'TRAINER',
    undefined,
  ] as const)('роль %s видит заработок без сброса пароля', async (role) => {
    useUiStore.setState({
      authedUser: role
        ? {
            id: 'profile-test',
            email: 'profile@test.invalid',
            name: 'Profile reader',
            company: 'Test',
            role,
          }
        : null,
    })
    const mutate = vi.fn()
    pharmacistHooks.useUpdatePharmacist.mockReturnValue({ mutate, isPending: false })
    setPharmacists([
      mkPharmacist({ id: 'ordinary-read', name: 'Читатель' }),
      mkPharmacist({ id: 'no-password', name: 'Без пароля', hasPassword: false }),
    ])
    const user = userEvent.setup()
    renderPage()
    const row = screen.getByTestId('pharmacist-row-ordinary-read')
    expect(within(row).getByText(/50\s000\s₸/)).toBeInTheDocument()
    expect(within(row).queryByRole('spinbutton')).not.toBeInTheDocument()
    for (const name of ['Читатель', 'Без пароля']) {
      const menu = await openRowMenu(user, name)
      expect(within(menu).queryByRole('menuitem', { name: /пароль/i })).not.toBeInTheDocument()
      await user.keyboard('{Escape}')
    }
    const more = await openMore(user)
    expect(within(more).queryByRole('menuitem', { name: /Показать ИИН/ })).not.toBeInTheDocument()
    expect(mutate).not.toHaveBeenCalled()
  })

  it.each(['SYSTEM_ADMIN', 'HQ_HEAD'] as const)(
    'роль %s меняет пароль без balance в PATCH',
    async (role) => {
      useUiStore.setState({
        authedUser: {
          id: 'profile-test',
          email: 'profile@test.invalid',
          name: 'Profile editor',
          company: 'Test',
          role,
        },
      })
      const mutate = vi.fn()
      pharmacistHooks.useUpdatePharmacist.mockReturnValue({ mutate, isPending: false })
      setPharmacists([mkPharmacist({ id: 'ordinary-edit' })])
      const user = userEvent.setup()
      renderPage()
      const row = screen.getByTestId('pharmacist-row-ordinary-edit')
      expect(within(row).getByText(/50\s000\s₸/)).toBeInTheDocument()
      expect(within(row).queryByRole('spinbutton')).not.toBeInTheDocument()
      const menu = await openRowMenu(user, 'Айгерим Касенова')
      await user.click(within(menu).getByRole('menuitem', { name: 'Сменить пароль' }))
      await user.type(screen.getByLabelText('Новый пароль'), 'TestPassword123!')
      await user.click(screen.getByRole('button', { name: 'Сохранить пароль' }))
      expect(mutate).toHaveBeenCalledWith(
        { id: 'ordinary-edit', patch: { password: 'TestPassword123!' } },
        expect.any(Object),
      )
      expect(mutate.mock.calls[0][0].patch).not.toHaveProperty('balance')
    },
  )

  it('не отправляет новый HQ-пароль за пределами BCrypt и принимает 72 байта без сокращения', async () => {
    const mutate = vi.fn()
    pharmacistHooks.useUpdatePharmacist.mockReturnValue({ mutate, isPending: false })
    setPharmacists([mkPharmacist({ id: 'password-boundary' })])
    const user = userEvent.setup()
    renderPage()
    await openRowMenu(user, 'Айгерим Касенова')
    await user.click(screen.getByRole('menuitem', { name: 'Сменить пароль' }))
    const input = screen.getByLabelText('Новый пароль')
    const save = screen.getByRole('button', { name: 'Сохранить пароль' })
    for (const value of ['я'.repeat(37), 'x'.repeat(73), ' '.repeat(8)]) {
      fireEvent.change(input, { target: { value } })
      expect(save).toBeDisabled()
      await user.click(save)
      expect(mutate).not.toHaveBeenCalled()
    }
    fireEvent.change(input, { target: { value: 'я'.repeat(36) } })
    expect(save).toBeEnabled()
    await user.click(save)
    expect(mutate).toHaveBeenCalledWith(
      { id: 'password-boundary', patch: { password: 'я'.repeat(36) } },
      expect.any(Object),
    )
  })

  it('потеря полномочий закрывает уже открытый сброс пароля', async () => {
    const mutate = vi.fn()
    pharmacistHooks.useUpdatePharmacist.mockReturnValue({ mutate, isPending: false })
    setPharmacists([mkPharmacist()])
    const user = userEvent.setup()
    renderPage()
    await openRowMenu(user, 'Айгерим Касенова')
    await user.click(screen.getByRole('menuitem', { name: 'Сменить пароль' }))
    await user.type(screen.getByLabelText('Новый пароль'), 'TestPassword123!')
    act(() =>
      useUiStore.setState({
        authedUser: {
          id: 'finance-test',
          email: 'finance@test.invalid',
          name: 'Finance',
          company: 'Test',
          role: 'FINANCE_REVIEWER',
        },
      }),
    )
    expect(screen.queryByRole('button', { name: 'Сохранить пароль' })).not.toBeInTheDocument()
    expect(mutate).not.toHaveBeenCalled()
  })

  it('таблица показывает строки', () => {
    setPharmacists([
      mkPharmacist({ id: 'u_a', name: 'Айгерим' }),
      mkPharmacist({ id: 'u_b', name: 'Бауыржан', status: 'blocked' }),
    ])
    renderPage()
    expect(screen.getByTestId('pharmacists-table')).toBeInTheDocument()
    expect(screen.getByTestId('pharmacist-row-u_a')).toBeInTheDocument()
    expect(screen.getByTestId('pharmacist-row-u_b')).toBeInTheDocument()
  })

  it('маскирует ИИН и телефон до явного раскрытия без пароля или его хеша', async () => {
    const pharmacist = {
      ...mkPharmacist({ iin: '001201000001' }),
      password: 'SyntheticPasswordMustNotBeRendered!',
      passwordHash: '$2b$12$syntheticHashMustNotBeRendered',
    }
    setPharmacists([pharmacist])
    const user = userEvent.setup()
    renderPage()

    const table = screen.getByTestId('pharmacists-table')
    const header = within(table).getByRole('columnheader', { name: 'ИИН' })
    expect(header).toHaveAttribute('scope', 'col')
    const row = screen.getByTestId('pharmacist-row-u_1')
    expect(within(row).getByRole('cell', { name: '••••••••0001' })).toBeInTheDocument()
    expect(within(cell('u_1', 'phone')).getByRole('img', { name: 'Телефон указан' })).toBeInTheDocument()
    expect(row).not.toHaveTextContent('001201000001')
    expect(row).not.toHaveTextContent('+7 (701) 100-10-20')
    expect(row).not.toHaveTextContent('1020')
    await openMore(user)
    await user.click(screen.getByRole('menuitem', { name: 'Показать ИИН и телефоны' }))
    expect(within(row).getByRole('cell', { name: '001201000001' })).toBeInTheDocument()
    expect(cell('u_1', 'phone')).toHaveTextContent('+7 (701) 100-10-20')
    expect(within(row).getAllByRole('cell')).toHaveLength(
      within(table).getAllByRole('columnheader').length,
    )
    expect(table.outerHTML).not.toContain(pharmacist.password)
    expect(table.outerHTML).not.toContain(pharmacist.passwordHash)
    // The password column shows only whether one is set.
    expect(within(table).getByRole('columnheader', { name: 'Пароль' })).toBeInTheDocument()
    expect(cell('u_1', 'password').textContent).toBe('')
    expect(within(cell('u_1', 'password')).getByRole('img', { name: 'Пароль задан' })).toBeInTheDocument()
    const menu = await openRowMenu(user, 'Айгерим Касенова')
    expect(within(menu).getByRole('menuitem', { name: 'Сменить пароль' })).toBeInTheDocument()
  })

  it('не переносит раскрытие ИИН другому аккаунту или роли в том же экземпляре страницы', async () => {
    const pharmacist = mkPharmacist({ iin: '001201000001' })
    setPharmacists([pharmacist])
    const user = userEvent.setup()
    renderPage()
    const row = screen.getByTestId('pharmacist-row-u_1')
    await openMore(user)
    await user.click(screen.getByRole('menuitem', { name: 'Показать ИИН и телефоны' }))
    expect(row).toHaveTextContent(pharmacist.iin!)

    act(() =>
      useUiStore.setState({
        authedUser: { ...useUiStore.getState().authedUser!, id: 'another-hq' },
      }),
    )
    expect(row).not.toHaveTextContent(pharmacist.iin!)
    expect(row).toHaveTextContent('••••••••0001')
    await openMore(user)
    await user.click(screen.getByRole('menuitem', { name: 'Показать ИИН и телефоны' }))
    expect(row).toHaveTextContent(pharmacist.iin!)

    act(() =>
      useUiStore.setState({
        authedUser: { ...useUiStore.getState().authedUser!, role: 'REGIONAL_MANAGER' },
      }),
    )
    expect(row).not.toHaveTextContent(pharmacist.iin!)
    expect(row).not.toHaveTextContent(pharmacist.phone!)
    await openMore(user)
    expect(screen.queryByRole('menuitem', { name: /ИИН и телефоны/ })).not.toBeInTheDocument()
  })

  it('оставляет пустой ИИН явным и сохраняет колонки без массового выбора', () => {
    lmsHooks.useTrainingDashboard.mockReturnValue({
      data: { capabilities: { canManagePreferences: false, canManageAssignments: false } },
    })
    setPharmacists([mkPharmacist({ iin: '' })])
    renderPage()

    const table = screen.getByTestId('pharmacists-table')
    const row = screen.getByTestId('pharmacist-row-u_1')
    expect(within(table).getByRole('columnheader', { name: 'ИИН' })).toBeInTheDocument()
    expect(cell('u_1', 'iin')).toHaveTextContent(/^—$/)
    expect(within(row).getAllByRole('cell')).toHaveLength(11)
    expect(within(table).getAllByRole('columnheader')).toHaveLength(11)
    expect(within(table).queryByRole('checkbox')).not.toBeInTheDocument()
  })

  it('позволяет выбрать сотрудников для D9 без прав на изменение обучения', async () => {
    const user = userEvent.setup()
    accessHooks.useAdministrativeCapabilities.mockReturnValue({
      canExportLearnerCredentials: true,
      canExportPharmacists: false,
      data: { version: 1 },
    })
    lmsHooks.useTrainingDashboard.mockReturnValue({
      data: { capabilities: { canManagePreferences: false, canManageAssignments: false } },
    })
    setPharmacists([mkPharmacist()])
    renderPage()
    let menu = await openMore(user)
    expect(within(menu).getByRole('menuitem', { name: /^Учётные данные ЛК/ })).toBeDisabled()
    await user.keyboard('{Escape}')
    await user.click(screen.getByRole('checkbox', { name: 'Выбрать Айгерим Касенова' }))
    expect(
      screen.queryByRole('button', { name: /Изменить формат|Назначить обучение/ }),
    ).not.toBeInTheDocument()
    menu = await openMore(user)
    const download = within(menu).getByRole('menuitem', { name: /^Учётные данные ЛК/ })
    expect(download).toBeEnabled()
    expect(download).toHaveTextContent('Выбрано: 1')
    await user.click(download)
    expect(screen.getByRole('dialog')).toHaveTextContent('Айгерим Касенова')
  })

  it('показывает скрытые поля прочерком и не выдаёт ноль за скрытые продажи и деньги', () => {
    setPharmacists([
      mkPharmacist({ id: 'visible', name: 'Доступный баланс' }),
      mkPharmacist({
        id: 'restricted',
        name: 'Скрытые поля',
        iin: null,
        phone: null,
        receipts30d: null,
        rulesAccepted30d: null,
        earned30d: null,
        balance: null,
        lastSaleAt: null,
        earnedMonthKzt: null,
      }),
    ])
    renderPage()
    const row = screen.getByTestId('pharmacist-row-restricted')
    for (const column of ['iin', 'lastSale', 'receipts30d', 'earned']) {
      expect(cell('restricted', column)).toHaveTextContent(/^—$/)
    }
    expect(cell('restricted', 'lastSale').title).toBe('Нет доступа к продажам')
    // Hidden number, known presence: the server still says whether a phone exists.
    expect(within(cell('restricted', 'phone')).getByRole('img', { name: 'Телефон указан' })).toBeInTheDocument()
    expect(row).not.toHaveTextContent('null')
    expect(row).not.toHaveTextContent(/\b0\s*₸/)
    expect(screen.queryByText('Текущие балансы')).not.toBeInTheDocument()
  })

  it('отличает отсутствие продаж от старого сервера без новых полей', () => {
    const legacy = mkPharmacist({ id: 'legacy', phone: '+7 701 000 00 00' })
    for (const key of ['accountSource', 'hasPhone', 'lastSaleAt', 'earnedMonthKzt', 'openAssignments', 'standardNPharmacies'] as const)
      delete legacy[key]
    setPharmacists([legacy, mkPharmacist({ id: 'never', lastSaleAt: null, receipts30d: 0 })])
    renderPage()
    expect(cell('never', 'lastSale').title).toBe('Продаж не было')
    expect(cell('legacy', 'lastSale').title).toBe('Нет данных')
    for (const column of ['source', 'earned', 'tasks']) expect(cell('legacy', column)).toHaveTextContent(/^—$/)
    expect(within(cell('legacy', 'phone')).getByRole('img', { name: 'Телефон указан' })).toBeInTheDocument()
  })

  it('показывает пометку только при подтверждённом несовпадении ИИН Стандарт‑Н', () => {
    setPharmacists([
      mkPharmacist({ id: 'mismatch', standardNIinMatchStatus: 'MISMATCH' }),
      mkPharmacist({
        id: 'identity-iin-mismatch',
        standardNIdentityMatchMethod: 'IIN',
        standardNIdentityMatchStatus: 'MISMATCH',
      }),
      mkPharmacist({
        id: 'iin-primary',
        standardNIinMatchStatus: 'MISMATCH',
        standardNIdentityMatchMethod: 'NAME',
        standardNIdentityMatchStatus: 'MATCH',
      }),
      mkPharmacist({ id: 'match', standardNIinMatchStatus: 'MATCH' }),
      mkPharmacist({ id: 'unknown', standardNIinMatchStatus: 'UNKNOWN' }),
      mkPharmacist({ id: 'legacy' }),
    ])
    renderPage()
    const warning = 'ИИН не совпадает со Стандарт‑Н'
    for (const id of ['mismatch', 'identity-iin-mismatch', 'iin-primary']) {
      const badge = within(cell(id, 'iin')).getByRole('img', { name: warning })
      expect(badge).toBeVisible()
      expect(badge).toHaveClass('text-accent-warning')
      expect(within(cell(id, 'iin')).queryByRole('img', { name: 'Совпадение по ФИО' })).not.toBeInTheDocument()
    }
    for (const id of ['match', 'unknown', 'legacy']) {
      expect(within(cell(id, 'iin')).queryByRole('img')).not.toBeInTheDocument()
    }
  })

  it.each(['ru', 'kk'] as const)(
    'показывает диагностику по ФИО на языке %s без раскрытия персональных данных',
    (language) => {
      useUiStore.setState({ language })
      setPharmacists([
        ...(['MATCH', 'MISMATCH', 'UNKNOWN'] as const).map((status) =>
          mkPharmacist({
            id: `name-${status}`,
            standardNIinMatchStatus: 'UNKNOWN',
            standardNIdentityMatchMethod: 'NAME',
            standardNIdentityMatchStatus: status,
            standardNIdentityCheckedAt: '2026-11-06T00:00:00Z',
          }),
        ),
        mkPharmacist({
          id: 'redacted',
          iin: null,
          phone: null,
          standardNIinMatchStatus: null,
          standardNIinCheckedAt: null,
          standardNIdentityMatchMethod: null,
          standardNIdentityMatchStatus: null,
          standardNIdentityCheckedAt: null,
        }),
        mkPharmacist({
          id: 'none',
          standardNIdentityMatchMethod: 'NONE',
          standardNIdentityMatchStatus: 'UNKNOWN',
        }),
      ])
      renderPage()

      const matchLabel = language === 'ru' ? 'Совпадение по ФИО' : 'Аты-жөні бойынша сәйкестік'
      const mismatchLabel = language === 'ru' ? 'ФИО не совпадает' : 'Аты-жөні сәйкес келмейді'
      const match = within(cell('name-MATCH', 'iin')).getByRole('img', { name: matchLabel })
      expect(match).toBeVisible()
      expect(match).toHaveClass('text-ink-500')
      expect(match.title).toContain(
        language === 'ru' ? 'ИИН текущего аккаунта Стандарт‑Н недоступен' : 'ЖСН-і қолжетімсіз',
      )
      const mismatch = within(cell('name-MISMATCH', 'iin')).getByRole('img', { name: mismatchLabel })
      expect(mismatch).toBeVisible()
      expect(mismatch).toHaveClass('text-accent-warning')
      for (const id of ['name-UNKNOWN', 'redacted', 'none']) {
        const row = screen.getByTestId(`pharmacist-row-${id}`)
        expect(within(cell(id, 'iin')).queryByRole('img')).not.toBeInTheDocument()
        expect(row).not.toHaveTextContent('null')
      }
      const table = screen.getByTestId('pharmacists-table')
      expect(table).not.toHaveTextContent('950101000001')
      expect(table).not.toHaveTextContent('+7 (701) 100-10-20')
      expect(table.outerHTML).not.toContain('2026-11-06T00:00:00Z')
      expect(
        within(screen.getByTestId('pharmacist-row-name-MATCH')).getAllByRole('cell'),
      ).toHaveLength(within(table).getAllByRole('columnheader').length)
    },
  )

  it('сохраняет настоящий ноль и полную сумму', () => {
    setPharmacists([
      mkPharmacist({ id: 'zero', name: 'Нулевой баланс', earnedMonthKzt: 0, receipts30d: 0, openAssignments: 0 }),
    ])
    renderPage()
    expect(cell('zero', 'earned')).toHaveTextContent(/^0\s₸$/)
    expect(cell('zero', 'receipts30d')).toHaveTextContent(/^0$/)
    expect(cell('zero', 'tasks')).toHaveTextContent(/^0$/)
  })

  it('не добавляет буквальное null в поиск скрытых персональных полей', async () => {
    setPharmacists([mkPharmacist({ name: 'Скрытые данные', iin: null, phone: null })])
    renderPage()
    const user = userEvent.setup()
    await user.type(screen.getByPlaceholderText('Поиск по имени, аптеке, ИИН, телефону'), 'null')
    expect(screen.queryByTestId('pharmacist-row-u_1')).not.toBeInTheDocument()
    expect(screen.getByText('Ничего не найдено')).toBeInTheDocument()
  })

  it('окно активации сохраняет явные placeholders скрытого ИИН и телефона', async () => {
    setPharmacists([mkPharmacist({ status: 'pending', iin: null, phone: null })])
    renderPage()
    const user = userEvent.setup()
    await openRowMenu(user, 'Айгерим Касенова')
    await user.click(screen.getByRole('menuitem', { name: 'Активировать' }))
    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByText('— · —')).toBeInTheDocument()
    expect(dialog).not.toHaveTextContent('null')
  })

  it('поиск по ИИН сохраняет выбор строк и выбирает только видимых сотрудников', async () => {
    setPharmacists([
      mkPharmacist({ id: 'u_first', name: 'Первый', iin: '001201000001' }),
      mkPharmacist({ id: 'u_second', name: 'Второй', iin: '021201000002' }),
    ])
    const user = userEvent.setup()
    renderPage()

    await user.click(screen.getByRole('checkbox', { name: 'Выбрать Первый' }))
    expect(screen.getByTestId('pharmacist-row-u_first')).toHaveAttribute('data-selected', 'true')
    const search = screen.getByPlaceholderText('Поиск по имени, аптеке, ИИН, телефону')
    await user.type(search, '021201000002')
    expect(screen.queryByTestId('pharmacist-row-u_first')).not.toBeInTheDocument()
    expect(screen.getByRole('cell', { name: '••••••••0002' })).toBeInTheDocument()
    await user.click(screen.getByRole('checkbox', { name: 'Выбрать всех видимых фармацевтов' }))
    expect(screen.getByRole('button', { name: 'Назначить обучение (2)' })).toBeInTheDocument()

    await user.clear(search)
    expect(screen.getByRole('checkbox', { name: 'Выбрать Первый' })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: 'Выбрать Второй' })).toBeChecked()
  })

  it('фильтр active скрывает blocked', async () => {
    setPharmacists([
      mkPharmacist({ id: 'a', name: 'Active-X', status: 'active' }),
      mkPharmacist({ id: 'b', name: 'Blocked-X', status: 'blocked' }),
    ])
    const user = userEvent.setup()
    renderPage()
    const status = screen.getByRole('combobox', { name: 'Статус' })
    expect(within(status).getByRole('option', { name: 'Активные · 1' })).toBeInTheDocument()
    await user.selectOptions(status, 'active')
    expect(screen.getByText('Active-X')).toBeInTheDocument()
    expect(screen.queryByText('Blocked-X')).not.toBeInTheDocument()
  })

  it('пункт «Разблокировать» вызывает useUnblockPharmacist', async () => {
    const mutate = vi.fn()
    pharmacistHooks.useUnblockPharmacist.mockReturnValue({ mutate, isPending: false })
    setPharmacists([mkPharmacist({ id: 'u_b', status: 'blocked' })])
    const user = userEvent.setup()
    renderPage()
    await openRowMenu(user, 'Айгерим Касенова')
    await user.click(screen.getByRole('menuitem', { name: 'Разблокировать' }))
    expect(mutate).toHaveBeenCalledWith('u_b', expect.any(Object))
  })

  it('блокировка ждёт подтверждения и не уходит повторно, пока запрос этой строки в полёте', async () => {
    const mutate = vi.fn()
    pharmacistHooks.useBlockPharmacist.mockReturnValue({ mutate, isPending: false })
    setPharmacists([mkPharmacist({ id: 'u_x' })])
    const confirm = vi.spyOn(window, 'confirm').mockReturnValueOnce(false).mockReturnValueOnce(true)
    const user = userEvent.setup()
    renderPage()
    await openRowMenu(user, 'Айгерим Касенова')
    await user.click(screen.getByRole('menuitem', { name: 'Заблокировать' }))
    expect(mutate).not.toHaveBeenCalled()
    await openRowMenu(user, 'Айгерим Касенова')
    await user.click(screen.getByRole('menuitem', { name: 'Заблокировать' }))
    expect(mutate).toHaveBeenCalledWith('u_x', expect.any(Object))
    confirm.mockRestore()
  })

  it('администратор может задать новый пароль фармацевту', async () => {
    const mutate = vi.fn()
    pharmacistHooks.useUpdatePharmacist.mockReturnValue({ mutate, isPending: false })
    setPharmacists([mkPharmacist({ id: 'u_password', hasPassword: false })])
    const user = userEvent.setup()
    renderPage()

    expect(cell('u_password', 'password')).toHaveTextContent(/^—$/)
    await openRowMenu(user, 'Айгерим Касенова')
    await user.click(screen.getByRole('menuitem', { name: 'Задать пароль' }))
    await user.type(screen.getByLabelText('Новый пароль'), 'Farm123!')
    await user.click(screen.getByRole('button', { name: 'Сохранить пароль' }))

    expect(mutate).toHaveBeenCalledWith(
      { id: 'u_password', patch: { password: 'Farm123!' } },
      expect.any(Object),
    )
  })

  it('pending фармацевта можно назначить аптеке и активировать', async () => {
    const mutate = vi.fn()
    pharmacistHooks.useActivatePharmacist.mockReturnValue({ mutate, isPending: false })
    setPharmacists([
      mkPharmacist({
        id: 'u_pending',
        status: 'pending',
        pharmacyId: '',
        pharmacyName: '',
        city: '',
      }),
    ])
    const user = userEvent.setup()
    renderPage()

    await openRowMenu(user, 'Айгерим Касенова')
    await user.click(screen.getByRole('menuitem', { name: 'Активировать' }))
    const dialog = screen.getByRole('dialog', { name: 'Активация фармацевта' })
    await user.selectOptions(within(dialog).getByRole('combobox'), 'ph_1')
    await user.click(within(dialog).getByRole('button', { name: 'Активировать' }))

    expect(mutate).toHaveBeenCalledWith(
      { id: 'u_pending', request: { pharmacyId: 'ph_1' } },
      expect.any(Object),
    )
  })

  it('передаёт выбранных активных фармацевтов в массовое назначение обучения', async () => {
    setPharmacists([mkPharmacist({ id: 'u_active', name: 'Айгерим' })])
    const user = userEvent.setup()
    renderPage()

    await user.click(screen.getByRole('checkbox', { name: 'Выбрать Айгерим' }))
    await user.click(screen.getByRole('button', { name: 'Назначить обучение (1)' }))

    expect(screen.getByTestId('location-probe')).toHaveTextContent(
      '/lms?action=assign&pharmacists=u_active',
    )
  })

  it('меняет формат обучения одного фармацевта из меню строки', async () => {
    lmsHooks.useTrainingPreferences.mockReturnValue({
      data: [{ id: 'pref', pharmacistId: 'u_1', defaultFormat: 'online', validFrom: '2026-11-01T00:00:00Z', changedByName: 'HQ', reason: null }],
      isLoading: false,
      isError: false,
      isSuccess: true,
    })
    setPharmacists([mkPharmacist()])
    const user = userEvent.setup()
    renderPage()
    const menu = await openRowMenu(user, 'Айгерим Касенова')
    const item = within(menu).getByRole('menuitem', { name: /Формат обучения/ })
    expect(item).toHaveTextContent('Сейчас: Онлайн')
    await user.click(item)
    expect(screen.getByRole('dialog', { name: 'Формат обучения по умолчанию' })).toHaveTextContent('Айгерим Касенова')
  })

  it('открывает полную карточку обучения фармацевта', async () => {
    setPharmacists([mkPharmacist()])
    const user = userEvent.setup()
    renderPage()

    await openRowMenu(user, 'Айгерим Касенова')
    await user.click(screen.getByRole('menuitem', { name: 'Обучение' }))

    expect(lmsHooks.usePharmacistTrainingProfile).toHaveBeenCalledWith('u_1')
    const dialog = screen.getByRole('dialog', { name: 'Айгерим Касенова' })
    expect(within(dialog).getByText('Гибрид')).toBeInTheDocument()
    expect(within(dialog).getByText('Работа с продуктом')).toBeInTheDocument()
    expect(within(dialog).getByRole('link', { name: /Работа с продуктом/ })).toHaveAttribute(
      'href',
      '/api/public/training/certificates/verify-token/pdf',
    )
  })

  it('не показывает ноль вместо скрытой истории учебных начислений в профиле', async () => {
    setPharmacists([mkPharmacist()])
    const previous = lmsHooks.usePharmacistTrainingProfile.getMockImplementation()!
    lmsHooks.usePharmacistTrainingProfile.mockImplementation((...args: unknown[]) => {
      const result = previous(...args)
      return { ...result, data: { ...result.data, totalRewards: null, rewards: [] } }
    })
    const user = userEvent.setup()
    renderPage()
    await openRowMenu(user, 'Айгерим Касенова')
    await user.click(screen.getByRole('menuitem', { name: 'Обучение' }))
    const dialog = screen.getByRole('dialog', { name: 'Айгерим Касенова' })
    expect(within(dialog).getByText('Нет доступа')).toBeInTheDocument()
    expect(within(dialog).getByText('Бонусы за обучение')).toBeInTheDocument()
  })
})
