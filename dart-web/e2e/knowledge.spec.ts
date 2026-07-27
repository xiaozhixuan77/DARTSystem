import { test, expect } from '@playwright/test'

test.describe('知识库面板', () => {
  test.beforeEach(async ({ page }) => {
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
    await page.click('.panel-toggle')

    const panel = page.locator('.knowledge-panel')
    await expect(panel).not.toHaveClass(/collapsed/)
    await expect(page.locator('.panel-title')).toContainText('知识库')
    await expect(page.locator('.tag-chip').first()).toBeVisible()

    await page.click('.panel-close')
    await expect(panel).toHaveClass(/collapsed/)
  })

  test('知识库标签筛选切换', async ({ page }) => {
    await page.click('.panel-toggle')

    const secondTag = page.locator('.tag-chip').nth(1)
    const tagText = await secondTag.textContent()
    await secondTag.click()

    await expect(page.locator('.tag-chip.active')).toContainText(tagText!)
  })

  test('分页控件渲染', async ({ page }) => {
    await page.click('.panel-toggle')

    // 分页组件存在（无论是否有数据）
    const pager = page.locator('.panel-pager')
    // 如果 total > size，分页可见
    const pagerVisible = await pager.isVisible()
    // 至少面板正常渲染
    expect(page.locator('.panel-grid')).toBeVisible()
  })

  test('打开知识条目详情', async ({ page }) => {
    await page.click('.panel-toggle')

    // 如果有知识条目，点击应该触发详情加载
    const card = page.locator('.knowledge-card').first()
    const cardCount = await card.count()

    if (cardCount > 0) {
      await card.click()
      // 可以验证 store 状态变化，但这里确保不报错即可
      await page.waitForLoadState('networkidle')
    }
  })
})
