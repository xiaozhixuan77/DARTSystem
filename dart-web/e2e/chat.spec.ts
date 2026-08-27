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
    await expect(page.locator('.new-session-btn')).toContainText('新会话')
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

  test('会话导航：抽屉展开、点击问题滚动定位', async ({ page }) => {
    // 伪造 send SSE（真实 LLM 一轮 1~6.5 分钟远超 60s 超时）：两轮问答毫秒级完成。
    // 不发 conversation 事件且 done 不带 conversationId——currentId 保持 null，
    // onDone 跳过 selectConversation 重载，本地消息保留（重载会以真实查询结果覆盖伪造内容）
    await page.route('**/api/chat/send', async (route) => {
      const msg = String(route.request().postDataJSON()?.message ?? '')
      const chunks = [
        `针对「${msg}」的整理：`,
        '第一点，背景与定义。'.repeat(60),
        '第二点，核心机制与原理。'.repeat(60),
        '第三点，实践建议与常见问题。'.repeat(60),
      ]
      const events: string[] = [
        'event: status',
        'data: {"phase":"rag_check","message":"正在检索知识库..."}',
        '',
      ]
      chunks.forEach((c) => events.push('event: content', `data: ${c}`, ''))
      events.push('event: done', 'data: {"status":"complete"}', '')
      await route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        body: events.join('\n'),
      })
    })

    const input = page.locator('.input-field')
    const list = page.locator('.message-list')

    // 两轮问答（伪造流即时完成；第二轮前等 streaming-bar 消失即 isStreaming 复位，否则 send 守卫拦截）
    await input.fill('第一问：Spring Boot 数据源配置')
    await input.press('Enter')
    await expect(page.locator('.message-row.user')).toHaveCount(1)
    await expect(page.locator('.streaming-bar')).toBeHidden()
    await input.fill('第二问：Spring Security 认证流程')
    await input.press('Enter')
    await expect(page.locator('.message-row')).toHaveCount(4)

    // 长回答使列表可滚动，当前停在底部
    await expect.poll(() => list.evaluate((el) => el.scrollTop), { timeout: 10000 })
      .toBeGreaterThan(0)

    // 展开导航抽屉：问题列表两轮、内容截断展示
    await page.locator('.nav-toggle').click()
    await expect(page.locator('.nav-panel')).toBeVisible()
    const items = page.locator('.nav-item')
    await expect(items).toHaveCount(2)
    await expect(items.first()).toContainText('第一问')

    // 点击第一轮问题 → 平滑滚动回顶部（scroll-margin-top 对齐，scrollTop 归 0）且抽屉收起
    await items.first().click()
    await expect.poll(() => list.evaluate((el) => el.scrollTop), { timeout: 10000 }).toBe(0)
    await expect(page.locator('.nav-panel')).toBeHidden()
  })
})
