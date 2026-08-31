import type { Page, APIRequestContext } from '@playwright/test'

/**
 * 无登录模式：直接访问应用。E2E 使用测试数据池（pool_test，id 由后端/数据库提供），
 * 避免污染用户数据池（xiao）。测试池用户由开发期创建，生产不随部署。
 */
const TEST_POOL_USER_ID = 646

export async function prepareSession(page: Page, _request: APIRequestContext): Promise<void> {
  await page.addInitScript((id) => {
    localStorage.setItem('poolUserId', String(id))
  }, TEST_POOL_USER_ID)
}

export async function registerAndLogin(page: Page, request: APIRequestContext): Promise<string> {
  await prepareSession(page, request)
  return 'pool_test'
}

export async function gotoChat(page: Page) {
  await page.goto('/chat')
  await page.waitForLoadState('networkidle')
}
