import { test, expect, type Page, type APIRequestContext } from '@playwright/test'

async function prepareKnowledgePage(page: Page, request: APIRequestContext): Promise<void> {
  // E2E 使用测试池（pool_test 646），未建则回退用户池——实体数据以 xiao 为准时先确保
  await page.addInitScript(() => localStorage.setItem('poolUserId', '3'))
  await page.goto('/knowledge')
  await page.waitForSelector('.entity-bar', { timeout: 15000 })
}

test('实体栏折叠：按钮可见且可展开收起', async ({ page, request }) => {
  await prepareKnowledgePage(page, request)

  // 折叠状态：展开按钮可见（不被裁剪）
  const toggle = page.locator('.entity-toggle')
  await expect(toggle).toBeVisible()
  await expect(toggle).toContainText('显示全部')
  const bar = page.locator('.entity-bar')
  await expect(bar).toHaveClass(/collapsed/)

  // 点击展开：折叠移除 + 按钮变"收起"
  await toggle.click()
  await expect(bar).not.toHaveClass(/collapsed/)
  await expect(toggle).toContainText('收起')

  // 再点收起
  await toggle.click()
  await expect(bar).toHaveClass(/collapsed/)
  await expect(toggle).toContainText('显示全部')
})
