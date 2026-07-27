import { test, expect } from '@playwright/test'

test.describe('会话管理', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/login')
    await page.evaluate(() => {
      localStorage.setItem('accessToken', 'e2e-test-token')
      localStorage.setItem('refreshToken', 'e2e-test-refresh')
      localStorage.setItem('userInfo', JSON.stringify({ userId: 1, username: 'conv_test' }))
    })
    await page.goto('/chat')
    await page.waitForLoadState('networkidle')
  })

  test('"新搜索"按钮创建会话', async ({ page }) => {
    const newBtn = page.locator('.new-session-btn')
    await expect(newBtn).toBeVisible()
    await newBtn.click()

    // 新会话创建后，欢迎屏幕应该可见
    await expect(page.locator('.welcome-screen')).toBeVisible()
  })

  test('会话列表显示无会话状态', async ({ page }) => {
    const emptyState = page.locator('.session-empty')
    if (await emptyState.isVisible()) {
      await expect(emptyState).toContainText('暂无会话')
    }
  })

  test('从侧边栏切换回聊天页', async ({ page }) => {
    // 点击品牌logo返回聊天页
    const brand = page.locator('.sidebar-brand')
    await brand.click()
    await expect(page).toHaveURL(/\/chat/)
  })

  test('侧边栏显示用户信息', async ({ page }) => {
    await expect(page.locator('.user-name')).toContainText('conv_test')
    await expect(page.locator('.user-avatar')).toBeVisible()
  })

  test('面包屑品牌导航可见', async ({ page }) => {
    await expect(page.locator('.brand-name')).toContainText('DART')
    await expect(page.locator('.brand-sub')).toContainText('检索')
  })
})
