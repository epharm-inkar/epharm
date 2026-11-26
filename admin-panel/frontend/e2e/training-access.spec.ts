import type { Page } from '@playwright/test'

import {
  ACCOUNTS,
  expect,
  test,
  moduleTab,
  openSidebarModule,
  openTrainingMore,
  sidebarModule,
  trainingTab,
  type DevAccount,
} from './fixtures'

async function loginForRole(page: Page, account: DevAccount, landingPath: string): Promise<void> {
  await page.goto('/login')
  await page.getByLabel(/Email/i).fill(account.email)
  await page.getByLabel(/Пароль/i).fill(account.password)
  await page.getByRole('button', { name: /^Войти/i }).click()
  await page.waitForURL(`**${landingPath}`, { timeout: 10_000 })
}

test.describe('Role access — обучение и AI-экзамены', () => {
  // One navigation (25.11.2026): modules in the sidebar; «Обучение» has its own tab bar on the page,
  // the rarely used tabs and «AI-экзаменация» under «Ещё».
  test('HQ видит все разделы, а обучение открывается только для просмотра', async ({
    freshPage,
  }) => {
    await loginForRole(freshPage, ACCOUNTS.bauyrzhan, '/dashboard')

    await openSidebarModule(freshPage, 'Аналитика')
    await expect(moduleTab(freshPage, 'Аналитика', 'Дашборд аналитики')).toBeVisible()
    await openSidebarModule(freshPage, 'Обучение')
    await expect(freshPage).toHaveURL(/\/lms$/)
    await expect(trainingTab(freshPage, 'Дашборд')).toHaveAttribute('aria-current', 'page')
    await expect(freshPage.getByTestId('training-read-only')).toHaveText('Только просмотр')
    await openTrainingMore(freshPage)
    await expect(freshPage.getByRole('menuitem', { name: /AI-Экзаменация/i })).toBeVisible()
    await freshPage.keyboard.press('Escape')

    await trainingTab(freshPage, 'Программы').click()
    await expect(freshPage).toHaveURL(/\/lms\?tab=programs$/)
    await expect(freshPage.getByRole('button', { name: 'Новая программа' })).toHaveCount(0)

    await openTrainingMore(freshPage)
    await freshPage.getByRole('menuitem', { name: /AI-Экзаменация/i }).click()
    await expect(freshPage).toHaveURL(/\/ai-exam$/)
    await expect(freshPage.getByTestId('ai-exam-read-only')).toHaveText('Только просмотр')
    await expect(freshPage.getByRole('button', { name: /Новый вопрос/i })).toHaveCount(0)
  })

  test('руководитель обучения изолирован от HQ и сохраняет управляющие действия', async ({
    freshPage,
  }) => {
    await loginForRole(freshPage, ACCOUNTS.lms, '/lms')

    await expect(
      freshPage.getByRole('banner').getByRole('button', { name: new RegExp(ACCOUNTS.lms.name) }),
    ).toBeVisible()
    await expect(sidebarModule(freshPage, 'Обучение')).toBeVisible()
    await expect(freshPage.getByRole('button', { name: /Дашборд аналитики/i })).toHaveCount(0)
    await expect(freshPage.getByRole('button', { name: /Rules Engine/i })).toHaveCount(0)

    for (const section of ['Дашборд', 'Программы', 'Онлайн-курсы', 'Назначения', 'Сертификаты'])
      await expect(trainingTab(freshPage, section)).toBeVisible()
    await openTrainingMore(freshPage)
    for (const section of [
      'Офлайн-мероприятия',
      'Посещаемость',
      'Результаты и экзамены',
      'Аналитика',
      'Настройки обучения',
      'AI-экзаменация',
    ])
      await expect(freshPage.getByRole('menuitem', { name: section, exact: true })).toBeVisible()
    await freshPage.keyboard.press('Escape')
    // The sidebar holds modules only, no second copy of these tabs.
    await expect(
      freshPage
        .getByRole('complementary', { name: 'Основная навигация' })
        .getByRole('button', { name: 'Программы', exact: true }),
    ).toHaveCount(0)

    await trainingTab(freshPage, 'Программы').click()
    await expect(freshPage).toHaveURL(/\/lms\?tab=programs$/)
    await expect(freshPage.getByRole('heading', { level: 1, name: 'Программы' })).toBeVisible()
    await expect(trainingTab(freshPage, 'Программы')).toHaveAttribute('aria-current', 'page')
    await expect(freshPage.getByRole('button', { name: 'Новая программа' })).toBeVisible()

    await trainingTab(freshPage, 'Онлайн-курсы').click()
    await expect(freshPage).toHaveURL(/\/lms\?tab=courses$/)
    await expect(freshPage.getByRole('heading', { level: 1, name: 'Онлайн-курсы' })).toBeVisible()
    await freshPage.goBack()
    await expect(freshPage).toHaveURL(/\/lms\?tab=programs$/)
    await expect(trainingTab(freshPage, 'Программы')).toHaveAttribute('aria-current', 'page')

    await openTrainingMore(freshPage)
    await freshPage.getByRole('menuitem', { name: /AI-Экзаменация/i }).click()
    await expect(freshPage).toHaveURL(/\/ai-exam$/)
    await expect(freshPage.getByRole('button', { name: /Новый вопрос/i }).first()).toBeVisible()

    await freshPage.goto('/dashboard')
    await expect(freshPage).toHaveURL(/\/lms$/)
  })
})
