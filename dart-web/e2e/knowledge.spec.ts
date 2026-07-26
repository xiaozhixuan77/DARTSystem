import { test, expect } from '@playwright/test'

test.describe('知识库面板', () => {
  test.beforeEach(async ({ page }) => {
    // 先导航到任意页面建立 document context，再设置 Token
    await page.goto('/login')
    await page.evaluate(() => {
      localStorage.setItem('accessToken', 'e2e-test-token')
      localStorage.setItem('refreshToken', 'e2e-test-refresh')
    })
    await page.goto('/chat')
    await page.waitForLoadState('networkidle')
  })

  test('知识库面板默认关闭', async ({ page }) => {
    const panel = page.locator('.knowledge-panel')
    await expect(panel).toHaveClass(/collapsed/)
  })

  test('通过侧边栏按钮打开知识库面板', async ({ page }) => {
    // 点击侧边栏"知识库"按钮
    await page.click('.panel-toggle')

    const panel = page.locator('.knowledge-panel')
    await expect(panel).not.toHaveClass(/collapsed/)

    // 面板标题
    await expect(page.locator('.panel-title')).toContainText('知识库')

    // 标签 chips
    await expect(page.locator('.tag-chip').first()).toBeVisible()

    // 关闭面板
    await page.click('.panel-close')
    await expect(panel).toHaveClass(/collapsed/)
  })

  test('知识库标签筛选切换', async ({ page }) => {
    // 打开知识库
    await page.click('.panel-toggle')

    // 点击第二个标签
    const secondTag = page.locator('.tag-chip').nth(1)
    const tagText = await secondTag.textContent()
    await secondTag.click()

    // 验证该标签变为 active
    await expect(page.locator('.tag-chip.active')).toContainText(tagText!)
  })
})
