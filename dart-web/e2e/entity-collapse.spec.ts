import { test, expect, type Page, type APIRequestContext } from '@playwright/test'

async function prepareKnowledgePage(page: Page, request: APIRequestContext): Promise<void> {
  // E2E 使用测试池（pool_test 646），未建则回退用户池——实体数据以 xiao 为准时先确保
  await page.addInitScript(() => localStorage.setItem('poolUserId', '3'))
  await page.goto('/knowledge')
  await page.waitForSelector('.entity-bar', { timeout: 15000 })
}

test('实体栏默认只显示前 2 个：按钮可见且可展开收起', async ({ page, request }) => {
  await prepareKnowledgePage(page, request)

  // 默认只露出最精确 Top 2：chip 数 = 1（全部实体）+ 2；展开按钮可见（不被裁剪）
  const toggle = page.locator('.entity-toggle')
  const chips = page.locator('.entity-chip')
  await expect(toggle).toBeVisible()
  await expect(toggle).toContainText('显示全部')
  await expect(chips).toHaveCount(3)

  // 从按钮文本提取实体总数（"显示全部 N 个实体"），展开后应渲染 N 个实体
  const count = Number((await toggle.textContent())?.match(/\d+/)?.[0])

  // 点击展开：全部实体渲染 + 按钮变"收起"
  await toggle.click()
  await expect(toggle).toContainText('收起')
  await expect(chips).toHaveCount(1 + count)

  // 再点收起：恢复 Top 2
  await toggle.click()
  await expect(toggle).toContainText('显示全部')
  await expect(chips).toHaveCount(3)
})
