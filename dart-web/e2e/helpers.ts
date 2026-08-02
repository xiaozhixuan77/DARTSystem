import type { Page, APIRequestContext } from '@playwright/test'

/**
 * 通过 API 真实注册一个新用户并登录，把有效 token 写入 localStorage。
 * 假 token 会被后端 401 踢回登录页，E2E 必须使用真实登录态。
 */
export async function registerAndLogin(page: Page, request: APIRequestContext): Promise<string> {
  // 用户名限制 3-20 位字母数字下划线：e2e + 13 位时间戳 = 16 字符
  const user = `e2e${Date.now()}`
  const reg = await request.post('/api/auth/register', {
    data: { username: user, password: 'testpass123' }
  })
  const regJson = await reg.json()
  if (!regJson.data?.userId) {
    throw new Error(`register failed: ${JSON.stringify(regJson)}`)
  }
  const login = await request.post('/api/auth/login', {
    data: { username: user, password: 'testpass123' }
  })
  const loginJson = await login.json()
  if (!loginJson.data?.accessToken) {
    throw new Error(`login failed: ${JSON.stringify(loginJson)}`)
  }
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
  return user
}

export async function gotoChat(page: Page) {
  await page.goto('/chat')
  await page.waitForLoadState('networkidle')
}
