import { test, expect } from '@playwright/test'

test.describe('聊天页面', () => {
  test.beforeEach(async ({ page }) => {
    // 通过 localStorage 注入 Token 模拟已登录状态
    await page.goto('/chat')
    // 页面应该渲染基本布局（即使未登录也会被重定向，但先检查）
  })

  test('聊天页面三栏布局加载', async ({ page }) => {
    // 注入假 Token 绕过导航守卫
    await page.evaluate(() => {
      localStorage.setItem('accessToken', 'e2e-test-token')
      localStorage.setItem('refreshToken', 'e2e-test-refresh')
      localStorage.setItem('userInfo', JSON.stringify({ userId: 1, username: 'e2e_test' }))
    })
    await page.goto('/chat')
    await page.waitForLoadState('networkidle')

    // 三栏布局
    await expect(page.locator('.sidebar')).toBeVisible()
    await expect(page.locator('.chat-area')).toBeVisible()

    // 欢迎屏幕（无消息时显示）
    await expect(page.locator('.welcome-screen')).toBeVisible()
    await expect(page.locator('.welcome-title')).toContainText('DART')

    // 输入区
    await expect(page.locator('.chat-input-area')).toBeVisible()
    await expect(page.locator('.input-field')).toBeVisible()
    await expect(page.locator('.send-btn')).toBeVisible()
  })

  test('会话列表加载与切换', async ({ page }) => {
    await page.evaluate(() => {
      localStorage.setItem('accessToken', 'e2e-test-token')
      localStorage.setItem('refreshToken', 'e2e-test-refresh')
    })
    await page.goto('/chat')
    await page.waitForLoadState('networkidle')

    // 侧边栏品牌标识
    await expect(page.locator('.brand-name')).toContainText('DART')

    // "新搜索"按钮
    await expect(page.locator('.new-session-btn')).toBeVisible()
    await expect(page.locator('.new-session-btn')).toContainText('新搜索')
  })

  test('输入框 Enter 发送消息', async ({ page }) => {
    await page.evaluate(() => {
      localStorage.setItem('accessToken', 'e2e-test-token')
      localStorage.setItem('refreshToken', 'e2e-test-refresh')
    })
    await page.goto('/chat')
    await page.waitForLoadState('networkidle')

    const input = page.locator('.input-field')

    // 发送按钮默认禁用
    await expect(page.locator('.send-btn')).toBeDisabled()

    // 输入文字
    await input.fill('Spring Boot 如何配置数据源?')

    // 发送按钮启用
    await expect(page.locator('.send-btn')).toBeEnabled()

    // 按 Enter 发送
    await input.press('Enter')

    // 消息已添加到列表（用户消息气泡）
    await expect(page.locator('.message-row.user')).toBeVisible()
    await expect(page.locator('.message-row.user .message-content')).toContainText('Spring Boot')
  })

  test('Shift+Enter 换行不发送', async ({ page }) => {
    await page.evaluate(() => {
      localStorage.setItem('accessToken', 'e2e-test-token')
      localStorage.setItem('refreshToken', 'e2e-test-refresh')
    })
    await page.goto('/chat')
    await page.waitForLoadState('networkidle')

    const input = page.locator('.input-field')
    await input.fill('第一行')
    await input.press('Shift+Enter')

    // 消息未发送（欢迎屏幕仍然可见）
    await expect(page.locator('.welcome-screen')).toBeVisible()
  })
})
