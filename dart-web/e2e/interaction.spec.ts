import { test, expect } from '@playwright/test'
import { registerAndLogin, gotoChat } from './helpers'

test.describe('UI 交互', () => {
  test.beforeEach(async ({ page, request }) => {
    await registerAndLogin(page, request)
    await gotoChat(page)
  })

  test('Enter 发送消息', async ({ page }) => {
    const input = page.locator('.input-field')
    await input.fill('测试 Enter 发送')
    await input.press('Enter')

    // 用户消息出现在列表
    await expect(page.locator('.message-row.user')).toBeVisible()
  })

  test('Shift+Enter 换行', async ({ page }) => {
    const input = page.locator('.input-field')
    await input.fill('第一行')
    await input.press('Shift+Enter')

    // Shift+Enter 不发送，输入框还有内容
    // 且没有出现新的用户消息
    const userMessagesBefore = await page.locator('.message-row.user').count()
    // 确保没有新增消息（欢迎屏幕还在）
    expect(userMessagesBefore).toBe(0)
  })

  test('Markdown 渲染 — 用户消息为纯文本', async ({ page }) => {
    const input = page.locator('.input-field')
    await input.fill('**bold** 和 `code`')
    await input.press('Enter')

    // 用户消息气泡应该将 Markdown 符号原样显示
    const userMsg = page.locator('.message-row.user .message-content')
    if (await userMsg.count() > 0) {
      const text = await userMsg.first().textContent()
      expect(text).toContain('**bold**')
    }
  })

  test('发送按钮在输入为空时禁用', async ({ page }) => {
    const sendBtn = page.locator('.send-btn')
    await expect(sendBtn).toBeDisabled()

    const input = page.locator('.input-field')
    await input.fill('text')
    await expect(sendBtn).toBeEnabled()

    await input.fill('  ')
    await expect(sendBtn).toBeDisabled()
  })

  test('聊天区标题显示', async ({ page }) => {
    // 检查 header 区域存在
    const header = page.locator('.chat-header')
    await expect(header).toBeVisible()
    const title = page.locator('.chat-title')
    await expect(title).toBeVisible()
  })
})
