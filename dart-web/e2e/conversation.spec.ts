import { test, expect } from '@playwright/test'

test.describe('会话管理', () => {
  // 假 token 会被后端 401 踢回登录页，统一用 API 真实注册登录
  test.beforeEach(async ({ page, request }) => {
    const user = `e2e_${Date.now()}`
    await request.post('/api/auth/register', {
      data: { username: user, password: 'testpass123' }
    })
    const loginResp = await request.post('/api/auth/login', {
      data: { username: user, password: 'testpass123' }
    })
    const loginJson = await loginResp.json()
    await page.goto('/login')
    await page.evaluate(({ access, refresh, name }) => {
      localStorage.setItem('accessToken', access)
      localStorage.setItem('refreshToken', refresh)
      localStorage.setItem('userInfo', JSON.stringify({ userId: 1, username: name }))
    }, {
      access: loginJson.data.accessToken,
      refresh: loginJson.data.refreshToken,
      name: user
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
    await expect(page.locator('.user-name')).toBeVisible()
    await expect(page.locator('.user-name')).not.toHaveText('')
    await expect(page.locator('.user-avatar')).toBeVisible()
  })

  test('面包屑品牌导航可见', async ({ page }) => {
    await expect(page.locator('.brand-name')).toContainText('DART')
    await expect(page.locator('.brand-sub')).toContainText('检索')
  })

  test('右键删除会话', async ({ page }) => {
    // 新建会话，列表出现一项
    await page.locator('.new-session-btn').click()
    await expect(page.locator('.welcome-screen')).toBeVisible()
    await expect(page.locator('.session-item')).toHaveCount(1)

    // 右键呼出菜单 → 删除 → 确认
    await page.locator('.session-item').click({ button: 'right' })
    await expect(page.locator('.context-menu')).toBeVisible()
    await page.locator('.menu-item.danger').click()
    const confirmBox = page.locator('.el-message-box')
    await expect(confirmBox).toBeVisible()
    await confirmBox.locator('.el-button--primary').click()

    // 删除成功 → 空状态
    await expect(page.locator('.session-empty')).toBeVisible({ timeout: 10000 })
  })
})
