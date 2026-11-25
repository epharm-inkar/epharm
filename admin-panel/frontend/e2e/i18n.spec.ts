// E2E: локализация — переключение языка ru↔kk через Settings.
// Проверяем, что меняется навигация, заголовок секции и что выбор персистится.
//
// На /settings остался один рабочий combobox — язык. Неподключённый timezone
// отображается disabled input, а не фиктивным редактируемым select.

import { test, expect, moduleTab, openSidebarModule } from './fixtures'

test.describe('i18n — переключение языка ru ↔ kk', () => {
  test('смена на Қазақша переводит заголовок и навигацию', async ({ loggedInPage }) => {
    const page = loggedInPage
    await page.goto('/settings')
    await expect(page.getByRole('heading', { level: 1, name: 'Настройки' })).toBeVisible()

    await page.getByRole('combobox').selectOption('kk')

    await expect(page.getByRole('heading', { level: 1, name: 'Баптаулар' })).toBeVisible()
    await openSidebarModule(page, 'Аналитика')
    await expect(moduleTab(page, 'Аналитика', 'Аналитика дашборды')).toBeVisible() // module tabs
    await expect(page.getByRole('heading', { level: 1, name: 'Настройки' })).toHaveCount(0)
  })

  test('переключение назад на Русский', async ({ loggedInPage }) => {
    const page = loggedInPage
    await page.goto('/settings')
    await page.getByRole('combobox').selectOption('kk')
    await expect(page.getByRole('heading', { level: 1, name: 'Баптаулар' })).toBeVisible()
    await page.getByRole('combobox').selectOption('ru')
    await expect(page.getByRole('heading', { level: 1, name: 'Настройки' })).toBeVisible()
  })

  test('язык сохраняется после перезагрузки', async ({ loggedInPage }) => {
    const page = loggedInPage
    await page.goto('/settings')
    await page.getByRole('combobox').selectOption('kk')
    await expect(page.getByRole('heading', { level: 1, name: 'Баптаулар' })).toBeVisible()

    await page.reload()
    await expect(page.getByRole('heading', { level: 1, name: 'Баптаулар' })).toBeVisible()
  })

  test('казахский язык виден на других секциях (Промо)', async ({ loggedInPage }) => {
    const page = loggedInPage
    await page.goto('/settings')
    await page.getByRole('combobox').selectOption('kk')
    await expect(page.getByRole('heading', { level: 1, name: 'Баптаулар' })).toBeVisible()

    await openSidebarModule(page, 'Фармацевттерді ынталандыру')
    await moduleTab(page, 'Фармацевттерді ынталандыру', 'Промо-науқандар').click()
    await expect(page).toHaveURL(/\/promo$/)
    await expect(page.getByRole('heading', { level: 1, name: 'Промо-науқандар' })).toBeVisible()
  })
})
