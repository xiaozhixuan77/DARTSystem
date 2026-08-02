import { test, expect } from '@playwright/test'
import { registerAndLogin, gotoChat } from './helpers'

const uniqueUser = `e2e_${Date.now()}`
const password = 'testpass123'

test.describe('认证流程', () => {
  test('注册 → 登录 → 重定向到聊天页', async ({ page }) => {
    await page.goto('/register')
    await expect(page.locator('.auth-page')).toBeVisible()
    await expect(page.locator('.auth-tab.active')).toContainText('注册')

    await page.fill('#regUser', uniqueUser)
    await page.fill('#regPass', password)
    await page.fill('#regPass2', password)

    await page.click('.auth-form.active .submit-btn')

    await expect(page.locator('.el-message--success')).toBeVisible({ timeout: 10000 })
    await expect(page.locator('.auth-tab.active')).toContainText('登录')

    await page.fill('#loginUser', uniqueUser)
    await page.fill('#loginPass', password)

    await page.click('.auth-form.active .submit-btn')

    await expect(page).toHaveURL(/\/chat/, { timeout: 10000 })
  })

  test('登录页 Tab 切换', async ({ page }) => {
    await page.goto('/login')
    await expect(page.locator('.auth-tab.active')).toContainText('登录')

    await page.click('.auth-tab:has-text("注册")')
    await expect(page.locator('.auth-tab.active')).toContainText('注册')

    await page.click('.auth-tab:has-text("登录")')
    await expect(page.locator('.auth-tab.active')).toContainText('登录')
  })

  test('未登录访问 /chat 重定向到 /login', async ({ page }) => {
    await page.goto('/')
    await page.evaluate(() => localStorage.clear())

    await page.goto('/chat')
    await expect(page).toHaveURL(/\/login/, { timeout: 5000 })
  })

  test('登录后 Token 持久化到 localStorage', async ({ page, request }) => {
    await registerAndLogin(page, request)
    await gotoChat(page)

    const tokenBefore = await page.evaluate(() => localStorage.getItem('accessToken'))
    expect(tokenBefore).toBeTruthy()

    // 刷新页面后 token 仍在，登录态保持
    await page.reload()
    await page.waitForLoadState('networkidle')

    const tokenAfter = await page.evaluate(() => localStorage.getItem('accessToken'))
    expect(tokenAfter).toBe(tokenBefore)
  })

  test('退出登录清除状态并跳转', async ({ page, request }) => {
    await registerAndLogin(page, request)
    await gotoChat(page)

    // 点击退出按钮
    await expect(page.locator('.logout-btn')).toBeVisible()
    await page.locator('.logout-btn').click()

    // 应该跳转到登录页
    await expect(page).toHaveURL(/\/login/, { timeout: 5000 })
  })
})
