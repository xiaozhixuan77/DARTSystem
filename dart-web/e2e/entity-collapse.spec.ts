import { test, expect, type Page, type APIRequestContext } from '@playwright/test'

async function loginXiao(page: Page, request: APIRequestContext): Promise<void> {
  const login = await request.post('/api/auth/login', {
    data: { username: 'xiao', password: '123456' }
  })
  expect(login.ok()).toBeTruthy()
  const token = (await login.json()).data.accessToken as string
  await page.goto('/')
  await page.evaluate((t) => localStorage.setItem('accessToken', t), token)
  await page.goto('/knowledge')
  await page.waitForSelector('.entity-bar', { timeout: 15000 })
}

test('实体栏折叠：按钮可见且可展开收起', async ({ page, request }) => {
  await loginXiao(page, request)

  // 折叠状态：展开按钮可见（不被裁剪）
  const toggle = page.locator('.entity-toggle')
  await expect(toggle).toBeVisible()
  await expect(toggle).toContainText('显示全部')
  const bar = page.locator('.entity-bar')
  await expect(bar).toHaveClass(/collapsed/)

  // 点击展开：折叠移除 + 按钮变"收起"
  await toggle.click()
  await expect(bar).not.toHaveClass(/collapsed/)
  await expect(toggle).toContainText('收起')

  // 再点收起
  await toggle.click()
  await expect(bar).toHaveClass(/collapsed/)
  await expect(toggle).toContainText('显示全部')
})
