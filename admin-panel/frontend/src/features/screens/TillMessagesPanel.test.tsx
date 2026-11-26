import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ToastHost } from '@/ui'
import type { PosmCourseOptionDto, PosmMessageDto, PosmMessagePreviewDto, PosmPersonDto } from '@/lib/api-types'
import { TillMessagesPanel } from './TillMessagesPanel'

const messageHooks = vi.hoisted(() => ({
  POSM_PREVIEW_DEBOUNCE_MS: 0,
  usePosmMessages: vi.fn(),
  useSendPosmMessage: vi.fn(),
  useWithdrawPosmMessage: vi.fn(),
  usePosmCourses: vi.fn(),
  usePosmMessagePreview: vi.fn(),
  usePosmPharmacistSearch: vi.fn(),
}))
vi.mock('@/lib/queries/posmMessages', () => messageHooks)
const pharmacyHooks = vi.hoisted(() => ({ usePharmacies: vi.fn() }))
vi.mock('@/lib/queries/pharmacies', () => pharmacyHooks)

const sendMutate = vi.fn()
const withdrawMutate = vi.fn()

function sent(overrides: Partial<PosmMessageDto> = {}): PosmMessageDto {
  return {
    id: 'm1',
    title: 'Инвентаризация',
    text: 'Завтра в 9:00 инвентаризация.',
    audience: 'pharmacies',
    targetCount: 2,
    targetNames: ['Аптека со склада, Абая 103', 'Аптека со склада, Толе би 286'],
    recipientsTotal: 8,
    shownCount: 6,
    closedCount: 4,
    createdAt: '2026-11-25T09:00:00Z',
    createdBy: 'hq@example.test',
    expiresAt: '2026-12-02T09:00:00Z',
    withdrawnAt: null,
    withdrawnBy: null,
    active: true,
    ...overrides,
  }
}

function preview(overrides: Partial<PosmMessagePreviewDto> = {}): PosmMessagePreviewDto {
  return {
    audience: 'all',
    recipients: 1843,
    atTillNow: 212,
    frozen: false,
    sample: [],
    sampleMore: 0,
    windowMinutes: 20,
    ...overrides,
  }
}

const people: PosmPersonDto[] = [
  {
    id: 'ph_aigerim',
    name: 'Сапарова Айгерим',
    pharmacyName: 'Аптека со склада',
    city: 'Алматы',
    address: 'Абая 103',
    phoneTail: '4567',
    status: 'active',
    atTillNow: true,
    lastTillSeenAt: '2026-11-26T09:00:00Z',
  },
  {
    id: 'ph_erbol',
    name: 'Тұрсынов Ербол',
    pharmacyName: 'Аптека со склада',
    city: 'Алматы',
    address: 'Толе би 286',
    phoneTail: null,
    status: 'active',
    atTillNow: false,
    lastTillSeenAt: '2026-11-25T12:00:00Z',
  },
]

const courses: PosmCourseOptionDto[] = [
  { id: 'course-b', name: 'Витамины группы B', openCount: 312, dueOn: '2099-12-31' },
  { id: 'course-s', name: 'Сорбенты', openCount: 40, dueOn: null },
]

function renderPanel() {
  return render(
    <ToastHost>
      <TillMessagesPanel />
    </ToastHost>,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  messageHooks.usePosmMessages.mockReturnValue({ data: [sent()], isLoading: false, isError: false })
  messageHooks.useSendPosmMessage.mockReturnValue({ mutate: sendMutate, isPending: false })
  messageHooks.useWithdrawPosmMessage.mockReturnValue({ mutate: withdrawMutate, isPending: false })
  messageHooks.usePosmCourses.mockReturnValue({ data: courses, isLoading: false, isError: false })
  messageHooks.usePosmMessagePreview.mockReturnValue({ data: preview(), isLoading: false, isFetching: false, isError: false })
  messageHooks.usePosmPharmacistSearch.mockReturnValue({
    data: { total: people.length, items: people },
    isLoading: false,
    isError: false,
  })
  pharmacyHooks.usePharmacies.mockReturnValue({
    data: [
      { id: 'ph_abay', name: 'Аптека со склада', city: 'Алматы', addr: 'Абая 103' },
      { id: 'ph_tole', name: 'Аптека со склада', city: 'Алматы', addr: 'Толе би 286' },
    ],
    isLoading: false,
    isError: false,
  })
})

describe('TillMessagesPanel', () => {
  it('отправляет уведомление всем после подтверждения', async () => {
    const user = userEvent.setup()
    renderPanel()
    await user.type(screen.getByTestId('till-message-title'), 'Инвентаризация в субботу')
    await user.type(screen.getByTestId('till-message-text'), 'Приходите к 8:30.')
    expect(screen.getByTestId('till-message-preview')).toHaveTextContent('Инвентаризация в субботу')
    await user.click(screen.getByTestId('till-message-send'))

    const dialog = screen.getByRole('dialog')
    expect(dialog).toHaveTextContent('Кому: всем фармацевтам. Показывать на кассах: 7 дней.')
    expect(dialog).toHaveTextContent('Его увидят фармацевты на всех кассах с ePharm 1.0.99 и новее')
    expect(dialog).toHaveTextContent('Получат: 1843. Сейчас за кассой: 212.')
    expect(screen.getByText(/Уведомления видны только на кассах с ePharm 1\.0\.99 и новее/)).toBeInTheDocument()
    expect(sendMutate).not.toHaveBeenCalled()
    await user.click(within(dialog).getByTestId('till-message-confirm'))
    expect(sendMutate).toHaveBeenCalledWith(
      { title: 'Инвентаризация в субботу', text: 'Приходите к 8:30.', audience: 'all', days: 7 },
      expect.anything(),
    )
  })

  it('текст на казахском уходит вместе с русским, виден в предпросмотре и в подтверждении', async () => {
    const user = userEvent.setup()
    renderPanel()
    await user.type(screen.getByTestId('till-message-title'), 'Инвентаризация')
    await user.type(screen.getByTestId('till-message-text'), 'Завтра в 9:00')
    expect(screen.queryByTestId('till-message-title-kk')).not.toBeInTheDocument()
    await user.click(screen.getByRole('switch', { name: 'Добавить текст на казахском' }))
    await user.type(screen.getByTestId('till-message-title-kk'), 'Түгендеу')

    // Половину казахской пары не отправляем.
    await user.click(screen.getByTestId('till-message-send'))
    expect(screen.getByTestId('till-message-error')).toHaveTextContent('Введите текст на казахском или выключите казахский текст')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()

    await user.type(screen.getByTestId('till-message-text-kk'), 'Ертең сағат 9:00-де')
    expect(screen.getByTestId('till-message-preview')).toHaveTextContent('Завтра в 9:00')
    expect(screen.getByTestId('till-message-preview-kk')).toHaveTextContent('Түгендеу')
    expect(screen.getByTestId('till-message-preview-kk')).toHaveTextContent('Ертең сағат 9:00-де')
    await user.click(screen.getByTestId('till-message-send'))
    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByTestId('till-message-confirm-kk')).toHaveTextContent(
      'Кто выбрал казахский язык в кабинете, увидит текст на казахском.',
    )
    await user.click(within(dialog).getByTestId('till-message-confirm'))
    expect(sendMutate).toHaveBeenCalledWith(
      {
        title: 'Инвентаризация',
        text: 'Завтра в 9:00',
        audience: 'all',
        days: 7,
        titleKk: 'Түгендеу',
        textKk: 'Ертең сағат 9:00-де',
      },
      expect.anything(),
    )
  })

  it('не отправляет без заголовка и текста и говорит, чего не хватает', async () => {
    const user = userEvent.setup()
    renderPanel()
    await user.click(screen.getByTestId('till-message-send'))
    expect(screen.getByTestId('till-message-error')).toHaveTextContent('Введите заголовок')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(sendMutate).not.toHaveBeenCalled()
  })

  it('аптекам: выбор аптек и срок показа уходят в запрос', async () => {
    const user = userEvent.setup()
    renderPanel()
    await user.type(screen.getByTestId('till-message-title'), 'Проверка')
    await user.type(screen.getByTestId('till-message-text'), 'Завтра проверка.')
    await user.click(screen.getByTestId('till-message-audience-pharmacies'))
    await user.click(screen.getByTestId('till-message-send'))
    expect(screen.getByTestId('till-message-error')).toHaveTextContent('Выберите хотя бы одну аптеку')

    await user.click(screen.getByTestId('till-message-pharmacies-ph_tole'))
    await user.selectOptions(screen.getByRole('combobox', { name: 'Показывать на кассах' }), '3')
    await user.click(screen.getByTestId('till-message-send'))
    expect(screen.getByRole('dialog')).toHaveTextContent('аптеки (1): Аптека со склада')
    await user.click(screen.getByTestId('till-message-confirm'))
    expect(sendMutate).toHaveBeenCalledWith(
      { title: 'Проверка', text: 'Завтра проверка.', audience: 'pharmacies', days: 3, pharmacyIds: ['ph_tole'] },
      expect.anything(),
    )
  })

  it('сотрудникам: поиск по фамилии, телефону или ИИН на сервере, выбор нескольких и пометка «за кассой»', async () => {
    const user = userEvent.setup()
    renderPanel()
    await user.type(screen.getByTestId('till-message-title'), 'Ербол')
    await user.type(screen.getByTestId('till-message-text'), 'Подойдите к заведующей.')
    await user.click(screen.getByTestId('till-message-audience-pharmacists'))
    await user.type(screen.getByPlaceholderText('Фамилия, имя, телефон, ИИН или аптека'), 'ербол')
    await waitFor(() => expect(messageHooks.usePosmPharmacistSearch).toHaveBeenLastCalledWith('ербол'))

    const picker = screen.getByTestId('till-message-pharmacists')
    expect(within(picker).getByText('Сапарова Айгерим')).toBeInTheDocument()
    expect(within(picker).getByText('Аптека со склада, Абая 103 · Алматы · тел. …4567')).toBeInTheDocument()
    // Кто сейчас за кассой, а кто был и когда.
    expect(within(picker).getAllByTestId('till-seen-now')).toHaveLength(1)
    expect(within(picker).getByTestId('till-seen-last')).toHaveTextContent(/был за кассой 25\.11 \d{2}:\d{2}/)
    expect(screen.getByTestId('till-message-pharmacists-found')).toHaveTextContent('Найдено: 2')

    await user.click(screen.getByTestId('till-message-pharmacists-ph_erbol'))
    await user.click(screen.getByTestId('till-message-pharmacists-ph_aigerim'))
    expect(screen.getByTestId('till-message-pharmacists-chosen')).toHaveTextContent('Тұрсынов Ербол')
    await user.click(screen.getByTestId('till-message-send'))
    expect(screen.getByRole('dialog')).toHaveTextContent('сотрудники (2): Тұрсынов Ербол, Сапарова Айгерим')
    await user.click(screen.getByTestId('till-message-confirm'))
    expect(sendMutate).toHaveBeenCalledWith(
      expect.objectContaining({ audience: 'pharmacists', pharmacistIds: ['ph_erbol', 'ph_aigerim'] }),
      expect.anything(),
    )
  })

  it('выбранного человека можно убрать из списка, даже когда поиск его уже не показывает', async () => {
    const user = userEvent.setup()
    renderPanel()
    await user.click(screen.getByTestId('till-message-audience-pharmacists'))
    await user.click(screen.getByTestId('till-message-pharmacists-ph_erbol'))
    messageHooks.usePosmPharmacistSearch.mockReturnValue({ data: { total: 0, items: [] }, isLoading: false, isError: false })
    await user.type(screen.getByPlaceholderText('Фамилия, имя, телефон, ИИН или аптека'), 'нет такого')
    const chosen = await screen.findByTestId('till-message-pharmacists-chosen')
    expect(chosen).toHaveTextContent('Тұрсынов Ербол')
    await user.click(within(chosen).getByRole('button', { name: /Убрать из списка: Тұрсынов Ербол/ }))
    expect(screen.queryByTestId('till-message-pharmacists-chosen')).not.toBeInTheDocument()
  })

  it('показывает, сколько увидели и закрыли, и снимает уведомление с касс', async () => {
    const user = userEvent.setup()
    renderPanel()
    const row = screen.getByTestId('till-message-m1')
    expect(row).toHaveTextContent('аптеки (2): Аптека со склада, Абая 103, Аптека со склада, Толе би 286')
    expect(screen.getByTestId('till-message-m1-shown')).toHaveTextContent('6 из 8')
    expect(screen.getByTestId('till-message-m1-closed')).toHaveTextContent('4 из 8')
    await user.click(within(row).getByRole('button', { name: 'Снять с касс' }))
    await user.click(screen.getByTestId('till-message-withdraw-confirm'))
    expect(withdrawMutate).toHaveBeenCalledWith('m1', expect.anything())
  })

  it('снятое уведомление нельзя снять ещё раз, пустой список подписан', () => {
    messageHooks.usePosmMessages.mockReturnValue({
      data: [sent({ active: false, withdrawnAt: '2026-11-25T10:00:00Z', withdrawnBy: 'hq@example.test' })],
      isLoading: false,
      isError: false,
    })
    const { unmount } = renderPanel()
    const row = screen.getByTestId('till-message-m1')
    expect(row).toHaveTextContent('Снято')
    expect(within(row).queryByRole('button', { name: 'Снять с касс' })).not.toBeInTheDocument()
    unmount()

    messageHooks.usePosmMessages.mockReturnValue({ data: [], isLoading: false, isError: false })
    renderPanel()
    expect(screen.getByText('Уведомлений пока не было.')).toBeInTheDocument()
  })

  describe('напоминание о курсе и «только тем, кто сейчас за кассой»', () => {
    it('одна кнопка выбирает курс, включает «за кассой» и подставляет текст, который можно поправить', async () => {
      const user = userEvent.setup()
      renderPanel()
      await user.click(screen.getByTestId('till-message-remind-button'))

      expect(screen.getByTestId('till-message-title')).toHaveValue('Напоминание о курсе')
      expect(screen.getByTestId('till-message-text')).toHaveValue('Пройдите курс «Витамины группы B» до 31.12: Мой кабинет → Обучение')
      expect(screen.getByTestId('till-message-audience-course')).toHaveAttribute('aria-checked', 'true')
      expect(screen.getByRole('combobox', { name: 'Курс' })).toHaveValue('course-b')
      expect(screen.getByRole('switch', { name: 'Только тем, кто сейчас за кассой' })).toHaveAttribute('aria-checked', 'true')
      expect(screen.getByTestId('till-message-preview')).toHaveTextContent('Пройдите курс «Витамины группы B» до 31.12')

      await user.type(screen.getByTestId('till-message-text'), ' Это важно.')
      await user.click(screen.getByTestId('till-message-send'))
      const dialog = screen.getByRole('dialog')
      expect(dialog).toHaveTextContent('Кому: не прошли курс «Витамины группы B», только тем, кто был за кассой при отправке.')
      expect(dialog).toHaveTextContent('Уйдёт только тем, кто сейчас за кассой; кто сядет за кассу позже, не получит.')
      expect(dialog).toHaveTextContent('Кто пройдёт курс раньше, чем увидит уведомление, его не увидит.')
      await user.click(within(dialog).getByTestId('till-message-confirm'))
      expect(sendMutate).toHaveBeenCalledWith(
        {
          title: 'Напоминание о курсе',
          text: 'Пройдите курс «Витамины группы B» до 31.12: Мой кабинет → Обучение Это важно.',
          audience: 'course',
          programId: 'course-b',
          onlyAtTill: true,
          days: 7,
          // Тем, кто выбрал казахский, напоминание уходит на казахском.
          titleKk: 'Курс туралы еске салу',
          textKk: '«Витамины группы B» курсын 31.12 дейін өтіңіз: «Мой кабинет» → Оқыту',
        },
        expect.anything(),
      )
    })

    it('смена курса обновляет подставленный текст, а текст, который поправили, не трогает', async () => {
      const user = userEvent.setup()
      renderPanel()
      await user.click(screen.getByTestId('till-message-remind-button'))
      await user.selectOptions(screen.getByRole('combobox', { name: 'Курс' }), 'course-s')
      // У «Сорбентов» нет одного срока: даты в тексте нет.
      expect(screen.getByTestId('till-message-text')).toHaveValue('Пройдите курс «Сорбенты»: Мой кабинет → Обучение')

      await user.type(screen.getByTestId('till-message-text'), ' Срочно.')
      await user.selectOptions(screen.getByRole('combobox', { name: 'Курс' }), 'course-b')
      expect(screen.getByTestId('till-message-text')).toHaveValue('Пройдите курс «Сорбенты»: Мой кабинет → Обучение Срочно.')
    })

    it('в списке курса показано, сколько человек ещё не прошли', async () => {
      const user = userEvent.setup()
      renderPanel()
      await user.click(screen.getByTestId('till-message-audience-course'))
      const select = screen.getByRole('combobox', { name: 'Курс' })
      expect(within(select).getByRole('option', { name: 'Витамины группы B: не прошли 312' })).toBeInTheDocument()
      expect(within(select).getByRole('option', { name: 'Сорбенты: не прошли 40' })).toBeInTheDocument()
      await user.click(screen.getByTestId('till-message-send'))
      // Заголовок и текст пусты: сначала просят их, курс тоже обязателен.
      expect(screen.getByTestId('till-message-error')).toHaveTextContent('Введите заголовок')
    })

    it('без выбранного курса не отправляет и просит выбрать курс', async () => {
      const user = userEvent.setup()
      renderPanel()
      await user.type(screen.getByTestId('till-message-title'), 'Курс')
      await user.type(screen.getByTestId('till-message-text'), 'Пройдите курс.')
      await user.click(screen.getByTestId('till-message-audience-course'))
      await user.click(screen.getByTestId('till-message-send'))
      expect(screen.getByTestId('till-message-error')).toHaveTextContent('Выберите курс')
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })

    it('нет курсов, которые кто-то не прошёл: кнопка выключена и сказано почему', () => {
      messageHooks.usePosmCourses.mockReturnValue({ data: [], isLoading: false, isError: false })
      renderPanel()
      expect(screen.getByTestId('till-message-remind-button')).toBeDisabled()
      expect(screen.getByTestId('till-message-remind-none')).toHaveTextContent('Все курсы пройдены, напоминать не о чем')
    })

    it('курсы не загрузились: кнопка выключена и сказано, что случилось', () => {
      messageHooks.usePosmCourses.mockReturnValue({ data: undefined, isLoading: false, isError: true })
      renderPanel()
      expect(screen.getByTestId('till-message-remind-button')).toBeDisabled()
      expect(screen.getByText('Курсы не загрузились. Обновите страницу.')).toBeInTheDocument()
    })

    it('переключатель «только тем, кто сейчас за кассой» доходит до запроса любым адресатам', async () => {
      const user = userEvent.setup()
      renderPanel()
      await user.type(screen.getByTestId('till-message-title'), 'Срочно')
      await user.type(screen.getByTestId('till-message-text'), 'Зайдите в кабинет.')
      await user.click(screen.getByRole('switch', { name: 'Только тем, кто сейчас за кассой' }))
      await user.click(screen.getByTestId('till-message-send'))
      const dialog = screen.getByRole('dialog')
      expect(dialog).toHaveTextContent('Кому: всем фармацевтам, только тем, кто был за кассой при отправке.')
      expect(dialog).not.toHaveTextContent('Его увидят фармацевты на всех кассах')
      await user.click(within(dialog).getByTestId('till-message-confirm'))
      expect(sendMutate).toHaveBeenCalledWith(
        { title: 'Срочно', text: 'Зайдите в кабинет.', audience: 'all', onlyAtTill: true, days: 7 },
        expect.anything(),
      )
    })
  })

  describe('сколько человек получат', () => {
    it('считает по выбору: число адресатов, сколько из них за кассой и первых по алфавиту', async () => {
      messageHooks.usePosmMessagePreview.mockReturnValue({
        data: preview({ audience: 'course', recipients: 37, atTillNow: 21, frozen: true, sample: people, sampleMore: 35 }),
        isLoading: false,
        isFetching: false,
        isError: false,
      })
      const user = userEvent.setup()
      renderPanel()
      await user.click(screen.getByTestId('till-message-audience-course'))
      await user.selectOptions(screen.getByRole('combobox', { name: 'Курс' }), 'course-b')
      await waitFor(() => expect(screen.getByTestId('till-message-count')).toHaveTextContent('Получат: 37. Сейчас за кассой: 21.'))
      const count = screen.getByTestId('till-message-count')
      expect(count).toHaveTextContent('Адресатов выбирают при отправке и запоминают.')
      expect(within(screen.getByTestId('till-message-count-sample')).getByText('Сапарова Айгерим')).toBeInTheDocument()
      expect(count).toHaveTextContent('и ещё 35')
      // Запрос подсчёта: тот же, что у отправки, без заголовка и текста.
      expect(messageHooks.usePosmMessagePreview).toHaveBeenLastCalledWith({ audience: 'course', programId: 'course-b' })
    })

    it('пока выбор неполный, подсчёта нет и сказано, что выбрать', async () => {
      const user = userEvent.setup()
      renderPanel()
      await user.click(screen.getByTestId('till-message-audience-pharmacies'))
      expect(screen.getByTestId('till-message-count')).toHaveTextContent('Выберите, кому отправить')
      await waitFor(() => expect(messageHooks.usePosmMessagePreview).toHaveBeenLastCalledWith(null))
    })

    it('некому отправлять: сказано об этом и отправка не открывается', async () => {
      messageHooks.usePosmMessagePreview.mockReturnValue({
        data: preview({ recipients: 0, atTillNow: 0, frozen: true }),
        isLoading: false,
        isFetching: false,
        isError: false,
      })
      const user = userEvent.setup()
      renderPanel()
      await user.type(screen.getByTestId('till-message-title'), 'Срочно')
      await user.type(screen.getByTestId('till-message-text'), 'Зайдите в кабинет.')
      await user.click(screen.getByRole('switch', { name: 'Только тем, кто сейчас за кассой' }))
      await waitFor(() => expect(screen.getByTestId('till-message-count')).toHaveTextContent('Получить некому'))
      await user.click(screen.getByTestId('till-message-send'))
      expect(screen.getByTestId('till-message-error')).toHaveTextContent('Получить некому')
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
      expect(sendMutate).not.toHaveBeenCalled()
    })

    it('подсчёт не получился: сказано об этом, отправка остаётся (сервер проверит сам)', async () => {
      messageHooks.usePosmMessagePreview.mockReturnValue({ data: undefined, isLoading: false, isFetching: false, isError: true })
      const user = userEvent.setup()
      renderPanel()
      expect(screen.getByTestId('till-message-count')).toHaveTextContent('Не получилось посчитать адресатов')
      await user.type(screen.getByTestId('till-message-title'), 'Проверка')
      await user.type(screen.getByTestId('till-message-text'), 'Текст.')
      await user.click(screen.getByTestId('till-message-send'))
      expect(screen.getByRole('dialog')).toBeInTheDocument()
    })
  })

  it('в списке отправленных подписано, что уведомление было про курс и ушло только тем, кто за кассой', () => {
    messageHooks.usePosmMessages.mockReturnValue({
      data: [
        sent({
          id: 'm2',
          audience: 'course',
          targetCount: 0,
          targetNames: [],
          recipientsTotal: 21,
          programId: 'course-b',
          programName: 'Витамины группы B',
          onlyAtTill: true,
        }),
      ],
      isLoading: false,
      isError: false,
    })
    renderPanel()
    const row = screen.getByTestId('till-message-m2')
    expect(row).toHaveTextContent('не прошли курс «Витамины группы B», только тем, кто был за кассой при отправке')
    expect(row).toHaveTextContent('Адресатов при отправке: 21')
  })
})
