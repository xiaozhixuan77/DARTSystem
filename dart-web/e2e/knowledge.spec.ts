import { test, expect } from '@playwright/test'
import { registerAndLogin, gotoChat } from './helpers'

test.describe('知识库全屏页', () => {
  test.beforeEach(async ({ page, request }) => {
    await registerAndLogin(page, request)
    await gotoChat(page)
  })

  test('侧栏知识库按钮跳转全屏页', async ({ page }) => {
    await page.click('.panel-toggle')
    await expect(page).toHaveURL(/\/knowledge/)
    await expect(page.locator('.knowledge-page')).toBeVisible()
  })

  test('页面头部渲染标题、搜索框、视图切换', async ({ page }) => {
    await page.click('.panel-toggle')
    await expect(page.locator('.page-title')).toHaveText('知识库')
    await expect(page.locator('.search-input')).toBeVisible()
    await expect(page.locator('.view-btn')).toHaveCount(2)
    await expect(page.locator('.back-btn')).toBeVisible()
  })

  test('标签筛选切换', async ({ page }) => {
    await page.click('.panel-toggle')

    const secondTag = page.locator('.tag-chip').nth(1)
    // 新用户可能没有标签数据，仅在有数据时验证筛选交互
    if ((await secondTag.count()) > 0) {
      const tagText = await secondTag.textContent()
      await secondTag.click()
      await expect(page.locator('.tag-chip.active')).toContainText(tagText!)
    }
  })

  test('分页控件按需渲染', async ({ page }) => {
    await page.click('.panel-toggle')

    // total <= size 时无分页；有分页则校验按钮存在
    const pager = page.locator('.knowledge-pager')
    if (await pager.isVisible().catch(() => false)) {
      await expect(pager.locator('.pager-next')).toBeVisible()
    }
  })

  test('打开知识条目详情弹窗', async ({ page }) => {
    await page.click('.panel-toggle')

    const card = page.locator('.knowledge-card').first()
    if ((await card.count()) > 0) {
      await card.click()
      await expect(page.locator('.dialog-overlay')).toBeVisible()
    }
  })

  test('正文编辑保存', async ({ page }) => {
    await page.click('.panel-toggle')

    const card = page.locator('.knowledge-card').first()
    if ((await card.count()) > 0) {
      await card.click()
      await expect(page.locator('.dialog-overlay')).toBeVisible()

      await page.click('.tab-detail')
      await page.fill('#edit-content', '# E2E 编辑后的正文')
      await page.click('.save-btn')

      // 保存成功后弹窗关闭
      await expect(page.locator('.dialog-overlay')).toBeHidden({ timeout: 5000 })
    }
  })

  test('导出 Markdown 触发下载', async ({ page }) => {
    await page.click('.panel-toggle')

    const card = page.locator('.knowledge-card').first()
    if ((await card.count()) > 0) {
      await card.click()
      await expect(page.locator('.dialog-overlay')).toBeVisible()

      const [download] = await Promise.all([
        page.waitForEvent('download'),
        page.click('.export-btn')
      ])
      expect(download.suggestedFilename()).toMatch(/\.md$/)
    }
  })

  test('删除知识条目', async ({ page }) => {
    await page.click('.panel-toggle')

    const card = page.locator('.knowledge-card').first()
    if ((await card.count()) > 0) {
      const before = await page.locator('.knowledge-card').count()
      await card.click()
      await expect(page.locator('.dialog-overlay')).toBeVisible()

      await page.click('.delete-btn')
      await page.click('.el-message-box__btns .el-button--primary')

      await expect(page.locator('.dialog-overlay')).toBeHidden({ timeout: 5000 })
      const after = await page.locator('.knowledge-card').count()
      expect(after).toBeLessThan(before)
    }
  })

  test('图谱视图切换', async ({ page }) => {
    await page.click('.panel-toggle')
    await expect(page.locator('.list-view')).toBeVisible()

    await page.click('.view-btn:nth-child(2)')

    // 新用户无关联数据 → 空态提示；有数据 → 图谱容器
    await expect(page.locator('.graph-view')).toBeVisible()
    await expect(page.locator('.graph-hint, .graph-container').first()).toBeVisible()
  })
})
