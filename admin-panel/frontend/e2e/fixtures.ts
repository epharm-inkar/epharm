// E2E fixtures — расширяем `test` с готовым залогиненным state'ом + helpers.

import {
  test as base,
  expect as baseExpect,
  type Page,
  type Locator,
  type APIRequestContext,
} from '@playwright/test'

export const BACKEND_URL = process.env.E2E_BACKEND_URL ?? 'http://localhost:8080'

export interface DevAccount {
  email: string
  password: string
  name: string
}

export const ACCOUNTS = {
  damir: { email: 'damir@jadran.com', password: 'damir2026', name: 'Дамир Нурланов' },
  aigerim: { email: 'aigerim@inkar.kz', password: 'aigerim2026', name: 'Айгерим Сарсенова' },
  bauyrzhan: { email: 'bauyrzhan@inkar.kz', password: 'bauyrzhan2026', name: 'Бауыржан Тлеуов' },
  lms: { email: 'lms@epharm.kz', password: 'lms123', name: 'Руководитель обучения' },
} as const satisfies Record<string, DevAccount>

/**
 * Логинит юзера через UI и ждёт редирект на /rules. Используется в `loggedIn`
 * фиксче ниже + можно вручную в отдельных тестах.
 */
export async function loginViaUI(page: Page, acc: DevAccount = ACCOUNTS.damir): Promise<void> {
  await page.goto('/login')
  await page.getByLabel(/Email/i).fill(acc.email)
  await page.getByLabel(/Пароль/i).fill(acc.password)
  await page.getByRole('button', { name: /^Войти/i }).click()
  await page.waitForURL('**/rules', { timeout: 10_000 })
}

/** The sidebar lists modules only (25.11.2026); a module opens on its first section. */
export function sidebarModule(page: Page, label: string): Locator {
  return page
    .getByRole('complementary', { name: 'Основная навигация' })
    .getByTitle(label, { exact: true })
}

export async function openSidebarModule(page: Page, label: string): Promise<void> {
  const module = sidebarModule(page, label)
  await baseExpect(module).toBeVisible()
  if ((await module.getAttribute('aria-current')) !== 'page') await module.click()
  await baseExpect(module).toHaveAttribute('aria-current', 'page')
}

/** A section of the open module: a tab at the top of the page (layout/ModuleTabs). */
export function moduleTab(page: Page, module: string, section: string): Locator {
  return page
    .getByRole('navigation', { name: module, exact: true })
    .getByRole('button', { name: section, exact: true })
}

/** «Обучение» keeps its own tab bar; the rarely used tabs and «AI-экзаменация» sit under «Ещё». */
export function trainingTab(page: Page, label: string): Locator {
  return page
    .getByRole('navigation', { name: 'Разделы обучения' })
    .getByRole('button', { name: label, exact: true })
}

export async function openTrainingMore(page: Page): Promise<void> {
  const more = page
    .getByRole('navigation', { name: 'Разделы обучения' })
    .getByRole('button', { name: 'Ещё' })
  if ((await more.getAttribute('aria-expanded')) !== 'true') await more.click()
  await baseExpect(page.getByRole('menu')).toBeVisible()
}

/**
 * Backend health check — гарантия что Spring Boot запущен.
 * Запускается в beforeAll каждого spec'а через fixture.
 */
export async function assertBackendUp(request: APIRequestContext): Promise<void> {
  const r = await request.get(`${BACKEND_URL}/api/health`).catch(() => null)
  if (!r || !r.ok()) {
    throw new Error(
      `Backend (${BACKEND_URL}) недоступен. Запусти \`./gradlew bootRun\` ` +
        'из admin-panel/backend перед E2E тестами.',
    )
  }
}

/**
 * Получает Bearer access-token через прямой backend login. Используется в тестах
 * которым нужно создать предусловие (например, создать промо для теста detail-modal).
 */
export async function getBearer(
  request: APIRequestContext,
  acc: DevAccount = ACCOUNTS.damir,
): Promise<string> {
  const r = await request.post(`${BACKEND_URL}/api/admin/auth/login`, {
    data: { email: acc.email, password: acc.password },
  })
  if (!r.ok()) throw new Error(`Login failed: ${r.status()}`)
  const body = (await r.json()) as { tokens: { accessToken: string } }
  return `Bearer ${body.tokens.accessToken}`
}

/**
 * Возвращает dev-БД к seed-базису. Вызывается перед каждым UI-тестом (через
 * фикстуры) → каждый тест стартует с идентичной фикстуры независимо от мутаций
 * предыдущих тестов/файлов (workers:1, тесты независимы). Эндпоинт — profile=dev.
 */
export async function resetBackend(request: APIRequestContext): Promise<void> {
  const r = await request.post(`${BACKEND_URL}/api/admin/dev/reset`).catch(() => null)
  if (!r || !r.ok()) {
    throw new Error(
      'POST /api/admin/dev/reset не удался. Backend должен быть в profile=dev ' +
        '(DevController доступен только там).',
    )
  }
}

/**
 * Чистит localStorage перед тестом — иначе persist'ed cache из прошлого run'а
 * может закэшировать данные и сломать assertions «правил пока нет».
 *
 * page.goto('/about:blank') нужен потому что localStorage привязан к origin,
 * а до первого goto'а origin неизвестен.
 */
async function clearStorage(page: Page): Promise<void> {
  await page.goto('/', { waitUntil: 'commit' }).catch(() => {})
  await page.evaluate(() => {
    try {
      localStorage.clear()
      sessionStorage.clear()
    } catch {
      /* ignore */
    }
  })
}

// ── test fixtures ─────────────────────────────────────────────────────────

interface Fixtures {
  /** Backend pre-check + clean state. Auto, не нужно вызывать вручную. */
  freshPage: Page
  /** Уже залогиненный page (Damir по умолчанию). */
  loggedInPage: Page
}

export const test = base.extend<Fixtures>({
  freshPage: async ({ page, request }, use) => {
    await assertBackendUp(request)
    await resetBackend(request)
    await clearStorage(page)
    await use(page)
  },
  loggedInPage: async ({ page, request }, use) => {
    await assertBackendUp(request)
    await resetBackend(request)
    await clearStorage(page)
    await loginViaUI(page)
    await use(page)
  },
})

export const expect = baseExpect
