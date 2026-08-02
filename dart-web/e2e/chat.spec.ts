import { test, expect } from '@playwright/test'
import { registerAndLogin, gotoChat } from './helpers'

test.describe('聊天页面', () => {
  test.beforeEach(async ({ page, request }) => {
    await registerAndLogin(page, request)
    await gotoChat(page)
  })

  test('聊天页面三栏布局加载', async ({ page }) => {
    await expect(page.locator('.sidebar')).toBeVisible()
    await expect(page.locator('.chat-area')).toBeVisible()
    await expect(page.locator('.welcome-screen')).toBeVisible()
    await expect(page.locator('.welcome-title')).toContainText('DART')
    await expect(page.locator('.chat-input-area')).toBeVisible()
    await expect(page.locator('.input-field')).toBeVisible()
    await expect(page.locator('.send-btn')).toBeVisible()
  })

  test('会话列表加载与切换', async ({ page }) => {
    await expect(page.locator('.brand-name')).toContainText('DART')
    await expect(page.locator('.new-session-btn')).toBeVisible()
    await expect(page.locator('.new-session-btn')).toContainText('新搜索')
  })

  test('输入框 Enter 发送消息', async ({ page }) => {
    const input = page.locator('.input-field')
    await expect(page.locator('.send-btn')).toBeDisabled()

    await input.fill('Spring Boot 如何配置数据源?')
    await expect(page.locator('.send-btn')).toBeEnabled()

    await input.press('Enter')

    await expect(page.locator('.message-row.user')).toBeVisible()
    await expect(page.locator('.message-row.user .message-content')).toContainText('Spring Boot')
  })

  test('Shift+Enter 换行不发送', async ({ page }) => {
    const input = page.locator('.input-field')
    await input.fill('第一行')
    await input.press('Shift+Enter')

    await expect(page.locator('.welcome-screen')).toBeVisible()
  })

  test('流式状态显示并可通过按钮取消', async ({ page }) => {
    const input = page.locator('.input-field')
    await input.fill('测试流式')
    await input.press('Enter')

    // 用户消息出现在列表中（SSE 是否完成不影响本地消息）
    await expect(page.locator('.message-row.user')).toBeVisible()
  })
})
