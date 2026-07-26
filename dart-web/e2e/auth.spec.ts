import { test, expect } from '@playwright/test'

const uniqueUser = `e2e_${Date.now()}`
const password = 'testpass123'

test.describe('认证流程', () => {
  test('注册 → 登录 → 重定向到聊天页', async ({ page }) => {
    // 1. 访问注册页
    await page.goto('/register')
    await expect(page.locator('.auth-page')).toBeVisible()

    // 注册 Tab 默认激活
    await expect(page.locator('.auth-tab.active')).toContainText('注册')

    // 2. 填写注册表单
    await page.fill('#regUser', uniqueUser)
    await page.fill('#regPass', password)
    await page.fill('#regPass2', password)

    // 3. 提交注册
    await page.click('.auth-form.active .submit-btn')

    // 4. 等待注册成功提示并切换到登录 Tab
    await expect(page.locator('.el-message--success')).toBeVisible({ timeout: 10000 })
    await expect(page.locator('.auth-tab.active')).toContainText('登录')

    // 5. 填写登录表单
    await page.fill('#loginUser', uniqueUser)
    await page.fill('#loginPass', password)

    // 6. 提交登录
    await page.click('.auth-form.active .submit-btn')

    // 7. 验证跳转到聊天页
    await expect(page).toHaveURL(/\/chat/, { timeout: 10000 })
  })

  test('登录页 Tab 切换', async ({ page }) => {
    await page.goto('/login')
    await expect(page.locator('.auth-tab.active')).toContainText('登录')

    // 点击注册 Tab
    await page.click('.auth-tab:has-text("注册")')
    await expect(page.locator('.auth-tab.active')).toContainText('注册')

    // 切回登录
    await page.click('.auth-tab:has-text("登录")')
    await expect(page.locator('.auth-tab.active')).toContainText('登录')
  })

  test('未登录访问 /chat 重定向到 /login', async ({ page }) => {
    // 清除 Token
    await page.goto('/')
    await page.evaluate(() => localStorage.clear())

    await page.goto('/chat')
    // 应该被导航守卫重定向
    await expect(page).toHaveURL(/\/login/, { timeout: 5000 })
  })
})
